-- No owner is invented for bytes that have no database metadata. Fingerprints are personal/
-- pseudonymous operational evidence, not anonymous statistics. The collector retires these rows.
CREATE TABLE privacy_orphan_file_observations (
 file_id uuid PRIMARY KEY,
 byte_size integer NOT NULL CHECK(byte_size BETWEEN 0 AND 5242880),
 content_sha256 text NOT NULL CHECK(content_sha256 ~ '^[a-f0-9]{64}$'),
 fingerprint text NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'),
 first_seen_at timestamptz NOT NULL DEFAULT now(),
 last_seen_at timestamptz NOT NULL DEFAULT now(),
 CHECK(last_seen_at>=first_seen_at)
);
CREATE INDEX privacy_orphan_observation_cleanup ON privacy_orphan_file_observations(last_seen_at,file_id);
