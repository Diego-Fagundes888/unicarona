ALTER TABLE ledger_transactions ADD COLUMN body_hash text NOT NULL;
CREATE INDEX ledger_transaction_payment_idx ON ledger_transactions(payment_id);
CREATE INDEX payment_passenger_idx ON payments(passenger_id,created_at DESC);
CREATE INDEX transfer_user_idx ON transfers(user_id,created_at DESC);
CREATE INDEX request_passenger_idx ON ride_requests(passenger_id,status,created_at DESC);
CREATE INDEX file_owner_idx ON stored_files(owner_id,purpose) WHERE deleted_at IS NULL;
