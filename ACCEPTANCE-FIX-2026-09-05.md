# Acceptance blockers — diagnosis and fix (2026-09-05, follow-up)

Companion to `ACCEPTANCE-BLOCKERS-2026-09-05.md`. Both hypotheses in that
report (H1 config-drive label, H2 base image) are **disproved**. The real
causes are two independent bugs, both confirmed from the guest's own logs and
filesystem, read offline from a copy of the running instance's qcow2 overlay.

## Evidence source

`i-2-64-VM`'s root disk is a 17 MB overlay over the primary-storage template
copy, so a plain `cp` of the overlay plus `qemu-nbd --read-only` gives the
guest's real filesystem — including `/var/log/cloud-init.log`, the journal and
`/var/lib/dbaas/result.json` — without touching the running instance.

```bash
sudo cp /mnt/<primary>/3af6e35e-...  /tmp/vmdelta.qcow2
sudo qemu-nbd -r -c /dev/nbd2 -f qcow2 /tmp/vmdelta.qcow2
sudo mount -o ro,norecovery /dev/nbd2p1 /mnt/vmroot
sudo journalctl -D /mnt/vmroot/var/log/journal --no-pager --since "..."
```

## What was ruled out

- **Config-drive label is correct.** `blkid` on
  `/mnt/<configdrive>/i-2-64-VM.iso` reports `LABEL="config-2"`, which matches
  `VirtualMachineManager.VmConfigDriveLabel` (default `config-2`).
- **cloud-init found the datasource.** The guest log ends with
  `Datasource DataSourceConfigDrive [net,ver=2][source=/dev/sr1]`. It read the
  user data and ran `runcmd`.
- **The base image is fine.** cloud-init 22.4.2 ran all 13 modules with 0
  failures, created the `debian` user, generated host keys and executed our
  first-boot script. Test B in the previous report is unnecessary.

## Cause 1 — the NIC is never configured (the network silence)

`openstack/latest/network_data.json` on the config drive is exactly `{}`.

That is stock CloudStack behaviour, not a bug in our plugin:
`ConfigDriveBuilder.writeNetworkData()` only fills the document when
`needForGeneratingNetworkData()` is true, and that requires **ConfigDrive
itself to be the Dhcp or Dns provider** for the network
(`ConfigDriveBuilder.java:250-268`). `ConfigDriveNetworkElement
.getSupportedServicesByElementForNetwork()` (`:629-646`) only adds a service to
that map when `isProviderSupportServiceInNetwork(..., ConfigDrive)` holds. On
`dbaas-network` ConfigDrive provides **UserData only** — the virtual router
still owns DHCP and DNS — so the map is `[UserData]` and the file is written
empty.

cloud-init then treats `{}` as *a network configuration describing no
interfaces*, not as "no configuration supplied". It rendered:

```yaml
# /etc/netplan/50-cloud-init.yaml
network:
    version: 2
```

with no `ethernets:` block at all, and never fell back to DHCP. The guest's own
log shows the consequence:

```
ci-info: |  ens3  | False |     .     |     .     |   .   | 1e:01:fd:00:00:24 |
systemd-networkd-wait-online[383]: Timeout occurred while waiting for network connectivity.
```

`ens3` stayed down for the whole boot — hence zero packets on `cloudbr0`, no
DHCP DISCOVER, no ARP, and a 2 minute 13 second boot spent waiting for a
network that was never going to come up. The VR and its dnsmasq lease were
never the problem.

### Fix 1 — image-side fallback (applied in the repo)

New file `extensions/dbaas/provisioning/99-dbaas-fallback.network`, to be
installed at `/etc/systemd/network/99-dbaas-fallback.network` in every image:
DHCP on `en*`. systemd-networkd applies the first matching `.network` file in
lexical order and netplan renders its own as
`/run/systemd/network/10-netplan-*.network`, so:

- config drive carries a real network config → netplan's `10-` file wins, this
  file is never consulted
- config drive carries `{}` → nothing else matches, the interface comes up over
  DHCP from the VR

This unblocks the acceptance run without touching CloudStack or the network
offering, and it is harmless once Fix 1b is in place.

### Fix 1b — what the Phase B acceptance line actually needs (not applied)

Fix 1 depends on the VR for DHCP, so it cannot satisfy the acceptance line
"deploy with the VR down and still get a working database". For that, the
network must have **ConfigDrive as the Dhcp and Dns provider as well as
UserData**, which is what makes CloudStack write a populated
`network_data.json` (links + networks with the static IP) that cloud-init
applies without any DHCP at all. `ConfigDriveNetworkElement` advertises exactly
those capabilities (`:205-207`), so this is a supported configuration:

1. create a network offering with UserData, Dhcp **and** Dns provided by
   ConfigDrive (do **not** add a `Password` service row — 4.23's
   `Network.Service` has no such value and the map entry NPEs `listNetworks`,
   as the previous report already recorded)
2. create a network from it and deploy the acceptance instance there
3. confirm on the guest that `/etc/netplan/50-cloud-init.yaml` now contains an
   `ethernets:` block with the static address, and that the instance answers
   with the VR stopped

Fix 1 stays in the image regardless: it costs nothing and it turns "silent dead
NIC" into "works, but via DHCP" if a network is ever misconfigured again.

## Cause 2 — the engine script dies silently *after* creating the database

`/var/lib/dbaas/result.json` on the guest reads:

```json
{"status": "failed", "message": ""}
```

but `/var/lib/mysql/testdb1/` exists, created at 14:53:47 — the database and
the user were provisioned correctly. The script died immediately afterwards, at

```sh
CONF_FILE=$(grep -rls '^bind-address' /etc/mysql /etc/my.cnf /etc/my.cnf.d 2>/dev/null | head -1)
```

`/etc/my.cnf` and `/etc/my.cnf.d` do not exist on Debian's `mariadb-server`.
GNU grep exits **2** when any argument path is missing, *even when it matched
in another one*. With `set -o pipefail` that 2 becomes the pipeline's status,
`set -e` kills the script, and `2>/dev/null` means it dies without printing a
single character. Reproduced exactly:

```
$ bash -c "set -euo pipefail; V=\$(grep -rls '^bind-address' /tmp/greptest /tmp/missing 2>/dev/null | head -1); echo got"
$ echo $?
2                      # "got" never printed
```

Consequences, all of which were visible and none of which pointed at the cause:

- `bind-address` stayed `127.0.0.1` (the file's mtime is still the image build
  date), so **even with networking fixed the tenant could not have connected**
- the post-create login verification never ran
- the instance reported `failed` with an empty message, i.e. the one field that
  should have explained this was blank

`mysql.sh` carries the identical line and the identical bug.

### Fix 2 (applied)

- `mysql.sh:50` and `mariadb.sh:51`: `... | head -1 || true`, with a comment
  recording why the `|| true` is load-bearing
- `firstboot.sh`: capture the engine script's exit code explicitly and never
  report an empty message again —
  `"${OUTPUT:-${ENGINE_SCRIPT} exited ${ENGINE_RC} without writing anything to stdout or stderr}"`

Both verified with `bash -n` and by re-running the reproduction above against
the fixed form.

## Cause 3 — the readiness wait was a no-op (already fixed in the repo)

The guest log line `[dbaas-firstboot] engine ready (waited 0s)` is the bug
recorded in PLAN.md §9.0: `/opt/dbaas/engine` holds `mariadb.sh`, while
`engine_ready()` matched bare engine names, so every image fell through to the
`*)` arm and the 120 s wait never waited. The repo's current `firstboot.sh`
already strips the `.sh` suffix (`${marker%.sh}`); **the deployed images still
carry the old copy** and must be repatched.

In this particular boot it did no harm — MariaDB happened to be up two seconds
earlier — but on a slower boot it is the permanent-failure race the wait exists
to prevent.

## What is still to do

1. Patch all four v2 images offline (`qemu-nbd`, per README-BUILD-DEPLOY §6)
   with: fixed `firstboot.sh`, fixed `mysql.sh`/`mariadb.sh`, and the new
   `/etc/systemd/network/99-dbaas-fallback.network`. Remember the primary
   storage cache as well as the secondary copy — a VM deployed after patching
   only secondary is cloned from the stale cache.
2. Expunge `i-2-64-VM` (its credential row can stay; the sweeper collects it)
   and redeploy the acceptance instance from the patched `dbaas-mariadb-v2`.
3. Expect: NIC up via DHCP within seconds, `firstboot.sh` waiting for the
   engine properly, `result.json` = `confirmed`, and the credential flipping
   `pending → confirmed` through `reportDbaasProvisioningResult`.
4. Only then build the ConfigDrive-Dhcp offering (Fix 1b) and run the real
   acceptance: same deploy with the VR stopped.
5. Commit the four pending files from the previous report together with these
   fixes.
