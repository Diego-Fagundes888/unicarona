#!/bin/sh
set -eu
# Password is quoted by psql as a SQL literal, never interpolated into SQL by the shell.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 --set=app_password="$PG_APP_PASSWORD" <<'SQL'
CREATE ROLE unicarona LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER DATABASE unicarona OWNER TO unicarona;
SQL
