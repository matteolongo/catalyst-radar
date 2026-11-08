#!/bin/sh
psql --no-psqlrc --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_password CATALYST_DB_PASSWORD
CREATE EXTENSION vector;
CREATE EXTENSION pgcrypto;
CREATE ROLE catalyst LOGIN PASSWORD :'app_password';
ALTER DATABASE catalyst_radar OWNER TO catalyst;
SQL
