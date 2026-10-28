-- A short claim transaction leaves the provider GET outside database locks.
-- An expired lease is recoverable after a worker crash.
ALTER TABLE mercado_pago_orders
 ADD COLUMN next_check_at timestamptz NOT NULL DEFAULT now(),
 ADD COLUMN reconcile_lease uuid,
 ADD COLUMN reconcile_lease_until timestamptz,
 ADD COLUMN reconcile_failures integer NOT NULL DEFAULT 0 CHECK(reconcile_failures>=0),
 ADD COLUMN reconcile_error_code text,
 ADD CONSTRAINT mercado_pago_reconcile_lease_pair CHECK((reconcile_lease IS NULL)=(reconcile_lease_until IS NULL));
CREATE INDEX mercado_pago_orders_due ON mercado_pago_orders(next_check_at,payment_id)
 WHERE state<>'REFUNDED';
