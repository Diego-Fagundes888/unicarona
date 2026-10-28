CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE FUNCTION normalized_text(text) RETURNS text LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
 SELECT lower(public.unaccent('public.unaccent', $1))
$$;
DROP INDEX route_region_idx;
CREATE INDEX route_region_idx ON ride_routes USING gin(normalized_text(region) gin_trgm_ops);
CREATE UNIQUE INDEX driver_departure_idx ON rides(driver_id,ride_date,departure_time) WHERE status IN ('planned','active');
CREATE INDEX vehicle_owner_status_idx ON vehicles(owner_id,status);
