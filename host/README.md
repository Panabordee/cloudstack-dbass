# Host-side fixes

These live on the CloudStack management/KVM host itself, not inside a template.

Addresses and identifiers below are written as placeholders — substitute your
own. Nothing site-specific is committed to this repo.

> The current proof-of-concept lab additionally carries a POC-only mitigation
> that blocks the physical uplink's DHCP server from answering guest VMs (an
> ebtables rule plus a systemd unit installed directly on the host). It is
> intentionally **not** kept in this repo: it is only needed because the lab
> puts guest traffic on the same untagged flat L2 as the office LAN. A properly
> designed environment isolates guest traffic on its own VLAN/network, has no
> DHCP race, and must not install any such rule.

## `guest.cpu.mode=host-passthrough`

`/etc/cloudstack/agent/agent.properties` needs `guest.cpu.mode=host-passthrough`
so guests see the host CPU's AVX support. Without it MongoDB 5.0+ crashes with
`Illegal instruction (core dumped)`. The agent must be restarted and each VM
stopped and started — the libvirt XML is only regenerated on start.

## sshd scope for `addHost`

An sshd drop-in scopes `PermitRootLogin yes` to loopback and the host's own
address so `addHost` can SSH to itself. End the block with `Match all` so it
does not swallow the rest of the config.
