#!/usr/bin/env bash
# Runs INSIDE the dbaas-mongodb VM as root (via provision.sh). Reads JSON on
# stdin: {"db_name": "...", "db_user": "...", "db_password": "..."}
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

mongosh --quiet <<JS
db = db.getSiblingDB('${db_name}');
db.createUser({
  user: '${db_user}',
  pwd: '${db_password}',
  roles: [{ role: 'readWrite', db: '${db_name}' }]
});
JS

# Template image should already have mongod bind to the private interface
# with authentication enabled (--auth); this script only adds the per-tenant
# user/db, it does not toggle server-wide auth settings.
echo "ok"
