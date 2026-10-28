CREATE FUNCTION reservation_state_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'confirmed' THEN RAISE EXCEPTION 'Reservation must start confirmed' USING ERRCODE='23514'; END IF;
 ELSE
  IF ROW(OLD.id,OLD.request_id,OLD.ride_id,OLD.passenger_id,OLD.driver_id,OLD.subscription_id,OLD.payment_id,OLD.price_cents,OLD.pin_ciphertext,OLD.pin_hash)
    IS DISTINCT FROM ROW(NEW.id,NEW.request_id,NEW.ride_id,NEW.passenger_id,NEW.driver_id,NEW.subscription_id,NEW.payment_id,NEW.price_cents,NEW.pin_ciphertext,NEW.pin_hash)
   THEN RAISE EXCEPTION 'Immutable reservation contract' USING ERRCODE='23514'; END IF;
  IF OLD.status<>NEW.status AND NOT (
   (OLD.status='confirmed' AND NEW.status IN ('boarded','awaiting_confirmation','under_review','cancelled')) OR
   (OLD.status='boarded' AND NEW.status IN ('awaiting_confirmation','under_review','cancelled')) OR
   (OLD.status='awaiting_confirmation' AND NEW.status IN ('completed','under_review','cancelled')) OR
   (OLD.status='under_review' AND NEW.status IN ('completed','cancelled'))
  ) THEN RAISE EXCEPTION 'Invalid reservation transition' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reservation_state BEFORE INSERT OR UPDATE ON ride_reservations FOR EACH ROW EXECUTE FUNCTION reservation_state_guard();

CREATE FUNCTION parcel_state_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'future' THEN RAISE EXCEPTION 'Parcel must start future' USING ERRCODE='23514'; END IF;
 ELSE
  IF ROW(OLD.id,OLD.reservation_id,OLD.payment_id,OLD.passenger_id,OLD.driver_id,OLD.cents,OLD.commission_bps,OLD.created_at)
    IS DISTINCT FROM ROW(NEW.id,NEW.reservation_id,NEW.payment_id,NEW.passenger_id,NEW.driver_id,NEW.cents,NEW.commission_bps,NEW.created_at)
   THEN RAISE EXCEPTION 'Immutable parcel contract' USING ERRCODE='23514'; END IF;
  IF OLD.status IN ('released','credited','retained') AND OLD IS DISTINCT FROM NEW
   THEN RAISE EXCEPTION 'Immutable settled parcel' USING ERRCODE='23514'; END IF;
  IF OLD.status<>NEW.status AND NOT (
   (OLD.status='future' AND NEW.status IN ('awaiting','blocked','released','credited','retained')) OR
   (OLD.status='awaiting' AND NEW.status IN ('blocked','released','credited','retained')) OR
   (OLD.status='blocked' AND NEW.status IN ('released','credited','retained'))
  ) THEN RAISE EXCEPTION 'Invalid parcel transition' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.commission_bps NOT BETWEEN 0 AND 9999 OR
  (NEW.status NOT IN ('released','credited','retained') AND (NEW.commission_cents<>0 OR NEW.driver_cents<>0 OR NEW.settled_at IS NOT NULL)) OR
  (NEW.status IN ('credited','retained') AND (NEW.commission_cents<>0 OR NEW.driver_cents<>0)) OR
  (NEW.status IN ('released','credited','retained') AND NEW.settled_at IS NULL) OR
  (NEW.status='released' AND NEW.commission_cents<>((NEW.cents::bigint*NEW.commission_bps+5000)/10000))
 THEN RAISE EXCEPTION 'Invalid parcel distribution' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER parcel_state BEFORE INSERT OR UPDATE ON parcels FOR EACH ROW EXECUTE FUNCTION parcel_state_guard();
CREATE TRIGGER parcel_no_delete BEFORE DELETE ON parcels FOR EACH ROW EXECUTE FUNCTION prevent_history_change();

CREATE FUNCTION parcel_ledger_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p parcels%ROWTYPE; journal uuid; credit bigint; net bigint; fee bigint; retained bigint;
BEGIN
 SELECT * INTO p FROM parcels WHERE id=NEW.id;
 IF NOT EXISTS(SELECT 1 FROM ride_reservations rr JOIN payments pay ON pay.id=rr.payment_id
   JOIN ride_requests q ON q.id=rr.request_id WHERE rr.id=p.reservation_id AND rr.payment_id=p.payment_id
   AND rr.passenger_id=p.passenger_id AND rr.driver_id=p.driver_id AND rr.price_cents=p.cents
   AND pay.status='approved' AND pay.paid_at IS NOT NULL AND q.status='confirmed')
  THEN RAISE EXCEPTION 'Parcel requires its confirmed paid reservation' USING ERRCODE='23514'; END IF;
 IF p.status='blocked' AND NOT EXISTS(SELECT 1 FROM ledger_transactions WHERE event_key='parcel:'||p.id||':blocked' AND parcel_id=p.id AND kind='dispute_hold')
  THEN RAISE EXCEPTION 'Blocked parcel requires ledger hold' USING ERRCODE='23514'; END IF;
 IF p.status IN ('released','credited','retained') THEN
  SELECT id INTO journal FROM ledger_transactions WHERE event_key='parcel:'||p.id||':settled' AND parcel_id=p.id AND kind=p.status;
  IF journal IS NULL THEN RAISE EXCEPTION 'Settled parcel requires matching ledger' USING ERRCODE='23514'; END IF;
  SELECT coalesce(sum(e.cents) FILTER(WHERE a.owner_id=p.passenger_id AND a.kind='passenger_credit'),0),
   coalesce(sum(e.cents) FILTER(WHERE a.owner_id=p.driver_id AND a.kind='driver_available'),0),
   coalesce(sum(e.cents) FILTER(WHERE a.owner_id IS NULL AND a.kind='commission'),0),
   coalesce(sum(e.cents) FILTER(WHERE a.owner_id IS NULL AND a.kind='retained'),0)
   INTO credit,net,fee,retained FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id WHERE e.transaction_id=journal;
  IF credit<>(CASE WHEN p.status='credited' THEN p.cents ELSE 0 END) OR net<>p.driver_cents OR fee<>p.commission_cents OR
   retained<>(CASE WHEN p.status='retained' THEN p.cents ELSE 0 END)
   THEN RAISE EXCEPTION 'Parcel distribution disagrees with ledger' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER parcel_ledger AFTER INSERT OR UPDATE ON parcels DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION parcel_ledger_guard();

CREATE FUNCTION subscription_state_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>NEW.status AND NOT (
  (OLD.status='active' AND NEW.status IN ('grace','renew_conflict','cancelled_renewal','interrupted','ended','expired','renewed')) OR
  (OLD.status IN ('grace','renew_conflict') AND NEW.status IN ('cancelled_renewal','interrupted','ended','expired','renewed')) OR
  (OLD.status='cancelled_renewal' AND NEW.status IN ('interrupted','ended'))
 ) THEN RAISE EXCEPTION 'Invalid subscription transition' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER subscription_state BEFORE UPDATE OF status ON subscriptions FOR EACH ROW EXECUTE FUNCTION subscription_state_guard();
