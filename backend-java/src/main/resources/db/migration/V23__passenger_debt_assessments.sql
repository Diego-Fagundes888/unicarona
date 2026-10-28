-- Separate receivables from spendable credit and driver balances. Only verified
-- provider observations may create an assessment; repayment is a separate flow.
CREATE TABLE passenger_debt_assessments (
 payment_id uuid PRIMARY KEY REFERENCES payments(id),
 passenger_id uuid NOT NULL REFERENCES users(id),
 reversal_cents bigint NOT NULL CHECK(reversal_cents>0),
 held_credit_cents bigint NOT NULL CHECK(held_credit_cents>=0),
 debt_cents bigint NOT NULL CHECK(debt_cents>=0),
 reason text NOT NULL CHECK(reason IN ('external_refund','settled_chargeback')),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(held_credit_cents+debt_cents=reversal_cents)
);
CREATE INDEX passenger_debt_owner ON passenger_debt_assessments(passenger_id,payment_id);
CREATE FUNCTION passenger_debt_assessment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(OLD.payment_id,OLD.passenger_id,OLD.created_at) IS DISTINCT FROM ROW(NEW.payment_id,NEW.passenger_id,NEW.created_at)
    OR NEW.reversal_cents<OLD.reversal_cents OR NEW.held_credit_cents<OLD.held_credit_cents OR NEW.debt_cents<OLD.debt_cents
 THEN RAISE EXCEPTION 'Immutable debt assessment history' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER passenger_debt_assessment_state BEFORE UPDATE ON passenger_debt_assessments FOR EACH ROW EXECUTE FUNCTION passenger_debt_assessment_guard();
CREATE TRIGGER passenger_debt_assessment_no_delete BEFORE DELETE ON passenger_debt_assessments FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
