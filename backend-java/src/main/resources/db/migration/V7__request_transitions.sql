CREATE FUNCTION request_transition_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>NEW.status AND NOT (
  (OLD.status='pending' AND NEW.status IN ('accepted','rejected','cancelled','expired')) OR
  (OLD.status='accepted' AND NEW.status IN ('confirmed','cancelled','expired')) OR
  (OLD.status='confirmed' AND NEW.status='cancelled')
 ) THEN RAISE EXCEPTION 'Invalid request transition' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER request_transition BEFORE UPDATE OF status ON ride_requests FOR EACH ROW EXECUTE FUNCTION request_transition_guard();
CREATE UNIQUE INDEX request_active_routine_idx ON ride_requests(passenger_id,routine_id)
 WHERE type='mensal' AND status IN ('pending','accepted');
ALTER TABLE payments ADD COLUMN checkout_url text;
ALTER TABLE payments ADD COLUMN gateway_environment text CHECK(gateway_environment IN ('test','live'));
ALTER TABLE payments ADD COLUMN credit_requested boolean NOT NULL DEFAULT false;
CREATE INDEX request_expiry_idx ON ride_requests(expires_at) WHERE status IN ('pending','accepted');
CREATE INDEX payment_expiry_idx ON payments(expires_at) WHERE status IN ('awaiting','pending');
