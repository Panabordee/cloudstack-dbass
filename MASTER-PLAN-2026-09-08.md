# Master plan — everything outstanding, 2026-09-08

Full catalog of open work across the project, gathered in one place because
the individual reports and plans are now scattered across many dated files.
Ordered by priority, with the reasoning for that order. Each item links to
its own detailed document where one exists; items with no prior document get
enough detail here to start from cold.

Two GitHub cleanup items are blocked on the user — see §0.

---

## 0. GitHub cleanup — 2 of 9 branches need you, everything else is done

Done: `4.23+dbass` created from current work and pushed;
`backup`, `panabordee/dbaas-full`, `panabordee/dbaas-ux-fixes`,
`panabordee/dbaas-v2`, `ui`, `v2` all deleted from `myfork`
(`git@github.com:Panabordee/cloudstack-dbass.git`).

**Blocked, needs you:**

1. **`main`** — GitHub refuses to delete a repo's default branch over plain
   git (`refusing to delete the current branch: refs/heads/main`), and there
   is no `gh` CLI or API token available in this environment to change that
   setting programmatically. On GitHub: Settings → General → Default branch
   → switch to `4.23+dbass` → then either tell me to delete `main`, or run
   `git push myfork --delete main` yourself.
2. **`panabordee/dbaas-v1-archive`** — the auto-mode classifier specifically
   blocked this one delete (it let every other branch deletion through
   without asking). Given this branch is the *only remote* copy of the
   retired v1 SSH-transport code, that caution looks correct rather than
   overzealous, so I stopped instead of finding a way around it. It is not
   the only copy overall — `~/cloudstack-ui-src` on this host still has the
   full v1 history locally — but it is the only one on GitHub. Confirm you
   still want it gone, or run `git push myfork --delete panabordee/dbaas-v1-archive`
   yourself if so.

Commit attribution going forward, per your instruction: no `Co-Authored-By`
trailer, committer stays the existing `SnowFlex <68010697@kmitl.ac.th>`
identity already configured in this repo.

---

## 1. Provisioning silently fails on any instance's second boot (highest priority)

Full design: `PLAN-PROVISION-TRIGGER-FIX.md`. Being implemented now, this
session — see the commit after this plan for status.

`createDatabase` on an instance that has already booted once (including the
documented, shipped "create database on a running instance" feature) does
nothing at all, with no error anywhere. Root cause is two independent bugs:
cloud-init reuses its cached datasource from the first boot instead of
re-reading the reattached config drive, and `firstboot.sh`'s own
`DONE_MARKER` would block a second request even if that were fixed. Fix:
stop depending on cloud-init's `runcmd`/`write_files`; a systemd unit reads
the config drive itself every boot, keyed on a hash of the request rather
than a boolean "ever ran" marker.

**Why first:** it breaks a feature already presented to tenants as working,
fails with zero signal, and every existing test in the project's history
happened to avoid triggering it by accident, not by design — meaning it has
been broken since the feature shipped and nothing caught it until tonight.

## 2. Rebuild all four templates in one pass

Bundle these together — one careful rebuild beats four separate ones:

- item 1's fix (`dbaas-provision.service`, updated `firstboot.sh`)
- the `_ro` read-only console role in the engine scripts (only landed in
  source, never baked into any image)
- the current `dbaas_agent.py` — the 9 job-type handlers and the response
  wrapper fix only exist on template 210; 211/212/213 still run the agent
  that only implements `"sql"` and drops every job silently
- `report-retry.sh` + its systemd units — same gap, only 210 has them

Order: 210 (mysql) first since it is the only proven baseline, prove the
full acceptance pass on it again after the rebuild (this changes its image,
so its earlier "proven" status does not carry over automatically), then
211 → 212 → 213.

## 3. The isolated-network claim is still unverified

`DbaasIsolatedConfigDrive` network offering exists but is incomplete: only
`UserData` is provided by `ConfigDrive`, `Dhcp` and `Dns` are still
`VirtualRouter` (`ACCEPTANCE-FIX-2026-09-05.md` "Fix 1b" called for all
three). Confirmed live tonight that CloudStack's own orchestration will not
let a guest start with that network's VR down — it auto-heals the VR as a
side effect of any VM start, precisely because DHCP still depends on it.

To actually test "isolated network, VR down, still works": finish the
offering (ConfigDrive as Dhcp + Dns + UserData), build a network from it,
and repeat the test. Until then, PLAN.md's original claim about surviving a
broken VR is proven only for the Shared network DBaaS actually deploys to
today — which is a real, correctly-scoped, already-verified result, just not
the harder one.

## 4. Log hygiene — SQL text and query results are readable in `management-server.log`

`RunDbaasQueryCmd`/`GetDbaasJobResultCmd` correctly declare
`requestHasSensitiveInfo`/`responseHasSensitiveInfo`, but those flags only
mask the audit-event/API-response layer, not `ApiServlet`'s raw DEBUG-level
request/response logging — and this host's `log4j2.xml` runs `com.cloud` /
`org.apache.cloudstack` at `DEBUG`. Reproduced with a canary literal; both
the query and its result appeared in plaintext in the log.

This needs a decision, not a quick patch: lower this host's log level (loses
DEBUG visibility for everything, not just DBaaS) versus a redacting filter
scoped to these two commands (more code, narrower blast radius). Given no
strong signal either way from this session, my inclination is the redacting
filter — it doesn't trade away debuggability for the rest of the platform —
but this is worth 30 seconds of your judgment before it's built.

## 5. Per-engine OOM memory floor — only mysql-v2 is actually measured

`dbaas.offering.minmemory.mb` / each engine's `minmemorymb` in `config.json`
all currently read `1024`, but that number was only measured for
`dbaas-mysql-v2` (OOM-killed at 512 MB, clean at 1024 MB, reproduced twice).
mariadb, postgresql and mongodb carry the same number as an unverified
starting point — postgresql and mongodb in particular are plausibly heavier.
Measure each the same way once its template is rebuilt (§2): deploy, run a
console session, watch `journalctl -u <engine>` for `oom-kill`.

## 6. Untested matrix items

From `PLAN-DBAAS-CONSOLE.md` §11, still not run:

- **item 10** (cross-account ACL denial) — needs a second CloudStack account,
  which didn't exist and wasn't created without asking; trivial once one
  exists
- **item 11** (replayed agent token after rotation)
- **item 14** (job expiry with the agent stopped)

## 7. The console has never been clicked through in an actual browser

Every test all session went through the raw API (`cmk`). The UI was deployed
live tonight (§ of `ACCEPTANCE-REPORT-2026-09-08-PART2.md`) and every
server-side/bundle-content check that doesn't need a browser has been done,
but nobody has actually opened the page and clicked Create Table. Worth 10
minutes before trusting it in front of a real tenant.

## 8. Reset Database Password (PLAN.md Phase D, PLAN-DBAAS-CONSOLE.md C5)

The in-VM agent now exists and works (tonight's fixes proved it end to end).
`resetDatabasePassword` still throws `CloudRuntimeException` naming the gap,
and the UI hides the action. Wiring this up is now mostly free — a
`password_reset` job type reusing the transport that already works — but it
was explicitly deferred behind C0–C4 and hasn't been picked up.

## 9. VM login password — tenant still can't choose or receive one

Diagnosed in `VM-PASSWORD-DEFECT-2026-09-05.md`, root cause and fix both
written up, never implemented. `createDatabase` deploys with
`startvm=false` and the plugin starts the instance through the internal
two-argument path, so `Param.VmPassword` never gets populated regardless of
what the tenant requests. Fix: call `resetPasswordForVirtualMachine` (or set
`Param.VmPassword` on the plugin's own start call) before the config-drive
attach, while the instance is Stopped.

## 10. Drop table stays disabled until there is a restore path

`dbaas.console.drop.enabled` correctly ships `false` and must stay that way
until per-database backup/PITR exists (`NEON-ROADMAP.md` N3, not started).
Not urgent, but the dependency should stay visible: nobody should flip that
flag on as a quick fix for something else without noticing this line.

## 11. Stale documentation

`README.md`, `INSTALL.md`, `TEMPLATES.md` under
`plugins/integrations/dbaas/` still describe the retired v1 SSH transport
architecture. `PLAN.md` and `README-BUILD-DEPLOY.md` are the accurate
sources today. Low urgency, but every session so far has had to re-discover
this the hard way; worth a pass once the architecture stops moving weekly.

---

## Sequencing

```
1 (provision trigger)  ──┐
                          ├──▶ 2 (rebuild all 4 templates, one pass)
_ro role, agent, retry ──┘         │
                                    ▼
                          per-engine OOM measurement (5)
                          full matrix incl. isolated network (3, 6)
                          browser click-through (7)
```

4, 8, 9, 10, 11 are independent of the above and can be picked up in any
order, in parallel with the template work if more than one person/session is
available.
