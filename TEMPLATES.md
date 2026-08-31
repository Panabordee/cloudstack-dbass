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

## What every dbaas-* image carries

Under `/opt/dbaas`:

- `provision.sh` — SSH forced-command entrypoint; its `ALLOWED` list is the
  only gate on what can be run, so a new engine script is unreachable until it
  is added there **and** the images are patched.
- `<engine>.sh` — creates a database + user.
- `<engine>_reset.sh` — rotates an existing user's password.

Under `/etc`, from `provisioning/banner/`:

- `dbaas-engine` — `DBAAS_ENGINE_NAME` / `DBAAS_ENGINE_PORT` for this image.
- `update-motd.d/00-dbaas` — prints the banner at SSH login. Ubuntu 24.04 builds
  the login banner from that directory, so a plain `/etc/motd` ends up buried
  under the stock Ubuntu output; running first from here is what puts it in
  front of the user.
- `motd` — the same text, for anything reading the file directly.
- `issue` — shown at the console *before* login, which is the only thing a user
  with no credentials yet can read.

## Known issues

- **A 10 GB template needs noticeably more headroom than a 6 GB one.** A deploy
  reserves the template spool *and* the root volume, so `dbaas-mysql` asks for
  20 GiB against `pool.storage.allocated.capacity.disablethreshold` while the
  6 GB templates ask for 12 GiB. On a small pool that already carries the system
  VM volumes, MySQL can cross the 0.85 default while the other two stay well
  under it. Enlarging primary storage is the durable fix; raising the threshold
  only moves the ceiling. Rebuilding `dbaas-mysql` with a 6 GB root disk would
  also make the three templates consistent.
- **`create_database` restarts mysqld on every call.** `mysql.sh` rewrites
  `bind-address` whenever the line is present, which stays true after the first
  rewrite, so every subsequent provision restarts the server and drops the live
  connections of tenants already on that VM. Guard the rewrite on the value
  actually needing a change.

- **`Small Instance` is too small for these templates — use `Medium Instance`
  or larger (1 vCPU @ 1 GHz+, 1 GB+ RAM).** On `Small Instance`
  (1 vCPU capped at 500 MHz, 512 MB RAM) MySQL 8.0 on Ubuntu 24.04 pins the
  vCPU at **87-97% continuously**, which starves `sshd` badly enough that it
  cannot answer the SSH protocol banner in time. The failure looks like a
  network problem but is not: a raw TCP socket still reads the banner in ~2 s
  while both the OpenSSH client and paramiko time out during banner exchange,
  and `create_database` fails intermittently with `No existing session`.
  Measured handshake latency, same template and same host:

  | Offering | vCPU during idle | SSH handshake |
  | --- | --- | --- |
  | Small Instance (500 MHz / 512 MB) | 87-97% sustained | 5-15 s, often timing out |
  | Medium Instance (1 GHz / 1 GB) | settles under 40% | 0.15-0.26 s |

  Raising `ssh_connect_timeout_seconds` masks it at best; size the offering
  properly instead.

- **`mongod` is not listening yet when the deploy job completes.** A freshly
  deployed `dbaas-mongodb` instance answers SSH before the engine is up, so a
  `create_database` fired immediately fails with
  `MongoNetworkError: connect ECONNREFUSED 127.0.0.1:27017`. It is transient —
  the same call succeeds a minute later — and the UI already retries on it, but
  a script calling the API directly needs its own retry.

## Fixed

All three engines shared one bug: a repeat `create_database` for a name that
already existed skipped user creation, never applied the freshly generated
password, and still exited 0 — so the extension returned success with a
credential that could not authenticate. Each script now refuses the duplicate
outright and proves the new credential logs in before printing `ok`.

- **`mysql.sh` used to report success on a duplicate request.** Its
  `CREATE USER IF NOT EXISTS` skipped creation when the user already existed.
  It now fails with `user already exists: <user>@%`. The post-create check
  passes the password through `MYSQL_PWD` rather than `-p`, because the client
  writes "Using a password on the command line interface can be insecure" to
  stderr — which `2>&1` folded into the comparison and made every successful
  provision look like a failure — and because `-p` would expose the password in
  `ps` on the VM. MySQL's per-database GRANT isolation was already correct: a
  tenant cannot reach another tenant's database.
- **`postgresql.sh` used to report success on a duplicate request.** Its
  `DO $$ ... IF NOT EXISTS ... CREATE ROLE` block skipped role creation when the
  role already existed, so the freshly generated password was never applied
  while the script still exited 0. It now refuses outright with
  `role already exists: <user>` and verifies the new credential authenticates
  before printing `ok`.
- **PostgreSQL tenants were not isolated at the database level.** Any tenant
  role could `CONNECT` to another tenant's database and list its table names.
  `postgresql.sh` now runs `REVOKE CONNECT ON DATABASE <db> FROM PUBLIC`, and a
  cross-tenant connection is refused with
  `FATAL: permission denied for database` / `User does not have CONNECT privilege`.
- **`mongodb.sh` used to create tenant users through the localhost exception**,
  which only authorizes creating the first user in `admin`. It failed on the
  very first request and still exited 0. It now authenticates as `dbaas_admin`
  and verifies the new credential before printing `ok`.
