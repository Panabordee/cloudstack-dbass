#!/usr/bin/env bash
# Runs INSIDE the dbaas-postgresql VM as root (via firstboot.sh, which pipes the config-drive request into stdin). Reads JSON on
# stdin: {"db_user": "...", "db_password": "..."}
set -euo pipefail

payload=$(cat)
db_user=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_user"])')
db_password=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_password"])')

if [[ ! "$db_user" =~ ^[A-Za-z][A-Za-z0-9_]{0,31}$ ]]; then
  echo "invalid identifier: $db_user" >&2
  exit 1
fi

ROLE_EXISTS=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname = '${db_user}'")
if [[ "$(echo "$ROLE_EXISTS" | tr -d '[:space:]')" != "1" ]]; then
  echo "role does not exist: ${db_user}" >&2
  exit 1
fi

sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
ALTER ROLE "${db_user}" WITH LOGIN PASSWORD '${db_password}';
SQL

# PUBLIC has no CONNECT on a tenant database, so verification has to target one
# this role actually owns -- 'postgres' would be refused for the same reason a
# cross-tenant connection is.
VERIFY_DB=$(sudo -u postgres psql -tAc \
  "SELECT datname FROM pg_database WHERE pg_get_userbyid(datdba) = '${db_user}' LIMIT 1" | tr -d '[:space:]')
if [[ -z "$VERIFY_DB" ]]; then
  echo "role ${db_user} owns no database to verify the new password against" >&2
  exit 1
fi

VERIFY_OUTPUT=$(PGPASSWORD="${db_password}" psql -h 127.0.0.1 -U "${db_user}" -d "${VERIFY_DB}" -tAc "SELECT 1" 2>&1) \
  || { echo "post-reset login verification failed: ${VERIFY_OUTPUT}" >&2; exit 1; }

if [[ "$(echo "$VERIFY_OUTPUT" | tr -d '[:space:]')" != "1" ]]; then
  echo "post-reset login verification did not return expected result: ${VERIFY_OUTPUT}" >&2
  exit 1
fi

echo "ok"
