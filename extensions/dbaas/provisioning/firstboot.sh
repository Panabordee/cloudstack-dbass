#!/usr/bin/env bash
# Lives at /opt/dbaas/firstboot.sh INSIDE each config-drive dbaas-* template.
# Run by dbaas-provision.service, a systemd oneshot that fires on EVERY boot
# -- not by cloud-init's runcmd, and that is deliberate (read on).
#
# The provisioning request the management server put on the config drive
# carries db_name, db_user and db_password -- the same JSON document the SSH
# path sent on stdin, so the per-engine scripts are reused unchanged and
# there is one implementation of the engine SQL, not two.
#
# Provisioning itself never talks to the management server -- that is the
# entire point of the config-drive path. Reporting the outcome does, over the
# instance's normal network path, using the one-time token the management
# server minted for this instance; it is best-effort here (a handful of
# retries, then give up) and dbaas-report-retry.timer picks it up if that
# fails. A report that never arrives leaves the credential 'pending', which
# is visible and recoverable manually, not silently wrong.
#
# Why this does not run under cloud-init's runcmd/write_files (2026-09-08):
# createDatabase on a Running instance stops it, attaches a NEW config drive
# carrying a real request, and starts it again. On that second boot
# cloud-init logged "restored from checked cache: DataSourceConfigDrive" and
# "Skipping modules '...,runcmd' because no applicable config is provided" --
# it reused its cached datasource object from the instance's FIRST boot
# instead of re-reading the reattached ISO, so write_files never wrote
# request.json and runcmd never ran this script at all. No error anywhere;
# the credential just sat 'pending' forever. Reproduced and confirmed by
# reading the attached ISO directly: it had the correct data the whole time.
# This script now reads the config drive itself (refresh_request_from_configdrive
# below) instead of trusting cloud-init to have done it, and is triggered by
# a systemd unit that runs unconditionally every boot rather than a
# once-per-instance cloud-init module.
set -euo pipefail

DBAAS_DIR="${DBAAS_DIR:-/opt/dbaas}"
STATE_DIR="${DBAAS_STATE_DIR:-/var/lib/dbaas}"
REQUEST_FILE="${STATE_DIR}/request.json"
RESULT_FILE="${STATE_DIR}/result.json"
# Content-addressed, not a boolean "ever ran": holds the SHA-256 of the last
# request that was successfully provisioned. A plain reboot re-extracts the
# same request from the same (unchanged) config drive, hashes the same, and
# is skipped -- it must not re-run engine SQL against a tenant's live
# database. A NEW createDatabase call attaches a config drive with different
# content, hashes differently, and runs. This is what a boolean marker
# (the old /var/lib/dbaas/provisioned, removed 2026-09-08) could not do: it
# recorded "has this VM ever provisioned", which permanently blocked any
# second request, including the documented "create database on a running
# instance" feature. An older image may still have that file lying around;
# nothing here reads it, so it is inert and does not need cleaning up.
PROCESSED_HASH_FILE="${STATE_DIR}/processed.sha256"
CONFIGDRIVE_LABEL="${DBAAS_CONFIGDRIVE_LABEL:-config-2}"

log() { echo "[dbaas-firstboot] $*" >&2; }

# Reads the config drive directly and refreshes REQUEST_FILE from it,
# regardless of whether cloud-init ever wrote (or rewrote) that file. Safe to
# call every boot: it is a read-only mount of a read-only ISO. Returns
# non-zero (never fatal, callers treat it as best-effort) if the drive or its
# user-data cannot be found -- an instance deployed with no database attached
# legitimately has neither.
refresh_request_from_configdrive() {
    local device mountpoint userdata_file extracted
    device=$(blkid -t "LABEL=${CONFIGDRIVE_LABEL}" -o device 2>/dev/null | head -1)
    if [[ -z "$device" ]]; then
        log "no config drive labelled ${CONFIGDRIVE_LABEL} found"
        return 1
    fi
    mountpoint=$(mktemp -d)
    if ! mount -t iso9660 -o ro "$device" "$mountpoint" 2>/dev/null; then
        log "could not mount config drive ${device}"
        rmdir "$mountpoint" 2>/dev/null || true
        return 1
    fi
    userdata_file="${mountpoint}/openstack/latest/user_data"
    if [[ ! -f "$userdata_file" ]]; then
        umount "$mountpoint"
        rmdir "$mountpoint" 2>/dev/null || true
        log "config drive has no openstack/latest/user_data"
        return 1
    fi
    # user_data is the #cloud-config document DbaasManagerImpl.buildUserData
    # writes: a single write_files entry whose content is exactly the request
    # JSON, indented by the fixed amount that function always uses. Extract
    # it directly rather than pulling in a YAML parser for one known, fixed
    # field -- this project generates the document, so its shape is not a
    # guess.
    extracted=$(python3 - "$userdata_file" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
m = re.search(r'content: \|\n((?:^ {6}.*\n?)+)', text, re.MULTILINE)
if not m:
    sys.exit(1)
lines = [line[6:] for line in m.group(1).splitlines()]
print("\n".join(lines))
PY
    ) || { umount "$mountpoint"; rmdir "$mountpoint" 2>/dev/null || true; log "no write_files content found in user_data"; return 1; }
    umount "$mountpoint"
    rmdir "$mountpoint" 2>/dev/null || true
    if [[ -z "$extracted" ]]; then
        log "extracted an empty request from the config drive"
        return 1
    fi
    # Validate it parses as JSON before trusting it -- a truncated or
    # malformed extraction must fail loudly via the normal "no request"
    # path below, not silently leave a broken REQUEST_FILE in place that
    # every later step assumes is well-formed.
    if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' <(printf '%s' "$extracted") 2>/dev/null; then
        log "extracted request does not parse as JSON, discarding it"
        return 1
    fi
    mkdir -p "$STATE_DIR"
    printf '%s\n' "$extracted" > "$REQUEST_FILE"
    chmod 0600 "$REQUEST_FILE"
    chown root:root "$REQUEST_FILE"
    return 0
}

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
    local attempt response http_code body
    for attempt in 1 2 3 4 5; do
        # Keep curl's own diagnostics: swallowing them (the previous
        # `>/dev/null 2>&1`) turned every possible cause -- no route, refused,
        # DNS, TLS, 401, 429 -- into the same "report attempt failed" line and
        # left nothing to debug from on an instance nobody can log into.
        response=$(curl -sS -m 10 -w $'\n%{http_code}' -X POST "$report_url" \
            --data-urlencode "command=reportDbaasProvisioningResult" \
            --data-urlencode "response=json" \
            --data-urlencode "vmid=${vm_id}" \
            --data-urlencode "token=${report_token}" \
            --data-urlencode "status=${status}" \
            --data-urlencode "message=${message}" 2>&1) || response=""
        http_code="${response##*$'\n'}"
        body="${response%$'\n'*}"
        # HTTP 200 alone is not proof. The server answers a *rejected* report
        # with 403, so a 200 carrying no success payload is not a rejection --
        # it is the management server having failed before the command ran
        # (seen on 2026-09-05: the command bean could not be injected, the
        # servlet swallowed the exception and returned an empty 200). Treating
        # that as delivered would delete request.json and the one-time token
        # with it, making the credential unconfirmable forever.
        if [[ "$http_code" == "200" && "$body" == *success* && "$body" == *true* ]]; then
            log "provisioning result reported: ${status}"
            return 0
        fi
        log "report attempt ${attempt}/5 to ${report_url} failed (http=${http_code:-none}): ${body:-no body}"
        sleep $((attempt * 3))
    done
    log "could not report provisioning result after 5 attempts -- credential stays 'pending' until reported"
    return 1
}

# Always try to refresh from the config drive first -- see the header
# comment for why this cannot be left to cloud-init. Best-effort: if it
# fails (no drive, no database attached to this instance, a bad read),
# fall through to whatever REQUEST_FILE already holds, which is exactly
# the old behaviour for an instance with nothing to provision.
refresh_request_from_configdrive || true

if [[ ! -f "$REQUEST_FILE" ]]; then
    # No request anywhere: this instance was deployed without a database,
    # which is a normal thing to do. Not an error.
    log "no provisioning request at ${REQUEST_FILE}, nothing to do"
    exit 0
fi

CURRENT_HASH=$(sha256sum "$REQUEST_FILE" | awk '{print $1}')
if [[ -f "$PROCESSED_HASH_FILE" && "$(cat "$PROCESSED_HASH_FILE")" == "$CURRENT_HASH" ]]; then
    log "this exact request was already provisioned, nothing to do"
    # Deliberately NOT deleting the extracted request here, even though it
    # holds the cleartext password: it is also the only copy of the report
    # token, and request.json still existing at this point means the report
    # has NOT landed yet (every successful reporter deletes it). Deleting it
    # here strands a confirmed database with a 'pending' credential forever
    # (observed 2026-09-08). The retry service removes it once the report is
    # accepted; on an uneventful boot it is already gone.
    # The timer is not enabled in wants/ by design (scripts start it on
    # demand), so this boot must start it: this request was provisioned but
    # its report never landed -- the exact state this VM can be in after a
    # stop between "provisioned" and "reported". If the report already
    # landed, the timer's first tick finds nothing pending and stops itself.
    systemctl start dbaas-report-retry.timer >/dev/null 2>&1 || true
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
# Capture the exit code separately: an engine script killed by `set -e` on a
# command whose stderr it had suppressed reports nothing at all, and a bare
# {"status":"failed","message":""} tells whoever reads it exactly nothing
# about where it died. Observed for real on dbaas-mariadb-accept2 (mariadb.sh
# exited 2 at the bind-address grep, after the database was already created).
set +e
OUTPUT=$("$ENGINE_SCRIPT" < "$REQUEST_FILE" 2>&1)
ENGINE_RC=$?
set -e
if [[ $ENGINE_RC -eq 0 ]]; then
    write_result confirmed "database provisioned"
    # The agent reads its credentials from /var/lib/dbaas/roles.json (0600,
    # root-only): owner for DDL and write mode, readonly for browse and
    # query. Written from the request now -- the request itself is removed
    # once the report lands, and the agent must not depend on it. The
    # readonly entry is optional: instances provisioned without
    # db_user_ro / db_password_ro simply have no readonly role, and the
    # console falls back to the owner credential.
    python3 - "$REQUEST_FILE" /var/lib/dbaas/roles.json <<'PY'
import json, os, sys
req = json.load(open(sys.argv[1]))
out = {"owner": {"user": req.get("db_user", ""), "password": req.get("db_password", "")}}
ro_user = req.get("db_user_ro", "")
ro_password = req.get("db_password_ro", "")
if ro_user and ro_password:
    out["readonly"] = {"user": ro_user, "password": ro_password}
fd = os.open(sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f:
    json.dump(out, f)
PY
    chmod 0600 /var/lib/dbaas/roles.json
    # The console agent gets its identity (api base, vm uuid, agent token)
    # from the request and starts long-polling for jobs.
    if [[ -x /opt/dbaas/agent/dbaas-agent-env.sh ]]; then
        /opt/dbaas/agent/dbaas-agent-env.sh "$REQUEST_FILE" || log "agent bootstrap failed -- the agent service will retry"
    fi
    if report_result confirmed "database provisioned" "$REQUEST_FILE"; then
        rm -f "$REQUEST_FILE"
    else
        log "report did not land -- keeping ${REQUEST_FILE} so dbaas-report-retry can finish it"
        systemctl start dbaas-report-retry.timer >/dev/null 2>&1 || true
    fi
    # Recorded by content, not by a boolean -- see the header comment. A
    # later createDatabase call on this same instance attaches a config
    # drive whose request hashes differently, so this does not block it.
    printf '%s' "$CURRENT_HASH" > "$PROCESSED_HASH_FILE"
    chmod 0600 "$PROCESSED_HASH_FILE"
    log "provisioned successfully"
    exit 0
fi

# Keep the request on failure: an operator can fix the engine and re-run this
# script by hand without the management server having to reach the instance,
# and report_result still needs report_url/token/vm_id from it.
FAILURE="${OUTPUT:-${ENGINE_SCRIPT} exited ${ENGINE_RC} without writing anything to stdout or stderr}"
log "provisioning failed (rc=${ENGINE_RC}): ${FAILURE}"
write_result failed "${FAILURE}"
report_result failed "${FAILURE}" "$REQUEST_FILE" || true
exit 1
