-- Finite operational window for raw application documents. Existing dates are
-- the source of the deadline; migration does not invent a recent review/upload.
ALTER TABLE driver_applications ALTER COLUMN identity_file_id DROP NOT NULL;
ALTER TABLE driver_applications ALTER COLUMN license_file_id DROP NOT NULL;
ALTER TABLE driver_applications DROP CONSTRAINT driver_applications_status_check;
ALTER TABLE driver_applications ADD CONSTRAINT driver_applications_status_check
 CHECK(status IN ('pending','approved','rejected','replaced','expired'));
ALTER TABLE driver_applications ADD COLUMN documents_expires_at timestamptz;
UPDATE driver_applications SET documents_expires_at=coalesce(reviewed_at,created_at)+interval '30 days';
ALTER TABLE driver_applications ALTER COLUMN documents_expires_at SET NOT NULL;
ALTER TABLE driver_applications ALTER COLUMN documents_expires_at SET DEFAULT (now()+interval '30 days');
ALTER TABLE driver_applications ADD COLUMN documents_retired_at timestamptz;
ALTER TABLE driver_applications ADD COLUMN documents_policy_version text NOT NULL DEFAULT 'driver-documents-v1';
ALTER TABLE driver_applications ADD CONSTRAINT driver_documents_retired_links
 CHECK(documents_retired_at IS NULL OR (identity_file_id IS NULL AND license_file_id IS NULL AND status<>'pending'));
CREATE INDEX driver_documents_expiry ON driver_applications(documents_expires_at,id) WHERE documents_retired_at IS NULL;
CREATE INDEX stored_files_erased_metadata_expiry ON stored_files(erased_at,id) WHERE erased_at IS NOT NULL;
