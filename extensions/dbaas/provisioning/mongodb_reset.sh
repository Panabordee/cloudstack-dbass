#!/usr/bin/env bash
# Runs INSIDE the dbaas-mongodb VM as root (via provision.sh). Reads JSON on
# stdin: {"db_user": "...", "db_password": "..."}
set -euo pipefail

payload=$(cat)
db_user=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_user"])')
db_password=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_password"])')

if [[ ! "$db_user" =~ ^[A-Za-z][A-Za-z0-9_]{0,31}$ ]]; then
  echo "invalid identifier: $db_user" >&2
  exit 1
fi

# The rotation service rewrites this file after changing the password inside
# mongod, and there is a window where mongod already has the new password while
# the file still holds the old one. Wait for the marker it writes on success:
# no marker means the rotation never finished, and the file would still carry
# the password every image built from this template ships with.
MARKER=/var/lib/dbaas/admin-password-rotated
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

ADMIN_URI="mongodb://${admin_user}:${admin_password}@127.0.0.1:27017/admin"

# A mongo user belongs to the database it was created in, and the caller only
# gives us the name, so look up which one that is instead of guessing.
AUTH_DB=$(mongosh --quiet "$ADMIN_URI" --eval "
  const u = db.getSiblingDB('admin').system.users.findOne({ user: '${db_user}' });
  print(u ? u.db : '');
" 2>&1) || { echo "mongodb user lookup failed: $AUTH_DB" >&2; exit 1; }
AUTH_DB=$(echo "$AUTH_DB" | tr -d '[:space:]')

if [[ -z "$AUTH_DB" ]]; then
  echo "user does not exist: ${db_user}" >&2
  exit 1
fi

RESET_OUTPUT=$(mongosh --quiet "$ADMIN_URI" --eval "
  db.getSiblingDB('${AUTH_DB}').changeUserPassword('${db_user}', '${db_password}');
" 2>&1) || { echo "mongodb changeUserPassword failed: $RESET_OUTPUT" >&2; exit 1; }

if echo "$RESET_OUTPUT" | grep -Eqi 'MongoServerError|Uncaught|error:'; then
  echo "mongodb changeUserPassword reported an error: $RESET_OUTPUT" >&2
  exit 1
fi

VERIFY_OUTPUT=$(mongosh --quiet "mongodb://${db_user}:${db_password}@127.0.0.1:27017/${AUTH_DB}" --eval "db.runCommand({ping:1})" 2>&1) \
  || { echo "post-reset login verification failed: $VERIFY_OUTPUT" >&2; exit 1; }

if ! echo "$VERIFY_OUTPUT" | grep -Eq '"ok"[[:space:]]*:[[:space:]]*1|ok:[[:space:]]*1'; then
  echo "post-reset login verification did not confirm success: $VERIFY_OUTPUT" >&2
  exit 1
fi

echo "ok"
