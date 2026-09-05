# DBaaS v2 — Build, Deploy, Template Setup

Quick reference for taking this branch (`panabordee/dbaas-v2`) from source to a
working config-drive DBaaS deployment. Read `plugins/integrations/dbaas/PLAN.md`
first for the "why" behind each piece; this file is just the "how".

## 1. Build

From the repo root:

```bash
packaging/build-deb.sh -o /home/nacl/dbaas-v2-deb
```

This builds every `.deb` (management, agent, usage, ui, …) for
`4.23.0.0`. It takes a while — the reactor has a lot more modules than the old
4.22 branch. Grab the two you actually need to install:

```bash
ls /home/nacl/dbaas-v2-deb/cloudstack-management_4.23.0.0*.deb
ls /home/nacl/dbaas-v2-deb/cloudstack-ui_4.23.0.0*.deb
```

If the build fails partway through (killed, out of memory, etc.), clean the
module it died on before retrying:

```bash
rm -rf plugins/<path-to-module>/target
```

## 2. Before you deploy — back up first

**This is a schema change.** `dbaas_credentials` on the live host was created
by the old v1 plugin and does **not** have the new `status`, `status_message`,
`report_token_hash`, `report_token_expires_at` columns. `CREATE TABLE IF NOT
EXISTS` in `ensureCredentialsTableExists()` will **not** add them to an
existing table — it only creates the table if it's missing entirely.

Pick one before starting the new management server:

```sql
-- Option A: you don't care about existing dbaas_credentials rows (test data)
DROP TABLE dbaas_credentials;
-- management server recreates it with the full v2 schema on next start

-- Option B: you want to keep the rows, add the columns by hand
ALTER TABLE dbaas_credentials
  ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'confirmed',
  ADD COLUMN status_message VARCHAR(1024) DEFAULT NULL,
  ADD COLUMN report_token_hash CHAR(64) DEFAULT NULL,
  ADD COLUMN report_token_expires_at DATETIME DEFAULT NULL;
```

Also dump it regardless, just in case:

```bash
mysqldump -u cloud -p cloud dbaas_credentials > dbaas_credentials.bak.sql
```

## 3. Deploy

Both packages together — `cloudstack-management.deb` also embeds the UI at
`/usr/share/cloudstack-management/webapp/`, but the standalone
`cloudstack-ui.deb` is still worth installing to be sure it's not stale.

```bash
sudo dpkg -i /home/nacl/dbaas-v2-deb/cloudstack-management_4.23.0.0*.deb
sudo dpkg -i /home/nacl/dbaas-v2-deb/cloudstack-ui_4.23.0.0*.deb
sudo systemctl restart cloudstack-management
```

Watch the log for the plugin coming up clean:

```bash
sudo tail -f /var/log/cloudstack/management/management-server.log | grep -i dbaas
```

You want to see the credentials table check pass and the cleanup sweep
schedule itself (`credentials cleanup sweep scheduled every 3600 s`), with no
`ERROR` lines mentioning `dbaas`.

## 4. Global settings (Infrastructure > Global Settings)

| Setting | What to set it to | Why |
| --- | --- | --- |
| `dbaas.config.path` | Default is fine (`/usr/share/cloudstack-management/extensions/dbaas/config.json`) unless you moved the folder | Where the engines map lives |
| `dbaas.report.api.url` | Your management API URL, reachable **from instance networks** — e.g. `http://10.60.0.1:8080/client/api` | Instances call this to report provisioning success/failure. **If you leave this empty, every config-drive credential stays `pending` forever** — Show Password will never confirm anything. |
| `dbaas.report.token.ttl` | Default `3600` is fine | How long an instance has to report back before its token expires |
| `dbaas.credentials.cleanup.interval` | Default `3600` is fine | How often expunged-instance credential rows get swept |

Copy `extensions/dbaas/config.json` from `extensions/dbaas/config.example.json`
to wherever `dbaas.config.path` points, if it isn't there already.

Restart the management server after changing `dbaas.report.api.url` (it's read
once per `createDatabase` call, but a clean restart avoids any doubt).

## 5. Network offering — enable Config Drive

This is the part that makes the whole rewrite work. The network the DBaaS
instances deploy onto **must** serve user data and the instance password
through Config Drive, not through the virtual router. If it doesn't,
`updateVirtualMachine(userdata=...)` still succeeds, but the instance never
sees it (no VR metadata route needed, but also no config drive attached).

1. **Network offering** used by your DBaaS network (or a new one, if the
   provider can't be changed on an offering already in use):
   - Infrastructure > Network Offerings > (your offering) — or create a new
     one with the same guest type (Shared/Isolated) you use today.
   - Under services, set:
     - **UserData provider** = `ConfigDrive`
     - **Password provider** = `ConfigDrive`
2. If your existing `dbaas-network` was created from an offering that can't
   switch providers after the fact, create a new network from the ConfigDrive
   offering and point new deploys at it (`listNetworks` / the wizard's network
   dropdown).
3. Sanity check after deploying a test instance:
   ```bash
   sudo virsh domblklist <instance-name>
   ```
   You should see a `cdrom`/config-drive device attached, separate from the
   root disk.

## 6. Build the mysql template

The engine SQL scripts (`extensions/dbaas/provisioning/mysql.sh` etc.) are
unchanged from v1 — only how they're invoked changed. The existing
`dbaas-mysql` template already has MySQL installed and configured from the v1
build; you only need to add the config-drive files to a **copy** of its disk
— never edit the live template in place, a mistake there breaks every future
deploy with no easy rollback.

Found on `cloudstackcve` (yours may differ — re-check with the query below if
you rebuild this on another host):

```sql
-- template id for dbaas-mysql, and where its disk actually lives
SELECT id, name, uuid FROM cloud.vm_template WHERE name='dbaas-mysql';
-- -> id 204, uuid 0dbba443-6278-4c8d-b74d-4bd53a099cb0
SELECT tsr.install_path, pool.path FROM cloud.template_spool_ref tsr
  JOIN cloud.storage_pool pool ON pool.id=tsr.pool_id WHERE tsr.template_id=204;
-- -> primary storage: /export/primary/0dbba443-6278-4c8d-b74d-4bd53a099cb0
```

Offline edit via `qemu-nbd` (no VM boot needed — `qemu-nbd` and the `nbd`
kernel module are already present on this host):

```bash
# 1. Work on a COPY, never the live file
sudo cp /export/primary/0dbba443-6278-4c8d-b74d-4bd53a099cb0/*.qcow2 \
  /home/nacl/dbaas-mysql-v2.qcow2

# 2. Attach it as a block device
sudo modprobe nbd max_part=8
sudo qemu-nbd --connect=/dev/nbd0 /home/nacl/dbaas-mysql-v2.qcow2
sudo partprobe /dev/nbd0
lsblk /dev/nbd0                     # find the root partition, usually /dev/nbd0p1

# 3. Mount and inject the files
sudo mkdir -p /mnt/dbaas-tmpl
sudo mount /dev/nbd0p1 /mnt/dbaas-tmpl
sudo mkdir -p /mnt/dbaas-tmpl/opt/dbaas /mnt/dbaas-tmpl/var/lib/dbaas
sudo cp extensions/dbaas/provisioning/mysql.sh /mnt/dbaas-tmpl/opt/dbaas/
sudo cp extensions/dbaas/provisioning/mysql_reset.sh /mnt/dbaas-tmpl/opt/dbaas/
sudo cp extensions/dbaas/provisioning/firstboot.sh /mnt/dbaas-tmpl/opt/dbaas/
sudo chmod +x /mnt/dbaas-tmpl/opt/dbaas/mysql.sh \
              /mnt/dbaas-tmpl/opt/dbaas/mysql_reset.sh \
              /mnt/dbaas-tmpl/opt/dbaas/firstboot.sh
echo mysql.sh | sudo tee /mnt/dbaas-tmpl/opt/dbaas/engine

# 4. Confirm cloud-init's ConfigDrive datasource is enabled
sudo grep -r "datasource_list" /mnt/dbaas-tmpl/etc/cloud/cloud.cfg.d/ 2>/dev/null
# either no restrictive datasource_list at all, or one that includes ConfigDrive

# 5. Unmount and detach cleanly, in this order
sudo umount /mnt/dbaas-tmpl
sudo qemu-nbd --disconnect /dev/nbd0
sudo rmmod nbd    # optional, only if nothing else needs the module
```

Register the result as a **new** template pointing at
`/home/nacl/dbaas-mysql-v2.qcow2` (step 7) rather than overwriting
`dbaas-mysql` — you can point `listDbaasEngines`/`config.json`'s
`dbaas-mysql` entry at whichever template name you actually register, or
register it under the same name `dbaas-mysql` once you're done testing and
want to replace the old one for real.

## 7. Register the template

```
registerTemplate name="dbaas-mysql" displaytext="MySQL (config-drive)" \
  format=QCOW2 hypervisor=KVM ostype=... url=<your image url> \
  zoneid=<zone> ispublic=true passwordenabled=true
```

`passwordenabled=true` is the important flag — it's what makes CloudStack
generate and manage the instance's own login password natively, replacing
v1's `vmaccess.sh`. There is no `provision_mode` field to set anywhere: v2
only has the config-drive path.

## 8. End-to-end test

1. Database section > Create Database Instance, pick the `dbaas-mysql`
   template, deploy it onto the ConfigDrive-enabled network.
2. Confirm in the API log that `createDatabase` attached user data and started
   the instance (`grep dbaas management-server.log`).
3. Inside the instance (console, not SSH — the whole point is you shouldn't
   need SSH): `cat /var/lib/dbaas/result.json` should show `"status":
   "confirmed"`.
4. Back in the UI, Show Password should show `status: confirmed` and a
   working password within a few seconds of the instance reporting back.
5. **The real test**: repeat step 1 on a network the management server has no
   route to (or with its virtual router stopped). It should still work — that
   was the entire point of this rewrite.

## 9. Known gaps (see PLAN.md for detail)

- **Reset Database Password** doesn't work yet and is hidden from the UI —
  needs the in-VM agent (Phase D), not built.
- Only `mysql` has a template ready; `mariadb`/`postgresql`/`mongodb` need the
  same steps 6–7 repeated with their own scripts.
- `README.md` / `INSTALL.md` / `TEMPLATES.md` in this plugin directory still
  describe the old SSH architecture — don't follow them for this branch,
  follow this file and PLAN.md instead.
