-- Renewal records the next paid period; it does not consume the current period's paid rights.
CREATE OR REPLACE FUNCTION subscription_state_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>NEW.status AND NOT (
  (OLD.status='active' AND NEW.status IN ('grace','renew_conflict','cancelled_renewal','interrupted','ended','expired','renewed')) OR
  (OLD.status IN ('grace','renew_conflict') AND NEW.status IN ('cancelled_renewal','interrupted','ended','expired','renewed')) OR
  (OLD.status='cancelled_renewal' AND NEW.status IN ('interrupted','ended')) OR
  (OLD.status='renewed' AND NEW.status IN ('interrupted','ended'))
 ) THEN RAISE EXCEPTION 'Invalid subscription transition' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;

CREATE VIEW subscriptions_with_open_rights AS
 SELECT s.* FROM subscriptions s
 WHERE s.status IN ('active','grace','cancelled_renewal','renew_conflict') OR
  (s.status='renewed' AND EXISTS (
   SELECT 1 FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id
    JOIN universities un ON un.id=r.university_id
   WHERE rr.subscription_id=s.id AND rr.status='confirmed' AND r.status='planned'
    AND (r.ride_date+r.departure_time) AT TIME ZONE un.time_zone>now()
  ));

CREATE INDEX subscriptions_previous_period_idx ON subscriptions(previous_subscription_id)
 WHERE previous_subscription_id IS NOT NULL;
CREATE INDEX reservation_subscription_confirmed_idx ON ride_reservations(subscription_id,ride_id)
 WHERE subscription_id IS NOT NULL AND status='confirmed';
