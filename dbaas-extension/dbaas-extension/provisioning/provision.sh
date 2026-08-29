#!/usr/bin/env bash
# Lives at /opt/dbaas/provision.sh INSIDE each dbaas-* template image.
# This is the ONLY thing the forced-command SSH key is allowed to run.
# Usage: provision.sh <mysql.sh|postgresql.sh|mongodb.sh>   (JSON on stdin)
set -euo pipefail

SCRIPT_NAME="${1:-}"
ALLOWED="mysql.sh postgresql.sh mongodb.sh"
DBAAS_DIR="/opt/dbaas"

if [[ -z "$SCRIPT_NAME" ]]; then
  echo "missing script name argument" >&2
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
