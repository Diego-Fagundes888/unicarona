-- Application roles must not be superusers. See deployment runbook.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE universities (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), slug text NOT NULL UNIQUE,
 name text NOT NULL, city text NOT NULL, state_code char(2) NOT NULL,
 latitude numeric(10,7) CHECK (latitude BETWEEN -90 AND 90),
 longitude numeric(10,7) CHECK (longitude BETWEEN -180 AND 180),
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE courses (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), university_id uuid NOT NULL REFERENCES universities,
 slug text NOT NULL, name text NOT NULL, active boolean NOT NULL DEFAULT true,
 UNIQUE(university_id,slug), UNIQUE(id,university_id)
);
CREATE TABLE classes (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), university_id uuid NOT NULL REFERENCES universities,
 course_id uuid NOT NULL, period_number integer NOT NULL CHECK(period_number>0),
 label text NOT NULL CHECK(label IN ('A','B','C')),
 FOREIGN KEY(course_id,university_id) REFERENCES courses(id,university_id),
 UNIQUE(course_id,period_number,label), UNIQUE(id,course_id,university_id)
);
CREATE TABLE regions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), slug text NOT NULL UNIQUE, name text NOT NULL,
 state_code char(2) NOT NULL
);
CREATE TABLE users (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), email text NOT NULL,
 password_hash text NOT NULL, name text NOT NULL CHECK(length(name) BETWEEN 2 AND 120),
 birth_date date NOT NULL, phone text, photo_file_id uuid,
 role text NOT NULL DEFAULT 'user' CHECK(role IN ('user','admin')),
 account_status text NOT NULL DEFAULT 'active' CHECK(account_status IN ('active','suspended','deleted')),
 email_verified_at timestamptz, password_changed_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz,
 CHECK(email=lower(email)), CHECK(phone IS NULL OR phone ~ '^\+?[0-9]{10,15}$')
);
CREATE UNIQUE INDEX users_email_unique ON users(email);
CREATE TABLE student_profiles (
 user_id uuid PRIMARY KEY REFERENCES users, university_id uuid NOT NULL REFERENCES universities,
 course_id uuid NOT NULL, class_id uuid,
 period_status text NOT NULL CHECK(period_status IN ('numbered','irregular','undefined')),
 period_number integer CHECK(period_number>0),
 shift text NOT NULL CHECK(shift IN ('morning','afternoon','night','full-time','variable')),
 region_id uuid REFERENCES regions, region_label text NOT NULL,
 verification_status text NOT NULL DEFAULT 'self_reported' CHECK(verification_status IN ('self_reported','pending','verified','rejected')),
 verification_source text, verified_at timestamptz,
 notices boolean NOT NULL DEFAULT true, withdrawal_cadence text NOT NULL DEFAULT 'daily' CHECK(withdrawal_cadence IN ('daily','monthly')),
 updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(course_id,university_id) REFERENCES courses(id,university_id),
 FOREIGN KEY(class_id,course_id,university_id) REFERENCES classes(id,course_id,university_id),
 CHECK((period_status='numbered' AND period_number IS NOT NULL) OR (period_status<>'numbered' AND period_number IS NULL))
);
CREATE INDEX student_class_idx ON student_profiles(class_id) WHERE class_id IS NOT NULL;
CREATE TABLE sessions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 access_hash text NOT NULL UNIQUE, refresh_hash text NOT NULL UNIQUE,
 csrf_hash text NOT NULL, family_id uuid NOT NULL, generation integer NOT NULL DEFAULT 0,
 access_expires_at timestamptz NOT NULL, refresh_expires_at timestamptz NOT NULL,
 revoked_at timestamptz, replaced_by uuid REFERENCES sessions,
 created_at timestamptz NOT NULL DEFAULT now(), last_seen_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sessions_user_idx ON sessions(user_id) WHERE revoked_at IS NULL;
CREATE TABLE auth_tokens (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 kind text NOT NULL CHECK(kind IN ('verify_email','reset_password')), token_hash text NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL, used_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE rate_limits (
 bucket text PRIMARY KEY, attempts integer NOT NULL, expires_at timestamptz NOT NULL
);
CREATE TABLE stored_files (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), owner_id uuid NOT NULL REFERENCES users,
 purpose text NOT NULL CHECK(purpose IN ('photo','identity','license','vehicle')),
 storage_key text NOT NULL UNIQUE, content_type text NOT NULL, byte_size integer NOT NULL CHECK(byte_size BETWEEN 1 AND 5242880),
 sha256 text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz
);
ALTER TABLE users ADD CONSTRAINT users_photo_fk FOREIGN KEY(photo_file_id) REFERENCES stored_files;
CREATE TABLE driver_profiles (
 user_id uuid PRIMARY KEY REFERENCES users, status text NOT NULL DEFAULT 'not_requested' CHECK(status IN ('not_requested','pending','approved','rejected')),
 approved_at timestamptz, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE vehicles (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), owner_id uuid NOT NULL REFERENCES users,
 model text NOT NULL, color text NOT NULL, plate text NOT NULL CHECK(plate ~ '^[A-Z]{3}[0-9][A-Z0-9][0-9]{2}$'),
 capacity smallint NOT NULL CHECK(capacity BETWEEN 1 AND 8),
 status text NOT NULL CHECK(status IN ('pending','approved','rejected','retired')),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz,
 UNIQUE(id,owner_id)
);
CREATE UNIQUE INDEX vehicles_current_owner_idx ON vehicles(owner_id) WHERE status='approved' AND deleted_at IS NULL;
CREATE TABLE driver_applications (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 vehicle_id uuid NOT NULL REFERENCES vehicles, identity_file_id uuid NOT NULL REFERENCES stored_files,
 license_file_id uuid NOT NULL REFERENCES stored_files,
 status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','rejected','replaced')),
 decision text, reviewed_by uuid REFERENCES users, reviewed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX driver_application_pending_idx ON driver_applications(user_id) WHERE status='pending';
CREATE TABLE pricing_versions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), university_id uuid NOT NULL REFERENCES universities,
 fuel_cents integer NOT NULL CHECK(fuel_cents>0), efficiency numeric(8,3) NOT NULL CHECK(efficiency>0),
 wear_cents integer NOT NULL CHECK(wear_cents>=0), reference_passengers smallint NOT NULL CHECK(reference_passengers BETWEEN 1 AND 8),
 commission_bps integer NOT NULL CHECK(commission_bps BETWEEN 0 AND 9500), discount_bps integer NOT NULL CHECK(discount_bps BETWEEN 0 AND 9999),
 created_by uuid REFERENCES users, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pricing_current_idx ON pricing_versions(university_id,created_at DESC);
CREATE TABLE ride_routes (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), university_id uuid NOT NULL REFERENCES universities,
 driver_id uuid NOT NULL REFERENCES users, region text NOT NULL, meeting text NOT NULL,
 origin_address text, destination_address text,
 origin_lat numeric(10,7) CHECK(origin_lat BETWEEN -90 AND 90), origin_lng numeric(10,7) CHECK(origin_lng BETWEEN -180 AND 180),
 destination_lat numeric(10,7) CHECK(destination_lat BETWEEN -90 AND 90), destination_lng numeric(10,7) CHECK(destination_lng BETWEEN -180 AND 180),
 distance_m integer NOT NULL CHECK(distance_m BETWEEN 100 AND 2000000),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE routines (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), route_id uuid NOT NULL REFERENCES ride_routes,
 driver_id uuid NOT NULL REFERENCES users, university_id uuid NOT NULL REFERENCES universities,
 vehicle_id uuid NOT NULL REFERENCES vehicles, class_id uuid NOT NULL REFERENCES classes,
 weekdays smallint[] NOT NULL CHECK(cardinality(weekdays)>0 AND weekdays <@ ARRAY[0,1,2,3,4,5,6]::smallint[]),
 ida_time time, volta_time time, ida_cents integer CHECK(ida_cents>0), volta_cents integer CHECK(volta_cents>0),
 capacity smallint NOT NULL CHECK(capacity BETWEEN 1 AND 8), automatic boolean NOT NULL DEFAULT false,
 status text NOT NULL DEFAULT 'active' CHECK(status IN ('active','paused','ended')), notes text NOT NULL DEFAULT '',
 pricing_version_id uuid NOT NULL REFERENCES pricing_versions,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(ida_time IS NOT NULL OR volta_time IS NOT NULL)
);
CREATE INDEX routine_search_idx ON routines(university_id,status,class_id);
CREATE TABLE rides (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), university_id uuid NOT NULL REFERENCES universities,
 route_id uuid NOT NULL REFERENCES ride_routes, routine_id uuid REFERENCES routines,
 driver_id uuid NOT NULL REFERENCES users, vehicle_id uuid NOT NULL REFERENCES vehicles,
 direction text NOT NULL CHECK(direction IN ('ida','volta')), ride_date date NOT NULL, departure_time time NOT NULL,
 capacity smallint NOT NULL CHECK(capacity BETWEEN 1 AND 8), price_cents integer NOT NULL CHECK(price_cents>0),
 commission_bps integer NOT NULL CHECK(commission_bps BETWEEN 0 AND 9500),
 pricing_version_id uuid NOT NULL REFERENCES pricing_versions, toll_cents integer NOT NULL DEFAULT 0 CHECK(toll_cents>=0),
 automatic boolean NOT NULL DEFAULT false, notes text NOT NULL DEFAULT '',
 status text NOT NULL DEFAULT 'planned' CHECK(status IN ('planned','active','completed','cancelled','no_class')),
 proposed_time time, proposal_reason text, started_at timestamptz, completed_at timestamptz,
 version integer NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(routine_id,ride_date,direction)
);
CREATE INDEX ride_search_idx ON rides(university_id,ride_date,direction,departure_time) WHERE status='planned';
CREATE INDEX ride_driver_idx ON rides(driver_id,ride_date DESC);
CREATE INDEX route_region_idx ON ride_routes USING gin(lower(region) gin_trgm_ops);
CREATE TABLE calendar_exclusions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), university_id uuid NOT NULL REFERENCES universities,
 class_id uuid REFERENCES classes, exclusion_date date NOT NULL, title text NOT NULL,
 created_by uuid REFERENCES users, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX calendar_date_idx ON calendar_exclusions(university_id,exclusion_date);
CREATE TABLE subscriptions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), routine_id uuid NOT NULL REFERENCES routines,
 driver_id uuid NOT NULL REFERENCES users, passenger_id uuid NOT NULL REFERENCES users,
 class_id uuid NOT NULL REFERENCES classes, start_date date NOT NULL, end_date date NOT NULL CHECK(end_date>=start_date),
 weekdays smallint[] NOT NULL, directions text NOT NULL CHECK(directions IN ('ida','volta','ambos')),
 total_cents integer NOT NULL CHECK(total_cents>0), discount_bps integer NOT NULL,
 status text NOT NULL CHECK(status IN ('active','grace','cancelled_renewal','renew_conflict','interrupted','ended','expired','renewed')),
 renew boolean NOT NULL DEFAULT true, grace_until timestamptz,
 previous_subscription_id uuid REFERENCES subscriptions, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(driver_id<>passenger_id)
);
CREATE INDEX subscription_participants_idx ON subscriptions(passenger_id,status,end_date);
CREATE INDEX subscription_driver_idx ON subscriptions(driver_id,status);
CREATE TABLE ride_requests (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), passenger_id uuid NOT NULL REFERENCES users,
 driver_id uuid NOT NULL REFERENCES users, ride_id uuid REFERENCES rides, routine_id uuid REFERENCES routines,
 previous_subscription_id uuid REFERENCES subscriptions,
 type text NOT NULL CHECK(type IN ('avulsa','mensal')),
 status text NOT NULL CHECK(status IN ('pending','accepted','rejected','confirmed','cancelled','expired')),
 preview jsonb, reason text, expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(passenger_id<>driver_id), CHECK((type='avulsa' AND ride_id IS NOT NULL) OR (type='mensal' AND routine_id IS NOT NULL))
);
CREATE UNIQUE INDEX request_active_ride_idx ON ride_requests(passenger_id,ride_id) WHERE status IN ('pending','accepted','confirmed');
CREATE INDEX request_driver_idx ON ride_requests(driver_id,status,created_at DESC);
CREATE TABLE request_lines (
 request_id uuid NOT NULL REFERENCES ride_requests ON DELETE CASCADE, ride_id uuid NOT NULL REFERENCES rides,
 cents integer NOT NULL CHECK(cents>0), commission_bps integer NOT NULL,
 PRIMARY KEY(request_id,ride_id)
);
CREATE TABLE payments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), request_id uuid NOT NULL UNIQUE REFERENCES ride_requests,
 passenger_id uuid NOT NULL REFERENCES users, amount_cents integer NOT NULL CHECK(amount_cents>0),
 credit_cents integer NOT NULL DEFAULT 0 CHECK(credit_cents>=0), external_cents integer NOT NULL DEFAULT 0 CHECK(external_cents>=0),
 provider text NOT NULL, provider_reference text UNIQUE,
 status text NOT NULL CHECK(status IN ('awaiting','pending','approved','failed','expired','refund_pending','refunded')),
 method text NOT NULL DEFAULT 'pix' CHECK(method IN ('pix','card')),
 expires_at timestamptz NOT NULL, paid_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(credit_cents+external_cents<=amount_cents)
);
CREATE TABLE ride_reservations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), request_id uuid NOT NULL REFERENCES ride_requests,
 ride_id uuid NOT NULL REFERENCES rides, passenger_id uuid NOT NULL REFERENCES users,
 driver_id uuid NOT NULL REFERENCES users, subscription_id uuid REFERENCES subscriptions,
 payment_id uuid NOT NULL REFERENCES payments,
 status text NOT NULL CHECK(status IN ('confirmed','boarded','awaiting_confirmation','completed','cancelled','under_review')),
 pin_ciphertext text NOT NULL, pin_hash text NOT NULL,
 price_cents integer NOT NULL CHECK(price_cents>0), boarded_at timestamptz,
 final_response text NOT NULL DEFAULT 'pending' CHECK(final_response IN ('pending','confirmed','contested','timeout')),
 availability text CHECK(availability IN ('fulfilled','fulfilled_absence','no_class')),
 absence text CHECK(absence IN ('notified','notified_absence','passenger_absent')),
 absence_reason text, confirmation_deadline timestamptz, proposed_time time,
 time_response text CHECK(time_response IN ('pending','accepted','declined','withdrawn')),
 cancel_reason text, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(request_id,ride_id), CHECK(passenger_id<>driver_id)
);
CREATE UNIQUE INDEX reservation_active_idx ON ride_reservations(ride_id,passenger_id) WHERE status IN ('confirmed','boarded','awaiting_confirmation');
CREATE INDEX reservation_ride_idx ON ride_reservations(ride_id,status);
CREATE INDEX reservation_passenger_idx ON ride_reservations(passenger_id,created_at DESC);
CREATE TABLE subscription_schedule (
 subscription_id uuid NOT NULL REFERENCES subscriptions, ride_id uuid NOT NULL REFERENCES rides,
 reservation_id uuid UNIQUE REFERENCES ride_reservations, cents integer NOT NULL CHECK(cents>0),
 held boolean NOT NULL DEFAULT false, PRIMARY KEY(subscription_id,ride_id)
);
CREATE INDEX schedule_holds_idx ON subscription_schedule(ride_id) WHERE held;
CREATE TABLE parcels (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), reservation_id uuid NOT NULL UNIQUE REFERENCES ride_reservations,
 payment_id uuid NOT NULL REFERENCES payments, passenger_id uuid NOT NULL REFERENCES users, driver_id uuid NOT NULL REFERENCES users,
 cents integer NOT NULL CHECK(cents>0), commission_bps integer NOT NULL,
 status text NOT NULL CHECK(status IN ('future','awaiting','blocked','released','credited','retained')),
 commission_cents integer NOT NULL DEFAULT 0 CHECK(commission_cents>=0), driver_cents integer NOT NULL DEFAULT 0 CHECK(driver_cents>=0),
 reason text, settled_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(status<>'released' OR commission_cents+driver_cents=cents)
);
CREATE INDEX parcel_driver_idx ON parcels(driver_id,status);
CREATE INDEX parcel_passenger_idx ON parcels(passenger_id,status);
CREATE TABLE ledger_accounts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), owner_id uuid REFERENCES users,
 kind text NOT NULL CHECK(kind IN ('provider_clearing','future','blocked','driver_available','passenger_credit','commission','retained','payout_pending','refund_pending','paid_out','refunded')),
 currency char(3) NOT NULL DEFAULT 'BRL', UNIQUE NULLS NOT DISTINCT(owner_id,kind,currency)
);
CREATE TABLE ledger_transactions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), event_key text NOT NULL UNIQUE, kind text NOT NULL,
 payment_id uuid REFERENCES payments, parcel_id uuid REFERENCES parcels, reason text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ledger_entries (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), transaction_id uuid NOT NULL REFERENCES ledger_transactions,
 account_id uuid NOT NULL REFERENCES ledger_accounts, cents bigint NOT NULL CHECK(cents<>0),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ledger_balance_idx ON ledger_entries(account_id);
CREATE TABLE transfers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 type text NOT NULL CHECK(type IN ('withdraw','refund')), cents integer NOT NULL CHECK(cents>0),
 status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','failed')),
 cadence text CHECK(cadence IN ('daily','monthly')), provider_reference text UNIQUE, decision text,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE occurrences (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), reporter_id uuid NOT NULL REFERENCES users,
 other_id uuid REFERENCES users, reservation_id uuid REFERENCES ride_reservations,
 kind text NOT NULL CHECK(kind IN ('safety','dispute','absence_review')),
 reason text NOT NULL CHECK(length(reason) BETWEEN 3 AND 2000), severe boolean NOT NULL DEFAULT false,
 status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','financial_pending','resolved')),
 outcome text CHECK(outcome IN ('released','credited','retained','no_financial_change')),
 decision text, reviewed_by uuid REFERENCES users, reviewed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE occurrence_parcels (
 occurrence_id uuid NOT NULL REFERENCES occurrences, parcel_id uuid NOT NULL REFERENCES parcels,
 PRIMARY KEY(occurrence_id,parcel_id)
);
CREATE INDEX occurrence_review_idx ON occurrences(status,created_at);
CREATE TABLE user_blocks (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), by_id uuid NOT NULL REFERENCES users,
 other_id uuid NOT NULL REFERENCES users, active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(by_id,other_id), CHECK(by_id<>other_id)
);
CREATE INDEX block_reverse_idx ON user_blocks(other_id,by_id) WHERE active;
CREATE TABLE waitlist (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 routine_id uuid NOT NULL REFERENCES routines, priority boolean NOT NULL DEFAULT false,
 status text NOT NULL DEFAULT 'waiting' CHECK(status IN ('waiting','cancelled')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX waitlist_active_idx ON waitlist(user_id,routine_id) WHERE status='waiting';
CREATE TABLE notifications (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users,
 title text NOT NULL, body text NOT NULL, route text NOT NULL, ref_id uuid,
 read_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notices_user_idx ON notifications(user_id,created_at DESC);
CREATE TABLE ratings (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), reservation_id uuid NOT NULL REFERENCES ride_reservations,
 by_id uuid NOT NULL REFERENCES users, other_id uuid NOT NULL REFERENCES users,
 score smallint NOT NULL CHECK(score BETWEEN 1 AND 5), comment text CHECK(length(comment)<=500),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(reservation_id,by_id), CHECK(by_id<>other_id)
);
CREATE TABLE audit_logs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), actor_id uuid REFERENCES users,
 action text NOT NULL, resource_type text NOT NULL, resource_id uuid, request_id text,
 metadata jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_resource_idx ON audit_logs(resource_type,resource_id,created_at DESC);
CREATE TABLE idempotency_keys (
 actor_id uuid NOT NULL REFERENCES users, key text NOT NULL, scope text NOT NULL,
 body_hash text NOT NULL, response jsonb, expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(actor_id,key,scope)
);
CREATE TABLE webhook_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), provider text NOT NULL, event_id text NOT NULL,
 payload_hash text NOT NULL, payload jsonb NOT NULL, status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','processed','failed')),
 received_at timestamptz NOT NULL DEFAULT now(), processed_at timestamptz,
 UNIQUE(provider,event_id)
);
CREATE TABLE outbox_jobs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), type text NOT NULL, event_key text NOT NULL UNIQUE,
 payload jsonb NOT NULL, status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','processing','done','dead')),
 attempts integer NOT NULL DEFAULT 0, available_at timestamptz NOT NULL DEFAULT now(), leased_until timestamptz,
 last_error_code text, created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz
);
CREATE INDEX outbox_pending_idx ON outbox_jobs(available_at) WHERE status IN ('pending','processing');
