#!/usr/bin/env bash
# Runs INSIDE the dbaas-postgresql VM as root (via provision.sh). Reads JSON
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

sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${db_user}') THEN
    CREATE ROLE "${db_user}" LOGIN PASSWORD '${db_password}';
  END IF;
END
\$\$;
SELECT 'CREATE DATABASE "${db_name}" OWNER "${db_user}"'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${db_name}')\gexec
GRANT ALL PRIVILEGES ON DATABASE "${db_name}" TO "${db_user}";
SQL

# listen_addresses / pg_hba.conf must already allow non-local connections in
# the base template image — CloudStack Security Groups / Network ACLs are
# what actually gate who can reach the port from outside the VM.
echo "ok"
