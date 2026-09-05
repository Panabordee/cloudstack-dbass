#!/usr/bin/env bash
# Runs INSIDE the dbaas-* VM as root (via provision.sh). Reads JSON on stdin:
# {"vm_user": "...", "vm_password": "..."}
#
# Gives the tenant shell access to their own instance: sets the login user's
# password and makes sure sshd accepts password logins. Templates built before
# this script existed simply don't ship it — provision.sh then refuses the
# name and the extension skips VM credentials instead of failing the database
# that was already provisioned.
set -euo pipefail

payload=$(cat)
vm_user=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["vm_user"])')
vm_password=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["vm_password"])')

if [[ ! "$vm_user" =~ ^[A-Za-z][A-Za-z0-9_.-]{0,31}$ ]]; then
  echo "invalid vm_user: $vm_user" >&2
  exit 1
fi
if ! id "$vm_user" >/dev/null 2>&1; then
  echo "user does not exist: $vm_user" >&2
  exit 1
fi
# Length-check the password rather than pattern-matching it: the generator is
# alphanumeric, but chpasswd reads user:password on stdin so the value never
# touches an argument list or a log line either way.
if [[ ${#vm_password} -lt 8 || ${#vm_password} -gt 64 ]]; then
  echo "vm_password must be 8-64 characters" >&2
  exit 1
fi

printf '%s:%s' "$vm_user" "$vm_password" | chpasswd

# sshd_config.d uses first-obtained-value-wins, so a file sorting BEFORE
# cloud-init's 50-cloud-init.conf (which sets PasswordAuthentication no on
# cloud images) is what actually enables password logins.
printf 'PasswordAuthentication yes\n' > /etc/ssh/sshd_config.d/10-dbaas-vm-access.conf
sshd -t
systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true

echo "ok"
