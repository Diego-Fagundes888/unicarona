-- Only the ciphertext representation may change during the verified application rotation.
-- This transaction-local guard prevents accidental edits; it is not a boundary against a DB administrator.
CREATE OR REPLACE FUNCTION protect_account_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' THEN
  IF current_setting('unicarona.privacy_key_rotation',true)='on'
     AND (to_jsonb(NEW)-'reason_ciphertext') IS NOT DISTINCT FROM (to_jsonb(OLD)-'reason_ciphertext')
     AND OLD.reason_ciphertext IS NOT NULL AND NEW.reason_ciphertext ~ '^pvc1\.[a-zA-Z0-9_-]{1,48}\.[A-Za-z0-9_-]+$'
  THEN RETURN NEW; END IF;
  IF (to_jsonb(NEW)-'retain_until') IS DISTINCT FROM (to_jsonb(OLD)-'retain_until') OR NEW.retain_until<OLD.retain_until THEN
   RAISE EXCEPTION 'Account decision evidence is immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 IF OLD.retain_until>now() OR EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=OLD.user_id AND h.category='account_decision' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=OLD.id)) THEN
  RAISE EXCEPTION 'Account decision evidence is still retained' USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION protect_legal_acceptance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE document legal_document_versions%ROWTYPE;
BEGIN
 IF TG_OP='INSERT' THEN
  SELECT * INTO document FROM legal_document_versions WHERE slug=NEW.document_slug AND version=NEW.document_version;
  IF document.slug IS NULL OR document.content_sha256 IS DISTINCT FROM NEW.content_sha256 OR document.kind IS DISTINCT FROM NEW.kind
  THEN RAISE EXCEPTION 'Acceptance must match the published document' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 ELSIF TG_OP='UPDATE' THEN
  IF current_setting('unicarona.privacy_key_rotation',true)='on'
     AND (to_jsonb(NEW)-'ip_ciphertext') IS NOT DISTINCT FROM (to_jsonb(OLD)-'ip_ciphertext')
     AND OLD.ip_ciphertext IS NOT NULL AND NEW.ip_ciphertext ~ '^pvc1\.[a-zA-Z0-9_-]{1,48}\.[A-Za-z0-9_-]+$'
  THEN RETURN NEW; END IF;
  IF ROW(OLD.id,OLD.user_id,OLD.document_slug,OLD.document_version,OLD.kind,OLD.content_sha256,OLD.source,OLD.accepted_at)
    IS DISTINCT FROM ROW(NEW.id,NEW.user_id,NEW.document_slug,NEW.document_version,NEW.kind,NEW.content_sha256,NEW.source,NEW.accepted_at)
    OR NEW.retain_until<OLD.retain_until
    OR (ROW(OLD.ip_ciphertext,OLD.ip_delete_after) IS DISTINCT FROM ROW(NEW.ip_ciphertext,NEW.ip_delete_after)
      AND NOT (NEW.ip_ciphertext IS NULL AND NEW.ip_delete_after IS NULL AND OLD.ip_delete_after<=now()))
  THEN RAISE EXCEPTION 'Acceptance evidence is immutable except justified expiry metadata' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 ELSE
  IF OLD.retain_until>now() OR NOT EXISTS(SELECT 1 FROM users WHERE id=OLD.user_id AND deleted_at<=now()-interval '5 years')
    OR EXISTS(SELECT 1 FROM privacy_legal_holds WHERE user_id=OLD.user_id AND category='legal' AND released_at IS NULL AND expires_at>now())
  THEN RAISE EXCEPTION 'Acceptance evidence is still retained' USING ERRCODE='23514'; END IF;
  RETURN OLD;
 END IF;
END $$;
