ALTER TABLE mercado_pago_orders
 ADD COLUMN transaction_id text,
 ADD COLUMN refunded_cents integer NOT NULL DEFAULT 0 CHECK(refunded_cents>=0),
 ADD COLUMN untracked_refunds boolean NOT NULL DEFAULT false;
ALTER TABLE mercado_pago_refunds
 ADD COLUMN transfer_id uuid REFERENCES transfers(id),
 ADD COLUMN transaction_id text,
 ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 ADD COLUMN last_error_code text,
 ADD COLUMN completed_at timestamptz;
CREATE INDEX mercado_pago_refunds_transfer ON mercado_pago_refunds(transfer_id,id);
ALTER TABLE transfers DROP CONSTRAINT transfers_status_check;
ALTER TABLE transfers ADD CONSTRAINT transfers_status_check CHECK(status IN ('pending','approved','failed','partially_refunded'));

-- All old request/result rules remain. The new terminal state must exactly split
-- the requested amount between verified refunds and restored passenger credit.
CREATE OR REPLACE FUNCTION transfer_ledger_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE transfer transfers%ROWTYPE; journal uuid; source_kind text; pending_kind text; target_kind text; source_delta bigint; target_delta bigint; restored bigint; count_entries integer;
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
 IF transfer.status='partially_refunded' THEN
  IF transfer.type<>'refund' THEN RAISE EXCEPTION 'Partial state is only valid for refunds' USING ERRCODE='23514'; END IF;
  SELECT id INTO journal FROM ledger_transactions WHERE event_key='transfer:'||transfer.id||':completed' AND kind='refund_partial';
  SELECT coalesce(sum(e.cents) FILTER(WHERE a.owner_id=transfer.user_id AND a.kind='refund_pending'),0),
   coalesce(sum(e.cents) FILTER(WHERE a.owner_id=transfer.user_id AND a.kind='refunded'),0),
   coalesce(sum(e.cents) FILTER(WHERE a.owner_id=transfer.user_id AND a.kind='passenger_credit'),0),count(*)
   INTO source_delta,target_delta,restored,count_entries FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id WHERE e.transaction_id=journal;
  IF journal IS NULL OR count_entries<>3 OR source_delta<>-transfer.cents OR target_delta<=0 OR restored<=0 OR target_delta+restored<>transfer.cents
   THEN RAISE EXCEPTION 'Partial refund requires exact completed/restored ledger' USING ERRCODE='23514'; END IF;
 ELSIF transfer.status<>'pending' THEN
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

CREATE FUNCTION mercado_pago_refund_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target transfers%ROWTYPE;
BEGIN
 IF TG_OP='UPDATE' THEN
  IF ROW(OLD.id,OLD.payment_id,OLD.user_id,OLD.amount_cents,OLD.reason,OLD.transfer_id,OLD.transaction_id,OLD.created_at)
    IS DISTINCT FROM ROW(NEW.id,NEW.payment_id,NEW.user_id,NEW.amount_cents,NEW.reason,NEW.transfer_id,NEW.transaction_id,NEW.created_at)
    OR (OLD.status IN ('approved','failed') AND OLD.status<>NEW.status)
    OR (OLD.provider_reference IS NOT NULL AND OLD.provider_reference IS DISTINCT FROM NEW.provider_reference)
   THEN RAISE EXCEPTION 'Immutable refund identity/result' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.transfer_id IS NULL THEN RETURN NEW; END IF; -- Preserve any historical, unlinked diagnostic rows.
 SELECT * INTO target FROM transfers WHERE id=NEW.transfer_id;
 IF target.type<>'refund' OR target.user_id<>NEW.user_id
    OR NOT EXISTS(SELECT 1 FROM payments WHERE id=NEW.payment_id AND passenger_id=NEW.user_id AND provider='mercadopago' AND paid_at IS NOT NULL)
   THEN RAISE EXCEPTION 'Refund ownership/receipt mismatch' USING ERRCODE='23514'; END IF;
 IF NEW.status='approved' AND NEW.provider_reference IS NULL THEN RAISE EXCEPTION 'Verified refund reference required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mercado_pago_refund_state BEFORE INSERT OR UPDATE ON mercado_pago_refunds FOR EACH ROW EXECUTE FUNCTION mercado_pago_refund_guard();
CREATE TRIGGER mercado_pago_refund_no_delete BEFORE DELETE ON mercado_pago_refunds FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
CREATE FUNCTION mercado_pago_refund_batch_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target transfers%ROWTYPE; target_id uuid; allocated bigint; approved bigint; failed bigint; pending integer; children integer; returned bigint; restored bigint;
BEGIN
 IF TG_TABLE_NAME='transfers' THEN target_id=NEW.id; ELSE target_id=NEW.transfer_id; END IF;
 IF target_id IS NULL THEN RETURN NULL; END IF;
 SELECT * INTO target FROM transfers WHERE id=target_id;
 SELECT coalesce(sum(amount_cents),0),coalesce(sum(amount_cents) FILTER(WHERE status='approved'),0),
   coalesce(sum(amount_cents) FILTER(WHERE status='failed'),0),count(*) FILTER(WHERE status IN ('pending','processing')),count(*)
   INTO allocated,approved,failed,pending,children FROM mercado_pago_refunds WHERE transfer_id=target.id;
 IF children=0 AND TG_TABLE_NAME='transfers' THEN RETURN NULL; END IF;
 IF allocated<>target.cents THEN RAISE EXCEPTION 'Refund batch differs from reserved request' USING ERRCODE='23514'; END IF;
 IF target.status<>'pending' THEN
  IF pending<>0 OR (target.status='approved' AND approved<>target.cents) OR (target.status='failed' AND failed<>target.cents)
     OR (target.status='partially_refunded' AND (approved<=0 OR failed<=0))
   THEN RAISE EXCEPTION 'Refund batch result differs from verified operations' USING ERRCODE='23514'; END IF;
  SELECT coalesce(sum(e.cents) FILTER(WHERE a.owner_id=target.user_id AND a.kind='refunded'),0),
    coalesce(sum(e.cents) FILTER(WHERE a.owner_id=target.user_id AND a.kind='passenger_credit'),0)
    INTO returned,restored FROM ledger_transactions h JOIN ledger_entries e ON e.transaction_id=h.id JOIN ledger_accounts a ON a.id=e.account_id
    WHERE h.event_key='transfer:'||target.id||':completed';
  IF returned<>approved OR restored<>failed THEN RAISE EXCEPTION 'Refund journal differs from verified operations' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER mercado_pago_refund_batch AFTER INSERT OR UPDATE ON mercado_pago_refunds DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION mercado_pago_refund_batch_guard();
CREATE CONSTRAINT TRIGGER mercado_pago_transfer_refunds AFTER INSERT OR UPDATE ON transfers DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION mercado_pago_refund_batch_guard();
