-- Keep verified external identities even if a later GET omits refund history.
CREATE TABLE mercado_pago_observed_refunds (
 provider_reference text PRIMARY KEY CHECK(provider_reference ~ '^REF[A-Za-z0-9]{10,64}$'),
 payment_id uuid NOT NULL REFERENCES payments(id),
 transaction_id text NOT NULL CHECK(transaction_id ~ '^PAY[A-Za-z0-9]{10,64}$'),
 amount_cents integer NOT NULL CHECK(amount_cents>0),
 status text NOT NULL CHECK(length(status) BETWEEN 1 AND 80),
 first_observed_at timestamptz NOT NULL DEFAULT now(),
 last_observed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX mercado_pago_observed_refunds_payment ON mercado_pago_observed_refunds(payment_id,provider_reference);

-- Backfill only sanitized snapshots already processed by the backend, not raw notifications.
INSERT INTO mercado_pago_observed_refunds(provider_reference,payment_id,transaction_id,amount_cents,status,first_observed_at,last_observed_at)
SELECT DISTINCT ON (r->>'id') r->>'id',p.id,r->>'transactionId',(r->>'cents')::integer,r->>'status',h.received_at,h.received_at
FROM webhook_events h JOIN payments p ON p.id::text=h.payload->>'payment' AND p.provider='mercadopago'
CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(h.payload->'refunds')='array' THEN h.payload->'refunds' ELSE '[]'::jsonb END) r
WHERE h.provider='mercadopago' AND h.status='processed'
 AND r->>'id' ~ '^REF[A-Za-z0-9]{10,64}$' AND r->>'transactionId' ~ '^PAY[A-Za-z0-9]{10,64}$'
 AND CASE WHEN r->>'cents' ~ '^[1-9][0-9]{0,9}$' THEN (r->>'cents')::numeric BETWEEN 1 AND 2147483647 ELSE false END
 AND length(r->>'status') BETWEEN 1 AND 80
ORDER BY r->>'id',CASE WHEN r->>'status' IN ('processed','failed','canceled','cancelled') THEN 1 ELSE 0 END DESC,h.received_at DESC;

ALTER TABLE mercado_pago_orders ADD COLUMN refund_history_incomplete boolean NOT NULL DEFAULT false;
-- Missing historical evidence must not silently release an existing financial hold.
UPDATE mercado_pago_orders m SET refund_history_incomplete=true
WHERE untracked_refunds AND NOT EXISTS(SELECT 1 FROM mercado_pago_observed_refunds o WHERE o.payment_id=m.payment_id);

CREATE FUNCTION mercado_pago_observed_refund_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(OLD.provider_reference,OLD.payment_id,OLD.transaction_id,OLD.amount_cents,OLD.first_observed_at)
    IS DISTINCT FROM ROW(NEW.provider_reference,NEW.payment_id,NEW.transaction_id,NEW.amount_cents,NEW.first_observed_at)
    OR (OLD.status IN ('processed','failed','canceled','cancelled') AND OLD.status<>NEW.status)
  THEN RAISE EXCEPTION 'Immutable observed refund identity/result' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mercado_pago_observed_refund_state BEFORE UPDATE ON mercado_pago_observed_refunds FOR EACH ROW EXECUTE FUNCTION mercado_pago_observed_refund_guard();
CREATE TRIGGER mercado_pago_observed_refund_no_delete BEFORE DELETE ON mercado_pago_observed_refunds FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
