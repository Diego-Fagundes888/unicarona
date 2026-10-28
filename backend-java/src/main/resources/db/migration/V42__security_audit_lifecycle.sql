-- Operational security policy, not a claim that these events are application
-- access records under the Marco Civil. No IP/access logging is introduced.
CREATE FUNCTION privacy_security_audit_deadline(a audit_logs) RETURNS timestamptz
LANGUAGE sql IMMUTABLE STRICT AS $$
 SELECT CASE WHEN
   (a.resource_type='user' AND a.actor_id IS NOT DISTINCT FROM a.resource_id AND a.action IN (
     'email_requested','email_verification_invalid','email_verification_replaced',
     'email_verification_used','email_verification_expired','email_verified',
     'password_reset','password_changed','settings_updated','email_change_cancelled'))
   OR (a.resource_type='identity_change' AND a.actor_id IS NOT NULL AND a.resource_id IS NOT NULL
     AND a.action IN ('email_change_requested','email_changed'))
 THEN ((a.created_at AT TIME ZONE 'America/Sao_Paulo')+interval '6 months') AT TIME ZONE 'America/Sao_Paulo' END
$$;

CREATE FUNCTION privacy_security_audit_case_safe(a audit_logs) RETURNS boolean
LANGUAGE sql STABLE STRICT AS $$
 SELECT NOT EXISTS(SELECT 1 FROM privacy_legal_holds h
   WHERE h.category='security_audit' AND h.released_at IS NULL AND h.expires_at>now()
     AND (h.resource_id IS NULL OR h.resource_id=a.id)
     AND NOT EXISTS(SELECT 1 FROM privacy_retained_records p WHERE p.user_id=h.user_id
       AND p.category='security_audit' AND p.resource_type='audit_log' AND p.resource_id=a.id AND p.hold_only))
$$;

-- Update remains forbidden, including attempts to change an original deadline.
-- Only explicitly classified expired events can be removed. Financial/legal/
-- community history retains its original guard pending its own lifecycle.
CREATE FUNCTION protect_audit_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND privacy_security_audit_deadline(OLD)<=clock_timestamp()
    AND privacy_security_audit_case_safe(OLD) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Audit history is immutable or still retained' USING ERRCODE='23514';
END;
$$;
DROP TRIGGER audit_immutable ON audit_logs;
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON audit_logs
 FOR EACH ROW EXECUTE FUNCTION protect_audit_lifecycle();
CREATE TRIGGER privacy_audit_delete_coordination BEFORE DELETE ON audit_logs
 FOR EACH STATEMENT EXECUTE FUNCTION privacy_retention_coordination();
CREATE INDEX privacy_security_audit_expiry ON audit_logs(created_at,id);
-- No deletion in this migration. Maintenance checks the independent preservation
-- checkpoint and replays newer case evidence before any destructive cleanup.
