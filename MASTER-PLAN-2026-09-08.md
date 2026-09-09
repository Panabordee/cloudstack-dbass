# Master plan — everything outstanding

Living document. Rewritten 2026-09-09 after the acceptance session at
`bca61f6720` closed most of it. Do not start a new dated plan file; update
this one.

Baseline: branch `4.23+dbass`, commit `bca61f6720`, host `cloudstackcve`,
repo `/home/nacl/dbaas-v2`.

---

## 0. Standing rules

**Never, without asking first:** delete or modify
`/export/primary/tplbackup/**`; touch `DATA-73`; destroy or modify an
instance you did not create; set `dbaas.console.drop.enabled` or
`dbaas.datadisk.cleanup.enabled` to `true`; weaken a default, widen a
permission or disable a check to make a test pass; `git push --force`.

**Always:** engine/script/port mappings live in
`extensions/dbaas/config.json`, never in a dict or switch in `.py`/`.java`.
Commits carry **no `Co-Authored-By` trailer**; author stays
`SnowFlex <68010697@kmitl.ac.th>` or the Panabordee identity. Every host
change is reported with its undo. When scope balloons: **report it, do not
chase it**.

GitHub cleanup is closed: `4.23+dbass` is the working branch; `main` and
`panabordee/dbaas-v1-archive` stay by decision.

---

## Done — verified by observation, not by claim

| Was item | What is now true | Evidence |
| --- | --- | --- |
| 1 provisioning on second boot | all six cases observed on 210, including the case never tested before and the failure→retry case | `ACCEPTANCE-REPORT-2026-09-09.md` §2 |
| 2 rebuild four templates | all four images rebuilt 2026-09-09 07:51, secondary + primary cache, backups taken, `qemu-img check` clean | §4, image mtimes |
| — deploy on all four engines | mysql/mariadb/postgresql/mongodb all provision to `confirmed` and run a real console job | §5 |
| 5 memory floors | measured, not guessed: mariadb/postgresql/mongodb clean at **512 MB**, mysql stays **1024 MB**; `config.example.json` updated | §8 |
| 6 matrix 10/11/14 | cross-account denial clean, replayed token 403 + logged, job expiry reported immediately | §6 |
| — 11 defects | found and fixed this session, `4f0d2928a1`–`d51018873d` | §3 |

The console works end to end **through the API** on all four engines, as
`_ro` for reads and owner for writes, with the engine itself refusing what
`_ro` may not do. A normal DB client connects from another machine.

---

## Scope — decided 2026-09-09: v1 is the three features, nothing else

The product is three things a tenant does with their database: **run a
query, create and drop a table, browse tables** — plus connecting a normal DB
client, which already works.

It is an **add-on to a credit-billed departmental cloud**, not a standalone
managed service. A DBaaS instance is an ordinary VM the user owns and pays
for; DBaaS only pre-installs and configures the engine. Everything a user can
do with an instance they created themselves — SSH in, reset the password,
attach a keypair — must also work here (items D, D2, D3).

`NEON-ROADMAP.md` proposed seven further phases (project/branch model,
same-instance branching, PITR, volume-clone branching, scale-to-zero, an HTTP
SQL endpoint). **None of them is being built.** They were proposed because
Neon was used as a comparison, not because this product needs them, and
carrying them in the plan made the remaining work look several times larger
than it is. Reasons, per phase, so this is not relitigated:

| Phase | Why it is cut |
| --- | --- |
| N0 substrate decision | a gate for N4/N6 only; with those cut there is nothing to decide |
| N1 project/branch/endpoint model | a data-model rewrite that exists to support branching; today's flat model works |
| N2 same-instance branching | useful for a dev/test workflow, irrelevant to the three features |
| N3 backup / PITR | the *goal* is kept (see below), full point-in-time restore is not |
| N4 volume-clone branching | expensive, and it is not even known whether this storage does CoW clones |
| N5 scale to zero | genuinely poor on this substrate — a 30–90 s wake exceeds most drivers' connect timeouts. Documented as the weakest item when it was proposed |
| N6 HTTP SQL endpoint | a new externally reachable surface, and there is no TLS anywhere in this design yet |

`NEON-ROADMAP.md` stays in the tree as the analysis behind that decision.
`PLAN-NEON-STEPS.md` is deleted: it sequenced work that is not being done.

### What survives from N3, in cheap form

Data-loss protection is not a Neon feature, it is table stakes for any
database service — but it does not require PITR:

1. **Dump the table before dropping it** (item C2). A single-table
   `mysqldump` / `pg_dump` to the instance's own disk before the `DROP`
   executes, plus a typed confirmation of the table name. The real risk is a
   mis-click in a console, not a need to rewind three days
2. **CloudStack's own `createSnapshotPolicy`** on the data volume. Daily
   volume snapshots, built into the platform, roughly an hour to configure

Together, roughly five hours, for most of what PITR would have given.
`dbaas.console.drop.enabled` may be turned on **only** once C2 ships.

---

## Remaining work

### A. DONE 2026-09-09 — the console works in a browser

Root cause was not the component at all: the UI was being deployed to
`/usr/share/cloudstack-ui/`, which nothing serves. It has to go into
`/usr/share/cloudstack-management/webapp/`. Once deployed there, list,
describe, preview, query and create-table all work in the browser, Drop is
absent, and the create wizard runs end to end with the offering filter
behaving (stranded-selection fix in `a914b4a5a1`).

### A2. The ACL guard is correct — the "it denies admin too" report is a test-harness error

`8f2ad48295` closed a real, confirmed hole: `getEntityOwnerId` returns the
*target* VM's owner, so the framework's entity-access check compared the
caller against an owner the plugin itself supplied, and every caller passed.
`acctb` read an admin instance's database password. `checkCallerOwnsVm` fixes
it.

The follow-up report that the guard also denies admins, with
`DBG-ACL deny: caller=4 type=NORMAL callerAcct=4 vmAcct=2` captured during
what was believed to be an admin call, is **not a CloudStack defect**.
Verified 2026-09-09: `cmk list users` on this host returns
`username=userb, account=acctb, accounttype=0`. `~/.cmk/config` still reads
`username = admin`, but that field is cosmetic — the API-key signature
decides identity (`ApiServlet.java:623`, `ApiServer.java:1158`, both register
the authenticated account, neither consults `getEntityOwnerId`). The admin
key in that profile was overwritten with userb's when the tenant keys were
registered, so **every "admin" call in that test was an acctb call**, and
the guard denied it correctly.

**Do not `git revert 8f2ad48295`.** That would reopen a confirmed
cross-account password disclosure in order to fix a bug that does not exist.

Remaining, and it is test-harness work rather than code: restore an admin API
key into `~/.cmk/config` (from the UI as admin, or regenerate), then re-run
the admin path to confirm the positive case. Only the negative case (a
non-owner is denied) has been proven so far; that admin passes is currently
inference from the code, not observation.

### A3. Deploy the UI with an overlay, never `rsync --delete`

An overlay deploy that deleted `webapp/WEB-INF/` took `/client/api` down on
2026-09-09. Recovered from `webapp.bak.20260909b`; the zone is healthy
(`/client/api` 401, `/client/` 200, `WEB-INF` present). The UI bundle and the
servlet share that directory, so a mirroring copy removes the application.
Copy over the top; do not mirror. Three backup trees exist and can be removed
once this is settled: `webapp.bak.20260908`, `webapp.bak.20260909b`,
`cloudstack-ui.bak.20260909`.

### A-old. Original description, kept for context

Everything above was proven through `cmk`. In the browser, after the
nesting-depth fix (`d51018873d`) was deployed, the console's Refresh click
produces **no API request at all**: the handler runs, no POST is sent, no
client error appears. Two working hypotheses recorded: the drawer's resource
binding, or an axios-level rejection swallowing the call. A debug-logging
build was interrupted; the temporary lines were removed before committing.

Also unverified in the browser: describe / preview / create-table, and the
create-wizard's service-offering filter (disabled until an engine is chosen,
floor filtering, stranded selection cleared, warning paragraph).

Screenshots so far: `ACCEPTANCE-SHOTS-20260909/` (00–14). Playwright and
headless Chromium are installed on the host. Previous UI tree preserved at
`/usr/share/cloudstack-ui.bak.20260909`.

**This is the difference between "the API works" and "a tenant can use it".**

### B. Log hygiene — query text still reaches `management-server.log`

`requestHasSensitiveInfo` / `responseHasSensitiveInfo` mask the audit and
API-response layer, not `ApiServlet`'s DEBUG request/response logging, and
this host logs `com.cloud` at DEBUG. Build a **redacting filter scoped to
`RunDbaasQueryCmd` and `GetDbaasJobResultCmd`** — not a blanket log-level
drop, which trades the whole platform's debuggability for one leak.

Done when a query with a canary literal, then a `grep` for that literal *and*
for the returned row values, produces empty output — command and empty output
pasted.

### C. Reset Database Password

`DbaasManagerImpl.java:1367` still throws an exception whose message says the
in-VM agent does not exist. It does, and it is now proven on all four
engines. Add a `password_reset` job type to `JOB_HANDLERS`
(`extensions/dbaas/agent/dbaas_agent.py`), generate the password
server-side, update `dbaas_credentials` only after the agent confirms, never
log the plaintext. `<engine>_reset.sh` already has the SQL shape.

**Guest-side change → needs an image pass. See "the batching rule" below.**

### C2. Dump before drop — what unlocks the third feature

`DropDbaasTableCmd` → `table_drop` → `run_ddl_job` is complete and working;
the only thing stopping it is `dbaas.console.drop.enabled=false`, held there
because dropping a table is the first irreversible thing this product would
offer a tenant.

Rather than wait for PITR, make the drop recoverable: before the `DROP`
executes, dump that one table (`mysqldump --single-transaction <db> <table>`,
`pg_dump -t`, `mongodump --collection`) to a timestamped file under
`/var/lib/dbaas/predrop/` on the instance's own disk, and **refuse the job if
the dump fails**. Add a typed confirmation of the table name in the UI. Keep
the last N dumps and say what N is.

Then, and only then, `dbaas.console.drop.enabled` may be set to `true`.

**Guest-side; rides the same image pass as C and E2.**

### D. VM access parity — required, not optional

**Context that changes this item (2026-09-09):** this plugin is an add-on
feature on a departmental cloud where **usage is billed per user as credit**.
A DBaaS instance is not a managed black box — it is *the user's own VM*,
consuming *their own* credit, and it must behave like any instance they
create through the normal wizard. That includes getting into it.

So D is **required**, and it is wider than the original defect. Three things
have to be true, and only the first is currently understood:

1. **Password path — known broken.** `createDatabase` deploys with
   `startvm=false` and the plugin then starts the instance through the
   internal two-argument path, so `Param.VmPassword` is never populated. All
   four templates already report `passwordenabled=true`, so the template side
   is fine; the fix is on the plugin's start call
   (`resetPasswordForVirtualMachine`, or set `Param.VmPassword` there),
   while the instance is Stopped.
2. **SSH keypair path — untested, probably works, must be proven.** The
   wizard already sends `keypairs` to `deployVirtualMachine`
   (`CreateDatabaseInstance.vue:491`), and cloud-init injects keys from the
   config drive's `meta_data.json`. But all four templates report
   `sshkeyenabled=false`, which is CloudStack's flag for the *legacy*
   key-injection script — so whether a key actually lands in
   `~/.ssh/authorized_keys` on these images has never been checked. Deploy
   with a keypair and `ssh` in. If it fails, that is a template fix, not a
   plugin fix.
3. **Parity, stated plainly.** Whatever a user can do to an instance they
   created normally — reset password, attach a keypair, use the console
   proxy — must work the same on a DBaaS instance. Anything that does not
   work is a defect, not a design choice.

### D2. Usage and credit correctness

Because credit is the unit that matters here, "it works" is not enough — the
right account has to be charged the right amount.

1. **Attribution.** The wizard calls `deployVirtualMachine` from the tenant's
   own session, so the instance should be owned by, and billed to, that
   tenant. Verify it in `cloud_usage` rather than assuming: deploy as a
   tenant, then confirm usage records for the VM *and* its data disk carry
   that account, not the admin's.
2. **Orphan data disks are a billing bug, not just clutter.**
   `dbaas.datadisk.cleanup.enabled` ships `false`, so when a user destroys a
   DBaaS instance the data disk is *reported* by the sweep but not deleted —
   and on a credit-billed cloud an undeleted volume **keeps charging the
   user for storage they can no longer use or see**. The existing sweep
   already identifies exactly these disks (unattached, `dbaas.instance`
   marker, instance expunged, older than 24 h).

   This needs an owner decision, and the safe default is no longer obviously
   `false`: either turn the sweep on, or make the wizard's destroy flow warn
   the user that the volume survives and must be deleted manually. Silently
   billing for an invisible disk is the one outcome that is clearly wrong.

### D3. The tenant has root in their own VM — threat model addendum

Follows directly from D: if users SSH in, they are root on a machine that
holds DBaaS material. Most of it is fine, because everything in that VM is
scoped to that VM's own tenant:

- the DB credentials are their own
- `request.json` is deleted after provisioning, though the **config drive
  ISO still holds the original request**, including the database password —
  again, their own
- they can break or remove the agent, which breaks their own console. E2's
  `last_seen_at` alert is what turns that into something support can see

**The one thing that must be tested rather than assumed:** that an agent
token taken out of one instance cannot be used to poll for, or answer, jobs
belonging to a *different* instance. The lookup is keyed on `vm_id`
(`DbaasManagerImpl.java:781`), so it should hold — but this is now a tenant
with root and a shell, not a hypothetical attacker, so prove it. Add it to
the matrix alongside item 11.

### E. Tenant self-service — mandatory, not a preference

**Decision: tenants use DBaaS themselves**, and on a credit-billed cloud
this is structural rather than a preference: the instance must be created by,
owned by, and charged to the tenant, which only happens if the tenant makes
the call. The plugin's commands are not in the default User role's list, so a
tenant account is refused at the API-permission layer even on its own
instances. Matrix item 10 passed *because of that*, which is a pass for the
wrong reason.

Work: add the DBaaS commands to the User role, then **re-run item 10
properly** — a tenant must reach its own instances and be refused on
another account's. The plugin's `getEntityOwnerId` ACL checks exist but have
never once been executed, because the role layer refuses first. That first
real ACL run is the risk here, not the role edit.

### E2. Agent token durability — decided 2026-09-09, scope reduced

Investigated rather than assumed. Rotation is **poll-driven**
(`rotateAgentTokenIfDue`, `DbaasManagerImpl.java:848`), not a server-side
timer, so the feared case — an instance stopped for weeks coming back to a
rotated-away token — **cannot happen**: no poll, no rotation. The real
window is only between the server committing the new hash and the agent
persisting it: sub-second, once per `dbaas.agent.token.rotate.days` (7).

But `save_conf` (`extensions/dbaas/agent/dbaas_agent.py:44`) opens with
`O_TRUNC` and writes in place. A power loss or a force-stop mid-write leaves
a truncated or empty config, so the agent loses its whole configuration, not
just a token — and force-stopping a VM is a routine operator action, unlike
a process dying at exactly the wrong instant.

**Do (both cheap):**

1. atomic write — temp file + `os.replace()` + fsync. Four lines. Turns
   "corrupt config" into "either the old token or the new one, never
   garbage". Guest-side, so it rides the image pass C already needs; cost
   above that pass is zero
2. alert on `last_seen_at` in `dbaas_agent_tokens` not moving for N hours.
   Server-side, no image pass

**Do not do:** full self-heal (server pushing a fresh token through a new
config drive). The problem is not that a stuck agent cannot be recovered — a
new provisioning request recovers it — it is that **nobody finds out it is
stuck**. Monitoring addresses that directly; self-heal is the expensive way
round. Revisit only if this is observed in the wild.

### F. The isolated-network / VR-down claim

`DbaasIsolatedConfigDrive` still has Dhcp and Dns on `VirtualRouter`, so
CloudStack auto-heals the VR on any VM start and "VR down" never actually
happens. `ConfigDriveNetworkElement` advertises Dhcp and Dns capabilities, so
a correct offering is constructible; services cannot be edited after
creation, so make a new offering with Dhcp + Dns + UserData on ConfigDrive
(SourceNat/Firewall/PortForwarding stay on VirtualRouter), build a network,
deploy onto it, stop the VR, repeat the matrix.

**This is proof, not function.** DBaaS deploys to a Shared network today and
that path is already verified. Worth doing before claiming the architecture
survives a broken VR — not worth blocking a demo on.

### G. Housekeeping

- destroy the four leftover measurement instances (`glmc-*`)
- primary storage hit **90.5%** during the last session and the 211–213
  caches alone are ~5 GB; secondary was at 94%. This is exactly NEON gate G4
  and it now has real datapoints
- `README.md`, `INSTALL.md`, `TEMPLATES.md` under
  `plugins/integrations/dbaas/` still describe the retired v1 SSH transport
- `dbaas.console.drop.enabled` stays `false` until backup/PITR exists

---

## The batching rule — the single biggest efficiency lever

The agent has **no self-update path**. Any change to `dbaas_agent.py` or the
engine scripts means another four-image `qemu-nbd` pass, which is the most
expensive and most error-prone operation in this project (it has broken twice:
the engine-marker bug, and the stale-agent gap).

So: **do not do a guest-side change alone.** Collect them and pay for one
pass. Currently queued for the next one:

- C2 (dump before drop) — the one that unlocks the third feature
- E2's atomic `save_conf` write (four lines, decided)
- C (`password_reset` job type), if wanted

There is no later pass to save anything for: with the Neon phases cut, this
is the last guest-side change on the plan.

Everything else on this page — A, B, D, F, G — is server-side, UI-side or
zone-side and needs **no image pass at all**.

## Order

```
A (browser UI)          ← blocker; nothing else makes the product usable
   │
B (log redaction)       ← server-only, small, security
D (VM password) + D2 (usage/credit) + D3 (token scoping test)
E (tenant role + first real ACL run)  ← server-only; the ACL run is the risk
E2 monitoring alert     ← server-only, small
snapshot policy         ← server-only, ~1h, CloudStack's own feature
   │
one image pass: C2 + E2 atomic write (+ C if wanted)
   │
enable dbaas.console.drop.enabled   ← the third feature goes live here
   │
G (housekeeping)   ·   F (VR-down proof, optional)
```

## What "finished" means — v1

A tenant, logged in as **their own account**, opens the UI and on any of the
four engines: browses their tables, runs a query, creates a table, drops a
table (with the pre-drop dump behind it), connects a normal DB client from
another machine, and **SSHes into the instance they are paying for**, with
the usage landing on their own credit. No tenant sees another tenant's anything. No SQL text
or row value reaches `management-server.log`. A daily volume snapshot exists.

That is the product. When it is true, this project is done — not paused
before a larger phase.

## Rough remaining effort

| | Hours |
| --- | --- |
| A browser console bug + full click-through | 2–4 |
| B log redaction | ~1 |
| D VM password + keypair proof | 2–3 |
| D2 usage attribution + the orphan-disk decision | 1–2 |
| D3 cross-VM token test | ~1 |
| E tenant role + the first real ACL run | 1–2 |
| E2 monitoring alert | ~1 |
| snapshot policy | ~1 |
| C2 dump before drop | 3–4 |
| one image pass (C2 + E2, plus C at +2–3) | 2–3 |
| G housekeeping | ~1 |
| **Total** | **16–23** |

Optional on top: C reset-password (+2–3), F VR-down proof (+1–2).
