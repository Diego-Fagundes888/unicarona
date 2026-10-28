-- Account controls do not remove ledger entries or equate a restriction with data erasure.
ALTER TABLE users DROP CONSTRAINT users_account_status_check;
ALTER TABLE users ADD CONSTRAINT users_account_status_check
 CHECK(account_status IN ('active','deactivated','suspended','banned','closing','deleted'));
ALTER TABLE users ADD COLUMN voluntarily_deactivated_at timestamptz;
ALTER TABLE privacy_registration_restrictions ADD COLUMN source_user_id uuid REFERENCES users;
CREATE INDEX privacy_restriction_source ON privacy_registration_restrictions(source_user_id)
 WHERE released_at IS NULL;
CREATE TABLE privacy_account_decisions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL REFERENCES users,
 action text NOT NULL CHECK(action IN ('ban','close','lift_ban','cancel_closure')),
 previous_status text NOT NULL,new_status text NOT NULL,
 reason_code text NOT NULL CHECK(reason_code IN ('fraud','violence','harassment','false_documents','security','repeated_terms_breach')),
 public_reason text NOT NULL CHECK(length(public_reason) BETWEEN 10 AND 1000),
 reason_ciphertext text NOT NULL,created_by uuid NOT NULL REFERENCES users,
 restriction_days integer CHECK(restriction_days BETWEEN 1 AND 365),
 CHECK((action='ban')=(restriction_days IS NOT NULL)),
 created_at timestamptz NOT NULL DEFAULT now(),retain_until timestamptz NOT NULL DEFAULT now()+interval '5 years',
 CHECK(retain_until>created_at)
);
CREATE INDEX privacy_account_decision_user ON privacy_account_decisions(user_id,created_at DESC,id);
CREATE FUNCTION protect_account_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' THEN
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
CREATE TRIGGER privacy_account_decision_immutable BEFORE UPDATE OR DELETE ON privacy_account_decisions
 FOR EACH ROW EXECUTE FUNCTION protect_account_decision();
