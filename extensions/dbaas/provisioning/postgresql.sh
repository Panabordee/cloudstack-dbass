#!/usr/bin/env bash
# Runs INSIDE the dbaas-postgresql VM as root (via firstboot.sh, which pipes the config-drive request into stdin). Reads JSON
# on stdin: {"db_name": "...", "db_user": "...", "db_password": "..."}
set -euo pipefail

payload=$(cat)
db_name=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_name"])')
db_user=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_user"])')
db_password=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_password"])')

for val in "$db_name" "$db_user"; do
  if [[ ! "$val" =~ ^[A-Za-z][A-Za-z0-9_]{0,31}$ ]]; then
    echo "invalid identifier: $val" >&2
    exit 1
  fi
done

# Refuse outright if the role already exists. The old script's
# "IF NOT EXISTS ... CREATE ROLE" skipped creation on a repeat request but
# still reported ok with a freshly generated password that was never applied
# — this must fail loudly instead, same as the MySQL/MongoDB behavior.
ROLE_EXISTS=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname = '${db_user}'")
if [[ "$(echo "$ROLE_EXISTS" | tr -d '[:space:]')" == "1" ]]; then
  echo "role already exists: ${db_user}" >&2
  exit 1
fi

sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
CREATE ROLE "${db_user}" LOGIN PASSWORD '${db_password}';
SELECT 'CREATE DATABASE "${db_name}" OWNER "${db_user}"'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${db_name}')\gexec
GRANT ALL PRIVILEGES ON DATABASE "${db_name}" TO "${db_user}";
REVOKE CONNECT ON DATABASE "${db_name}" FROM PUBLIC;
SQL

# Don't trust exit code alone — verify the new credential actually authenticates,
# same discipline as mongodb.sh after its equivalent bug.
VERIFY_OUTPUT=$(PGPASSWORD="${db_password}" psql -h 127.0.0.1 -U "${db_user}" -d "${db_name}" -tAc "SELECT 1" 2>&1) \
  || { echo "post-create login verification failed: ${VERIFY_OUTPUT}" >&2; exit 1; }

if [[ "$(echo "$VERIFY_OUTPUT" | tr -d '[:space:]')" != "1" ]]; then
  echo "post-create login verification did not return expected result: ${VERIFY_OUTPUT}" >&2
  exit 1
fi

echo "ok"
