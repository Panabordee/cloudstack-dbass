# Host-side fixes

These live on the CloudStack management/KVM host itself, not inside a template.

Addresses and identifiers below are written as placeholders — substitute your
own. Nothing site-specific is committed to this repo; the one real value the
scripts need lives in `/etc/default/dbaas-block-rogue-dhcp` on the host.

## `dbaas-block-rogue-dhcp` — stop the site DHCP server answering guests

When the Basic Zone shares one L2 segment with an existing LAN, a booting guest
races two DHCP servers: the CloudStack virtual router and the site gateway. When
the gateway wins, the guest configures an address the VR never handed out,
CloudStack's ebtables ARP pinning drops its traffic, and the VM comes up with no
network at all — roughly half the time in our case.

The rule drops only IPv4/UDP 67→68 frames whose source MAC is the gateway's, and
only in the bridge `FORWARD` chain:

```
ebtables -t filter -I FORWARD 1 -p IPv4 -s <GATEWAY_MAC> \
  --ip-protocol udp --ip-source-port 67 --ip-destination-port 68 -j DROP
```

Find `<GATEWAY_MAC>` with `ip neigh show <gateway-ip>` — do not guess it.

The guest bridge is shared here: it carries the host uplink NIC, the host's own
static address and default route, and every guest `vnet` port. That is still
safe, because `FORWARD` only sees frames bridged between ports — the host's own
traffic goes through `INPUT`/`OUTPUT` — and netplan has `dhcp4: false`, so the
host has no DHCP client to break. The virtual router's MAC is a different
address and is untouched.

Before installing, confirm all three:

1. which bridge carries guest traffic, and whether it is shared with the host's
   own uplink (`ip -br addr`, `ls /sys/class/net/<bridge>/brif/`);
2. the gateway's real MAC from the ARP table, not an assumption;
3. that you have console access to the host in case connectivity drops.

Measured: 1 of 2 VMs got a working network before the rule, 6 of 6 across three
consecutive deploy rounds after it.

### Install

```
cp dbaas-block-rogue-dhcp.default.example /etc/default/dbaas-block-rogue-dhcp
chmod 600 /etc/default/dbaas-block-rogue-dhcp
# edit it and set GW_MAC to the real gateway MAC

install -o root -g root -m 755 dbaas-block-rogue-dhcp.sh /usr/local/sbin/
install -o root -g root -m 644 dbaas-block-rogue-dhcp.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now dbaas-block-rogue-dhcp.service
```

The script is idempotent, so restarting the unit is safe. Test recovery by
deleting the rule with `ebtables -t filter -D FORWARD ...` and restarting the
unit rather than by rebooting.

### Caveat

If the router is replaced its MAC changes and the rule silently stops matching,
bringing the DHCP race back. Re-check with `ip neigh` and update `GW_MAC`. The
real fix is to put the Basic Zone on its own VLAN.

## Other host settings this deployment depends on

- `/etc/cloudstack/agent/agent.properties` needs `guest.cpu.mode=host-passthrough`
  so guests see the host CPU's AVX support. Without it MongoDB 5.0+ crashes with
  `Illegal instruction (core dumped)`. The agent must be restarted and each VM
  stopped and started — the libvirt XML is only regenerated on start.
- An sshd drop-in scopes `PermitRootLogin yes` to loopback and the host's own
  address so `addHost` can SSH to itself. End the block with `Match all` so it
  does not swallow the rest of the config.
