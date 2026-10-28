ALTER TABLE legal_document_versions ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK(revision>0);
ALTER TABLE legal_document_versions ADD COLUMN kind text NOT NULL DEFAULT 'contract' CHECK(kind IN ('contract','privacy_notice','driver_contract'));
ALTER TABLE legal_document_versions ADD COLUMN required_scopes text[] NOT NULL DEFAULT '{}'
 CHECK(required_scopes<@ARRAY['registration','service','driver']::text[]);
CREATE TABLE legal_change_notices (
 user_id uuid NOT NULL REFERENCES users,
 document_slug text NOT NULL, document_version text NOT NULL,
 notified_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,document_slug,document_version),
 FOREIGN KEY(document_slug,document_version) REFERENCES legal_document_versions(slug,version)
);
CREATE FUNCTION protect_legal_acceptance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE document legal_document_versions%ROWTYPE;
BEGIN
 IF TG_OP='INSERT' THEN
  SELECT * INTO document FROM legal_document_versions WHERE slug=NEW.document_slug AND version=NEW.document_version;
  IF document.slug IS NULL OR document.content_sha256 IS DISTINCT FROM NEW.content_sha256 OR document.kind IS DISTINCT FROM NEW.kind
  THEN RAISE EXCEPTION 'Acceptance must match the published document' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 ELSIF TG_OP='UPDATE' THEN
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
CREATE TRIGGER legal_acceptance_evidence BEFORE INSERT OR UPDATE OR DELETE ON legal_acceptances
 FOR EACH ROW EXECUTE FUNCTION protect_legal_acceptance();
