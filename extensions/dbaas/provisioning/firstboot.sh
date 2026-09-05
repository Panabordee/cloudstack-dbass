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
# Provisioning itself never talks to the management server -- that is the
# entire point of the config-drive path. Reporting the outcome does, over the
# instance's normal network path, using the one-time token the management
# server minted for this instance; it is best-effort here (a handful of
# retries, then give up) and there is no built-in way to retry later yet --
# that lands with the in-VM agent (Phase D). Until then a report that never
# arrives leaves the credential 'pending', which is visible and recoverable
# manually, not silently wrong.
set -euo pipefail

DBAAS_DIR="${DBAAS_DIR:-/opt/dbaas}"
STATE_DIR="${DBAAS_STATE_DIR:-/var/lib/dbaas}"
REQUEST_FILE="${STATE_DIR}/request.json"
RESULT_FILE="${STATE_DIR}/result.json"
DONE_MARKER="${STATE_DIR}/provisioned"

log() { echo "[dbaas-firstboot] $*" >&2; }

write_result() {
    # status message -- recorded for anyone reading the instance's console.
    # Never contains the password.
    printf '{"status": "%s", "message": %s}\n' "$1" "$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$2")" \
        > "$RESULT_FILE"
    chmod 0600 "$RESULT_FILE"
}

# report_url/report_token/vm_id are only present when the management server
# has dbaas.report.api.url configured (DbaasManagerImpl#buildUserData); a
# request without them means reporting is not set up, not that this call
# failed, so callers must not treat a missing field as an error.
report_field() {
    python3 -c '
import json, sys
try:
    with open(sys.argv[2]) as f:
        print(json.load(f).get(sys.argv[1], ""))
except Exception:
    print("")
' "$1" "$2"
}

# Posts the outcome using the token from the request; every rejection reason
# on the server side (wrong token, expired, already used) looks identical from
# here, so this only ever logs whether the call itself succeeded or not.
report_result() {
    local status="$1" message="$2" request_file="$3"
    # The server stores status_message in a varchar(1024) column -- a longer
    # message would fail the whole UPDATE and the report would be lost, so
    # truncate here (byte-wise; good enough for a human-readable tail). The
    # full output stays in result.json on the instance.
    message="${message:0:1000}"
    local report_url report_token vm_id
    report_url=$(report_field report_url "$request_file")
    report_token=$(report_field report_token "$request_file")
    vm_id=$(report_field vm_id "$request_file")
    if [[ -z "$report_url" || -z "$report_token" || -z "$vm_id" ]]; then
        log "no report_url in the provisioning request -- reporting is not configured, staying local-only"
        return 0
    fi
    local attempt
    for attempt in 1 2 3 4 5; do
        if curl -fsS -m 10 -X POST "$report_url" \
            --data-urlencode "command=reportDbaasProvisioningResult" \
            --data-urlencode "response=json" \
            --data-urlencode "vmid=${vm_id}" \
            --data-urlencode "token=${report_token}" \
            --data-urlencode "status=${status}" \
            --data-urlencode "message=${message}" >/dev/null 2>&1; then
            log "provisioning result reported: ${status}"
            return 0
        fi
        log "report attempt ${attempt}/5 failed, retrying"
        sleep $((attempt * 3))
    done
    log "could not report provisioning result after 5 attempts -- credential stays 'pending' until reported"
    return 1
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

# cloud-init's runcmd fires in the final boot stage, which races the engine
# service starting -- and runcmd never retries, so running the engine script
# against a not-yet-listening socket would fail provisioning permanently
# (a reboot does not re-run it). Wait for the engine instead: up to
# ENGINE_WAIT seconds, polling the per-engine readiness probe.
ENGINE_WAIT="${DBAAS_ENGINE_WAIT:-120}"

engine_ready() {
    # The marker holds the script name (mysql.sh), the probe keys off the
    # engine (mysql) -- strip exactly the .sh suffix, nothing else.
    local marker
    marker="$(tr -d '[:space:]' < "$ENGINE_FILE")"
    case "${marker%.sh}" in
        mysql|mariadb)
            mysqladmin --protocol=socket -uroot ping >/dev/null 2>&1
            ;;
        postgresql)
            pg_isready -q >/dev/null 2>&1
            ;;
        mongodb)
            mongosh --quiet --eval "db.adminCommand({ ping: 1 })" >/dev/null 2>&1 \
                || mongo --quiet --eval "db.adminCommand({ ping: 1 })" >/dev/null 2>&1
            ;;
        *)
            # Unknown engine marker: no probe known -- proceed and let the
            # engine script report its own failure.
            return 0
            ;;
    esac
}

waited=0
until engine_ready; do
    if [[ $waited -ge $ENGINE_WAIT ]]; then
        log "engine not ready after ${ENGINE_WAIT}s -- failing instead of running the engine script against a dead socket"
        write_result failed "database engine did not become ready within ${ENGINE_WAIT}s"
        report_result failed "database engine did not become ready within ${ENGINE_WAIT}s" "$REQUEST_FILE" || true
        exit 1
    fi
    sleep 2
    waited=$((waited + 2))
done
log "engine ready (waited ${waited}s)"

log "provisioning with ${ENGINE_SCRIPT}"
if OUTPUT=$("$ENGINE_SCRIPT" < "$REQUEST_FILE" 2>&1); then
    write_result confirmed "database provisioned"
    report_result confirmed "database provisioned" "$REQUEST_FILE" || true
    # The request holds the password in cleartext and has served its purpose:
    # remove it so it does not sit on the instance's disk afterwards, now that
    # the report (if any) already read the fields it needed from it. The
    # config drive itself is read-only and detaches with the instance.
    rm -f "$REQUEST_FILE"
    : > "$DONE_MARKER"
    chmod 0600 "$DONE_MARKER"
    log "provisioned successfully"
    exit 0
fi

# Keep the request on failure: an operator can fix the engine and re-run this
# script by hand without the management server having to reach the instance,
# and report_result still needs report_url/token/vm_id from it.
log "provisioning failed: ${OUTPUT}"
write_result failed "${OUTPUT}"
report_result failed "${OUTPUT}" "$REQUEST_FILE" || true
exit 1
