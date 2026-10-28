-- Operational free text has a finite purpose; amounts and transport facts are separate.
ALTER TABLE rides ADD COLUMN details_expires_at timestamptz;
ALTER TABLE rides ADD COLUMN details_retired_at timestamptz;
UPDATE rides r SET details_expires_at=((r.ride_date+1)::timestamp AT TIME ZONE u.time_zone)+interval '4320 hours'
 FROM universities u WHERE u.id=r.university_id;
ALTER TABLE rides ALTER COLUMN details_expires_at SET NOT NULL;
ALTER TABLE ride_routes ADD COLUMN details_retired_at timestamptz;
ALTER TABLE routines ADD COLUMN details_inactive_since timestamptz;
ALTER TABLE routines ADD COLUMN details_retired_at timestamptz;
UPDATE routines SET details_inactive_since=updated_at WHERE status<>'active';

CREATE FUNCTION privacy_trip_route_lock(route uuid,try_only boolean) RETURNS boolean
LANGUAGE plpgsql VOLATILE STRICT AS $$ BEGIN
 IF try_only THEN RETURN pg_try_advisory_xact_lock(hashtextextended(route::text,74020)); END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(route::text,74020));RETURN true;
END $$;
CREATE FUNCTION privacy_route_details_case(route uuid) RETURNS boolean LANGUAGE sql STABLE STRICT AS $$
 SELECT EXISTS(SELECT 1 FROM ride_routes rt JOIN privacy_legal_holds h ON h.user_id=rt.driver_id
   WHERE rt.id=route AND h.released_at IS NULL AND h.expires_at>clock_timestamp()
    AND ((h.category='location' AND (h.resource_id IS NULL OR h.resource_id=rt.id)) OR h.category='financial'))
 OR EXISTS(SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id
   LEFT JOIN parcels p ON p.reservation_id=rr.id WHERE r.route_id=route AND (rr.status='under_review' OR p.status='blocked'))
 OR EXISTS(SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id
   JOIN occurrences o ON o.reservation_id=rr.id WHERE r.route_id=route AND o.status<>'resolved')
 OR EXISTS(SELECT 1 FROM ride_routes rt JOIN occurrences o ON o.reservation_id IS NULL
    AND (o.reporter_id=rt.driver_id OR o.other_id=rt.driver_id) WHERE rt.id=route AND o.status<>'resolved')
 OR EXISTS(SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id
   JOIN mercado_pago_orders m ON m.payment_id=rr.payment_id WHERE r.route_id=route
    AND (m.state='UNDER_REVIEW' OR m.chargeback_review OR m.untracked_refunds OR m.refund_history_incomplete))
 OR EXISTS(SELECT 1 FROM ride_routes rt JOIN privacy_requests q ON q.user_id=rt.driver_id
   WHERE rt.id=route AND q.resolved_at IS NULL AND q.kind IN ('access','portability','restriction'))
 OR EXISTS(SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id
   JOIN privacy_requests q ON q.user_id=rr.passenger_id WHERE r.route_id=route
    AND q.resolved_at IS NULL AND q.kind IN ('access','portability','restriction'))
 OR EXISTS(SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id
   JOIN privacy_legal_holds h ON h.user_id=rr.passenger_id WHERE r.route_id=route
    AND h.category='financial' AND h.released_at IS NULL AND h.expires_at>clock_timestamp())
 OR EXISTS(SELECT 1 FROM rides r JOIN request_lines l ON l.ride_id=r.id JOIN ride_requests q ON q.id=l.request_id
   JOIN payments p ON p.request_id=q.id WHERE r.route_id=route AND p.status IN ('awaiting','pending','refund_pending'))
 OR EXISTS(SELECT 1 FROM rides r JOIN request_lines l ON l.ride_id=r.id JOIN ride_requests q ON q.id=l.request_id
   JOIN privacy_requests p ON p.user_id IN (q.passenger_id,q.driver_id) WHERE r.route_id=route
    AND p.resolved_at IS NULL AND p.kind IN ('access','portability','restriction'))
 OR EXISTS(SELECT 1 FROM rides r JOIN request_lines l ON l.ride_id=r.id JOIN ride_requests q ON q.id=l.request_id
   JOIN privacy_legal_holds h ON h.user_id=q.passenger_id WHERE r.route_id=route
    AND h.category='financial' AND h.released_at IS NULL AND h.expires_at>clock_timestamp())
$$;
CREATE FUNCTION privacy_ride_details_visible(r rides) RETURNS boolean LANGUAGE sql STABLE STRICT AS $$
 SELECT r.details_retired_at IS NULL AND r.details_expires_at>clock_timestamp()
$$;
CREATE FUNCTION privacy_routine_details_visible(r routines) RETURNS boolean LANGUAGE sql STABLE STRICT AS $$
 SELECT r.details_retired_at IS NULL AND (r.status='active' OR r.details_inactive_since+interval '4320 hours'>clock_timestamp())
$$;
CREATE FUNCTION privacy_route_details_visible(rt ride_routes) RETURNS boolean LANGUAGE sql STABLE STRICT AS $$
 SELECT rt.details_retired_at IS NULL AND (
   EXISTS(SELECT 1 FROM routines r WHERE r.route_id=rt.id AND privacy_routine_details_visible(r))
   OR EXISTS(SELECT 1 FROM rides r WHERE r.route_id=rt.id AND r.details_expires_at>clock_timestamp())
   OR (NOT EXISTS(SELECT 1 FROM rides r WHERE r.route_id=rt.id) AND NOT EXISTS(SELECT 1 FROM routines r WHERE r.route_id=rt.id)
     AND rt.created_at+interval '4320 hours'>clock_timestamp()))
$$;
CREATE FUNCTION privacy_route_details_retirable(rt ride_routes) RETURNS boolean LANGUAGE sql STABLE STRICT AS $$
 SELECT rt.details_retired_at IS NULL AND NOT privacy_route_details_visible(rt) AND NOT privacy_route_details_case(rt.id)
$$;

CREATE FUNCTION privacy_trip_route_reference() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE retired timestamptz; BEGIN
 PERFORM privacy_trip_route_lock(NEW.route_id,false);
 SELECT details_retired_at INTO retired FROM ride_routes WHERE id=NEW.route_id;
 IF retired IS NOT NULL THEN RAISE EXCEPTION 'Retired route requires a new explicit offer' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER privacy_ride_route_insert BEFORE INSERT ON rides FOR EACH ROW EXECUTE FUNCTION privacy_trip_route_reference();
CREATE TRIGGER privacy_ride_route_change BEFORE UPDATE OF route_id ON rides FOR EACH ROW WHEN (OLD.route_id IS DISTINCT FROM NEW.route_id) EXECUTE FUNCTION privacy_trip_route_reference();
CREATE TRIGGER privacy_routine_route_insert BEFORE INSERT ON routines FOR EACH ROW EXECUTE FUNCTION privacy_trip_route_reference();
CREATE TRIGGER privacy_routine_route_change BEFORE UPDATE OF route_id ON routines FOR EACH ROW WHEN (OLD.route_id IS DISTINCT FROM NEW.route_id) EXECUTE FUNCTION privacy_trip_route_reference();

CREATE FUNCTION privacy_ride_details_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE deadline timestamptz; BEGIN
 IF TG_OP='INSERT' THEN
   SELECT ((NEW.ride_date+1)::timestamp AT TIME ZONE u.time_zone)+interval '4320 hours' INTO deadline FROM universities u WHERE u.id=NEW.university_id;
   IF NEW.details_expires_at IS NOT NULL AND NEW.details_expires_at IS DISTINCT FROM deadline THEN RAISE EXCEPTION 'Original trip details deadline required' USING ERRCODE='23514';END IF;
   IF NEW.details_retired_at IS NOT NULL THEN RAISE EXCEPTION 'Trip retirement is lifecycle generated' USING ERRCODE='23514';END IF;
   NEW.details_expires_at:=deadline;
 ELSE
   IF NEW.details_expires_at IS DISTINCT FROM OLD.details_expires_at THEN RAISE EXCEPTION 'Original trip details deadline is immutable' USING ERRCODE='23514';END IF;
   IF OLD.details_retired_at IS NOT NULL AND (NEW.details_retired_at IS DISTINCT FROM OLD.details_retired_at OR NEW.notes<>'' OR NEW.proposal_reason IS NOT NULL) THEN RAISE EXCEPTION 'Retired trip text cannot return' USING ERRCODE='23514';END IF;
   IF OLD.details_retired_at IS NULL AND NEW.details_retired_at IS NOT NULL THEN
     IF OLD.details_expires_at>clock_timestamp() OR privacy_route_details_case(OLD.route_id) OR NEW.notes<>'' OR NEW.proposal_reason IS NOT NULL THEN RAISE EXCEPTION 'Trip details still have a purpose' USING ERRCODE='23514';END IF;
     NEW.details_retired_at:=clock_timestamp();
   END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER privacy_ride_details BEFORE INSERT OR UPDATE ON rides FOR EACH ROW EXECUTE FUNCTION privacy_ride_details_guard();
CREATE FUNCTION privacy_route_details_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='INSERT' THEN IF NEW.details_retired_at IS NOT NULL THEN RAISE EXCEPTION 'Route retirement is lifecycle generated' USING ERRCODE='23514';END IF;
 ELSE
  IF OLD.details_retired_at IS NOT NULL AND (NEW.details_retired_at IS DISTINCT FROM OLD.details_retired_at OR NEW.region<>'Região removida' OR NEW.meeting<>'Detalhes removidos') THEN RAISE EXCEPTION 'Retired route text cannot return' USING ERRCODE='23514';END IF;
  IF OLD.details_retired_at IS NULL AND NEW.details_retired_at IS NOT NULL THEN
   IF NOT privacy_route_details_retirable(OLD) OR NEW.region<>'Região removida' OR NEW.meeting<>'Detalhes removidos' THEN RAISE EXCEPTION 'Route details still have a purpose' USING ERRCODE='23514';END IF;
   NEW.details_retired_at:=clock_timestamp();
  END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER privacy_route_details BEFORE INSERT OR UPDATE ON ride_routes FOR EACH ROW EXECUTE FUNCTION privacy_route_details_guard();
CREATE FUNCTION privacy_routine_details_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.details_retired_at IS NOT NULL THEN RAISE EXCEPTION 'Routine retirement is lifecycle generated' USING ERRCODE='23514';END IF;
  NEW.details_inactive_since:=CASE WHEN NEW.status='active' THEN NULL ELSE NEW.created_at END;
 ELSE
  IF OLD.details_retired_at IS NOT NULL AND (NEW.details_retired_at IS DISTINCT FROM OLD.details_retired_at OR NEW.notes<>'' OR NEW.status='active') THEN RAISE EXCEPTION 'Retired routine requires a new explicit offer' USING ERRCODE='23514';END IF;
  IF OLD.status<>'active' AND NOT privacy_routine_details_visible(OLD) AND (NEW.status='active' OR (NEW.notes IS DISTINCT FROM OLD.notes AND NEW.notes<>'')) THEN RAISE EXCEPTION 'Expired routine requires a new explicit offer' USING ERRCODE='23514';END IF;
  IF OLD.status IS DISTINCT FROM NEW.status THEN
    NEW.details_inactive_since:=CASE WHEN NEW.status='active' THEN NULL WHEN OLD.status='active' THEN clock_timestamp() ELSE OLD.details_inactive_since END;
  ELSIF NEW.details_inactive_since IS DISTINCT FROM OLD.details_inactive_since THEN RAISE EXCEPTION 'Routine inactivity date is immutable without a status change' USING ERRCODE='23514';END IF;
  IF OLD.details_retired_at IS NULL AND NEW.details_retired_at IS NOT NULL THEN
   IF privacy_routine_details_visible(OLD) OR privacy_route_details_case(OLD.route_id) OR NEW.notes<>'' THEN RAISE EXCEPTION 'Routine details still have a purpose' USING ERRCODE='23514';END IF;
   NEW.details_retired_at:=clock_timestamp();
  END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER privacy_routine_details BEFORE INSERT OR UPDATE ON routines FOR EACH ROW EXECUTE FUNCTION privacy_routine_details_guard();
CREATE TRIGGER privacy_ride_details_coordination BEFORE UPDATE OF details_retired_at ON rides FOR EACH STATEMENT EXECUTE FUNCTION privacy_retention_coordination();
CREATE TRIGGER privacy_route_details_coordination BEFORE UPDATE OF details_retired_at ON ride_routes FOR EACH STATEMENT EXECUTE FUNCTION privacy_retention_coordination();
CREATE TRIGGER privacy_routine_details_coordination BEFORE UPDATE OF details_retired_at ON routines FOR EACH STATEMENT EXECUTE FUNCTION privacy_retention_coordination();
CREATE INDEX privacy_ride_details_due ON rides(details_expires_at,id) WHERE details_retired_at IS NULL;
CREATE INDEX privacy_routine_details_due ON routines(details_inactive_since,id) WHERE details_retired_at IS NULL AND status<>'active';
