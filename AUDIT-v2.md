# Audit — DBaaS v2 (self-review of everything written this session)

Scope: the 12 commits from `987178ef4d` (port onto 4.23) through `a5a4cf7982`
(runbook), plus the live changes made on `cloudstackcve` (template
`dbaas-mysql-v2`, network offering `DbaasIsolatedConfigDrive`, network
`dbaas-configdrive-test`).

Verdict: the code compiles and the design holds, but **it cannot pass its own
end-to-end test yet** — P0-1 below blocks the new template from even appearing
in the wizard. Two more (P1-1, P1-2) will bite on the first real deploy.

---

## P0 — blocks the end-to-end test

### P0-1. The new template is invisible to the plugin

`config.json` on the host lists engines by **template name**:

```
['dbaas-mysql', 'dbaas-mariadb', 'dbaas-postgresql', 'dbaas-mongodb']
```

The config-drive template registered today is named **`dbaas-mysql-v2`**, which
is not in that map. Consequences, all silent:

- `listDbaasEngines` never returns it → `CreateDatabaseInstance.vue:319`
  filters templates against exactly that set → **the new template never shows
  in the engine dropdown**
- `engineConfigForVm()` returns null → `enginePort()` returns null → the
  response carries no port → the connect command in the UI is malformed
- `DatabaseInstances.isEngineMember()` returns false → instances built from it
  get no DBaaS row actions

Fix (one of):

```bash
# either add the new name to the deployed config.json
sudo python3 - <<'EOF'
import json
p = '/usr/share/cloudstack-management/extensions/dbaas/config.json'
cfg = json.load(open(p))
cfg['engines']['dbaas-mysql-v2'] = {"script": "mysql.sh", "reset_script": "mysql_reset.sh", "port": 3306}
json.dump(cfg, open(p, 'w'), indent=2)
EOF

# or register the template under the existing name 'dbaas-mysql' instead
```

The same entry must be added to `extensions/dbaas/config.example.json` in the
repo, or the next deployment loses it again.

---

## P1 — will fail on a real deploy

### P1-1. Nothing waits for the database engine before provisioning it

`buildUserData()` emits `runcmd: [ /opt/dbaas/firstboot.sh ]`. cloud-init's
`runcmd` fires in the *final* boot stage, which races `mysql.service`
starting. `mysql.sh`'s very first action is a socket connection:

```sh
USER_EXISTS=$(mysql --protocol=socket -uroot -N -B -e "SELECT COUNT(*) ...")
```

There is no wait loop before it (the `seq 1 10` loop at `mysql.sh:63` only runs
*after* a bind-address restart, much later). If mysqld isn't accepting
connections yet, provisioning fails on first boot.

Worse, it fails **permanently**: `runcmd` is a first-boot-only module, so a
reboot does not retry, and firstboot.sh's own `provisioned` marker logic never
gets a second chance. The tenant is left with `status=failed` and no path
forward except Create Database again (which now restarts the instance).

Fix: wait for the engine in `firstboot.sh` before invoking the engine script —
e.g. poll `mysqladmin --protocol=socket -uroot ping` (or a per-engine readiness
command supplied by the image) for ~120s, and only then run it. A systemd unit
with `After=mysql.service` would be sturdier than `runcmd` long-term.

### P1-2. A stopped instance can be left stopped with no database

`createDatabase()` stops a running instance (line 411), then does three things
that can each throw: state re-check (418), `updateVirtualMachine` (437),
`startVirtualMachine` (468). On any of those failures the method throws and
**never restarts the instance it stopped**. The tenant asked for a database and
got an outage.

The state re-check is the most likely to trip: `stopVirtualMachine` returns and
we immediately re-read the VM through `EntityManager`, which may still hand
back the pre-stop state. That path throws `InvalidParameterValueException`
saying the instance "must be Stopped" — right after we stopped it.

Fix: wrap everything after the stop in try/catch and attempt
`startVirtualMachine` on failure before rethrowing; and poll for the Stopped
state with a short timeout instead of reading once.

### P1-3. A long failure message silently voids the report

`dbaas_credentials.status_message` is `varchar(1024)`.
`firstboot.sh` reports the engine script's entire captured stdout+stderr as
`message`. On a real engine failure that easily exceeds 1024 characters →
`UPDATE` fails with data truncation under strict mode → `applyProvisioningReport`
returns false → **the token is not consumed and the credential stays `pending`
forever**, i.e. the one case where the report matters most is the one that
loses it.

Fix: truncate server-side in `applyProvisioningReport` (e.g. first 1000 chars)
and client-side in `report_result`.

### P1-4. Nothing stops a v1 template being deployed into the v2 flow

The wizard now always sends `startvm=false` and `createDatabase` always takes
the config-drive path — but nothing checks that the selected template actually
*contains* `firstboot.sh`. Deploying an old `dbaas-mysql` (v1, SSH-only image)
succeeds, attaches user data nothing will read, starts the instance, and leaves
the credential `pending` forever with no error anywhere.

Fix: mark config-drive-capable images (template detail/tag, or simply the
`engines` map entry gaining `"config_drive": true`) and reject the rest with a
clear message.

---

## P2 — smaller

- **`createDatabase` javadoc lies**: says the caller "sees it in the response's
  message" for the restart; `DbaasResponse` has no message field and none is
  set. The only warning is the pre-submit banner in `CreateDatabase.vue`.
- **`response.setHost(primaryIpAddress(vm))`** reads the NIC before the start;
  for a never-started instance this can be null, leaving the UI's connect
  command blank on the success screen.
- **Token expiry compares DB clock to management-server clock**:
  `report_token_expires_at` is computed with `System.currentTimeMillis()` but
  checked with `NOW()`. Clock skew between the two shifts the real TTL.
- **Show Password gives up after 2 minutes** (`maxAutoChecks: 12` × 10s). A
  config-drive provision now includes a full boot; 2 minutes is optimistic, and
  the exhausted state tells the user provisioning "may have failed" while it is
  probably still booting.
- **`message.dbaas.waiting.engine`** ("Waiting for the database engine to
  start…") is shown while the plugin is actually attaching user data and
  starting the instance — the engine hasn't been asked to do anything yet.
- **No rate limiting on `reportDbaasProvisioningResult`** (already recorded in
  PLAN.md Phase C). The token itself is 256-bit so guessing is not the worry;
  request flooding is.
- **Dead branch**: `DatabaseInstances.vue` still imports and renders a
  `resetDatabasePassword` modal branch that `rowActions()` can no longer emit.

---

## What is actually verified

- `mvn -pl plugins/integrations/dbaas -am install` — clean, no errors
- Every touched `.vue`/`.js` parses (`node --check` on the extracted script
  blocks)
- `en.json` valid, 4549 keys, diff kept to 9 lines (no repeat of the v1
  whole-file reformat that caused the merge conflict)
- Template `dbaas-mysql-v2` registered, downloaded, `isready=true`,
  `passwordenabled=true`, and its image really contains `/opt/dbaas/firstboot.sh`
  + `/opt/dbaas/engine` (verified by mounting the copy before registering)
- Network `dbaas-configdrive-test` reports `UserData → ConfigDrive`
- The pre-existing `dbaas-network` and template `dbaas-mysql` (v1) are
  untouched — only additive changes were made on the host

## What is not verified

- **No instance has ever been deployed from this template.** Every claim about
  the config-drive path working end to end is design-level, not observed.
- The `.deb` packages have not finished building, so nothing of the new plugin
  code is running on the host yet — the management server is still on the v1
  build.
