ALTER TABLE webhook_events ADD COLUMN last_error_code text;
CREATE INDEX webhook_retry_idx ON webhook_events(received_at) WHERE status IN ('pending','failed');
