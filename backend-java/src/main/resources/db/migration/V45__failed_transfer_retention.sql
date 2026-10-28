-- Only the supported, definitively failed generic transfer graph may expire.
-- Its two journals must cancel on EACH account. Successful money is untouched.
CREATE FUNCTION privacy_failed_transfer_expired(t transfers) RETURNS boolean
LANGUAGE sql STABLE STRICT AS $$
 WITH journals AS MATERIALIZED (
   SELECT j.* FROM ledger_transactions j WHERE j.event_key IN ('transfer:'||t.id||':requested','transfer:'||t.id||':completed')
 ), entries AS MATERIALIZED (
   SELECT e.*,a.owner_id,a.kind,a.currency FROM ledger_entries e JOIN journals j ON j.id=e.transaction_id JOIN ledger_accounts a ON a.id=e.account_id
 )
 SELECT t.status='failed' AND t.provider_reference IS NOT NULL AND t.decision IS NULL
   AND (SELECT count(*) FROM journals)=2
   AND EXISTS(SELECT 1 FROM journals WHERE event_key='transfer:'||t.id||':requested' AND kind='transfer_requested' AND created_at>=t.created_at)
   AND EXISTS(SELECT 1 FROM journals WHERE event_key='transfer:'||t.id||':completed' AND kind='transfer_failed'
     AND created_at>=(SELECT created_at FROM journals WHERE event_key='transfer:'||t.id||':requested'))
   AND NOT EXISTS(SELECT 1 FROM journals WHERE payment_id IS NOT NULL OR parcel_id IS NOT NULL)
   AND NOT EXISTS(SELECT 1 FROM ledger_transactions j WHERE j.event_key LIKE 'transfer:'||t.id||':%' AND j.id NOT IN(SELECT id FROM journals))
   AND (SELECT count(*) FROM entries)=4
   AND NOT EXISTS(SELECT 1 FROM entries WHERE owner_id IS DISTINCT FROM t.user_id OR currency<>'BRL')
   AND NOT EXISTS(SELECT 1 FROM journals j WHERE (SELECT count(*) FROM entries e WHERE e.transaction_id=j.id)<>2)
   AND NOT EXISTS(SELECT 1 FROM entries e JOIN journals j ON j.id=e.transaction_id WHERE e.cents IS DISTINCT FROM
     CASE WHEN j.kind='transfer_requested' THEN
       CASE e.kind WHEN CASE WHEN t.type='withdraw' THEN 'driver_available' ELSE 'passenger_credit' END THEN -t.cents
         WHEN CASE WHEN t.type='withdraw' THEN 'payout_pending' ELSE 'refund_pending' END THEN t.cents END
       ELSE CASE e.kind WHEN CASE WHEN t.type='withdraw' THEN 'driver_available' ELSE 'passenger_credit' END THEN t.cents
         WHEN CASE WHEN t.type='withdraw' THEN 'payout_pending' ELSE 'refund_pending' END THEN -t.cents END END)
   AND NOT EXISTS(SELECT 1 FROM entries GROUP BY account_id HAVING sum(cents)<>0)
   AND (((greatest(t.created_at,(SELECT max(created_at) FROM journals),(SELECT max(created_at) FROM entries),
     (SELECT max(created_at) FROM audit_logs WHERE resource_type='transfer' AND resource_id=t.id),
     (SELECT max(greatest(o.created_at,o.reviewed_at)) FROM occurrences o WHERE (o.reporter_id=t.user_id OR o.other_id=t.user_id)
       AND (o.kind='dispute' OR o.outcome IN ('released','credited','retained'))))
       AT TIME ZONE 'America/Sao_Paulo') + interval '5 years') AT TIME ZONE 'America/Sao_Paulo')<=now()
   AND NOT EXISTS(SELECT 1 FROM mercado_pago_refunds WHERE transfer_id=t.id)
   AND NOT EXISTS(SELECT 1 FROM occurrences WHERE (reporter_id=t.user_id OR other_id=t.user_id) AND status IN ('pending','financial_pending'))
   AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=t.user_id AND h.category='financial' AND h.released_at IS NULL AND h.expires_at>now())
   AND NOT EXISTS(SELECT 1 FROM notifications n JOIN privacy_legal_holds h ON h.user_id=n.user_id AND h.category='notifications'
     AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=n.id) WHERE n.ref_id=t.id)
   AND NOT EXISTS(SELECT 1 FROM notifications n WHERE n.ref_id=t.id AND (n.user_id<>t.user_id OR n.route<>'wallet'))
   AND NOT EXISTS(SELECT 1 FROM privacy_requests r WHERE r.user_id=t.user_id AND r.resolved_at IS NULL AND r.kind IN ('access','portability','restriction'))
   AND NOT EXISTS(SELECT 1 FROM idempotency_keys k WHERE k.actor_id=t.user_id AND k.expires_at>now() AND privacy_unsubmitted_cache_points_to(k,t.id))
   AND NOT EXISTS(SELECT 1 FROM audit_logs a WHERE a.resource_type='transfer' AND a.resource_id=t.id AND (
     (a.action='transfer_requested' AND a.actor_id=t.user_id AND a.metadata=jsonb_build_object('type',t.type,'cents',t.cents)) OR
     (a.action='transfer_provider_result' AND a.actor_id IS NULL AND a.metadata='{"status":"failed"}'::jsonb)) IS DISTINCT FROM true)
   -- A provider name/receipt contract must be reviewed before adding it here.
   AND EXISTS(SELECT 1 FROM webhook_events w WHERE lower(w.payload->>'transferId')=t.id::text)
   AND NOT EXISTS(SELECT 1 FROM webhook_events w WHERE (lower(w.payload->>'transferId')=t.id::text OR lower(w.payload->>'transfer')=t.id::text) AND (
     w.provider='development-transfer' AND w.status='processed' AND w.processed_at IS NOT NULL
     AND w.payload=jsonb_build_object('eventId',w.event_id,'transferId',t.id::text,'reference',t.provider_reference,'amountCents',t.cents,'status','failed')) IS DISTINCT FROM true)
   AND NOT EXISTS(SELECT 1 FROM outbox_jobs j WHERE (lower(j.payload->>'transferId')=t.id::text OR j.event_key='transfer:'||t.id) AND (
     j.type='transfer' AND j.event_key='transfer:'||t.id AND j.payload=jsonb_build_object('transferId',t.id::text)
       AND j.status IN ('done','dead') AND (j.leased_until IS NULL OR j.leased_until<=now())) IS DISTINCT FROM true)
   -- Do not erase a source still witnessed by an autonomous identity/evidence copy.
   AND NOT EXISTS(SELECT 1 FROM privacy_retained_records r WHERE r.resource_id=t.id
     OR r.resource_id IN(SELECT id FROM journals UNION ALL SELECT id FROM entries
       UNION ALL SELECT id FROM audit_logs WHERE resource_type='transfer' AND resource_id=t.id
       UNION ALL SELECT id FROM webhook_events WHERE lower(payload->>'transferId')=t.id::text)
     OR r.financial_purpose->'facts' @> jsonb_build_array(jsonb_build_object('kind','transfer','id',t.id))
     OR EXISTS(SELECT 1 FROM entries e WHERE r.financial_purpose->'facts' @> jsonb_build_array(jsonb_build_object('kind','ledger_entry','id',e.id))))
$$;

CREATE FUNCTION privacy_failed_transfer_for_journal(journal uuid) RETURNS uuid
LANGUAGE sql STABLE STRICT AS $$
 SELECT t.id FROM transfers t JOIN ledger_transactions j ON j.id=journal
   AND j.event_key IN ('transfer:'||t.id||':requested','transfer:'||t.id||':completed')
 WHERE privacy_failed_transfer_expired(t)
$$;

-- STABLE reads the original statement graph. Deferred guards below require the
-- WHOLE neutral group to disappear; a partial journal deletion never commits.
CREATE FUNCTION protect_financial_retirement() RETURNS trigger LANGUAGE plpgsql STABLE AS $$
DECLARE target uuid;
BEGIN
 IF TG_OP='DELETE' THEN
   IF TG_TABLE_NAME='transfers' THEN
     IF privacy_failed_transfer_expired(OLD) THEN RETURN OLD; END IF;
   ELSE
     IF TG_TABLE_NAME='ledger_entries' THEN target:=OLD.transaction_id; ELSE target:=OLD.id; END IF;
     IF privacy_failed_transfer_for_journal(target) IS NOT NULL THEN RETURN OLD; END IF;
   END IF;
 END IF;
 RAISE EXCEPTION 'Financial history is immutable or still retained' USING ERRCODE='23514';
END $$;
DROP TRIGGER transfer_no_delete ON transfers;
CREATE TRIGGER transfer_no_delete BEFORE DELETE ON transfers FOR EACH ROW EXECUTE FUNCTION protect_financial_retirement();
DROP TRIGGER ledger_transaction_immutable ON ledger_transactions;
CREATE TRIGGER ledger_transaction_immutable BEFORE UPDATE OR DELETE ON ledger_transactions FOR EACH ROW EXECUTE FUNCTION protect_financial_retirement();
DROP TRIGGER ledger_entry_immutable ON ledger_entries;
CREATE TRIGGER ledger_entry_immutable BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION protect_financial_retirement();

CREATE FUNCTION privacy_neutral_graph_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='ledger_entries' THEN
   IF EXISTS(SELECT 1 FROM ledger_transactions WHERE id=OLD.transaction_id) THEN RAISE EXCEPTION 'Retired entry requires complete journal retirement' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='ledger_transactions' THEN
   IF EXISTS(SELECT 1 FROM transfers t WHERE OLD.event_key IN ('transfer:'||t.id||':requested','transfer:'||t.id||':completed')) THEN RAISE EXCEPTION 'Retired journal requires complete transfer retirement' USING ERRCODE='23514'; END IF;
 ELSE
   IF EXISTS(SELECT 1 FROM ledger_transactions WHERE event_key IN ('transfer:'||OLD.id||':requested','transfer:'||OLD.id||':completed')) THEN RAISE EXCEPTION 'Transfer retirement cannot leave its journals' USING ERRCODE='23514'; END IF;
   IF EXISTS(SELECT 1 FROM audit_logs WHERE resource_type='transfer' AND resource_id=OLD.id)
     OR EXISTS(SELECT 1 FROM webhook_events WHERE lower(payload->>'transferId')=OLD.id::text OR lower(payload->>'transfer')=OLD.id::text)
     OR EXISTS(SELECT 1 FROM outbox_jobs WHERE event_key='transfer:'||OLD.id OR lower(payload->>'transferId')=OLD.id::text)
     OR EXISTS(SELECT 1 FROM notifications WHERE ref_id=OLD.id)
     OR EXISTS(SELECT 1 FROM idempotency_keys k WHERE k.actor_id=OLD.user_id AND privacy_unsubmitted_cache_points_to(k,OLD.id)) THEN
       RAISE EXCEPTION 'Transfer retirement cannot leave its dependent operational graph' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER privacy_neutral_entry_complete AFTER DELETE ON ledger_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION privacy_neutral_graph_complete();
CREATE CONSTRAINT TRIGGER privacy_neutral_journal_complete AFTER DELETE ON ledger_transactions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION privacy_neutral_graph_complete();
CREATE CONSTRAINT TRIGGER privacy_neutral_transfer_complete AFTER DELETE ON transfers DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION privacy_neutral_graph_complete();

CREATE OR REPLACE FUNCTION protect_audit_lifecycle() RETURNS trigger LANGUAGE plpgsql STABLE AS $$
BEGIN
 IF TG_OP='DELETE' AND ((privacy_security_audit_deadline(OLD)<=clock_timestamp() AND privacy_security_audit_case_safe(OLD))
   OR privacy_unsubmitted_audit_expired(OLD)
   OR (OLD.resource_type='transfer' AND EXISTS(SELECT 1 FROM transfers t WHERE t.id=OLD.resource_id AND privacy_failed_transfer_expired(t)))) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Audit history is immutable or still retained' USING ERRCODE='23514';
END $$;
CREATE TRIGGER privacy_financial_entry_coordination BEFORE DELETE ON ledger_entries FOR EACH STATEMENT EXECUTE FUNCTION privacy_retention_coordination();
CREATE TRIGGER privacy_financial_journal_coordination BEFORE DELETE ON ledger_transactions FOR EACH STATEMENT EXECUTE FUNCTION privacy_retention_coordination();
CREATE TRIGGER privacy_financial_transfer_coordination BEFORE DELETE ON transfers FOR EACH STATEMENT EXECUTE FUNCTION privacy_retention_coordination();
CREATE TRIGGER privacy_financial_entry_no_truncate BEFORE TRUNCATE ON ledger_entries FOR EACH STATEMENT EXECUTE FUNCTION prevent_history_change();
CREATE TRIGGER privacy_financial_journal_no_truncate BEFORE TRUNCATE ON ledger_transactions FOR EACH STATEMENT EXECUTE FUNCTION prevent_history_change();
CREATE TRIGGER privacy_financial_transfer_no_truncate BEFORE TRUNCATE ON transfers FOR EACH STATEMENT EXECUTE FUNCTION prevent_history_change();
CREATE INDEX privacy_failed_transfer_scan ON transfers(id) WHERE status='failed';
CREATE INDEX privacy_failed_transfer_journal_entries ON ledger_entries(transaction_id);
CREATE INDEX privacy_failed_transfer_receipt_id ON webhook_events((lower(payload->>'transferId')));
CREATE INDEX privacy_failed_transfer_legacy_receipt_id ON webhook_events((lower(payload->>'transfer')));
