-- Minimum claimant/vehicle evidence has its own purpose, not merely the age of
-- the account closure. This does not change or compact monetary history.
ALTER TABLE privacy_retained_records ADD COLUMN financial_purpose jsonb;
-- Closed request narratives can expire before a dependent claimant identity.
-- Keep only the immutable lifecycle witness while that dependency exists.
ALTER TABLE privacy_requests ADD COLUMN details_retired_at timestamptz
 CHECK(details_retired_at IS NULL OR (resolved_at IS NOT NULL AND details_retired_at>=resolved_at));
-- For an unresolved obligation the defence threshold may already be past when
-- a delayed scrub creates its minimum evidence. Keep the real copy timestamp;
-- do not invent a fresh five-year window merely to satisfy the old check.
DO $$ DECLARE n name; BEGIN
 SELECT conname INTO STRICT n FROM pg_constraint WHERE conrelid='privacy_retained_records'::regclass
   AND contype='c' AND pg_get_constraintdef(oid) LIKE '%delete_after > created_at%';
 EXECUTE format('ALTER TABLE privacy_retained_records DROP CONSTRAINT %I',n);
END $$;
ALTER TABLE privacy_retained_records ADD CONSTRAINT privacy_retained_purpose_window
 CHECK(delete_after>created_at OR (NOT hold_only AND category IN ('contract_identity','vehicle_identity')));

CREATE FUNCTION privacy_identity_vehicle_payment(owner uuid,vehicle uuid,payment uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM parcels p JOIN ride_reservations rr ON rr.id=p.reservation_id
   JOIN rides r ON r.id=rr.ride_id WHERE p.payment_id=payment AND r.driver_id=owner AND (vehicle IS NULL OR r.vehicle_id=vehicle))
$$;

CREATE FUNCTION privacy_identity_purpose_open(owner uuid,category text,resource uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN category='contract_identity' THEN
   EXISTS(SELECT 1 FROM ledger_accounts a JOIN ledger_entries e ON e.account_id=a.id
     WHERE a.owner_id=owner AND a.kind IN ('future','blocked','driver_available','passenger_credit','payout_pending','refund_pending','passenger_receivable')
     GROUP BY a.id HAVING sum(e.cents)<>0)
   OR EXISTS(SELECT 1 FROM payments p WHERE p.passenger_id=owner AND p.status IN ('awaiting','pending','refund_pending'))
   OR EXISTS(SELECT 1 FROM transfers t WHERE t.user_id=owner AND t.status='pending')
   OR EXISTS(SELECT 1 FROM passenger_debt_payments d WHERE d.passenger_id=owner AND d.state IN ('CREATED','PENDING','PROCESSING','UNDER_REVIEW'))
   OR EXISTS(SELECT 1 FROM mercado_pago_refunds f WHERE f.user_id=owner AND f.status IN ('pending','processing'))
   OR EXISTS(SELECT 1 FROM payments p WHERE p.passenger_id=owner AND (
     EXISTS(SELECT 1 FROM mercado_pago_card_commands c WHERE c.payment_id=p.id AND c.status='pending')
     OR EXISTS(SELECT 1 FROM mercado_pago_pix_commands c WHERE c.payment_id=p.id AND c.status='pending')))
   OR EXISTS(SELECT 1 FROM occurrences o LEFT JOIN ride_reservations rr ON rr.id=o.reservation_id
     WHERE o.status<>'resolved' AND (o.reporter_id=owner OR o.other_id=owner OR rr.passenger_id=owner OR rr.driver_id=owner))
   OR EXISTS(SELECT 1 FROM privacy_requests q WHERE q.user_id=owner AND q.resolved_at IS NULL)
   OR EXISTS(SELECT 1 FROM mercado_pago_orders m JOIN payments p ON p.id=m.payment_id JOIN ride_requests q ON q.id=p.request_id
     WHERE (p.passenger_id=owner OR q.driver_id=owner) AND (m.state='UNDER_REVIEW' OR m.chargeback_review OR m.untracked_refunds))
 WHEN category='vehicle_identity' THEN
   EXISTS(SELECT 1 FROM parcels p JOIN ride_reservations rr ON rr.id=p.reservation_id JOIN rides r ON r.id=rr.ride_id
     WHERE (resource IS NULL OR r.vehicle_id=resource) AND r.driver_id=owner AND p.status IN ('future','awaiting','blocked'))
   OR EXISTS(SELECT 1 FROM occurrences o JOIN ride_reservations rr ON rr.id=o.reservation_id JOIN rides r ON r.id=rr.ride_id
     WHERE (resource IS NULL OR r.vehicle_id=resource) AND r.driver_id=owner AND o.status<>'resolved')
   OR EXISTS(SELECT 1 FROM mercado_pago_orders m JOIN parcels p ON p.payment_id=m.payment_id
     JOIN ride_reservations rr ON rr.id=p.reservation_id JOIN rides r ON r.id=rr.ride_id
     WHERE (resource IS NULL OR r.vehicle_id=resource) AND r.driver_id=owner AND (m.state='UNDER_REVIEW' OR m.chargeback_review OR m.untracked_refunds))
 ELSE false END
$$;

-- Fixed, typed source identities. Fingerprints exclude mutable polling clocks.
CREATE FUNCTION privacy_identity_purpose_facts(owner uuid,category text,resource uuid)
RETURNS TABLE(kind text,id uuid,sha256 text,happened timestamptz) LANGUAGE sql STABLE AS $$
 SELECT 'ledger_entry',e.id,encode(digest(concat_ws('|',e.id::text,e.transaction_id::text,e.account_id::text,e.cents::text,extract(epoch FROM t.created_at)::text),'sha256'),'hex'),t.created_at
 FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id JOIN ledger_transactions t ON t.id=e.transaction_id
 WHERE a.owner_id=owner AND (category='contract_identity' OR (category='vehicle_identity' AND privacy_identity_vehicle_payment(owner,resource,t.payment_id)))
 UNION ALL
 SELECT 'occurrence',o.id,encode(digest(concat_ws('|',o.id::text,o.reporter_id::text,coalesce(o.other_id::text,'-'),coalesce(o.reservation_id::text,'-'),extract(epoch FROM o.created_at)::text),'sha256'),'hex'),coalesce(o.reviewed_at,o.created_at)
 FROM occurrences o LEFT JOIN ride_reservations rr ON rr.id=o.reservation_id LEFT JOIN rides r ON r.id=rr.ride_id
 WHERE (category='contract_identity' AND (o.reporter_id=owner OR o.other_id=owner OR rr.passenger_id=owner OR rr.driver_id=owner))
   OR (category='vehicle_identity' AND (resource IS NULL OR r.vehicle_id=resource) AND r.driver_id=owner)
 UNION ALL
 SELECT 'webhook',w.id,encode(digest(concat_ws('|',w.id::text,w.payload_hash,extract(epoch FROM w.received_at)::text),'sha256'),'hex'),
   CASE WHEN w.payload->>'state' IN ('APPROVED','REJECTED','CANCELLED','EXPIRED','REFUNDED','PARTIALLY_REFUNDED')
     THEN w.received_at END
 FROM (SELECT w.*,row_number() OVER(PARTITION BY w.payload-'version' ORDER BY w.received_at,w.id) AS semantic_first FROM webhook_events w
 WHERE w.provider='mercadopago' AND (EXISTS(SELECT 1 FROM payments p JOIN ride_requests q ON q.id=p.request_id
   WHERE p.id::text=w.payload->>'payment' AND ((category='contract_identity' AND (p.passenger_id=owner OR q.driver_id=owner))
     OR (category='vehicle_identity' AND q.driver_id=owner AND privacy_identity_vehicle_payment(owner,resource,p.id))))
   OR (category='contract_identity' AND EXISTS(SELECT 1 FROM passenger_debt_payments d WHERE d.passenger_id=owner AND d.id::text=w.payload->>'payment')))) w WHERE w.semantic_first=1
 UNION ALL
 SELECT 'transfer',t.id,encode(digest(concat_ws('|',t.id::text,t.user_id::text,t.type,t.cents::text,extract(epoch FROM t.created_at)::text),'sha256'),'hex'),NULL::timestamptz
 FROM transfers t WHERE category='contract_identity' AND t.user_id=owner
 UNION ALL
 SELECT 'card_command',c.payment_id,c.body_hash,NULL::timestamptz FROM mercado_pago_card_commands c JOIN payments p ON p.id=c.payment_id
 WHERE p.passenger_id=owner AND (category='contract_identity' OR (category='vehicle_identity' AND privacy_identity_vehicle_payment(owner,resource,p.id)))
 UNION ALL
 SELECT 'pix_command',c.payment_id,encode(digest(concat_ws('|',c.payment_id::text,c.amount_cents::text,c.expiration_seconds::text,extract(epoch FROM c.created_at)::text),'sha256'),'hex'),NULL::timestamptz
 FROM mercado_pago_pix_commands c JOIN payments p ON p.id=c.payment_id
 WHERE p.passenger_id=owner AND (category='contract_identity' OR (category='vehicle_identity' AND privacy_identity_vehicle_payment(owner,resource,p.id)))
 UNION ALL
 SELECT 'debt_payment',d.id,encode(digest(concat_ws('|',d.id::text,d.passenger_id::text,d.amount_cents::text,extract(epoch FROM d.created_at)::text),'sha256'),'hex'),NULL::timestamptz
 FROM passenger_debt_payments d WHERE category='contract_identity' AND d.passenger_id=owner
 UNION ALL
 SELECT 'privacy_request',q.id,encode(digest(concat_ws('|',q.id::text,q.user_id::text,q.kind,extract(epoch FROM q.created_at)::text),'sha256'),'hex'),coalesce(q.resolved_at,q.created_at)
 FROM privacy_requests q WHERE category='contract_identity' AND q.user_id=owner
$$;

CREATE FUNCTION privacy_identity_facts_present(owner uuid,category text,resource uuid,facts jsonb) RETURNS boolean
LANGUAGE sql STABLE AS $$
 WITH available AS MATERIALIZED(SELECT kind,id,sha256 FROM privacy_identity_purpose_facts(owner,category,resource)),
 expected AS (SELECT * FROM jsonb_to_recordset(coalesce(facts,'[]')) AS f(kind text,id uuid,sha256 text))
 SELECT NOT EXISTS(SELECT 1 FROM expected f LEFT JOIN available a ON a.kind=f.kind AND a.id=f.id AND a.sha256=f.sha256 WHERE a.id IS NULL)
$$;

CREATE FUNCTION privacy_identity_evidence_required(r privacy_retained_records) RETURNS boolean
LANGUAGE sql STABLE AS $$
 SELECT NOT r.hold_only AND r.category IN ('contract_identity','vehicle_identity') AND (
   privacy_identity_purpose_open(r.user_id,r.category,r.resource_id)
   OR coalesce((r.financial_purpose->>'pending')::boolean,false)
   OR NOT privacy_identity_facts_present(r.user_id,r.category,r.resource_id,r.financial_purpose->'facts'))
$$;

CREATE FUNCTION protect_identity_evidence_expiry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF privacy_identity_evidence_required(OLD) THEN
  RAISE EXCEPTION 'Minimum identity evidence still has an unresolved purpose' USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END;
$$;
CREATE TRIGGER privacy_identity_evidence_delete BEFORE DELETE ON privacy_retained_records
 FOR EACH ROW EXECUTE FUNCTION protect_identity_evidence_expiry();
CREATE INDEX privacy_identity_evidence_candidates ON privacy_retained_records(id)
 WHERE NOT hold_only AND category IN ('contract_identity','vehicle_identity');
CREATE INDEX privacy_verified_payment_facts ON webhook_events((payload->>'payment'),id)
 WHERE provider='mercadopago';
