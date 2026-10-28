-- NULL means this database has not yet completed guarded startup. Never manufacture freshness for an old restore.
CREATE TABLE privacy_restore_state (
 singleton boolean PRIMARY KEY CHECK(singleton),
 dataset_id uuid NOT NULL DEFAULT gen_random_uuid(),
 observed_at timestamptz
);
INSERT INTO privacy_restore_state(singleton,observed_at) VALUES(true,NULL);
CREATE FUNCTION privacy_restore_monotonic() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR NEW.dataset_id<>OLD.dataset_id OR (OLD.observed_at IS NOT NULL AND (NEW.observed_at IS NULL OR NEW.observed_at<OLD.observed_at)) THEN
  RAISE EXCEPTION 'Privacy recovery watermark cannot regress' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER privacy_restore_state_monotonic BEFORE UPDATE OR DELETE ON privacy_restore_state
 FOR EACH ROW EXECUTE FUNCTION privacy_restore_monotonic();
-- A file-only restore can reintroduce bytes after a short-lived user suppression entry has expired.
ALTER TABLE stored_files ADD COLUMN erasure_last_scan_at timestamptz NOT NULL DEFAULT 'epoch';
CREATE INDEX stored_files_suppression_scan ON stored_files(erasure_last_scan_at,id) WHERE erased_at IS NOT NULL;
-- Reapplying an old signed deletion may happen after its minimum suppression deadline.
-- Keep the actual reapplied time distinct from the original preparation time.
ALTER TABLE privacy_erasure_events ADD COLUMN original_prepared_at timestamptz;
UPDATE privacy_erasure_events SET original_prepared_at=erased_at;
ALTER TABLE privacy_erasure_events ALTER COLUMN original_prepared_at SET NOT NULL;
ALTER TABLE privacy_erasure_events DROP CONSTRAINT privacy_erasure_events_check;
ALTER TABLE privacy_erasure_events ADD CONSTRAINT privacy_erasure_events_check
 CHECK(suppression_until>original_prepared_at AND erased_at>=original_prepared_at);
