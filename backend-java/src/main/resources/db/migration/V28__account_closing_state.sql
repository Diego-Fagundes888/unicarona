-- Closing is reversible and keeps access to existing obligations and privacy rights.
-- It is not a completed erasure and never sets deleted_at.
ALTER TABLE users DROP CONSTRAINT users_account_status_check;
ALTER TABLE users ADD CONSTRAINT users_account_status_check
 CHECK(account_status IN ('active','suspended','closing','deleted'));
