#!/usr/bin/env bash
# Drop DHCP offers/acks coming from the site's own DHCP server so they never
# reach CloudStack guest VMs.
#
# Why: when the Basic Zone shares one L2 with an existing LAN, guests race two
# DHCP servers — CloudStack's virtual router and the site gateway. When the
# gateway wins, the guest ends up with an IP the VR never handed out, and
# CloudStack's ebtables ARP pinning silently blackholes it.
#
# Only the configured source MAC is filtered, and only in the bridge FORWARD
# chain, so the host's own networking and the CloudStack VR are both untouched.
set -euo pipefail

CONFIG_FILE="/etc/default/dbaas-block-rogue-dhcp"

# GW_MAC is the MAC of the DHCP server to silence. Find it with
#   ip neigh show <gateway-ip>
# and put it in CONFIG_FILE as: GW_MAC="aa:bb:cc:dd:ee:ff"
# It is read from there rather than hardcoded so this script carries no
# site-specific detail, and so replacing the router is a one-line change.
GW_MAC="${GW_MAC:-}"
# shellcheck source=/dev/null
[[ -r "$CONFIG_FILE" ]] && source "$CONFIG_FILE"

if [[ -z "$GW_MAC" ]]; then
  echo "GW_MAC is not set; put GW_MAC=\"aa:bb:cc:dd:ee:ff\" in $CONFIG_FILE" >&2
  exit 1
fi

if ebtables -t filter -L FORWARD 2>/dev/null | grep -qi "$GW_MAC"; then
  echo "rule already present"
  exit 0
fi

ebtables -t filter -I FORWARD 1 \
  -p IPv4 -s "$GW_MAC" \
  --ip-protocol udp --ip-source-port 67 --ip-destination-port 68 \
  -j DROP

echo "rule installed"
