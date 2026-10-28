-- A rejected retry cannot disprove a charge sent during an earlier uncertain call.
ALTER TABLE mercado_pago_card_commands ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0);
ALTER TABLE mercado_pago_pix_commands ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0);
-- Prior submissions have unknown outcomes; their next send is always a retry.
UPDATE mercado_pago_card_commands SET attempts=1 WHERE started_at IS NOT NULL;
UPDATE mercado_pago_pix_commands SET attempts=1 WHERE started_at IS NOT NULL;
