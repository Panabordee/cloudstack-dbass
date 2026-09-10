# DBaaS template images — building, patching, verifying

How the four `dbaas-*` template images are made and changed. Written
2026-09-10 from doing it, including the parts that went wrong.

The images themselves are qcow2 files on storage, not in this repo — a few GB
each. What is version controlled is everything that goes *inside* them
(`extensions/dbaas/`) plus this runbook.

`plugins/integrations/dbaas/TEMPLATES.md` still describes the retired v1 SSH
architecture and should not be followed.

---

## 1. What a DBaaS image has to contain

Every image, all four engines. Sources are all under `extensions/dbaas/`.

| Path in the image | From | Mode |
| --- | --- | --- |
| `/opt/dbaas/firstboot.sh` | `provisioning/` | 0755 |
| `/opt/dbaas/report-retry.sh` | `provisioning/` | 0755 |
| `/opt/dbaas/<engine>.sh` | `provisioning/` | 0755 |
| `/opt/dbaas/<engine>_reset.sh` | `provisioning/` | 0755 |
| `/opt/dbaas/engine` | **a one-line marker, see below** | 0644 |
| `/opt/dbaas/agent/dbaas_agent.py` | `agent/` | 0755 |
| `/etc/systemd/system/dbaas-provision.service` | `provisioning/` | 0644 |
| `/etc/systemd/system/dbaas-report-retry.{service,timer}` | `provisioning/` | 0644 |
| `/etc/systemd/system/dbaas-agent.service` | `agent/` | 0644 |
| `/etc/systemd/network/99-dbaas-fallback.network` | `provisioning/` | 0644 |

Enabled units (symlink into the `.wants` directory):
`dbaas-provision.service` and `dbaas-agent.service` into
`multi-user.target.wants/`, `dbaas-report-retry.timer` into
`timers.target.wants/`. **`dbaas-report-retry.service` is deliberately not
enabled** — the scripts start it on demand and it disables itself once the
report lands.

Engine client library, which the agent imports:

| Engine | Package |
| --- | --- |
| mysql, mariadb | `python3-pymysql` |
| postgresql | `python3-psycopg2` |
| mongodb | `python3-pymongo` |

### `/opt/dbaas/engine` is a marker, not a copy

It contains **only the script's file name**, e.g. `mysql.sh`. `firstboot.sh`
reads it and appends the value to `/opt/dbaas/`, so a *copy of the script*
there produces a garbage path and provisioning fails with

```
engine script ... missing or not executable
```

This broke a rebuild on 2026-09-08. If a patched image will not provision,
check this file first — `cat /opt/dbaas/engine` should print one short line.

---

## 2. Changing an existing image (the usual case)

Nothing here needs a full image rebuild. This is the `qemu-nbd` path.

### 2.1 Nothing may be using the image

A template's qcow2 is held open by every running VM that was deployed from
it, so `qemu-nbd` will refuse:

```
qemu-nbd: Failed to blk_new_open '...': Failed to get "write" lock
Is another process using the image?
```

**Do not force this.** Breaking the lock on a file backing a live instance
risks that instance's disk. Destroy or stop the instances first, then prove
the file is free:

```bash
sudo fuser -v /export/primary/<template-uuid>          # no output == free
```

### 2.2 Patch both copies

There are two files per template and they drift independently:

| Copy | Path | Used for |
| --- | --- | --- |
| primary cache | `/export/primary/<template-uuid>` | deploying new instances on this host |
| secondary | `/export/secondary/template/tmpl/2/<id>/<uuid>.qcow2` | the source of truth CloudStack re-syncs from |

Patching only the primary cache means the change silently disappears the next
time CloudStack re-caches from secondary. Patch both, every time.

Current ids on this host:

| id | Template | Template UUID |
| --- | --- | --- |
| 210 | dbaas-mysql-v2 | `1d9e7b9c-afc2-4cfd-92a0-6a23ca5dfd48` |
| 211 | dbaas-mariadb-v2 | `8ceff582-71ee-41c9-aa8d-82f0a72fc488` |
| 212 | dbaas-postgresql-v2 | `1020609f-1c71-4003-b758-93e3debd61c0` |
| 213 | dbaas-mongodb-v2 | `a645211d-8b18-4842-bcbe-50ac0eb7db9f` |

### 2.3 Take an undo point first

A full `cp -a` is 2-3 GB per image. When storage is tight, a qcow2 **internal
snapshot** is the same guarantee for almost no space, because it only stores
blocks that change afterwards:

```bash
sudo qemu-img snapshot -c prepatch-$(date +%Y%m%d) "$IMG"   # create
sudo qemu-img snapshot -l "$IMG"                            # list
sudo qemu-img snapshot -a prepatch-YYYYMMDD "$IMG"          # revert
sudo qemu-img snapshot -d prepatch-YYYYMMDD "$IMG"          # drop once happy
```

Full copies go in `/export/primary/tplbackup/`, which is **not** to be
deleted from without asking — it is the rollback path for every image.

### 2.4 The patch itself

```bash
IMG=/export/primary/8ceff582-71ee-41c9-aa8d-82f0a72fc488     # or the secondary path
REPO=/home/nacl/dbaas-v2

lsmod | grep -q '^nbd ' || sudo modprobe nbd max_part=8
sudo qemu-nbd --connect=/dev/nbd6 -f qcow2 "$IMG"
lsblk /dev/nbd6                       # root filesystem is nbd6p1 on these images

sudo mkdir -p /mnt/tplpatch
sudo mount /dev/nbd6p1 /mnt/tplpatch

sudo cat /mnt/tplpatch/opt/dbaas/engine        # confirm the right engine before writing
sudo cp "$REPO/extensions/dbaas/agent/dbaas_agent.py" /mnt/tplpatch/opt/dbaas/agent/
sudo chmod 0755 /mnt/tplpatch/opt/dbaas/agent/dbaas_agent.py
sudo chown root:root /mnt/tplpatch/opt/dbaas/agent/dbaas_agent.py

# verify inside the mount, before unmounting
sudo python3 -m py_compile /mnt/tplpatch/opt/dbaas/agent/dbaas_agent.py
md5sum "$REPO/extensions/dbaas/agent/dbaas_agent.py"
sudo md5sum /mnt/tplpatch/opt/dbaas/agent/dbaas_agent.py       # must match

sudo sync
sudo umount /mnt/tplpatch
sudo qemu-nbd --disconnect /dev/nbd6
sudo qemu-img check "$IMG"                                     # must say no errors
```

**`sync` → `umount` → `qemu-nbd -d`, in that order.** Disconnecting before
unmounting corrupts the image.

Shell scripts get `sudo bash -n <file>` instead of `py_compile`.

---

## 3. Verifying — a checksum is not enough

A matching `md5sum` proves the file is in the image. It does not prove the
image *provisions*. Always finish with a real deploy:

```bash
cmk deployVirtualMachine templateid=<uuid> serviceofferingid=<offering> \
    networkids=<net> zoneid=<zone> keypairs=<key> name=verify-<engine> startvm=false
cmk createDatabase virtualmachineid=<id> dbname=vdb dbusername=vuser dbpassword=<pw>
# then, once it is Running:
cmk listDbaasTables virtualmachineid=<id>
cmk getDbaasJobResult jobid=<jobid>          # expect state=confirmed
```

**This is not ceremony.** On 2026-09-10 all four images passed the checksum
check and three of four then worked; postgresql's first console job failed
with

```
permission denied for database "vdb": User does not have CONNECT privilege
```

because `postgresql.sh` in template 212 was missing one line —
`GRANT CONNECT ON DATABASE ... TO ..._ro` — that had been committed to the
repo weeks earlier and never actually baked in, including by a rebuild that
reported itself complete. Only the fresh deploy caught it. Diffing the
guest's copy against the repo is the check that finds this class of drift:

```bash
ssh -i ~/.ssh/dbaas_build_key debian@<ip> "sudo cat /opt/dbaas/postgresql.sh" \
  | diff - extensions/dbaas/provisioning/postgresql.sh
```

Also worth checking on the guest:

```bash
sudo systemctl is-active dbaas-agent          # active
cat /opt/dbaas/engine                         # one line, e.g. mariadb.sh
sudo cat /var/lib/dbaas/result.json           # {"status": "confirmed", ...}
```

---

## 4. Things that will stop you, and what they mean

**`No destination found for a deployment`** on a VM start, when nothing is
obviously wrong: CloudStack refuses to allocate on a primary storage pool
past its capacity threshold (85% by default). Check actual disk use, not the
allocated figure:

```bash
df -h /export/primary
cmk list storagepools | grep -E "disksizeused|disksizetotal"
```

Free real space and it starts working again. Do not raise the threshold to
get past it.

**Build fails in `cloud-utils` with `Could not initialize plugin: MockMaker`**:
a Mockito 5.16.1 / JDK 17 issue in CloudStack's own test harness, unrelated to
DBaaS. Use `packaging/dbaas-build-and-deploy.sh`, which passes the supported
`ACS_BUILD_OPTS="-DskipTests"`.

**UI build dies with `ERR_OSSL_EVP_UNSUPPORTED`**: webpack 4 calls
`createHash('md4')`, which OpenSSL 3 removed. Needs
`NODE_OPTIONS=--openssl-legacy-provider` — already wired into the build
script and `ui/package.json`.

**A patched image provisions but the console does nothing**: check
`/opt/dbaas/engine` (§1) and `journalctl -u dbaas-provision` on the guest.

---

## 5. Building a brand-new image from scratch

Only needed for a new engine or a new base OS. The offline `qemu-nbd` path
above cannot install packages, so this one has to boot.

1. Deploy an instance from a plain Debian 12 template
2. Install the engine and its python client (§1), configure it to listen on
   `0.0.0.0` and to start at boot
3. Copy in every file from §1 and enable the units
4. Set `/opt/dbaas/engine` to the script *name* (§1)
5. Clean up: `cloud-init clean --logs`, remove `/var/lib/dbaas/*`,
   truncate logs, remove SSH host keys and `~/.ssh/authorized_keys`, clear
   shell history
6. Stop the instance and `createTemplate` from its ROOT volume
7. Add the engine to `extensions/dbaas/config.json` under `engines`
   (`script`, `port`, `minmemorymb`) and, if it supports schema DDL, to the
   top-level `types` allowlist. **Never hardcode an engine in `.py` or
   `.java`** — the config file is the only place engines are named
8. Measure the memory floor rather than guessing it: deploy on the smallest
   offering, run a console session, and watch `journalctl -u <engine>` and
   `dmesg` for `oom-kill`. Step up until clean, then record the number as
   `minmemorymb`
9. Run §3's verification

Step 5 matters: anything left behind ships to every tenant who deploys the
template.
