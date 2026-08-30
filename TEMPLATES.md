# DBaaS template images

The three `dbaas-*` templates are qcow2 disk images living on the CloudStack
secondary storage NFS share, not in this repo — they are 2.6–3.8 GB each
(~9.3 GB total), well past GitHub's 100 MB per-file limit. What is version
controlled here is everything needed to rebuild them: the provisioning scripts
that go inside the image, the sshd/sudo wiring, and the runbook below.

## Manifest

| Template | Root disk | Image size on secondary storage |
| --- | --- | --- |
| `dbaas-mysql` | 10 GB | 2.63 GiB |
| `dbaas-postgresql` | 6 GB | 2.47 GiB |
| `dbaas-mongodb` | 6 GB | 3.56 GiB |

Look up the live UUIDs and storage paths for your own install with:

```
cloudmonkey list templates templatefilter=self
# or straight from the database:
SELECT vt.name, vt.uuid, ts.install_path
FROM vm_template vt JOIN template_store_ref ts ON ts.template_id = vt.id
WHERE vt.removed IS NULL AND ts.destroyed = 0 AND vt.name LIKE 'dbaas%';
```

All three are built from an `ubuntu-24.04-base` template registered from
`https://cloud-images.ubuntu.com/releases/noble/release/ubuntu-24.04-server-cloudimg-amd64.img`.
Use that direct URL, not a `/current/` redirect — the SSVM does not follow
302s and the registration fails with "due to redirection, response code: 302".

`dbaas-mysql` has a 10 GB root disk while the other two have 6 GB. That is not
deliberate; see "Known issues" below.

## What is inside every image

| Path | Owner / mode | Contents |
| --- | --- | --- |
| `/opt/dbaas/provision.sh` | `root:root` 755 | `provisioning/provision.sh` verbatim |
| `/opt/dbaas/<engine>.sh` | `root:root` 755 | `provisioning/mysql.sh`, `postgresql.sh` or `mongodb.sh` |
| `/home/dbaas-provisioner/.ssh/authorized_keys` | `root:root` 644 | `provisioning/authorized_keys.example` — root-owned so the user cannot edit its own forced command |
| `/etc/sudoers.d/dbaas-provisioner` | `root:root` 440 | `provisioning/sudoers.d-dbaas-provisioner` |

MongoDB additionally carries:

| Path | Owner / mode | Contents |
| --- | --- | --- |
| `/opt/dbaas/admin_credentials.json` | `root:root` 600 | `{"user": "dbaas_admin", "password": "..."}` |
| `/opt/dbaas/rotate-admin-password.sh` | `root:root` 700 | `provisioning/rotate-admin-password.sh` |
| `/etc/systemd/system/dbaas-rotate-admin-password.service` | `root:root` 644 | enabled via a `multi-user.target.wants` symlink |

### Why the forced command runs under sudo

`authorized_keys` uses `command="sudo -n /opt/dbaas/provision.sh"`, not the bare
path the README originally showed. All three engine scripts need root — MySQL
uses socket auth as root, PostgreSQL shells out to `sudo -u postgres`, and
MongoDB reads a 0600 root-owned credentials file. The sudoers entry pins the
command to exactly that path *with no arguments* (`/opt/dbaas/provision.sh ""`),
so the only thing the key holder controls is `$SSH_ORIGINAL_COMMAND`, which
`provision.sh` validates against its own allowlist. sudo strips
`SSH_ORIGINAL_COMMAND` under `env_reset`, hence the `env_keep` line.

### Engine configuration

- **MySQL** — `bind-address` is rewritten to `0.0.0.0` by `mysql.sh` on first
  provision. `/var/lib/mysql/auto.cnf` is deleted before templating so each VM
  generates its own `server_uuid`.
- **PostgreSQL** — `listen_addresses = '*'` and `pg_hba.conf` allows
  `scram-sha-256` from `0.0.0.0/0`. The perimeter is CloudStack security groups.
- **MongoDB** — `bindIp: 0.0.0.0` with `security.authorization: enabled`.
  Requires `guest.cpu.mode=host-passthrough` in the KVM agent's
  `agent.properties`: MongoDB 5.0+ needs AVX, and the default QEMU model does
  not expose it, so `mongod` dies with `Illegal instruction (core dumped)`.

## Rebuilding a template

1. Deploy a VM from `ubuntu-24.04-base` with a keypair you hold.
2. Install the engine. Do **not** create any databases — the extension does that.
3. Copy `provisioning/provision.sh` and the matching `provisioning/<engine>.sh`
   into `/opt/dbaas/`, `chmod 755`, owned by root.
4. Create the `dbaas-provisioner` user, install
   `provisioning/authorized_keys.example` as its root-owned 644
   `authorized_keys`, and `provisioning/sudoers.d-dbaas-provisioner` as
   `/etc/sudoers.d/dbaas-provisioner` (440). Verify with `visudo -c -f`.
5. MongoDB only: create the `dbaas_admin` root role, write
   `/opt/dbaas/admin_credentials.json` (0600), install
   `rotate-admin-password.sh` (0700) and its unit, and enable the unit.
6. Generalize, then create the template from the stopped VM's ROOT volume.

### Generalizing before `createTemplate`

```
systemctl stop <engine>
cloud-init clean --logs --seed
rm -f /etc/ssh/ssh_host_*
truncate -s 0 /etc/machine-id
rm -f /var/lib/dbus/machine-id
rm -f /var/lib/dhcpcd/* /run/systemd/netif/leases/*
rm -f /var/lib/mysql/auto.cnf            # MySQL only
rm -rf /var/lib/dbaas                    # MongoDB only: the rotation marker
apt-get clean
journalctl --rotate && journalctl --vacuum-time=1s
find /var/log -type f -exec truncate -s 0 {} \;
rm -f /root/.bash_history /home/ubuntu/.bash_history
rm -f /home/ubuntu/.ssh/authorized_keys /root/.ssh/authorized_keys
```

The last line removes your build key, so run it last — you cannot SSH back in.

## Patching an existing template in place

Templates can be edited offline instead of rebuilt:

```
modprobe nbd max_part=16
cp -a <image>.qcow2 <image>.qcow2.bak
qemu-nbd -c /dev/nbd0 -f qcow2 <image>.qcow2
partprobe /dev/nbd0 && mount /dev/nbd0p1 /mnt/tplroot
# ... edit files under /mnt/tplroot ...
sync && umount /mnt/tplroot && qemu-nbd -d /dev/nbd0
qemu-img check <image>.qcow2
```

**CloudStack also caches templates on primary storage.** Editing only the
secondary copy is not enough — VMs deployed afterwards are cloned from the
primary cache and silently keep the old contents. Either patch
`<primary-storage-mount>/<template-uuid>` the same way, or expunge every VM using the
template and let the storage garbage collector drop the cached copy so the next
deploy re-copies from secondary. This cost one full debugging cycle.

## Known issues

- **A 10 GB template needs noticeably more headroom than a 6 GB one.** A deploy
  reserves the template spool *and* the root volume, so `dbaas-mysql` asks for
  20 GiB against `pool.storage.allocated.capacity.disablethreshold` while the
  6 GB templates ask for 12 GiB. On a small pool that already carries the system
  VM volumes, MySQL can cross the 0.85 default while the other two stay well
  under it. Fix by enlarging primary storage, raising the threshold, or
  rebuilding `dbaas-mysql` with a 6 GB root disk to match the others.
- **`postgresql.sh` reports success on a duplicate request.** Its
  `DO $$ ... IF NOT EXISTS ... CREATE ROLE` block skips role creation when the
  role already exists, so the freshly generated password is never applied, yet
  the script exits 0 and the extension hands the caller a password that fails to
  authenticate. `mongodb.sh` had the same class of bug and was fixed by
  verifying the new credential before printing `ok`.
- **PostgreSQL tenants are not isolated at the database level.** Any tenant role
  can `CONNECT` to another tenant's database and list its table names, though
  not read rows or create objects. Add
  `REVOKE CONNECT ON DATABASE <db> FROM PUBLIC` after creating each database.
