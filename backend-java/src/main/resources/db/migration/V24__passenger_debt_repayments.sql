-- A receivable is a negative balance, distinct from spendable passenger credit.
ALTER TABLE ledger_accounts DROP CONSTRAINT ledger_accounts_kind_check;
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_kind_check CHECK(kind IN
 ('provider_clearing','future','blocked','driver_available','passenger_credit','commission','retained','payout_pending','refund_pending','paid_out','refunded','passenger_receivable'));
CREATE OR REPLACE FUNCTION nonnegative_ledger_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE k text; total bigint;
BEGIN
 SELECT kind INTO k FROM ledger_accounts WHERE id=NEW.account_id FOR UPDATE;
 SELECT coalesce(sum(cents),0) INTO total FROM ledger_entries WHERE account_id=NEW.account_id;
 IF (k='passenger_receivable' AND total>0) OR (k NOT IN ('provider_clearing','passenger_receivable') AND total<0)
 THEN RAISE EXCEPTION 'Invalid ledger account balance' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE TABLE passenger_debt_payments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 passenger_id uuid NOT NULL REFERENCES users,
 amount_cents integer NOT NULL CHECK(amount_cents>0),
 payer_email text NOT NULL,
 state text NOT NULL DEFAULT 'CREATED' CHECK(state IN ('CREATED','PENDING','PROCESSING','UNDER_REVIEW','APPROVED','REJECTED','CANCELLED','EXPIRED','REFUNDED','PARTIALLY_REFUNDED')),
 order_id text UNIQUE,
 transaction_id text,
 provider_version bigint NOT NULL DEFAULT 0,
 status_detail text,
 qr_code text, qr_base64 text,
 started_at timestamptz, paid_at timestamptz,
 refunded_cents integer NOT NULL DEFAULT 0 CHECK(refunded_cents>=0 AND refunded_cents<=amount_cents),
 last_error_code text,
 expires_at timestamptz NOT NULL DEFAULT now()+interval '30 minutes',
 next_check_at timestamptz NOT NULL DEFAULT now(),
 lease_id uuid, lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX passenger_debt_single_open ON passenger_debt_payments(passenger_id) WHERE state IN ('CREATED','PENDING','PROCESSING','UNDER_REVIEW');
CREATE INDEX passenger_debt_payment_checks ON passenger_debt_payments(next_check_at);
CREATE TABLE passenger_debt_allocations (
 repayment_id uuid NOT NULL REFERENCES passenger_debt_payments,
 payment_id uuid NOT NULL REFERENCES passenger_debt_assessments,
 cents integer NOT NULL CHECK(cents>0),
 reversed_cents integer NOT NULL DEFAULT 0 CHECK(reversed_cents>=0 AND reversed_cents<=cents),
 PRIMARY KEY(repayment_id,payment_id)
);
CREATE TABLE passenger_debt_releases (
 payment_id uuid PRIMARY KEY REFERENCES passenger_debt_assessments,
 waived_cents bigint NOT NULL CHECK(waived_cents>=0),
 credit_cents bigint NOT NULL CHECK(credit_cents>=0),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER passenger_debt_release_no_update BEFORE UPDATE OR DELETE ON passenger_debt_releases FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
CREATE FUNCTION passenger_debt_payment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(OLD.id,OLD.passenger_id,OLD.amount_cents,OLD.payer_email,OLD.expires_at,OLD.created_at)
  IS DISTINCT FROM ROW(NEW.id,NEW.passenger_id,NEW.amount_cents,NEW.payer_email,NEW.expires_at,NEW.created_at)
  OR (OLD.order_id IS NOT NULL AND OLD.order_id IS DISTINCT FROM NEW.order_id)
  OR (OLD.transaction_id IS NOT NULL AND OLD.transaction_id IS DISTINCT FROM NEW.transaction_id)
  OR (OLD.paid_at IS NOT NULL AND OLD.paid_at IS DISTINCT FROM NEW.paid_at)
  OR NEW.refunded_cents<OLD.refunded_cents OR NEW.provider_version<OLD.provider_version
 THEN RAISE EXCEPTION 'Immutable debt payment identity' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER passenger_debt_payment_state BEFORE UPDATE ON passenger_debt_payments FOR EACH ROW EXECUTE FUNCTION passenger_debt_payment_guard();
CREATE TRIGGER passenger_debt_payment_no_delete BEFORE DELETE ON passenger_debt_payments FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
CREATE FUNCTION passenger_debt_allocation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(OLD.repayment_id,OLD.payment_id,OLD.cents) IS DISTINCT FROM ROW(NEW.repayment_id,NEW.payment_id,NEW.cents)
  OR NEW.reversed_cents<OLD.reversed_cents
 THEN RAISE EXCEPTION 'Immutable debt allocation' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER passenger_debt_allocation_state BEFORE UPDATE ON passenger_debt_allocations FOR EACH ROW EXECUTE FUNCTION passenger_debt_allocation_guard();
CREATE TRIGGER passenger_debt_allocation_no_delete BEFORE DELETE ON passenger_debt_allocations FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
