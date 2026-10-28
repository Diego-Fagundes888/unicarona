-- The original receipt remains immutable. External lifecycle is tracked separately.
CREATE TABLE mercado_pago_orders (
 payment_id uuid PRIMARY KEY REFERENCES payments(id),
 order_id text NOT NULL UNIQUE,
 state text NOT NULL CHECK(state IN ('CREATED','PENDING','PROCESSING','APPROVED','REJECTED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED','EXPIRED','UNDER_REVIEW')),
 provider_status text NOT NULL,
 status_detail text NOT NULL,
 provider_version bigint NOT NULL DEFAULT 0,
 qr_code text,
 qr_base64 text,
 ticket_url text,
 checked_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX mercado_pago_orders_reconcile ON mercado_pago_orders(checked_at)
 WHERE state IN ('CREATED','PENDING','PROCESSING');
CREATE TABLE mercado_pago_refunds (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 payment_id uuid NOT NULL REFERENCES payments(id),
 user_id uuid NOT NULL REFERENCES users(id),
 amount_cents integer NOT NULL CHECK(amount_cents>0),
 reason text NOT NULL,
 status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','processing','approved','failed')),
 provider_reference text UNIQUE,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX mercado_pago_refunds_payment ON mercado_pago_refunds(payment_id,status);
