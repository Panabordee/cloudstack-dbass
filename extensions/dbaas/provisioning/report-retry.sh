#!/usr/bin/env bash
# Lives at /opt/dbaas/report-retry.sh, run by dbaas-report-retry.timer.
#
# firstboot.sh reports the provisioning outcome once, with five quick
# attempts. If the management server is unreachable for longer than that --
# a slow virtual router, a route that appears a minute after the guest boots,
# a management server being restarted -- the credential stays 'pending'
# forever even though the database is fine, and the one-time token is inside
# request.json with no other copy anywhere.
#
# So: keep trying, slowly, until it lands. On success the request file is
# removed (it holds the database password in cleartext) and the timer
# disables itself. This is the only thing that runs after first boot, and it
# does nothing at all once /var/lib/dbaas/request.json is gone.
set -euo pipefail

DBAAS_DIR="${DBAAS_DIR:-/opt/dbaas}"
STATE_DIR="${DBAAS_STATE_DIR:-/var/lib/dbaas}"
REQUEST_FILE="${STATE_DIR}/request.json"
RESULT_FILE="${STATE_DIR}/result.json"

log() { echo "[dbaas-report-retry] $*" >&2; }

if [[ ! -f "$REQUEST_FILE" ]]; then
    log "nothing pending"
    systemctl disable --now dbaas-report-retry.timer >/dev/null 2>&1 || true
    exit 0
fi

json_field() {
    python3 -c '
import json, sys
try:
    with open(sys.argv[2]) as f:
        print(json.load(f).get(sys.argv[1], ""))
except Exception:
    print("")
' "$1" "$2"
}

report_url=$(json_field report_url "$REQUEST_FILE")
report_token=$(json_field report_token "$REQUEST_FILE")
vm_id=$(json_field vm_id "$REQUEST_FILE")
if [[ -z "$report_url" || -z "$report_token" || -z "$vm_id" ]]; then
    log "request carries no report target -- reporting was never configured, nothing to retry"
    systemctl disable --now dbaas-report-retry.timer >/dev/null 2>&1 || true
    exit 0
fi

# Report whatever the provisioning run actually concluded, not a fixed value:
# a failed provision needs its failure delivered just as much as a successful
# one needs its confirmation.
status=$(json_field status "$RESULT_FILE")
message=$(json_field message "$RESULT_FILE")
status="${status:-failed}"
message="${message:-provisioning result unavailable on the instance}"
message="${message:0:1000}"

out=$(curl -sS -m 10 -w $'\n%{http_code}' -X POST "$report_url" \
    --data-urlencode "command=reportDbaasProvisioningResult" \
    --data-urlencode "response=json" \
    --data-urlencode "vmid=${vm_id}" \
    --data-urlencode "token=${report_token}" \
    --data-urlencode "status=${status}" \
    --data-urlencode "message=${message}" 2>&1) || out=""

http_code="${out##*$'\n'}"
body="${out%$'\n'*}"

# Same rule as firstboot.sh: a rejected report is answered with 403, so an
# empty 200 means the management server broke before the command ran. Only a
# success payload counts as delivered -- anything else keeps the token.
if [[ "$http_code" == "200" && "$body" == *success* && "$body" == *true* ]]; then
    log "reported ${status} successfully -- removing the request and stopping the timer"
    rm -f "$REQUEST_FILE"
    systemctl disable --now dbaas-report-retry.timer >/dev/null 2>&1 || true
    exit 0
fi

log "retry failed: ${out:-no output} -- will try again on the next timer tick"
exit 0
