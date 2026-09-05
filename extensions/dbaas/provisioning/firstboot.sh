#!/usr/bin/env bash
# Lives at /opt/dbaas/firstboot.sh INSIDE each config-drive dbaas-* template.
#
# cloud-init runs this once, on first boot, after writing the provisioning
# request the management server put on the config drive to
# /var/lib/dbaas/request.json. The request carries db_name, db_user and
# db_password -- the same JSON document the SSH path sends on stdin, so the
# per-engine scripts are reused unchanged and there is one implementation of
# the engine SQL, not two.
#
# Nothing here talks to the management server: that is the entire point of the
# config-drive path. The result is recorded locally, and (once the report-back
# endpoint exists) reported from a separate unit that can retry.
set -euo pipefail

DBAAS_DIR="${DBAAS_DIR:-/opt/dbaas}"
STATE_DIR="${DBAAS_STATE_DIR:-/var/lib/dbaas}"
REQUEST_FILE="${STATE_DIR}/request.json"
RESULT_FILE="${STATE_DIR}/result.json"
DONE_MARKER="${STATE_DIR}/provisioned"

log() { echo "[dbaas-firstboot] $*" >&2; }

write_result() {
    # status message -- recorded for the reporter and for anyone reading the
    # instance's console. Never contains the password.
    printf '{"status": "%s", "message": %s}\n' "$1" "$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$2")" \
        > "$RESULT_FILE"
    chmod 0600 "$RESULT_FILE"
}

if [[ -e "$DONE_MARKER" ]]; then
    log "already provisioned, nothing to do"
    exit 0
fi

if [[ ! -f "$REQUEST_FILE" ]]; then
    # No request on the config drive: this instance was deployed without a
    # database, which is a normal thing to do. Not an error.
    log "no provisioning request at ${REQUEST_FILE}, nothing to do"
    exit 0
fi

# Which engine this image is comes from the image itself, written at build
# time -- the request deliberately does not carry it, so a template can never
# be asked to provision an engine it does not have.
ENGINE_FILE="${DBAAS_DIR}/engine"
if [[ ! -f "$ENGINE_FILE" ]]; then
    log "no engine marker at ${ENGINE_FILE}: this image was not built for config-drive provisioning"
    write_result failed "image has no ${ENGINE_FILE}; rebuild it with the engine marker"
    exit 1
fi
ENGINE_SCRIPT="${DBAAS_DIR}/$(tr -d '[:space:]' < "$ENGINE_FILE")"
if [[ ! -x "$ENGINE_SCRIPT" ]]; then
    log "engine script ${ENGINE_SCRIPT} is missing or not executable"
    write_result failed "engine script ${ENGINE_SCRIPT} missing or not executable"
    exit 1
fi

log "provisioning with ${ENGINE_SCRIPT}"
if OUTPUT=$("$ENGINE_SCRIPT" < "$REQUEST_FILE" 2>&1); then
    # The request holds the password in cleartext and has served its purpose:
    # remove it so it does not sit on the instance's disk afterwards. The
    # config drive itself is read-only and detaches with the instance.
    rm -f "$REQUEST_FILE"
    : > "$DONE_MARKER"
    chmod 0600 "$DONE_MARKER"
    write_result confirmed "database provisioned"
    log "provisioned successfully"
    exit 0
fi

# Keep the request on failure: an operator can fix the engine and re-run this
# script by hand without the management server having to reach the instance.
log "provisioning failed: ${OUTPUT}"
write_result failed "${OUTPUT}"
exit 1
