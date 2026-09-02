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

DBAAS_DIR="/opt/dbaas"

if [[ -z "$SCRIPT_NAME" ]]; then
  echo "missing script name (checked \$1 and \$SSH_ORIGINAL_COMMAND)" >&2
  exit 1
fi

# No fixed per-engine allowlist to keep in sync with the extension's own
# config — anything named "<word>.sh" or "<word>_reset.sh" that actually
# exists in /opt/dbaas/ is fair game. That directory is root:root 755 and
# only ever populated at template-build time, so dbaas-provisioner (the only
# account this forced-command runs under) can never plant a file there —
# the allowlist IS the filesystem. provision.sh itself is excluded so a
# client can't ask to re-invoke the entrypoint.
if [[ ! "$SCRIPT_NAME" =~ ^[a-z0-9]+(_reset)?\.sh$ ]] || [[ "$SCRIPT_NAME" == "provision.sh" ]]; then
  echo "refusing to run unrecognized script: $SCRIPT_NAME" >&2
  exit 1
fi

TARGET="$DBAAS_DIR/$SCRIPT_NAME"
if [[ ! -f "$TARGET" || ! -x "$TARGET" ]]; then
  echo "refusing to run unrecognized script: $SCRIPT_NAME" >&2
  exit 1
fi

exec "$TARGET"
