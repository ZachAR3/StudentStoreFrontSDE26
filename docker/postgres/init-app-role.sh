#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${POSTGRES_APP_USER:-}" || -z "${POSTGRES_APP_PASSWORD:-}" ||
      -z "${POSTGRES_MIGRATION_USER:-}" || -z "${POSTGRES_MIGRATION_PASSWORD:-}" ]]; then
  echo "POSTGRES_APP_* and POSTGRES_MIGRATION_* variables are required" >&2
  exit 1
fi

if [[ "$POSTGRES_APP_USER" == "$POSTGRES_USER" ||
      "$POSTGRES_APP_USER" == "$POSTGRES_MIGRATION_USER" ||
      "$POSTGRES_MIGRATION_USER" == "$POSTGRES_USER" ]]; then
  echo "Bootstrap, runtime, and migration roles must be distinct" >&2
  exit 1
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=app_user="$POSTGRES_APP_USER" \
  --set=app_password="$POSTGRES_APP_PASSWORD" \
  --set=migration_user="$POSTGRES_MIGRATION_USER" \
  --set=migration_password="$POSTGRES_MIGRATION_PASSWORD" \
  --set=database_name="$POSTGRES_DB" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user')\gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'migration_user', :'migration_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migration_user')\gexec

GRANT CONNECT ON DATABASE :"database_name" TO :"app_user";
GRANT CONNECT, TEMPORARY ON DATABASE :"database_name" TO :"migration_user";
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"app_user";
GRANT USAGE, CREATE ON SCHEMA public TO :"migration_user";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_user" IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_user" IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO :"app_user";
SQL
