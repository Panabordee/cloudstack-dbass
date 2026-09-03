# DBaaS template images

The four `dbaas-*` templates are qcow2 disk images living on the CloudStack
secondary storage NFS share, not in this repo — each a few GB, well past
GitHub's 100 MB per-file limit. What is version controlled here is everything
needed to rebuild them: the provisioning scripts that go inside the image, the
sshd/sudo wiring, and the runbook below.

## Manifest

| Template | Engine | Root disk | Image size on secondary storage |
| --- | --- | --- | --- |
| `dbaas-mysql` | MySQL Community 8.0.46 (official MySQL APT repo) | 10 GB | 1.84 GiB |
| `dbaas-mariadb` | MariaDB 10.11 (Debian's native `mariadb-server`) | 6 GB | 1.36 GiB |
| `dbaas-postgresql` | PostgreSQL 15 (Debian's native `postgresql`) | 6 GB | 1.41 GiB |
| `dbaas-mongodb` | MongoDB 7.0 (official MongoDB APT repo) | 6 GB | 2.11 GiB |

`dbaas-mysql` and `dbaas-mariadb` exist side by side on purpose: Debian
bookworm's main repo dropped `mysql-server` in favor of MariaDB, but some
users want the real MySQL Community Server. `mysql.sh`/`mysql_reset.sh` and
`mariadb.sh`/`mariadb_reset.sh` are identical except for the config file path
and service name — MariaDB accepts the same wire protocol, `mysql` client,
and SQL.

Look up the live UUIDs and storage paths for your own install with:

```
cloudmonkey list templates templatefilter=self
# or straight from the database:
SELECT vt.name, vt.uuid, ts.install_path
FROM vm_template vt JOIN template_store_ref ts ON ts.template_id = vt.id
WHERE vt.removed IS NULL AND ts.destroyed = 0 AND vt.name LIKE 'dbaas%';
```

All four are built from a `debian-12-base` template (Debian 12/bookworm
`genericcloud` image). CloudStack's own mirror
(`https://download.cloudstack.org/templates/cloud-images/debian/debian-12-genericcloud-amd64.qcow2`)
serves it without a redirect and is what this install uses — the same
"SSVM does not follow 302s" caveat that applied to
`cloud-images.ubuntu.com/.../current/` also applies to `cloud.debian.org`,
which always 302s to a mirror regardless of whether you ask for `/latest/`
or a dated build. If you need to re-fetch it yourself, resolve a real mirror
URL first (`curl -sI <cloud.debian.org url>` and use the `Location:` target)
and confirm it 200s directly before registering.

Previously (Ubuntu 24.04) `dbaas-mysql` had a 10 GB root disk while the other
two had 6 GB, which was not deliberate (see "Known issues" below) — kept the
same split here since real MySQL Community Server's data footprint runs
larger than MariaDB's.

## What is inside every image

`/opt/dbaas` is the default, not a constant: the scripts read `DBAAS_DIR`
(and `DBAAS_STATE_DIR` for MongoDB's rotation marker, default
`/var/lib/dbaas`) from the environment, and the extension takes `dbaas_dir`
from `config.json`. Build images somewhere else if you like — just set both
sides, since `authorized_keys`' forced command names the path too. Existing
images keep working untouched.

| Path | Owner / mode | Contents |
| --- | --- | --- |
| `/opt/dbaas/provision.sh` | `root:root` 755 | `provisioning/provision.sh` verbatim |
| `/opt/dbaas/<engine>.sh` | `root:root` 755 | `provisioning/mysql.sh`, `mariadb.sh`, `postgresql.sh` or `mongodb.sh` |
| `/opt/dbaas/vmaccess.sh` | `root:root` 755 | `provisioning/vmaccess.sh` — sets the tenant login user's password and enables sshd password logins. Images without it still provision databases fine; the extension then reports no VM credentials (the UI hides its VM access block) |
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
path the README originally showed. All engine scripts need root — MySQL/MariaDB
use socket auth as root, PostgreSQL shells out to `sudo -u postgres`, and
MongoDB reads a 0600 root-owned credentials file. The sudoers entry pins the
command to exactly that path *with no arguments* (`/opt/dbaas/provision.sh ""`),
so the only thing the key holder controls is `$SSH_ORIGINAL_COMMAND`, which
`provision.sh` validates against its own allowlist. sudo strips
`SSH_ORIGINAL_COMMAND` under `env_reset`, hence the `env_keep` line.

### Engine configuration

- **MySQL** — installed from the official MySQL APT repo, not
  `mysql-apt-config` (that .deb's postinst is interactive and hangs over a
  non-interactive SSH session with no way to preseed the dynamically-generated
  `select-server` debconf question). Add the repo directly instead:
  ```
  curl -fsSL https://repo.mysql.com/RPM-GPG-KEY-mysql-2025 | gpg --dearmor | sudo tee /usr/share/keyrings/mysql.gpg > /dev/null
  echo "deb [signed-by=/usr/share/keyrings/mysql.gpg] http://repo.mysql.com/apt/debian bookworm mysql-8.0" | sudo tee /etc/apt/sources.list.d/mysql.list
  ```
  Use `RPM-GPG-KEY-mysql-2025`, not `-2023` — the 2023 key had expired
  (`EXPKEYSIG`) by the time this was last rebuilt; check
  `https://repo.mysql.com/` for the current one. `gnupg` is not preinstalled on
  the Debian cloud image and must be installed before this step.
  The official package ships **no `bind-address` line at all** (unlike
  Ubuntu's repackaged `mysql-server`, which explicitly sets `127.0.0.1`), so
  it already listens on every interface by default — `mysql.sh`'s
  bind-address rewrite finds nothing to change and that's fine, it exists for
  defense in depth. `/var/lib/mysql/auto.cnf` is deleted before templating so
  each VM generates its own `server_uuid`.
- **MariaDB** — Debian's native `mariadb-server`, no external repo needed.
  Unlike the MySQL package, this one *does* ship `bind-address = 127.0.0.1`,
  so `mariadb.sh`'s rewrite to `0.0.0.0` actually fires here. `auto.cnf`
  cleanup applies here too.

  Neither script names a config path or a service unit: they locate the file
  that actually sets `bind-address` under `/etc/mysql`, `/etc/my.cnf` or
  `/etc/my.cnf.d`, and restart whichever of `mysql`/`mysqld`/`mariadb` is
  running. An image built from a different packaging (RHEL, Percona) works
  without editing the scripts.
- **PostgreSQL** — Debian's native `postgresql` package, no external repo
  needed. Unlike the MySQL/MariaDB engines, **nothing in `postgresql.sh` (or
  any runtime script) opens this up to the network** — `listen_addresses = '*'`
  and the `pg_hba.conf` line allowing `scram-sha-256` from `0.0.0.0/0` are a
  one-time manual step during image build (step 2 below), matching how the
  original Ubuntu template was built. Forgetting this step produces a template
  where `create_database` succeeds but the extension can never reach the
  resulting database (`listen_addresses` defaults to `localhost` only). On
  this install the perimeter is *not* CloudStack security groups (the zone's
  shared network offering has no SecurityGroup/Firewall/ACL service) — every
  VM on the guest subnet can reach every port of every other VM. Confirmed
  acceptable for this PoC zone since it carries no production data;
  re-evaluate before pointing this runbook at a zone that does.
- **MongoDB** — installed from the official MongoDB APT repo (Debian's own
  repo has no `mongodb-org`), same gnupg/keyring pattern as MySQL:
  ```
  curl -fsSL https://pgp.mongodb.com/server-7.0.asc | gpg --dearmor | sudo tee /usr/share/keyrings/mongodb-server-7.0.gpg > /dev/null
  echo "deb [signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg] https://repo.mongodb.org/apt/debian bookworm/mongodb-org/7.0 main" | sudo tee /etc/apt/sources.list.d/mongodb-org-7.0.list
  ```
  `bindIp: 0.0.0.0` with `security.authorization: enabled` set manually in
  `/etc/mongod.conf` during image build, same one-time-step caveat as
  PostgreSQL above. Requires `guest.cpu.mode=host-passthrough` in the KVM
  agent's `agent.properties`: MongoDB 5.0+ needs AVX, and the default QEMU
  model does not expose it, so `mongod` dies with
  `Illegal instruction (core dumped)`. Verified present and working on this
  install.

## Prerequisites

One build host (can be the management server itself) and one CloudStack
management server, with:

- `root` on the build host, and enough free disk for the raw base image plus
  the qcow2 overlay of the VM you build (~10 GB is comfortable per engine).
- `qemu-utils` (`qemu-nbd`, `qemu-img`) and the `nbd` kernel module loaded
  (`modprobe nbd max_part=16`) for offline disk patching.
- `cloudmonkey` (`cmk`) configured against the target CloudStack with an admin
  profile — template registration goes through it.
- CloudStack >= 4.22 management server with this repository's plugin deployed
  (the `dbaas-*` API commands must be reachable, since the extension reads the
  engines map at runtime).
- A guest network the management server can reach: provisioning SSHes from
  the management server into the guest on TCP 22. If SSH from the management
  server to a freshly deployed VM times out while ping and the console work,
  the guest subnet is typically not routed/shared to the management host yet —
  fix that before building anything (a secondary address for the management
  host inside the guest subnet is the usual trick).

## Step 1 — fetch the base image and verify it

Download `debian-12-genericcloud-amd64.qcow2` from the official Debian cloud
image directory and verify it against the `SHA512SUMS` file published at the
same path **at the time you download** (the checksums change with each point
release — for reference, the file this branch was built against had
`c602f42a374c097bafcbc77c2d034fb06cb8a831d791bcbaa5d043f029874b0c32d41cb72ba8b6d50ccfd64c9b4b0dc9ade5b6e4065712f3eb152338e532721f`):

```
BASE_URL=https://cloud.debian.org/images/cloud/bookworm/latest
wget "$BASE_URL/debian-12-genericcloud-amd64.qcow2" "$BASE_URL/SHA512SUMS"
sha512sum -c SHA512SUMS 2>/dev/null | grep genericcloud-amd64.qcow2
```

**Do not let CloudStack's SSVM download from `cloud.debian.org` directly:**
the directory always answers with a 302 redirect to a mirror, and the
secondary storage VM does not follow redirects — the download fails or lands
on an error page. Either resolve the final mirror URL first (`curl -sI` and
take the `Location:` header, confirm it answers 200), or use a
redirect-free mirror such as CloudStack's own
`https://download.cloudstack.org/templates/cloud-images/debian/debian-12-genericcloud-amd64.qcow2`.

## Rebuilding a template

Each engine is built **separately** — four builds, four registrations. The
per-engine contract:

| Template name | Engine script | Reset script | Banner files | Port |
| --- | --- | --- | --- | --- |
| `dbaas-mysql` | `mysql.sh` | `mysql_reset.sh` | `dbaas-engine-mysql` + `motd-mysql` + `issue-mysql` | 3306 |
| `dbaas-mariadb` | `mariadb.sh` | `mariadb_reset.sh` | `dbaas-engine-mariadb` + `motd-mariadb` + `issue-mariadb` | 3306 |
| `dbaas-postgresql` | `postgresql.sh` | `postgresql_reset.sh` | `dbaas-engine-postgresql` + `motd-postgresql` + `issue-postgresql` | 5432 |
| `dbaas-mongodb` | `mongodb.sh` | `mongodb_reset.sh` | `dbaas-engine-mongodb` + `motd-mongodb` + `issue-mongodb` | 27017 |

The template name is the engine key: it must match the key in `config.json`'s
`"engines"` map **exactly**, and (see "Adding a new engine") must be lowercase
alphanumerics — no `-` or `_` except the `_reset` script suffix.

Step 0 is one generic requirement: **the management server must be able to
reach TCP 22 of every guest it will provision.** On an all-in-one install the
management host usually needs its own address inside the guest subnet (e.g. a
secondary IP on the guest bridge); set that up for your network however it is
laid out, and verify with `ssh -i <provisioner-key> <user>@<guest-ip>` from
the management server before going further. It will bite every engine
equally if missing.

1. Deploy a VM from `debian-12-base` with a keypair you hold. Debian's
   `genericcloud` image sets its cloud-init default user to `debian`, not
   `ubuntu` — SSH in as `debian@<ip>` and `sudo -i` from there.
2. Install the engine. Do **not** create any databases — the extension does that.
   - `dbaas-mysql`: add the official MySQL APT repo directly (see "Engine
     configuration" above for the exact commands and the expired-key gotcha)
     before `apt-get install mysql-server` — bookworm's own repo has no
     `mysql-server` package. Needs `gnupg` installed first.
   - `dbaas-mariadb`: `apt-get install mariadb-server` from Debian's own repo,
     no extra setup.
   - `dbaas-postgresql`: `apt-get install postgresql` from Debian's own repo,
     then edit `/etc/postgresql/15/main/postgresql.conf`
     (`listen_addresses = '*'`) and append
     `host all all 0.0.0.0/0 scram-sha-256` to `pg_hba.conf`, then
     `systemctl restart postgresql`. Nothing else does this — skipping it
     silently produces a template where the database works locally but the
     extension can never reach it.
   - `dbaas-mongodb`: add the official MongoDB APT repo (see "Engine
     configuration" above) — Debian's own repo has no `mongodb-org` either.
     After install, create the `dbaas_admin` root role via `mongosh`, then set
     `bindIp: 0.0.0.0` and `security.authorization: enabled` in
     `/etc/mongod.conf` and restart. Same "nothing else does this" caveat as
     PostgreSQL.
   - No package installs needed for the login banner — Debian's cloud image
     already ships `/etc/update-motd.d/` and PAM's `pam_motd.so` wired up via
     `base-files`/`libpam-modules`, same as Ubuntu. There is no separate
     `update-motd` package to install.
3. Copy `provisioning/provision.sh` and the matching `provisioning/<engine>.sh`
   (+ `<engine>_reset.sh`) into `/opt/dbaas/`, `chmod 755`, owned by root.
4. Create the `dbaas-provisioner` user, install
   `provisioning/authorized_keys.example` as its root-owned 644
   `authorized_keys`, and `provisioning/sudoers.d-dbaas-provisioner` as
   `/etc/sudoers.d/dbaas-provisioner` (440). Verify with `visudo -c -f`.
5. MongoDB only: write `/opt/dbaas/admin_credentials.json` (0600) with the
   `dbaas_admin` credentials created in step 2, install
   `rotate-admin-password.sh` (0700) and its unit, and enable the unit. Test it
   with `systemctl start dbaas-rotate-admin-password.service` before
   generalizing — if it fires successfully here, re-enable it again afterward
   (starting it consumes the one-shot marker file, which generalizing must
   remove anyway, but the service itself must stay *enabled* for the next
   real boot).
6. Install `provisioning/banner/dbaas-engine-<engine>` as `/etc/dbaas-engine`,
   `provisioning/banner/00-dbaas` as `/etc/update-motd.d/00-dbaas` (755),
   `motd-<engine>` as `/etc/motd`, and `issue-<engine>` as `/etc/issue`.
7. Test every script end to end over the *forced-command* path before
   generalizing, not just as root locally — `ssh -i <provisioner-key>
   dbaas-provisioner@<ip> '<engine>.sh' <<< '{"db_name":...}'` should print
   `ok`. This exercises `provision.sh`'s allowlist, sudo wiring, and the real
   SSH path the extension uses, which running the engine script directly as
   root does not. Drop whatever test database/user/role this creates
   afterward — leftover MongoDB users are scoped to the database they were
   created in (`db.getSiblingDB(db_name).dropUser(...)`), not `admin`, and
   `DROP DATABASE` in PostgreSQL must be its own statement, not combined with
   other commands in one `-c` (it errors with "cannot run inside a transaction
   block").
8. Generalize, then create the template from the stopped VM's ROOT volume.

   Once `/etc/ssh/ssh_host_*` and the build-key `authorized_keys` are removed
   (generalizing step 3 below), SSH access to *that specific VM* is gone for
   good until it boots again as a fresh clone — there is no recovering into it
   over SSH even to fix a mistake in the same generalize pass. If you need to
   fix something after that point (e.g. leftover test data you forgot to
   clean up first), stop the VM and patch the disk offline instead, the same
   way as "Patching an existing template in place" below: `qemu-nbd` the ROOT
   volume, drop a temporary `authorized_keys` into `/home/debian/.ssh/`,
   unmount, boot, fix it, then repeat the key-removal step before templating.

### Generalizing before `createTemplate`

```
systemctl stop <engine>
cloud-init clean --logs --seed
rm -f /etc/ssh/ssh_host_*
truncate -s 0 /etc/machine-id
rm -f /var/lib/dbus/machine-id
rm -f /var/lib/dhcpcd/* /run/systemd/netif/leases/*
rm -f /var/lib/mysql/auto.cnf            # MySQL/MariaDB only
rm -rf /var/lib/dbaas                    # MongoDB only: the rotation marker
apt-get clean
journalctl --rotate && journalctl --vacuum-time=1s
find /var/log -type f -exec truncate -s 0 {} \;
rm -f /root/.bash_history /home/debian/.bash_history
rm -f /home/debian/.ssh/authorized_keys /root/.ssh/authorized_keys
```

The last line removes your build key, so run it last — you cannot SSH back in.

### Registering the template

Stop the VM, take its ROOT volume's volume id from CloudStack, and register it
with `cmk` — the `name` must equal the engine key in `config.json`'s
`"engines"` map exactly (this is how `detect_engine` resolves the template):

```
cmk create template volumeid=<volume-uuid> \
    name=dbaas-postgresql \
    displaytext="PostgreSQL 15 on Debian 12 x86_64" \
    ostypeid=<debian-12-64bit-os-type-id> \
    ispublic=false format=QCOW2 \
    requireshvm=true passwordenabled=false
```

- `passwordenabled=false` is deliberate: login credentials are provisioned by
  `vmaccess.sh` through the extension, not by CloudStack's password server.
- Repeat per engine; the four template names are exactly the four keys of the
  `engines` map (`dbaas-mysql`, `dbaas-mariadb`, `dbaas-postgresql`,
  `dbaas-mongodb`).
- After the first boot of an instance from the new template, confirm the
  engine listens on its port from outside (see the engine table above).

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

- `provision.sh` — SSH forced-command entrypoint. There is no hardcoded
  per-engine allowlist: it runs anything matching `^[a-z0-9]+(_reset)?\.sh$`
  that actually exists and is executable in `/opt/dbaas/` (and isn't
  `provision.sh` itself). The gate is the filesystem, not a list to keep in
  sync — `/opt/dbaas` is root:root 755 and only ever populated at
  template-build time, so `dbaas-provisioner` can never plant a file there
  to reach through it.
- `<engine>.sh` — creates a database + user.
- `<engine>_reset.sh` — rotates an existing user's password.

Under `/etc`, from `provisioning/banner/`:

- `dbaas-engine` — `DBAAS_ENGINE_NAME` / `DBAAS_ENGINE_PORT` for this image.
- `update-motd.d/00-dbaas` — prints the banner at SSH login. Both Ubuntu 24.04
  and Debian 12 build the login banner from that directory out of the box
  (`base-files`/`libpam-modules`, no extra package needed on either distro), so
  a plain `/etc/motd` ends up buried under the stock distro output; running
  first from here is what puts it in front of the user.
- `motd` — the same text, for anything reading the file directly.
- `issue` — shown at the console *before* login, which is the only thing a user
  with no credentials yet can read.

## Adding a new engine

Nothing about engine selection is hardcoded in the Python extension or in
`provision.sh` — both read the mapping or the filesystem at runtime. To add
engine `foo`:

0. Engine script names must match `^[a-z0-9]+(_reset)?\.sh$` — lowercase
   alphanumerics only, the only `_` allowed is the `_reset` suffix. Names with
   `-`, `_` elsewhere or capitals are rejected by `provision.sh` even though
   `config.json` would accept them; this is the forced-command path guard, and
   it is deliberate. `vmaccess.sh` (below) honours the same shape.
1. Write `provisioning/foo.sh` and `provisioning/foo_reset.sh` (same stdin/JSON
   contract as the existing engines) and, if it needs one, a
   `provisioning/banner/dbaas-engine-foo` + `motd-foo` + `issue-foo` set.
2. Add an entry to `config.json`'s `"engines"` map (both on your dev copy and
   on `/usr/share/cloudstack-management/extensions/dbaas/config.json` — see
   `config.example.json` for the shape): template name, `script`,
   `reset_script`, `port`. That is the *only* code-adjacent change; nothing in
   `actions/create_database.py`, `actions/reset_database_password.py`, or
   `provision.sh` needs to change or be redeployed for a new engine.
3. Build the template following "Rebuilding a template" above, using
   `dbaas-foo` as the template name — it has to match the config key exactly.
4. Deploy a VM from it and confirm `create_database`/`reset_password` both
   return `ok` before relying on it.

The one thing that **does** require redeploying the extension's `.py` files to
the management server path is a change to the extension's own logic (as
opposed to config) — e.g. this section didn't exist until a real
`create_database` call failed with "could not determine DB engine from
template (got None)" against a template (`dbaas-mariadb`) that config.json
didn't know about yet, because the deployed copy at
`/usr/share/cloudstack-management/extensions/dbaas/` was never synced after
the config-driven refactor landed in this repo. Config changes take effect on
the next SSH-triggered subprocess call with no CloudStack restart; `.py`/`.sh`
file changes need `cp`-ing to that path (there is no automated deploy step for
this yet — see `register_extension.sh`'s header comment).

## Known issues

- **A 10 GB template needs noticeably more headroom than a 6 GB one.** A deploy
  reserves the template spool *and* the root volume, so `dbaas-mysql` asks for
  20 GiB against `pool.storage.allocated.capacity.disablethreshold` while the
  6 GB templates ask for 12 GiB. On a small pool that already carries the system
  VM volumes, MySQL can cross the 0.85 default while the others stay well
  under it. Enlarging primary storage is the durable fix; raising the threshold
  only moves the ceiling.
- **`create_database` restarts mysqld/mariadbd on every call.** `mysql.sh` and
  `mariadb.sh` rewrite `bind-address` whenever the line is present, which stays
  true after the first rewrite, so every subsequent provision restarts the
  server and drops the live connections of tenants already on that VM. Guard
  the rewrite on the value actually needing a change.

- **`Small Instance` is too small for these templates — use `Medium Instance`
  or larger (1 vCPU @ 1 GHz+, 1 GB+ RAM).** Measured on Ubuntu 24.04 with
  MySQL 8.0 (not yet re-measured on the Debian 12 rebuild, but the mechanism is
  OS-independent and Debian's stock kernel/scheduler behaves the same way):
  on `Small Instance` (1 vCPU capped at 500 MHz, 512 MB RAM) the database
  engine pins the vCPU at **87-97% continuously**, which starves `sshd` badly
  enough that it cannot answer the SSH protocol banner in time. The failure
  looks like a network problem but is not: a raw TCP socket still reads the
  banner in ~2 s while both the OpenSSH client and paramiko time out during
  banner exchange, and `create_database` fails intermittently with
  `No existing session`. Measured handshake latency, same template and same
  host:

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

All three original engines shared one bug: a repeat `create_database` for a
name that already existed skipped user creation, never applied the freshly
generated password, and still exited 0 — so the extension returned success
with a credential that could not authenticate. Each script now refuses the
duplicate outright and proves the new credential logs in before printing `ok`.
`mariadb.sh`/`mariadb_reset.sh` were written after this fix and inherit it
directly — they were never affected.

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
