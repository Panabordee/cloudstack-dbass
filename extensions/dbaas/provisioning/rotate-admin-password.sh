#!/usr/bin/env bash
# First-boot rotation of the MongoDB `dbaas_admin` password.
#
# The dbaas-mongodb template ships with one baked-in admin password. Without
# this, every VM cloned from that template would share it, so a tenant with
# root on their own VM could reach any other tenant's MongoDB. This runs once
# per VM (guarded by a marker file) and replaces it with a fresh random one.
set -euo pipefail

CRED_FILE="/opt/dbaas/admin_credentials.json"
NEW_FILE="/opt/dbaas/.admin_credentials.json.new"
MARKER_DIR="/var/lib/dbaas"
MARKER="${MARKER_DIR}/admin-password-rotated"

if [[ -e "$MARKER" ]]; then
  echo "already rotated on a previous boot; nothing to do"
  exit 0
fi

if [[ ! -r "$CRED_FILE" ]]; then
  echo "admin credentials file missing: $CRED_FILE" >&2
  exit 1
fi

admin_user=$(python3 -c "import json;print(json.load(open('$CRED_FILE'))['user'])")
old_password=$(python3 -c "import json;print(json.load(open('$CRED_FILE'))['password'])")

# mongod is started in parallel with us; wait until it actually answers.
ready=false
for _ in $(seq 1 60); do
  if mongosh --quiet "mongodb://127.0.0.1:27017/admin" --eval 'db.runCommand({ping:1})' >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != "true" ]]; then
  echo "mongod did not become reachable within 120s" >&2
  exit 1
fi

# `tr </dev/urandom | head -c 32` makes tr die on EPIPE, which `set -o pipefail`
# turns into a script failure — use the same generator the extension itself uses.
new_password=$(python3 -c "import secrets, string; print(''.join(secrets.choice(string.ascii_letters + string.digits) for _ in range(32)))")
if [[ ${#new_password} -ne 32 ]]; then
  echo "failed to generate a 32-character password" >&2
  exit 1
fi

# Stage the new credentials file BEFORE touching MongoDB, so a crash between
# the two steps can never leave us with a password nobody has on disk.
umask 077
python3 - "$admin_user" "$new_password" "$NEW_FILE" <<'PY'
import json, os, sys
user, password, path = sys.argv[1:4]
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f:
    json.dump({"user": user, "password": password}, f)
os.chown(path, 0, 0)
PY

cleanup_staged() { rm -f "$NEW_FILE"; }
trap cleanup_staged ERR

mongosh --quiet "mongodb://${admin_user}:${old_password}@127.0.0.1:27017/admin" \
  --eval "db.getSiblingDB('admin').changeUserPassword('${admin_user}', '${new_password}')" >/dev/null

# Don't trust the command's exit code alone — prove the new password works.
if ! mongosh --quiet "mongodb://${admin_user}:${new_password}@127.0.0.1:27017/admin" \
     --eval 'db.runCommand({ping:1})' >/dev/null 2>&1; then
  echo "rotation did not take effect; keeping the old credentials file" >&2
  cleanup_staged
  exit 1
fi

trap - ERR
mv -f "$NEW_FILE" "$CRED_FILE"
chown root:root "$CRED_FILE"
chmod 600 "$CRED_FILE"

mkdir -p "$MARKER_DIR"
date -Iseconds > "$MARKER"
chmod 600 "$MARKER"

echo "dbaas_admin password rotated for this VM"
