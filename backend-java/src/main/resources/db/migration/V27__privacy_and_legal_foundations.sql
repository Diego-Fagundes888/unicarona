-- Additive foundations. Existing accounts have no invented/retroactive document acceptance.
CREATE TABLE legal_document_versions (
 slug text NOT NULL CHECK(slug ~ '^[a-z][a-z0-9-]{1,63}$'),
 version text NOT NULL CHECK(version ~ '^[A-Za-z0-9._-]{1,64}$'),
 title text NOT NULL CHECK(length(title) BETWEEN 2 AND 180),
 content jsonb NOT NULL CHECK(jsonb_typeof(content)='object'),
 content_sha256 text NOT NULL CHECK(content_sha256 ~ '^[a-f0-9]{64}$'),
 change_summary text NOT NULL CHECK(length(change_summary) BETWEEN 3 AND 2000),
 requires_acceptance boolean NOT NULL,
 published_at timestamptz NOT NULL DEFAULT now(), effective_at timestamptz NOT NULL,
 PRIMARY KEY(slug,version)
);
CREATE FUNCTION protect_legal_document_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Published document versions are immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER legal_document_version_immutable BEFORE UPDATE OR DELETE ON legal_document_versions
 FOR EACH ROW EXECUTE FUNCTION protect_legal_document_version();
CREATE TABLE legal_document_heads (
 slug text PRIMARY KEY, version text NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(slug,version) REFERENCES legal_document_versions(slug,version)
);
CREATE TABLE legal_acceptances (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 document_slug text NOT NULL, document_version text NOT NULL,
 kind text NOT NULL CHECK(kind IN ('contract','privacy_notice','driver_contract')),
 content_sha256 text NOT NULL CHECK(content_sha256 ~ '^[a-f0-9]{64}$'),
 source text NOT NULL CHECK(source IN ('registration','privacy_center','driver_application','terms_update')),
 accepted_at timestamptz NOT NULL DEFAULT now(),
 ip_ciphertext text, ip_delete_after timestamptz,
 retain_until timestamptz NOT NULL,
 FOREIGN KEY(document_slug,document_version) REFERENCES legal_document_versions(slug,version),
 UNIQUE(user_id,document_slug,document_version,kind),
 CHECK((ip_ciphertext IS NULL)=(ip_delete_after IS NULL)),
 CHECK(retain_until>accepted_at)
);
CREATE INDEX legal_acceptance_user ON legal_acceptances(user_id,accepted_at DESC);
CREATE TABLE privacy_preferences (
 user_id uuid PRIMARY KEY REFERENCES users,
 marketing boolean NOT NULL DEFAULT false,
 marketing_statement_version text,
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(NOT marketing OR marketing_statement_version IS NOT NULL)
);
CREATE TABLE privacy_consent_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 purpose text NOT NULL CHECK(purpose='marketing'), granted boolean NOT NULL,
 statement_version text NOT NULL, source text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), retain_until timestamptz NOT NULL,
 CHECK(retain_until>created_at)
);
CREATE INDEX privacy_consent_user ON privacy_consent_events(user_id,created_at DESC);
CREATE TABLE privacy_requests (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 kind text NOT NULL CHECK(kind IN ('access','correction','erasure','restriction','opposition','portability','appeal','other')),
 details_ciphertext text NOT NULL,
 status text NOT NULL DEFAULT 'received' CHECK(status IN ('received','in_review','awaiting_information','completed','denied')),
 created_at timestamptz NOT NULL DEFAULT now(), due_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(), resolved_at timestamptz,
 CHECK(due_at>created_at)
);
CREATE INDEX privacy_requests_user ON privacy_requests(user_id,created_at DESC);
CREATE INDEX privacy_requests_queue ON privacy_requests(status,due_at) WHERE resolved_at IS NULL;
CREATE TABLE privacy_request_responses (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), request_id uuid NOT NULL REFERENCES privacy_requests,
 author_id uuid NOT NULL REFERENCES users, body_ciphertext text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE privacy_account_closures (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 initiated_by text NOT NULL CHECK(initiated_by IN ('user','platform')),
 status text NOT NULL CHECK(status IN ('pending_obligations','scheduled','cancelled','completed')),
 previous_account_status text NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT now(), reauthenticated_at timestamptz NOT NULL,
 erase_after timestamptz NOT NULL, cancelled_at timestamptz, completed_at timestamptz,
 policy_version text NOT NULL, obligations_snapshot jsonb NOT NULL DEFAULT '{}',
 CHECK(erase_after>=requested_at),
 CHECK((status='cancelled')=(cancelled_at IS NOT NULL)),
 CHECK((status='completed')=(completed_at IS NOT NULL))
);
CREATE UNIQUE INDEX privacy_one_open_closure ON privacy_account_closures(user_id)
 WHERE status IN ('pending_obligations','scheduled');
CREATE INDEX privacy_closure_due ON privacy_account_closures(erase_after) WHERE status='scheduled';
CREATE TABLE privacy_legal_holds (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 category text NOT NULL, resource_type text NOT NULL, resource_id uuid,
 legal_basis text NOT NULL CHECK(legal_basis IN ('legal_obligation','regular_exercise_of_rights','contract','security')),
 reason_ciphertext text NOT NULL, created_by uuid NOT NULL REFERENCES users,
 created_at timestamptz NOT NULL DEFAULT now(), review_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 released_at timestamptz,
 CHECK(review_at>created_at AND expires_at>=review_at)
);
CREATE INDEX privacy_hold_user ON privacy_legal_holds(user_id,expires_at) WHERE released_at IS NULL;
CREATE TABLE privacy_retained_records (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 category text NOT NULL, content_ciphertext text NOT NULL,
 reason text NOT NULL, legal_basis text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), delete_after timestamptz NOT NULL,
 CHECK(delete_after>created_at)
);
CREATE INDEX privacy_retained_due ON privacy_retained_records(delete_after);
CREATE TABLE privacy_erasure_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 closure_id uuid NOT NULL UNIQUE REFERENCES privacy_account_closures,
 erased_at timestamptz NOT NULL DEFAULT now(), suppression_until timestamptz NOT NULL,
 CHECK(suppression_until>erased_at)
);
CREATE TABLE privacy_registration_restrictions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), email_hmac text NOT NULL CHECK(email_hmac ~ '^[a-f0-9]{64}$'),
 reason_code text NOT NULL CHECK(reason_code IN ('fraud','violence','harassment','false_documents','security','repeated_terms_breach')),
 created_by uuid NOT NULL REFERENCES users, created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL, released_at timestamptz,
 CHECK(expires_at>created_at AND expires_at<=created_at+interval '1 year')
);
CREATE INDEX privacy_registration_restricted ON privacy_registration_restrictions(email_hmac,expires_at) WHERE released_at IS NULL;
