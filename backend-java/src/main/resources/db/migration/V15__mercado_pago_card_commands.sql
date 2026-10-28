-- Only an official single-use token is retained, encrypted for lost-response recovery.
-- Neither PAN nor CVV is accepted or stored.
CREATE TABLE mercado_pago_card_commands (
 payment_id uuid PRIMARY KEY REFERENCES payments(id),
 body_hash text NOT NULL,
 payload_ciphertext text,
 status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','done','failed')),
 last_error_code text,
 started_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK((status='pending')=(payload_ciphertext IS NOT NULL))
);
CREATE INDEX mercado_pago_card_commands_pending ON mercado_pago_card_commands(created_at) WHERE status='pending';
ALTER TABLE mercado_pago_orders ADD COLUMN challenge_url text;
