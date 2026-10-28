-- Repeated uploads are deduplicated for the same owner and purpose under the
-- account lock. Keep historic duplicates valid; do not delete stored evidence.
CREATE INDEX stored_files_owner_digest_idx ON stored_files(owner_id,purpose,sha256)
 WHERE deleted_at IS NULL;
CREATE INDEX stored_files_owner_usage_idx ON stored_files(owner_id) INCLUDE(byte_size);
