-- Expire abandoned requests/quotes that never produced a reservation or any
-- financial/provider event. Received, externally submitted and uncertain money
-- retains the original immutable history guard.
CREATE FUNCTION privacy_unsubmitted_cache_points_to(k idempotency_keys,target uuid) RETURNS boolean
LANGUAGE sql IMMUTABLE STRICT AS $$
 SELECT target::text IN (lower(k.response->>'id'),lower(k.response->>'paymentId'),
   CASE WHEN jsonb_typeof(k.response)='string' THEN lower(k.response#>>'{}') END)
$$;
CREATE FUNCTION privacy_unsubmitted_request_deadline(q ride_requests) RETURNS timestamptz
LANGUAGE sql STABLE STRICT AS $$
 SELECT greatest(q.created_at,
   CASE WHEN q.status IN ('cancelled','rejected') THEN least(q.expires_at,q.updated_at) ELSE q.expires_at END,
   (SELECT max(greatest(p.created_at,p.expires_at)) FROM payments p WHERE p.request_id=q.id),
   (SELECT max(c.created_at) FROM mercado_pago_card_commands c JOIN payments p ON p.id=c.payment_id WHERE p.request_id=q.id),
   (SELECT max(c.created_at) FROM mercado_pago_pix_commands c JOIN payments p ON p.id=c.payment_id WHERE p.request_id=q.id),
   (SELECT max(a.created_at) FROM audit_logs a WHERE a.resource_type='request' AND a.resource_id=q.id AND a.action<>'request_expired'))
   +interval '2160 hours'
$$;

CREATE FUNCTION privacy_unsubmitted_request_safe(q ride_requests) RETURNS boolean
LANGUAGE sql STABLE STRICT AS $$
 SELECT q.status IN ('pending','accepted','rejected','cancelled','expired')
   AND NOT EXISTS(SELECT 1 FROM ride_reservations rr WHERE rr.request_id=q.id)
   AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id IN (q.passenger_id,q.driver_id)
     AND h.category='financial' AND h.released_at IS NULL AND h.expires_at>now())
   AND NOT EXISTS(SELECT 1 FROM privacy_requests r WHERE r.user_id IN (q.passenger_id,q.driver_id)
     AND r.resolved_at IS NULL AND r.kind IN ('access','portability','restriction'))
   AND NOT EXISTS(SELECT 1 FROM idempotency_keys k WHERE k.actor_id IN (q.passenger_id,q.driver_id)
     AND k.expires_at>now() AND (privacy_unsubmitted_cache_points_to(k,q.id) OR EXISTS(
       SELECT 1 FROM payments p WHERE p.request_id=q.id AND privacy_unsubmitted_cache_points_to(k,p.id))))
   AND NOT EXISTS(SELECT 1 FROM audit_logs a WHERE a.resource_type='request' AND a.resource_id=q.id AND (
     (a.action IN ('request_created','request_accepted','request_rejected','request_cancelled') AND a.actor_id IN (q.passenger_id,q.driver_id))
     OR (a.action='request_expired' AND a.actor_id IS NULL)) IS DISTINCT FROM true)
   AND NOT EXISTS(SELECT 1 FROM payments p WHERE p.request_id=q.id AND (
     p.paid_at IS NOT NULL OR p.status NOT IN ('awaiting','pending','failed','expired')
     -- Distribution fields are an intention, not a receipt. Mercado Pago's
     -- durable first-start markers and received journal establish submission.
     -- Unknown adapters have no verified submission protocol here.
     OR p.provider NOT IN ('mercadopago','development')
     OR (p.provider='development' AND (p.credit_cents<>0 OR p.external_cents<>0))
     OR p.provider_reference IS NOT NULL OR p.checkout_url IS NOT NULL OR p.gateway_environment IS NOT NULL
     OR EXISTS(SELECT 1 FROM ledger_transactions t WHERE t.payment_id=p.id)
     OR EXISTS(SELECT 1 FROM parcels z WHERE z.payment_id=p.id)
     OR EXISTS(SELECT 1 FROM mercado_pago_orders o WHERE o.payment_id=p.id)
     OR EXISTS(SELECT 1 FROM mercado_pago_refunds r WHERE r.payment_id=p.id)
     OR EXISTS(SELECT 1 FROM mercado_pago_observed_refunds r WHERE r.payment_id=p.id)
     OR EXISTS(SELECT 1 FROM passenger_debt_assessments d WHERE d.payment_id=p.id)
     OR EXISTS(SELECT 1 FROM mercado_pago_review_resolutions r WHERE r.payment_id=p.id)
     OR EXISTS(SELECT 1 FROM audit_logs a WHERE a.resource_type='payment' AND a.resource_id=p.id)
     OR EXISTS(SELECT 1 FROM webhook_events w WHERE lower(w.payload->>'payment')=p.id::text OR lower(w.payload->>'paymentId')=p.id::text)
     OR EXISTS(SELECT 1 FROM outbox_jobs j WHERE (j.payload->>'paymentId'=p.id::text
       OR j.event_key IN ('mercadopago_card:'||p.id,'mercadopago_pix:'||p.id)) AND (
       j.type NOT IN ('mercadopago_card','mercadopago_pix')
       OR j.event_key IS DISTINCT FROM j.type||':'||p.id
       OR j.payload->>'paymentId' IS DISTINCT FROM p.id::text
       OR (j.status='processing' AND (j.leased_until IS NULL OR j.leased_until>now()))))
     OR EXISTS(SELECT 1 FROM mercado_pago_card_commands c WHERE c.payment_id=p.id
       AND (c.started_at IS NOT NULL OR c.attempts<>0 OR c.status<>'failed' OR c.payload_ciphertext IS NOT NULL))
     OR EXISTS(SELECT 1 FROM mercado_pago_pix_commands c WHERE c.payment_id=p.id
       AND (c.started_at IS NOT NULL OR c.attempts<>0 OR c.status<>'failed' OR c.payer_email IS NOT NULL))
     -- A known independent identity boundary still needs its source witness.
     OR EXISTS(SELECT 1 FROM privacy_retained_records r WHERE NOT r.hold_only
       AND r.category IN ('contract_identity','vehicle_identity') AND (
         r.financial_purpose->'facts' @> jsonb_build_array(jsonb_build_object('kind','card_command','id',p.id))
         OR r.financial_purpose->'facts' @> jsonb_build_array(jsonb_build_object('kind','pix_command','id',p.id))))))
$$;

CREATE FUNCTION privacy_unsubmitted_request_expired(q ride_requests) RETURNS boolean
LANGUAGE sql STABLE STRICT AS $$
 SELECT privacy_unsubmitted_request_deadline(q)<=clock_timestamp() AND privacy_unsubmitted_request_safe(q)
$$;
CREATE FUNCTION privacy_unsubmitted_audit_expired(a audit_logs) RETURNS boolean
LANGUAGE sql STABLE STRICT AS $$
 SELECT a.resource_type='request' AND EXISTS(SELECT 1 FROM ride_requests q
   WHERE q.id=a.resource_id AND privacy_unsubmitted_request_expired(q) AND (
     (a.action IN ('request_created','request_accepted','request_rejected','request_cancelled') AND a.actor_id IN (q.passenger_id,q.driver_id))
     OR (a.action='request_expired' AND a.actor_id IS NULL)))
$$;

CREATE OR REPLACE FUNCTION protect_audit_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND ((privacy_security_audit_deadline(OLD)<=clock_timestamp()
    AND privacy_security_audit_case_safe(OLD)) OR privacy_unsubmitted_audit_expired(OLD)) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Audit history is immutable or still retained' USING ERRCODE='23514';
END;
$$;
CREATE FUNCTION protect_unsubmitted_payment_retirement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ride_requests q WHERE q.id=OLD.request_id AND privacy_unsubmitted_request_expired(q)) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Received, submitted, uncertain or retained payment history is immutable' USING ERRCODE='23514';
END;
$$;
DROP TRIGGER payment_no_delete ON payments;
CREATE TRIGGER payment_no_delete BEFORE DELETE ON payments FOR EACH ROW EXECUTE FUNCTION protect_unsubmitted_payment_retirement();
CREATE FUNCTION protect_unsubmitted_request_retirement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF privacy_unsubmitted_request_expired(OLD) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Active, contracted or retained request cannot expire' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER privacy_request_retirement BEFORE DELETE ON ride_requests
 FOR EACH ROW EXECUTE FUNCTION protect_unsubmitted_request_retirement();
CREATE FUNCTION protect_unsubmitted_command_retirement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM payments p JOIN ride_requests q ON q.id=p.request_id
    WHERE p.id=OLD.payment_id AND privacy_unsubmitted_request_expired(q)) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Submitted, uncertain or retained command cannot expire' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER privacy_card_command_retirement BEFORE DELETE ON mercado_pago_card_commands
 FOR EACH ROW EXECUTE FUNCTION protect_unsubmitted_command_retirement();
CREATE TRIGGER privacy_pix_command_retirement BEFORE DELETE ON mercado_pago_pix_commands
 FOR EACH ROW EXECUTE FUNCTION protect_unsubmitted_command_retirement();
CREATE TRIGGER privacy_unsubmitted_payment_coordination BEFORE DELETE ON payments
 FOR EACH STATEMENT EXECUTE FUNCTION privacy_retention_coordination();
CREATE TRIGGER privacy_unsubmitted_request_coordination BEFORE DELETE ON ride_requests
 FOR EACH STATEMENT EXECUTE FUNCTION privacy_retention_coordination();
CREATE INDEX privacy_unsubmitted_request_scan ON ride_requests(created_at,id);
CREATE INDEX privacy_unsubmitted_payment_journal ON ledger_transactions(payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX privacy_unsubmitted_observation_payment ON webhook_events(lower(payload->>'payment')) WHERE lower(payload->>'payment') IS NOT NULL;
CREATE INDEX privacy_unsubmitted_observation_payment_id ON webhook_events(lower(payload->>'paymentId')) WHERE lower(payload->>'paymentId') IS NOT NULL;
-- Once an external call may have happened, it cannot be relabelled as never
-- submitted by clearing/replacing its first-start marker. Normal retries use
-- coalesce(started_at,now()) and keep this original timestamp.
CREATE FUNCTION protect_provider_start_marker() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.started_at IS NOT NULL AND NEW.started_at IS DISTINCT FROM OLD.started_at THEN
   RAISE EXCEPTION 'The original external submission marker is immutable' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME IN ('mercado_pago_card_commands','mercado_pago_pix_commands')
   AND (to_jsonb(NEW)->>'attempts')::integer<(to_jsonb(OLD)->>'attempts')::integer THEN
   RAISE EXCEPTION 'External submission attempts cannot be forgotten' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER privacy_card_submission_marker BEFORE UPDATE ON mercado_pago_card_commands
 FOR EACH ROW EXECUTE FUNCTION protect_provider_start_marker();
CREATE TRIGGER privacy_pix_submission_marker BEFORE UPDATE ON mercado_pago_pix_commands
 FOR EACH ROW EXECUTE FUNCTION protect_provider_start_marker();
CREATE TRIGGER privacy_debt_submission_marker BEFORE UPDATE ON passenger_debt_payments
 FOR EACH ROW EXECUTE FUNCTION protect_provider_start_marker();
-- No data is removed in the migration. Maintenance/recovery must authenticate
-- the independent preservation checkpoint before executing the deletion graph.
