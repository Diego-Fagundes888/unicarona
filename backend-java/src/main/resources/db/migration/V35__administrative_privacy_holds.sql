ALTER TABLE privacy_legal_holds ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK(revision>0);
ALTER TABLE privacy_legal_holds ADD COLUMN reviewed_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE privacy_legal_holds ADD COLUMN retain_until timestamptz;
UPDATE privacy_legal_holds SET retain_until=greatest(expires_at,coalesce(released_at,expires_at))+interval '5 years';
ALTER TABLE privacy_legal_holds ALTER COLUMN retain_until SET NOT NULL;
ALTER TABLE privacy_legal_holds ALTER COLUMN retain_until SET DEFAULT now()+interval '5 years';
ALTER TABLE privacy_legal_holds ADD CONSTRAINT privacy_hold_identity UNIQUE(id,user_id);
ALTER TABLE privacy_retained_records ADD COLUMN hold_only boolean NOT NULL DEFAULT false;
UPDATE privacy_retained_records SET hold_only=true WHERE category IN ('profile','identity_change','notifications');
UPDATE privacy_retained_records p SET hold_only=true WHERE p.category='location' AND NOT EXISTS(
 SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id WHERE r.route_id=p.resource_id);

CREATE TABLE privacy_hold_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),hold_id uuid NOT NULL,
 user_id uuid NOT NULL REFERENCES users,revision integer NOT NULL CHECK(revision>0),
 action text NOT NULL CHECK(action IN ('created','reviewed','released','legacy_snapshot')),
 body_ciphertext text NOT NULL,created_by uuid NOT NULL REFERENCES users,
 created_at timestamptz NOT NULL DEFAULT now(),retain_until timestamptz NOT NULL DEFAULT now()+interval '5 years',
 UNIQUE(hold_id,revision),CHECK(retain_until>created_at),
 FOREIGN KEY(hold_id,user_id) REFERENCES privacy_legal_holds(id,user_id)
);
CREATE INDEX privacy_hold_events_owner ON privacy_hold_events(user_id,created_at,id);
CREATE INDEX privacy_holds_review_due ON privacy_legal_holds(review_at,id) WHERE released_at IS NULL;

CREATE FUNCTION privacy_retention_coordination() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM pg_advisory_xact_lock(74019,1);RETURN NULL; END;
$$;
-- Statement triggers acquire the lock before resource-row locks, including direct maintenance SQL.
CREATE TRIGGER privacy_hold_coordination BEFORE INSERT OR UPDATE OR DELETE ON privacy_legal_holds
 FOR EACH STATEMENT EXECUTE FUNCTION privacy_retention_coordination();
CREATE TRIGGER privacy_hold_event_coordination BEFORE INSERT OR UPDATE OR DELETE ON privacy_hold_events
 FOR EACH STATEMENT EXECUTE FUNCTION privacy_retention_coordination();

CREATE FUNCTION protect_privacy_hold_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' THEN
  IF current_setting('unicarona.privacy_key_rotation',true)='on'
     AND (to_jsonb(NEW)-'body_ciphertext') IS NOT DISTINCT FROM (to_jsonb(OLD)-'body_ciphertext')
     AND NEW.body_ciphertext ~ '^pvc1\.[a-zA-Z0-9_-]{1,48}\.[A-Za-z0-9_-]+$' THEN RETURN NEW; END IF;
  RAISE EXCEPTION 'Privacy hold history is immutable' USING ERRCODE='23514';
 END IF;
 IF OLD.retain_until>now() OR EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.id=OLD.hold_id AND h.released_at IS NULL AND h.expires_at>now()) THEN
  RAISE EXCEPTION 'Privacy hold history is still retained' USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END;
$$;
CREATE TRIGGER privacy_hold_event_immutable BEFORE UPDATE OR DELETE ON privacy_hold_events
 FOR EACH ROW EXECUTE FUNCTION protect_privacy_hold_event();

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
    OR EXISTS(SELECT 1 FROM privacy_legal_holds WHERE user_id=OLD.user_id AND category='legal' AND released_at IS NULL AND expires_at>now() AND (resource_id IS NULL OR resource_id=OLD.id))
  THEN RAISE EXCEPTION 'Acceptance evidence is still retained' USING ERRCODE='23514'; END IF;
  RETURN OLD;
 END IF;
END $$;
