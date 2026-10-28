-- Existing auth token hashes/identities and the transactional outbox are preserved.
ALTER TABLE auth_tokens ADD COLUMN invalidated_at timestamptz;
ALTER TABLE auth_tokens ADD COLUMN sent_at timestamptz;
ALTER TABLE auth_tokens ADD COLUMN last_delivery_error_at timestamptz;
CREATE INDEX auth_tokens_current_verification_idx ON auth_tokens(user_id,kind,created_at DESC)
 WHERE used_at IS NULL AND invalidated_at IS NULL;
