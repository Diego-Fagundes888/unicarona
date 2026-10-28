ALTER TABLE auth_tokens DROP CONSTRAINT auth_tokens_kind_check;
ALTER TABLE auth_tokens ADD CONSTRAINT auth_tokens_kind_check CHECK(kind IN ('verify_email','reset_password','change_email'));
-- A correction can reveal a minor; the accurate date may remain only in a restricted account.
-- Registration and restoration to the transport service still require adulthood.
ALTER TABLE users DROP CONSTRAINT users_adult;
ALTER TABLE users ADD CONSTRAINT users_adult CHECK(birth_date<=(now() AT TIME ZONE 'America/Sao_Paulo')::date AND
 (birth_date<=((now() AT TIME ZONE 'America/Sao_Paulo')::date-interval '18 years')::date OR account_status IN ('suspended','banned','closing','deleted')));
CREATE TABLE privacy_email_changes (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL REFERENCES users,
 token_id uuid UNIQUE REFERENCES auth_tokens ON DELETE SET NULL,
 new_email_ciphertext text,new_email_hmac text NOT NULL CHECK(new_email_hmac ~ '^[a-f0-9]{64}$'),
 original_email_hmac text NOT NULL CHECK(original_email_hmac ~ '^[a-f0-9]{64}$'),
 credential_hmac text,eligible boolean NOT NULL,
 status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','confirmed','cancelled','expired')),
 created_at timestamptz NOT NULL DEFAULT now(),expires_at timestamptz NOT NULL,
 completed_at timestamptz,
 CHECK(expires_at>created_at),
 CHECK((status='pending')=(new_email_ciphertext IS NOT NULL AND credential_hmac IS NOT NULL)),
 CHECK((status='pending')=(completed_at IS NULL))
);
CREATE UNIQUE INDEX privacy_one_pending_email_change ON privacy_email_changes(user_id) WHERE status='pending';
CREATE INDEX privacy_email_change_expiry ON privacy_email_changes(expires_at) WHERE status='pending';
