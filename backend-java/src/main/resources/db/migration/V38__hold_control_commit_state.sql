-- Marker for the database/filesystem commit gap. No user data or decision reason belongs here.
CREATE TABLE privacy_hold_control_state (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 sequence bigint NOT NULL DEFAULT 0 CHECK(sequence>=0),operation_id uuid,
 needs_confirmation boolean NOT NULL DEFAULT false,
 CHECK((sequence=0 AND operation_id IS NULL AND NOT needs_confirmation) OR (sequence>0 AND operation_id IS NOT NULL))
);
INSERT INTO privacy_hold_control_state(singleton) VALUES(true);
CREATE FUNCTION protect_hold_control_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'Hold control checkpoint cannot be removed' USING ERRCODE='23514'; END IF;
 IF NEW.sequence<OLD.sequence OR (NEW.sequence=OLD.sequence AND NEW.operation_id IS DISTINCT FROM OLD.operation_id) THEN
  RAISE EXCEPTION 'Hold control checkpoint cannot regress or change identity' USING ERRCODE='23514';
 END IF;
 IF NEW.sequence=OLD.sequence AND OLD.needs_confirmation AND NOT NEW.needs_confirmation
    AND current_setting('unicarona.privacy_hold_control_confirmed',true) IS DISTINCT FROM 'on' THEN
  RAISE EXCEPTION 'Hold control confirmation needs verified coordination' USING ERRCODE='23514';
 END IF;
 IF NEW.sequence>OLD.sequence AND (NEW.sequence<>OLD.sequence+1 OR NOT NEW.needs_confirmation)
    AND current_setting('unicarona.privacy_hold_control_replay',true) IS DISTINCT FROM 'on' THEN
  RAISE EXCEPTION 'Hold control advancement needs preparation or verified replay' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER privacy_hold_control_monotonic BEFORE UPDATE OR DELETE ON privacy_hold_control_state
 FOR EACH ROW EXECUTE FUNCTION protect_hold_control_state();
CREATE TRIGGER privacy_hold_control_no_truncate BEFORE TRUNCATE ON privacy_hold_control_state
 FOR EACH STATEMENT EXECUTE FUNCTION protect_hold_control_state();
CREATE OR REPLACE FUNCTION privacy_retention_coordination() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(74019,1);
 IF EXISTS(SELECT 1 FROM privacy_hold_control_state WHERE singleton AND needs_confirmation) THEN
  RAISE EXCEPTION 'Independent hold control confirmation is pending' USING ERRCODE='55000';
 END IF;
 RETURN NULL;
END;
$$;
