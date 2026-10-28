-- Financial objects retain their original identity and must agree with the
-- append-only journal at commit, including late receipts after cancellation.
CREATE FUNCTION payment_state_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'awaiting' OR NEW.paid_at IS NOT NULL OR NEW.credit_cents<>0 OR NEW.external_cents<>0 OR NEW.provider_reference IS NOT NULL
   THEN RAISE EXCEPTION 'Payment must start awaiting' USING ERRCODE='23514'; END IF;
 ELSE
  IF ROW(OLD.id,OLD.request_id,OLD.passenger_id,OLD.amount_cents,OLD.provider,OLD.created_at)
   IS DISTINCT FROM ROW(NEW.id,NEW.request_id,NEW.passenger_id,NEW.amount_cents,NEW.provider,NEW.created_at)
   THEN RAISE EXCEPTION 'Immutable payment contract' USING ERRCODE='23514'; END IF;
  IF OLD.paid_at IS NOT NULL AND ROW(OLD.status,OLD.credit_cents,OLD.external_cents,OLD.method,OLD.credit_requested,OLD.paid_at,OLD.provider_reference,OLD.checkout_url,OLD.gateway_environment)
   IS DISTINCT FROM ROW(NEW.status,NEW.credit_cents,NEW.external_cents,NEW.method,NEW.credit_requested,NEW.paid_at,NEW.provider_reference,NEW.checkout_url,NEW.gateway_environment)
   THEN RAISE EXCEPTION 'Immutable received payment' USING ERRCODE='23514'; END IF;
  IF OLD.provider_reference IS NOT NULL AND ROW(OLD.provider_reference,OLD.checkout_url,OLD.gateway_environment)
   IS DISTINCT FROM ROW(NEW.provider_reference,NEW.checkout_url,NEW.gateway_environment)
   THEN RAISE EXCEPTION 'Immutable provider checkout' USING ERRCODE='23514'; END IF;
  IF OLD.status<>NEW.status AND NOT (
   (OLD.status='awaiting' AND NEW.status IN ('pending','expired')) OR
   (OLD.status='pending' AND NEW.status IN ('approved','failed','expired')) OR
   (OLD.status IN ('failed','expired') AND NEW.status IN ('approved','failed'))
  ) THEN RAISE EXCEPTION 'Invalid payment transition' USING ERRCODE='23514'; END IF;
 END IF;
 IF (NEW.status='approved')<>(NEW.paid_at IS NOT NULL) OR
  (NEW.status='pending' AND NEW.credit_cents+NEW.external_cents<>NEW.amount_cents) OR
  (NEW.checkout_url IS NULL)<>(NEW.provider_reference IS NULL) OR
  (NEW.gateway_environment IS NULL)<>(NEW.provider_reference IS NULL)
  THEN RAISE EXCEPTION 'Invalid payment distribution' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER payment_state BEFORE INSERT OR UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION payment_state_guard();
CREATE TRIGGER payment_no_delete BEFORE DELETE ON payments FOR EACH ROW EXECUTE FUNCTION prevent_history_change();

CREATE FUNCTION payment_ledger_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p payments%ROWTYPE; journal uuid; journal_kind text; external_amount bigint; credit_delta bigint; future_delta bigint;
BEGIN
 SELECT * INTO p FROM payments WHERE id=NEW.id;
 IF NOT EXISTS(SELECT 1 FROM ride_requests q WHERE q.id=p.request_id AND q.passenger_id=p.passenger_id) OR
  p.amount_cents<>(SELECT coalesce(sum(cents),0) FROM request_lines WHERE request_id=p.request_id)
  THEN RAISE EXCEPTION 'Payment disagrees with accepted request' USING ERRCODE='23514'; END IF;
 IF p.paid_at IS NOT NULL THEN
  SELECT id,kind INTO journal,journal_kind FROM ledger_transactions WHERE payment_id=p.id AND
   ((event_key='payment:'||p.id||':confirmed' AND kind='receipt') OR (event_key='payment:'||p.id||':unavailable' AND kind='receipt_credit'));
  IF journal IS NULL THEN RAISE EXCEPTION 'Received payment requires matching ledger' USING ERRCODE='23514'; END IF;
  SELECT coalesce(sum(e.cents) FILTER(WHERE a.owner_id IS NULL AND a.kind='provider_clearing'),0),
   coalesce(sum(e.cents) FILTER(WHERE a.owner_id=p.passenger_id AND a.kind='passenger_credit'),0),
   coalesce(sum(e.cents) FILTER(WHERE a.owner_id=p.passenger_id AND a.kind='future'),0)
   INTO external_amount,credit_delta,future_delta FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id WHERE e.transaction_id=journal;
  IF external_amount<>-p.external_cents OR
   (journal_kind='receipt' AND (credit_delta<>-p.credit_cents OR future_delta<>p.amount_cents OR p.credit_cents+p.external_cents<>p.amount_cents OR
    NOT EXISTS(SELECT 1 FROM ride_requests WHERE id=p.request_id AND status='confirmed'))) OR
   (journal_kind='receipt_credit' AND (credit_delta<>p.external_cents OR future_delta<>0 OR p.credit_cents<>0))
   THEN RAISE EXCEPTION 'Payment distribution disagrees with ledger' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER payment_ledger AFTER INSERT OR UPDATE ON payments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payment_ledger_guard();

CREATE FUNCTION transfer_state_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'pending' OR NEW.provider_reference IS NOT NULL THEN RAISE EXCEPTION 'Transfer must start pending' USING ERRCODE='23514'; END IF;
 ELSE
  IF ROW(OLD.id,OLD.user_id,OLD.type,OLD.cents,OLD.cadence,OLD.created_at)
   IS DISTINCT FROM ROW(NEW.id,NEW.user_id,NEW.type,NEW.cents,NEW.cadence,NEW.created_at)
   THEN RAISE EXCEPTION 'Immutable transfer contract' USING ERRCODE='23514'; END IF;
  IF (OLD.status<>'pending' AND OLD.status<>NEW.status) OR (OLD.provider_reference IS NOT NULL AND OLD.provider_reference IS DISTINCT FROM NEW.provider_reference)
   THEN RAISE EXCEPTION 'Invalid transfer transition' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.status<>'pending' AND NEW.provider_reference IS NULL THEN RAISE EXCEPTION 'Transfer result requires provider reference' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER transfer_state BEFORE INSERT OR UPDATE ON transfers FOR EACH ROW EXECUTE FUNCTION transfer_state_guard();
CREATE TRIGGER transfer_no_delete BEFORE DELETE ON transfers FOR EACH ROW EXECUTE FUNCTION prevent_history_change();

CREATE FUNCTION transfer_ledger_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE transfer transfers%ROWTYPE; journal uuid; source_kind text; pending_kind text; target_kind text; source_delta bigint; target_delta bigint; count_entries integer;
BEGIN
 SELECT * INTO transfer FROM transfers WHERE id=NEW.id;
 source_kind=CASE WHEN transfer.type='withdraw' THEN 'driver_available' ELSE 'passenger_credit' END;
 pending_kind=CASE WHEN transfer.type='withdraw' THEN 'payout_pending' ELSE 'refund_pending' END;
 SELECT id INTO journal FROM ledger_transactions WHERE event_key='transfer:'||transfer.id||':requested' AND kind='transfer_requested';
 SELECT coalesce(sum(e.cents) FILTER(WHERE a.owner_id=transfer.user_id AND a.kind=source_kind),0),
  coalesce(sum(e.cents) FILTER(WHERE a.owner_id=transfer.user_id AND a.kind=pending_kind),0),count(*)
  INTO source_delta,target_delta,count_entries FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id WHERE e.transaction_id=journal;
 IF journal IS NULL OR count_entries<>2 OR source_delta<>-transfer.cents OR target_delta<>transfer.cents
  THEN RAISE EXCEPTION 'Transfer requires matching reserved ledger' USING ERRCODE='23514'; END IF;
 IF transfer.status<>'pending' THEN
  target_kind=CASE WHEN transfer.status='failed' THEN source_kind WHEN transfer.type='withdraw' THEN 'paid_out' ELSE 'refunded' END;
  SELECT id INTO journal FROM ledger_transactions WHERE event_key='transfer:'||transfer.id||':completed' AND kind=(CASE WHEN transfer.status='failed' THEN 'transfer_failed' ELSE transfer.type END);
  SELECT coalesce(sum(e.cents) FILTER(WHERE a.owner_id=transfer.user_id AND a.kind=pending_kind),0),
   coalesce(sum(e.cents) FILTER(WHERE a.owner_id=transfer.user_id AND a.kind=target_kind),0),count(*)
   INTO source_delta,target_delta,count_entries FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id WHERE e.transaction_id=journal;
  IF journal IS NULL OR count_entries<>2 OR source_delta<>-transfer.cents OR target_delta<>transfer.cents
   THEN RAISE EXCEPTION 'Transfer result requires matching ledger' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER transfer_ledger AFTER INSERT OR UPDATE ON transfers DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer_ledger_guard();
