#!/usr/bin/env bash
# Writes /var/lib/dbaas/agent.json for the console agent from the provisioning
# request, then enables and starts the agent. Called by firstboot.sh at the
# end of provisioning.
set -euo pipefail
REQUEST="${1:-/var/lib/dbaas/request.json}"
python3 - "$REQUEST" <<'PY'
import json, os, sys
request = json.load(open(sys.argv[1]))
conf = {
    "api_url": request["api_url"].rsplit("/client/api", 1)[0] + "/client/api",
    "vm_id": request["vm_id"],
    "token": request["agent_token"],
    "database": request.get("db_name", ""),
}
fd = os.open("/var/lib/dbaas/agent.json", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as handle:
    json.dump(conf, handle)
os.chmod("/var/lib/dbaas/agent.json", 0o600)
PY
systemctl enable dbaas-agent.service >/dev/null 2>&1 || true
systemctl restart dbaas-agent.service
