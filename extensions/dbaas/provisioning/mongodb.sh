#!/usr/bin/env bash
# Runs INSIDE the dbaas-mongodb VM as root (via firstboot.sh, which pipes the config-drive request into stdin). Reads JSON on
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

# The rotation service rewrites this file after changing the password inside
# mongod, and there is a window where mongod already has the new password while
# the file still holds the old one. Wait for the marker it writes on success:
# no marker means the rotation never finished, and the file would still carry
# the password every image built from this template ships with.
MARKER="${DBAAS_STATE_DIR:-/var/lib/dbaas}/admin-password-rotated"
for _ in $(seq 1 60); do
  [[ -e "$MARKER" ]] && break
  sleep 2
done
if [[ ! -e "$MARKER" ]]; then
  echo "admin password rotation has not completed on this instance" >&2
  exit 1
fi

DBAAS_DIR="${DBAAS_DIR:-/opt/dbaas}"
ADMIN_CRED_FILE="${DBAAS_DIR}/admin_credentials.json"
if [[ ! -r "$ADMIN_CRED_FILE" ]]; then
  echo "admin credentials file missing or unreadable: $ADMIN_CRED_FILE" >&2
  exit 1
fi
admin_user=$(python3 -c "import json;print(json.load(open('$ADMIN_CRED_FILE'))['user'])")
admin_password=$(python3 -c "import json;print(json.load(open('$ADMIN_CRED_FILE'))['password'])")

# Create the tenant DB + user, authenticated as the admin account (not localhost exception).
CREATE_OUTPUT=$(mongosh --quiet "mongodb://${admin_user}:${admin_password}@127.0.0.1:27017/admin" --eval "
  const target = db.getSiblingDB('${db_name}');
  target.createUser({
    user: '${db_user}',
    pwd: '${db_password}',
    roles: [{ role: 'readWrite', db: '${db_name}' }]
  });
" 2>&1) || { echo "mongodb createUser failed: $CREATE_OUTPUT" >&2; exit 1; }

if echo "$CREATE_OUTPUT" | grep -Eqi 'MongoServerError|Uncaught|error:'; then
  echo "mongodb createUser reported an error: $CREATE_OUTPUT" >&2
  exit 1
fi

# Don't trust exit code alone — verify the new credential actually works.
VERIFY_OUTPUT=$(mongosh --quiet "mongodb://${db_user}:${db_password}@127.0.0.1:27017/${db_name}" --eval "db.runCommand({ping:1})" 2>&1) \
  || { echo "post-create login verification failed: $VERIFY_OUTPUT" >&2; exit 1; }

if ! echo "$VERIFY_OUTPUT" | grep -Eq '"ok"[[:space:]]*:[[:space:]]*1|ok:[[:space:]]*1'; then
  echo "post-create login verification did not confirm success: $VERIFY_OUTPUT" >&2
  exit 1
fi

echo "ok"
