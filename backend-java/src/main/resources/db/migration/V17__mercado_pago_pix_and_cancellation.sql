-- Persist the complete Pix request before its first external call. Retried expiration is identical.
CREATE TABLE mercado_pago_pix_commands (
 payment_id uuid PRIMARY KEY REFERENCES payments(id),
 amount_cents integer NOT NULL CHECK(amount_cents>0),
 payer_email text NOT NULL,
 expiration_seconds integer NOT NULL CHECK(expiration_seconds BETWEEN 1800 AND 2592000),
 status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','done','failed')),
 started_at timestamptz,
 last_error_code text,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX mercado_pago_pix_commands_pending ON mercado_pago_pix_commands(updated_at) WHERE status='pending';
ALTER TABLE mercado_pago_orders
 ADD COLUMN cancel_command_id uuid UNIQUE,
 ADD COLUMN cancel_requested_at timestamptz,
 ADD COLUMN cancel_completed_at timestamptz,
 ADD COLUMN cancel_outcome text CHECK(cancel_outcome IN ('cancelled','already_terminal','paid_before_cancel')),
 ADD CONSTRAINT mercado_pago_cancel_identity CHECK((cancel_command_id IS NULL)=(cancel_requested_at IS NULL));
