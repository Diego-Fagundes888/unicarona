-- Keep abandoned-upload scanning separate from payment/closure work.
-- Reusing a deduplicated upload restarts its one-day allowance before form submission.
ALTER TABLE stored_files ADD COLUMN last_uploaded_at timestamptz;
UPDATE stored_files SET last_uploaded_at=created_at;
ALTER TABLE stored_files ALTER COLUMN last_uploaded_at SET DEFAULT now();
ALTER TABLE stored_files ALTER COLUMN last_uploaded_at SET NOT NULL;
CREATE INDEX stored_files_unused_scan ON stored_files(last_uploaded_at,id) WHERE erasure_requested_at IS NULL;
