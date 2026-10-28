ALTER TABLE users ADD CONSTRAINT users_adult CHECK(birth_date <= (CURRENT_DATE - INTERVAL '18 years')::date);

CREATE FUNCTION prevent_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Immutable history' USING ERRCODE='23514'; END $$;
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON audit_logs FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
CREATE TRIGGER ledger_transaction_immutable BEFORE UPDATE OR DELETE ON ledger_transactions FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
CREATE TRIGGER ledger_entry_immutable BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION prevent_history_change();

CREATE FUNCTION balanced_ledger_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; total bigint; n bigint;
BEGIN
 target := CASE WHEN TG_TABLE_NAME='ledger_transactions' THEN NEW.id ELSE NEW.transaction_id END;
 SELECT coalesce(sum(cents),0),count(*) INTO total,n FROM ledger_entries WHERE transaction_id=target;
 IF total<>0 OR n<2 THEN RAISE EXCEPTION 'Unbalanced ledger' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ledger_balance_header AFTER INSERT ON ledger_transactions
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION balanced_ledger_transaction();
CREATE CONSTRAINT TRIGGER ledger_balance_entry AFTER INSERT ON ledger_entries
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION balanced_ledger_transaction();

CREATE FUNCTION capacity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; seats integer; used bigint;
BEGIN
 IF TG_TABLE_NAME='rides' THEN target:=NEW.id; ELSE target:=NEW.ride_id; END IF;
 SELECT capacity INTO seats FROM rides WHERE id=target FOR UPDATE;
 IF seats IS NULL THEN RETURN NEW; END IF;
 SELECT (SELECT count(*) FROM ride_reservations WHERE ride_id=target AND status IN ('confirmed','boarded','awaiting_confirmation'))
      + (SELECT count(*) FROM subscription_schedule WHERE ride_id=target AND held) INTO used;
 IF used>seats THEN RAISE EXCEPTION 'Ride capacity exceeded' USING ERRCODE='23514',CONSTRAINT='ride_capacity_guard'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reservation_capacity AFTER INSERT OR UPDATE ON ride_reservations FOR EACH ROW EXECUTE FUNCTION capacity_guard();
CREATE TRIGGER held_capacity AFTER INSERT OR UPDATE ON subscription_schedule FOR EACH ROW EXECUTE FUNCTION capacity_guard();
CREATE TRIGGER ride_capacity AFTER UPDATE OF capacity ON rides FOR EACH ROW EXECUTE FUNCTION capacity_guard();

CREATE FUNCTION student_class_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.class_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM classes WHERE id=NEW.class_id AND period_number=NEW.period_number) THEN
  RAISE EXCEPTION 'Class period mismatch' USING ERRCODE='23514';
 END IF;
 IF NEW.period_status<>'numbered' AND NEW.class_id IS NOT NULL THEN
  RAISE EXCEPTION 'Class requires numbered period' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER student_class BEFORE INSERT OR UPDATE ON student_profiles FOR EACH ROW EXECUTE FUNCTION student_class_guard();

CREATE FUNCTION ride_transition_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>NEW.status AND NOT (
  (OLD.status='planned' AND NEW.status IN ('active','cancelled','no_class')) OR
  (OLD.status='active' AND NEW.status IN ('completed','cancelled'))
 ) THEN RAISE EXCEPTION 'Invalid ride transition' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ride_transition BEFORE UPDATE OF status ON rides FOR EACH ROW EXECUTE FUNCTION ride_transition_guard();
