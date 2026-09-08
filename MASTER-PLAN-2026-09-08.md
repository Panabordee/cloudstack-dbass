# Master plan — everything outstanding, 2026-09-08

Full catalog of open work across the project, gathered in one place because
the individual reports and plans are now scattered across many dated files.
Ordered by priority, with the reasoning for that order.

Every item below is written to be **self-contained**: what is wrong, where it
is in the tree, what to do, how to verify it, and what "done" means. Where a
longer design document already exists it is linked, but you should not need
to read it to start.

Baseline for all of this: branch `4.23+dbass`, commit `2bbd24ede6`, host
`cloudstackcve`, repo `/home/nacl/dbaas-v2`.

---

## 0. Standing rules — read before touching anything

These carried through every session and still hold.

**Never, without asking first:**

- delete or modify `/export/primary/tplbackup/**` — it is the template
  rollback path, and its pre-existing contents are not yours
- touch `DATA-73` — it is the evidence for the data-disk defect
- destroy or modify any instance you did not create yourself
- flip `dbaas.console.drop.enabled` or `dbaas.datadisk.cleanup.enabled` to
  `true`; both ship `false` deliberately
- weaken any default, widen any permission, or disable any check to make a
  test pass. A test that fails honestly is a result; one that passes because
  the guard was removed is a lie that costs a day later
- `git push --force`, or any history rewrite

**Always:**

- engine, script and port mappings live in `extensions/dbaas/config.json`,
  never in a dict or switch in `.py`/`.java`. This rule is why adding an
  engine is a config edit today
- commits carry **no `Co-Authored-By` trailer**; author stays
  `SnowFlex <68010697@kmitl.ac.th>` (or the Panabordee identity). This is a
  hard requirement from the repo owner
- every host change goes in the report with the command used and how to
  undo it. If you cannot state the undo, do not run the command
- when scope balloons past what the item describes: **report it, do not
  chase it**. A precise "here is what I found and why I stopped" is worth
  more than a half-finished second feature

## 0b. GitHub state — closed, no action needed

`4.23+dbass` is the working branch and is pushed. Six old branches were
deleted from `myfork` (`git@github.com:Panabordee/cloudstack-dbass.git`).
Two remain by decision:

- **`main`** — GitHub refuses to delete a repo's default branch over plain
  git, and there is no `gh` CLI or API token in this environment
- **`panabordee/dbaas-v1-archive`** — the only remote copy of the retired v1
  SSH-transport code

The owner has accepted leaving both. Do not try again.

---

## 1. Provisioning silently fails on any instance's second boot

**Status: code done and committed (`2bbd24ede6`), not baked into any image.**
Design: `PLAN-PROVISION-TRIGGER-FIX.md`. What remains is entirely host work
and is bundled into item 2.

### What is wrong

`createDatabase` against an instance that has already booted once — including
the documented, shipped "create a database on a running instance" feature —
does nothing at all, with no error anywhere, and the credential row sits
`pending` forever. Two independent causes:

1. cloud-init restores its cached datasource from the instance's *first*
   boot instead of re-reading the reattached config drive, so `write_files`
   never writes `/var/lib/dbaas/request.json` and `runcmd` never runs
   `firstboot.sh`
2. even with (1) fixed, `firstboot.sh`'s old `DONE_MARKER`
   (`/var/lib/dbaas/provisioned`) is a boolean "has this VM ever
   provisioned", so a second request could never be accepted

### What was done

- `extensions/dbaas/provisioning/dbaas-provision.service` — new oneshot unit,
  runs `/opt/dbaas/firstboot.sh` on **every** boot, `After=network-online.target`
  only (deliberately not ordered after any engine's unit — one file is shared
  by all four templates)
- `firstboot.sh` gained `refresh_request_from_configdrive()`: finds the drive
  by label (`blkid -t LABEL=config-2`, not a hardcoded `/dev/sr0` — the real
  log showed `/dev/sr1`), mounts read-only, extracts the request from
  `openstack/latest/user_data`, and **validates it parses as JSON before
  trusting it**
- `DONE_MARKER` replaced by `/var/lib/dbaas/processed.sha256`, the SHA-256 of
  the last successfully provisioned request. Hash matches → skip and delete
  the extracted `request.json` (it holds a cleartext password); differs →
  provision; written only on success, so a failure retries next boot

### How to verify (must be observed, not reasoned about)

1. **The bug**: deploy an instance, start it, let it boot fully, *then*
   `createDatabase` → reaches `confirmed`, database and both roles exist
2. **Second database on the same instance**: different name → provisions,
   does not touch the first
3. **Plain reboot**: engine SQL does not re-run (no second `CREATE USER` in
   the engine log), data intact, `processed.sha256` unchanged
4. **First-boot path**: the normal wizard flow (`startvm=false` →
   `createDatabase`) still behaves exactly as today
5. **Failure retry**: force the engine script to fail once → no hash written,
   next boot retries
6. `journalctl -u dbaas-provision` tells a readable story in each case

Case 1 has never been tested by anyone: every test in this project's history
deployed with `startvm=false` and called `createDatabase` immediately, always
that instance's first boot. Add it explicitly.

## 2. Rebuild all four templates in one pass

**This is the single biggest blocker.** Four separate pieces of finished work
exist only in the repo and in no image; each one alone would justify a
rebuild, so do them together.

Templates: `210` = dbaas-mysql-v2, `211` = mariadb, `212` = postgresql,
`213` = mongodb, under `/export/secondary/template/tmpl/2/<id>/` plus the
primary cache. UUIDs: mysql `1d9e7b9c-afc2-4cfd-92a0-6a23ca5dfd48`, mariadb
`8ceff582-71ee-41c9-aa8d-82f0a72fc488`, postgresql
`1020609f-1c71-4003-b758-93e3debd61c0`, mongodb
`a645211d-8b18-4842-bcbe-50ac0eb7db9f`.

### What every image needs

| Path in image | Source in repo | Mode |
| --- | --- | --- |
| `/opt/dbaas/firstboot.sh` | `extensions/dbaas/provisioning/` | 0755 |
| `/opt/dbaas/report-retry.sh` | same | 0755 |
| `/opt/dbaas/<engine>.sh`, `<engine>_reset.sh` | same (now with the `_ro` role) | 0755 |
| `/opt/dbaas/engine` | copy of `<engine>.sh` | 0644 |
| `/opt/dbaas/agent/dbaas_agent.py` | `extensions/dbaas/agent/` | 0755 |
| `/etc/systemd/system/dbaas-provision.service` | `extensions/dbaas/provisioning/` | 0644 |
| `/etc/systemd/system/dbaas-report-retry.{service,timer}` | same | 0644 |
| `/etc/systemd/system/dbaas-agent.service` | `extensions/dbaas/agent/` | 0644 |
| `/etc/systemd/network/99-dbaas-fallback.network` | `extensions/dbaas/provisioning/` | 0644 |

Enable symlinks: `dbaas-provision.service` and `dbaas-agent.service` into
`multi-user.target.wants/`, `dbaas-report-retry.timer` into
`timers.target.wants/`. `dbaas-report-retry.service` is **not** enabled — the
scripts start it on demand and it disables itself once the report lands.

Engine client library, needed by the agent:

- mysql / mariadb: `python3-pymysql`
- postgresql: `python3-psycopg2`
- mongodb: `python3-pymongo`

Only 210 currently has the agent, the retry units, or the current
`dbaas_agent.py`. 211/212/213 run the old agent that implements only `"sql"`
and drops every other job silently. None of the four has the `_ro` role or
`dbaas-provision.service`.

### Method

Offline `qemu-nbd`, exactly as in `RUNBOOK-PATCH-TEMPLATES-2026-09-05.md`:
back up the image first, `qemu-nbd --connect=/dev/nbd0 -f qcow2`, mount
`nbd0p1` at `/mnt/tplpatch`, copy files in, then **`sync` → `umount` →
`qemu-nbd -d`, in that order, always** — the wrong order corrupts the image.
`qemu-img check` after. Patch both the secondary copy and the primary cache.

Installing packages is the one thing that recipe cannot do. Two options —
pick one and say which in the report:

1. boot a VM from the template, `apt-get install`, re-template. Correct,
   slower, and it gives you a live instance to test the agent on first
2. `chroot` into the mounted image with `/dev`, `/proc`, `/sys` and
   `/etc/resolv.conf` bind-mounted. Faster, but a package manager that
   thinks it is a running system — check `dpkg -l` after and confirm nothing
   started a service

### Order and done-criteria

210 (mysql) first: it is the only proven baseline, but the rebuild changes
its image, so its earlier "proven" status does **not** carry over. Run item
1's full acceptance on it plus one console round trip. Only then 211 → 212 →
213, each with deploy → `confirmed` → one console job.

Verifying 210 before touching the other three is the difference between one
mistake and four.

## 3. The isolated-network claim is still unverified

`DbaasIsolatedConfigDrive` exists and is `Enabled`, but today reads:

```
UserData      ConfigDrive
Dhcp          VirtualRouter     <-- should be ConfigDrive
Dns           VirtualRouter     <-- should be ConfigDrive
SourceNat, Firewall, PortForwarding   VirtualRouter (correct, must stay)
```

`ACCEPTANCE-FIX-2026-09-05.md` "Fix 1b" called for all three of UserData,
Dhcp and Dns on ConfigDrive. It is constructible: `ConfigDriveNetworkElement`
(`server/src/main/java/com/cloud/network/element/ConfigDriveNetworkElement.java:203`)
advertises capabilities for `Service.UserData`, `Service.Dhcp` and
`Service.Dns`. It also matters functionally — CloudStack only writes
`network_data.json` into the config drive when ConfigDrive is the Dhcp/Dns
provider, so with the offering as it stands the guest has no static network
configuration to fall back on.

Confirmed live on 2026-09-08 that with the current offering, CloudStack's own
orchestration will not let a guest start with that network's VR down: it
auto-heals the VR as a side effect of any VM start, precisely because DHCP
still depends on it. So the test is not merely unrun — it is currently
impossible to run.

**Do:** create a new offering with Dhcp + Dns + UserData all on ConfigDrive
(a network offering's services cannot be edited after creation — make a new
one), build a network from it, deploy a DBaaS instance onto it, then stop the
VR and repeat the console matrix.

**Done when:** with the VR stopped, a tenant on an isolated network can still
browse tables, run a query and create a table — or, if they cannot, the
report says so plainly and names which step fails. Either answer is a result.
Until then, PLAN.md's "survives a broken VR" claim is proven only for the
Shared network DBaaS actually deploys to today, which is real and
correctly-scoped, just not the harder case.

## 4. Log hygiene — SQL text and query results are readable in `management-server.log`

`RunDbaasQueryCmd` and `GetDbaasJobResultCmd` correctly declare
`requestHasSensitiveInfo` / `responseHasSensitiveInfo`, but those flags only
mask the audit-event and API-response layer — not `ApiServlet`'s raw
DEBUG-level request/response logging. This host's `log4j2.xml` runs
`com.cloud` and `org.apache.cloudstack` at `DEBUG`. Reproduced with a canary
literal: both the query text and its result rows appeared in plaintext.

Two options; **build the second** unless the owner says otherwise:

1. lower this host's log level — one line, but loses DEBUG visibility for the
   whole platform, not just DBaaS
2. a redacting filter scoped to these two command names — more code, but it
   does not trade away debuggability everywhere else to fix one leak

**Done when:** running a query containing a distinctive literal, then
`grep`ping `management-server.log` for that literal *and* for the returned
row values, produces empty output — with the command and its empty output
pasted into the report — and DEBUG logging still works for everything else.

## 5. Per-engine OOM memory floor — only mysql-v2 is measured

`dbaas.offering.minmemory.mb` is `1024`, and every engine in
`config.example.json` carries `minmemorymb: 1024`. That number was measured
only for `dbaas-mysql-v2` (OOM-killed at 512 MB, clean at 1024 MB, reproduced
twice). mariadb, postgresql and mongodb carry it as an **unverified starting
point** — postgresql and mongodb are plausibly heavier.

This number is now load-bearing: the UI filters the service-offering dropdown
by it (`CreateDatabaseInstance.vue`, driven by `listDbaasEngines.minmemorymb`),
so a wrong value either hides valid offerings or lets a tenant pick one that
OOMs.

**Do**, once each template is rebuilt (item 2): deploy on the smallest
offering, provision, run a console session with a real query, and watch
`journalctl -u <engine>` and `dmesg` for `oom-kill`. Step up until clean.
Record the measured floor per engine in `config.example.json` and drop the
`_comment_minmemory` note once all four are real numbers.

## 6. Untested matrix items

From `PLAN-DBAAS-CONSOLE.md` §11, still not run:

- **item 10 — cross-account ACL denial.** Needs a second CloudStack account,
  which did not exist and was not created without asking. Create one, deploy
  an instance as account A, and confirm account B gets a clean denial (not a
  stack trace, not a leak of the instance's existence) from every console
  command
- **item 11 — replayed agent token after rotation.** Capture a token, force a
  rotation, replay the old one, confirm it is refused and the refusal is
  logged
- **item 14 — job expiry with the agent stopped.** Stop `dbaas-agent` on the
  guest, submit a job, confirm it expires cleanly rather than hanging
  forever, and that the UI shows a comprehensible state

## 7. The console has never been clicked through in a browser

Every test all session went through the raw API (`cmk`). The UI is built and
deployed, and every server-side and bundle-content check that does not need a
browser is done — but nobody has opened the page and clicked Create Table.

**Do:** open the UI, and for one instance walk: instance detail → console tab
→ table list → describe a table → preview rows → run a query → create a
table → confirm Drop is absent or disabled. Screenshot each. Ten minutes,
and it is the last thing between this and a real tenant.

Specifically watch the service-offering filter (item 5's consumer): the
dropdown must stay disabled until an engine is chosen, must show only
offerings with memory ≥ the engine's floor, must clear a stranded selection
when the engine changes, and must show the warning paragraph when nothing
qualifies.

## 8. Reset Database Password

`DbaasManagerImpl.java:1367` still throws:

```java
throw new CloudRuntimeException("resetting a database password requires the in-VM agent (PLAN.md Phase D),"
    + " which does not exist yet -- ...");
```

That comment is now out of date: the in-VM agent exists and was proven end to
end on 2026-09-08. The UI file `ui/src/views/compute/ResetDatabasePassword.vue`
already exists and the action is hidden.

**Do:** add a `password_reset` job type to `dbaas_agent.py`'s `JOB_HANDLERS`
(`extensions/dbaas/agent/dbaas_agent.py:414`) reusing the transport that
already works, generate the new password server-side, dispatch it as a job,
and update `dbaas_credentials` only after the agent confirms. Re-encrypt with
the same routine `createDatabase` uses; never log the plaintext. The engine
scripts already have `<engine>_reset.sh` for the SQL shape.

**Done when:** reset from the UI, then connect with a normal DB client using
the new password, and confirm the old one is refused.

## 9. VM login password — tenant still cannot choose or receive one

Diagnosed in `VM-PASSWORD-DEFECT-2026-09-05.md`; root cause and fix both
written up, never implemented. `createDatabase` deploys with `startvm=false`
and the plugin then starts the instance through the internal two-argument
path, so `Param.VmPassword` is never populated regardless of what the tenant
requested.

**Fix:** call `resetPasswordForVirtualMachine` (or set `Param.VmPassword` on
the plugin's own start call) before the config-drive attach, while the
instance is still Stopped. **Done when:** a tenant can retrieve the VM login
password through the normal CloudStack path and it works.

## 10. Drop table stays disabled until there is a restore path

`dbaas.console.drop.enabled` ships `false` and must stay that way until
per-database backup/PITR exists (`NEON-ROADMAP.md` N3, not started). Not
urgent. Listed so nobody flips that flag as a quick fix for something else
without seeing the dependency.

## 11. Stale documentation

`README.md`, `INSTALL.md` and `TEMPLATES.md` under
`plugins/integrations/dbaas/` still describe the retired v1 SSH transport.
`PLAN.md` and `README-BUILD-DEPLOY.md` are the accurate sources today.

Low urgency, but every session so far has re-discovered this the hard way and
one of them acted on it before noticing. Worth a pass once the architecture
stops moving weekly — which, after item 2, it largely has.

---

## Sequencing

```
1 (provision trigger, code done)  ──┐
_ro role, agent, retry units      ──┼──▶ 2 (rebuild all 4 templates, one pass)
                                     │         │
                                     │         ▼
                                     │   5 (per-engine OOM measurement)
                                     │   6 (matrix items 10, 11, 14)
                                     │   7 (browser click-through)
                                     └──▶ 3 (isolated network + VR down)
```

4, 8, 9, 10 and 11 are independent of all of the above and can be picked up
in any order, in parallel if more than one session is available.

## What "finished" looks like

A tenant, on a network the management server cannot reach, with the virtual
router stopped, can: create a database on any of the four engines; browse
their tables; run a query; create a table; connect with a normal DB client;
and reset their password. Dropping a table is still disabled. No SQL text or
row value appears in `management-server.log`. Every memory floor in
`config.json` is a measured number rather than a guess.
