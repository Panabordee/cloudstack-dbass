#!/usr/bin/env bash
# Lives at /opt/dbaas/provision.sh INSIDE each dbaas-* template image.
# Invoked via SSH forced-command (authorized_keys: command="/opt/dbaas/provision.sh"),
# which means sshd runs THIS exact string with no arguments and instead sets
# $SSH_ORIGINAL_COMMAND to whatever the client actually asked to run.
# Read the target script name from there, not from $1.
set -euo pipefail

SCRIPT_NAME="${1:-}"
if [[ -z "$SCRIPT_NAME" && -n "${SSH_ORIGINAL_COMMAND:-}" ]]; then
  SCRIPT_NAME="$(awk '{print $NF}' <<< "$SSH_ORIGINAL_COMMAND")"
fi

ALLOWED="mysql.sh postgresql.sh mongodb.sh mariadb.sh mysql_reset.sh postgresql_reset.sh mongodb_reset.sh mariadb_reset.sh"
DBAAS_DIR="/opt/dbaas"

if [[ -z "$SCRIPT_NAME" ]]; then
  echo "missing script name (checked \$1 and \$SSH_ORIGINAL_COMMAND)" >&2
  exit 1
fi

ok=false
for name in $ALLOWED; do
  if [[ "$SCRIPT_NAME" == "$name" ]]; then
    ok=true
    break
  fi
done

if [[ "$ok" != "true" ]]; then
  echo "refusing to run unrecognized script: $SCRIPT_NAME" >&2
  exit 1
fi

exec "$DBAAS_DIR/$SCRIPT_NAME"
