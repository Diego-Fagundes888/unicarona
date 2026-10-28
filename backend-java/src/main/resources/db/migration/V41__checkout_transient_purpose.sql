-- A checkout credential is not a financial receipt. Keep monetary identities and
-- histories intact while permitting one-way removal of completed request contact.
ALTER TABLE mercado_pago_pix_commands ALTER COLUMN payer_email DROP NOT NULL;
ALTER TABLE mercado_pago_pix_commands ADD CONSTRAINT pix_pending_payer_required
 CHECK(status<>'pending' OR payer_email IS NOT NULL);
ALTER TABLE passenger_debt_payments ALTER COLUMN payer_email DROP NOT NULL;

CREATE FUNCTION privacy_debt_payer_required(p passenger_debt_payments) RETURNS boolean LANGUAGE sql AS $$
 SELECT p.order_id IS NULL AND NOT (p.started_at IS NULL AND p.state IN ('REJECTED','CANCELLED','EXPIRED'));
$$;
ALTER TABLE passenger_debt_payments ADD CONSTRAINT debt_pending_payer_required
 CHECK(payer_email IS NOT NULL OR order_id IS NOT NULL OR
   (started_at IS NULL AND state IN ('REJECTED','CANCELLED','EXPIRED')));

CREATE FUNCTION privacy_order_checkout_current(payment uuid, lifecycle text) RETURNS boolean LANGUAGE sql AS $$
 SELECT lifecycle IN ('CREATED','PENDING','PROCESSING') AND EXISTS(
   SELECT 1 FROM payments p JOIN ride_requests q ON q.id=p.request_id WHERE p.id=payment
     AND p.provider='mercadopago' AND p.paid_at IS NULL AND p.status IN ('awaiting','pending')
     AND p.expires_at>clock_timestamp() AND q.status='accepted' AND q.expires_at>clock_timestamp());
$$;
CREATE FUNCTION privacy_debt_checkout_current(p passenger_debt_payments) RETURNS boolean LANGUAGE sql AS $$
 SELECT p.order_id IS NOT NULL AND p.paid_at IS NULL AND p.state IN ('CREATED','PENDING','PROCESSING')
   AND p.expires_at>clock_timestamp();
$$;

CREATE FUNCTION privacy_pix_command_purpose() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND (
   ROW(OLD.payment_id,OLD.amount_cents,OLD.expiration_seconds,OLD.created_at)
     IS DISTINCT FROM ROW(NEW.payment_id,NEW.amount_cents,NEW.expiration_seconds,NEW.created_at)
   OR (OLD.payer_email IS DISTINCT FROM NEW.payer_email AND NOT (
     OLD.payer_email IS NOT NULL AND NEW.payer_email IS NULL AND NEW.status<>'pending'))
 ) THEN RAISE EXCEPTION 'Immutable Pix command identity' USING ERRCODE='23514'; END IF;
 IF NEW.status<>'pending' THEN NEW.payer_email=NULL; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER pix_command_purpose BEFORE INSERT OR UPDATE ON mercado_pago_pix_commands
 FOR EACH ROW EXECUTE FUNCTION privacy_pix_command_purpose();

CREATE OR REPLACE FUNCTION passenger_debt_payment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(OLD.id,OLD.passenger_id,OLD.amount_cents,OLD.expires_at,OLD.created_at)
  IS DISTINCT FROM ROW(NEW.id,NEW.passenger_id,NEW.amount_cents,NEW.expires_at,NEW.created_at)
  OR (OLD.payer_email IS DISTINCT FROM NEW.payer_email AND NOT (
    OLD.payer_email IS NOT NULL AND NEW.payer_email IS NULL AND NOT privacy_debt_payer_required(NEW)))
  OR (OLD.order_id IS NOT NULL AND OLD.order_id IS DISTINCT FROM NEW.order_id)
  OR (OLD.transaction_id IS NOT NULL AND OLD.transaction_id IS DISTINCT FROM NEW.transaction_id)
  OR (OLD.paid_at IS NOT NULL AND OLD.paid_at IS DISTINCT FROM NEW.paid_at)
  OR NEW.refunded_cents<OLD.refunded_cents OR NEW.provider_version<OLD.provider_version
 THEN RAISE EXCEPTION 'Immutable debt payment identity' USING ERRCODE='23514'; END IF;
 IF NOT privacy_debt_payer_required(NEW) THEN NEW.payer_email=NULL; END IF;
 IF NOT privacy_debt_checkout_current(NEW) THEN NEW.qr_code=NULL;NEW.qr_base64=NULL; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION privacy_order_checkout_purpose() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE method text;
BEGIN
 SELECT p.method INTO method FROM payments p WHERE p.id=NEW.payment_id;
 -- No client in this product consumes the provider ticket URL.
 NEW.ticket_url=NULL;
 IF NOT privacy_order_checkout_current(NEW.payment_id,NEW.state) THEN
   NEW.qr_code=NULL;NEW.qr_base64=NULL;NEW.challenge_url=NULL;
 ELSE
   IF method IS DISTINCT FROM 'pix' THEN NEW.qr_code=NULL;NEW.qr_base64=NULL;END IF;
   IF method IS DISTINCT FROM 'card' OR NEW.status_detail<>'pending_challenge' THEN NEW.challenge_url=NULL;END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER order_checkout_purpose BEFORE INSERT OR UPDATE ON mercado_pago_orders
 FOR EACH ROW EXECUTE FUNCTION privacy_order_checkout_purpose();

CREATE INDEX checkout_order_transients ON mercado_pago_orders(payment_id)
 WHERE qr_code IS NOT NULL OR qr_base64 IS NOT NULL OR ticket_url IS NOT NULL OR challenge_url IS NOT NULL;
CREATE INDEX checkout_completed_pix_contact ON mercado_pago_pix_commands(payment_id)
 WHERE payer_email IS NOT NULL AND status<>'pending';
-- Existing rows are not erased by migration. Startup/worker minimization first
-- verifies the independent preservation checkpoint and current owner/resource.
