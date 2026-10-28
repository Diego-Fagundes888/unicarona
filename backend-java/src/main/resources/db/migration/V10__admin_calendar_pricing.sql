ALTER TABLE classes ADD CONSTRAINT classes_id_university_unique UNIQUE(id,university_id);
ALTER TABLE calendar_exclusions ADD CONSTRAINT calendar_class_university_fk
 FOREIGN KEY(class_id,university_id) REFERENCES classes(id,university_id);
ALTER TABLE calendar_exclusions ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE calendar_exclusions ADD COLUMN deactivated_by uuid REFERENCES users;
ALTER TABLE calendar_exclusions ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE UNIQUE INDEX calendar_active_unique ON calendar_exclusions(university_id,exclusion_date,class_id) NULLS NOT DISTINCT WHERE active;
CREATE INDEX calendar_active_lookup ON calendar_exclusions(university_id,exclusion_date,class_id) WHERE active;
CREATE TRIGGER pricing_history_immutable BEFORE UPDATE OR DELETE ON pricing_versions
 FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
