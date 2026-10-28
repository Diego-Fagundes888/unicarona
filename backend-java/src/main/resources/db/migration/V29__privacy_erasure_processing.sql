-- An irreversible processing stage is separate from the reversible grace period.
ALTER TABLE privacy_account_closures DROP CONSTRAINT privacy_account_closures_status_check;
ALTER TABLE privacy_account_closures ADD CONSTRAINT privacy_account_closures_status_check
 CHECK(status IN ('pending_obligations','scheduled','erasing','cancelled','completed'));
DROP INDEX privacy_one_open_closure;
CREATE UNIQUE INDEX privacy_one_open_closure ON privacy_account_closures(user_id)
 WHERE status IN ('pending_obligations','scheduled','erasing');
ALTER TABLE users ALTER COLUMN birth_date DROP NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_live_birth_required CHECK(deleted_at IS NOT NULL OR birth_date IS NOT NULL);
ALTER TABLE stored_files ADD COLUMN erasure_requested_at timestamptz;
ALTER TABLE stored_files ADD COLUMN erased_at timestamptz;
ALTER TABLE stored_files ADD COLUMN erasure_retry_after timestamptz NOT NULL DEFAULT now();
ALTER TABLE stored_files ADD COLUMN erasure_attempts integer NOT NULL DEFAULT 0 CHECK(erasure_attempts>=0);
ALTER TABLE stored_files ADD CONSTRAINT stored_file_erasure_state
 CHECK(erased_at IS NULL OR (deleted_at IS NOT NULL AND erasure_requested_at IS NOT NULL AND erased_at>=erasure_requested_at));
CREATE INDEX stored_files_pending_erasure ON stored_files(erasure_retry_after,id)
 WHERE erasure_requested_at IS NOT NULL AND erased_at IS NULL;
CREATE TABLE privacy_erasure_tasks (
 closure_id uuid PRIMARY KEY REFERENCES privacy_account_closures,
 user_id uuid NOT NULL REFERENCES users,
 status text NOT NULL CHECK(status IN ('prepared','scrubbed','completed')),
 prepared_at timestamptz NOT NULL DEFAULT now(),
 suppression_until timestamptz NOT NULL,
 journaled_at timestamptz, scrubbed_at timestamptz, completed_at timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT now(), attempts integer NOT NULL DEFAULT 0,
 last_error_code text,
 CHECK(status='prepared' OR (journaled_at IS NOT NULL AND scrubbed_at IS NOT NULL)),
 CHECK((status='completed')=(completed_at IS NOT NULL))
);
CREATE INDEX privacy_erasure_retry ON privacy_erasure_tasks(next_attempt_at,closure_id) WHERE status<>'completed';
ALTER TABLE privacy_retained_records ADD COLUMN resource_type text;
ALTER TABLE privacy_retained_records ADD COLUMN resource_id uuid;
CREATE INDEX privacy_retained_resource ON privacy_retained_records(user_id,category,resource_type,resource_id);
