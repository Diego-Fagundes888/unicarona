CREATE INDEX reservation_confirmation_deadline_idx ON ride_reservations(confirmation_deadline)
 WHERE status='awaiting_confirmation';
CREATE INDEX subscription_renewal_due_idx ON subscriptions(end_date,status)
 WHERE status IN ('active','cancelled_renewal','grace','renew_conflict');
CREATE INDEX subscription_grace_due_idx ON subscriptions(grace_until)
 WHERE status IN ('grace','renew_conflict');
