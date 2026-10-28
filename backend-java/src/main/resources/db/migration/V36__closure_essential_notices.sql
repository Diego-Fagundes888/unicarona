ALTER TABLE privacy_account_closures ADD COLUMN completion_contact_ciphertext text;
ALTER TABLE privacy_account_closures ADD COLUMN notice_contact_expires_at timestamptz;
ALTER TABLE privacy_account_closures ADD COLUMN completion_notice_enqueued_at timestamptz;
ALTER TABLE privacy_account_closures ADD COLUMN notice_suppressed_at timestamptz;
ALTER TABLE privacy_account_closures ADD COLUMN notice_retry_after timestamptz NOT NULL DEFAULT now();
ALTER TABLE privacy_account_closures ADD CONSTRAINT privacy_closure_contact_expiry
 CHECK(completion_contact_ciphertext IS NULL OR notice_contact_expires_at IS NOT NULL);
CREATE INDEX privacy_closure_contact_cleanup ON privacy_account_closures(notice_contact_expires_at,id)
 WHERE completion_contact_ciphertext IS NOT NULL;
