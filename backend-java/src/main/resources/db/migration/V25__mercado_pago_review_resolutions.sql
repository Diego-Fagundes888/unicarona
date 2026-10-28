-- A resolution records verified operations and a matching immutable journal,
-- rather than clearing a financial hold without accounting for its value.
ALTER TABLE mercado_pago_orders ADD COLUMN review_receipt_id uuid REFERENCES webhook_events;
ALTER TABLE mercado_pago_orders ADD COLUMN review_checked_at timestamptz;
ALTER TABLE passenger_debt_releases ADD COLUMN held_restored_cents bigint NOT NULL DEFAULT 0 CHECK(held_restored_cents>=0);
CREATE TABLE mercado_pago_review_resolutions (
 id uuid PRIMARY KEY,
 command_id uuid NOT NULL UNIQUE,
 payment_id uuid NOT NULL REFERENCES payments,
 actor_id uuid NOT NULL REFERENCES users,
 reason text NOT NULL CHECK(length(reason) BETWEEN 3 AND 1000),
 kind text NOT NULL CHECK(kind IN ('external_refund','settled_chargeback','reimbursed_chargeback')),
 evidence_key text NOT NULL CHECK(evidence_key ~ '^[a-f0-9]{64}$'),
 receipt_id uuid NOT NULL REFERENCES webhook_events,
 reversal_cents bigint NOT NULL CHECK(reversal_cents>=0),
 held_credit_cents bigint NOT NULL CHECK(held_credit_cents>=0),
 debt_cents bigint NOT NULL CHECK(debt_cents>=0),
 ledger_id uuid UNIQUE REFERENCES ledger_transactions,
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK((held_credit_cents=0)=(ledger_id IS NULL)),
 UNIQUE(payment_id,evidence_key)
);
CREATE INDEX mercado_pago_review_resolutions_payment ON mercado_pago_review_resolutions(payment_id,created_at);
CREATE TABLE mercado_pago_resolved_refunds (
 provider_reference text PRIMARY KEY REFERENCES mercado_pago_observed_refunds,
 resolution_id uuid NOT NULL REFERENCES mercado_pago_review_resolutions
);
CREATE FUNCTION mercado_pago_resolution_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r mercado_pago_review_resolutions%ROWTYPE; owner uuid; proof webhook_events%ROWTYPE;
 credit_delta bigint; clearing_delta bigint; entries integer; held bigint; applied bigint;
BEGIN
 IF TG_TABLE_NAME='mercado_pago_resolved_refunds' THEN
  SELECT * INTO r FROM mercado_pago_review_resolutions WHERE id=NEW.resolution_id;
  IF NOT EXISTS(SELECT 1 FROM mercado_pago_observed_refunds o WHERE o.provider_reference=NEW.provider_reference AND o.payment_id=r.payment_id AND o.status='processed')
    OR EXISTS(SELECT 1 FROM mercado_pago_refunds f WHERE f.provider_reference=NEW.provider_reference AND f.transfer_id IS NOT NULL)
  THEN RAISE EXCEPTION 'Resolution requires an unmatched verified processed refund' USING ERRCODE='23514'; END IF;
  RETURN NULL;
 END IF;
 r=NEW;
 SELECT passenger_id INTO owner FROM payments WHERE id=r.payment_id AND paid_at IS NOT NULL AND provider='mercadopago';
 SELECT * INTO proof FROM webhook_events WHERE id=r.receipt_id;
 IF owner IS NULL OR proof.provider IS DISTINCT FROM 'mercadopago' OR proof.status IS DISTINCT FROM 'processed'
    OR proof.payload->>'payment' IS DISTINCT FROM r.payment_id::text
 THEN RAISE EXCEPTION 'Resolution requires verified receipt identity' USING ERRCODE='23514'; END IF;
 SELECT coalesce(held_credit_cents,0) INTO held FROM passenger_debt_assessments WHERE payment_id=r.payment_id;
 SELECT coalesce(sum(held_credit_cents),0) INTO applied FROM mercado_pago_review_resolutions WHERE payment_id=r.payment_id;
 IF applied>coalesce(held,0) THEN RAISE EXCEPTION 'Resolution exceeds held cancellation credit' USING ERRCODE='23514'; END IF;
 IF r.held_credit_cents>0 THEN
  SELECT coalesce(sum(e.cents) FILTER(WHERE a.owner_id=owner AND a.kind='passenger_credit'),0),
   coalesce(sum(e.cents) FILTER(WHERE a.owner_id IS NULL AND a.kind='provider_clearing'),0),count(*)
   INTO credit_delta,clearing_delta,entries FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id WHERE e.transaction_id=r.ledger_id;
  IF entries<>2 OR credit_delta<>-r.held_credit_cents OR clearing_delta<>r.held_credit_cents
    OR NOT EXISTS(SELECT 1 FROM ledger_transactions WHERE id=r.ledger_id AND event_key='review:'||r.id||':credit-reversal' AND kind='external_credit_reversal')
  THEN RAISE EXCEPTION 'Resolution requires matching credit reversal ledger' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER mercado_pago_resolution_ledger AFTER INSERT ON mercado_pago_review_resolutions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION mercado_pago_resolution_guard();
CREATE CONSTRAINT TRIGGER mercado_pago_resolved_refund_identity AFTER INSERT ON mercado_pago_resolved_refunds DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION mercado_pago_resolution_guard();
CREATE TRIGGER mercado_pago_resolution_history BEFORE UPDATE OR DELETE ON mercado_pago_review_resolutions FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
CREATE TRIGGER mercado_pago_resolved_refund_history BEFORE UPDATE OR DELETE ON mercado_pago_resolved_refunds FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
