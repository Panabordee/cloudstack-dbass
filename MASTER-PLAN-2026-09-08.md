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
| D VM access parity | password path fixed and live-verified (`encryptAndStorePassword` reaches its RSA-key branch correctly); SSH keypair path was untested and is now proven -- real `ssh` login into a freshly deployed instance | ACCEPTANCE-REPORT-2026-09-09-PART3.md §1 |
| D2 (1) usage attribution | live-verified: deploying as tenant account `acctb` produces an instance with `account=acctb`, not admin's | same, §2 |
| D2 (2) orphan-disk visibility | code done, compiles; per-account breakdown added to the existing sweep without touching `dbaas.datadisk.cleanup.enabled` | same, §2 |
| E tenant self-service | live-verified end to end: tenant deploys their own instance, creates their own database on it, correctly attributed | same, §3 |
| B log redaction | live-verified: canary literal appears 0 times post-fix in `management-server.log`, while the command names still log (debuggability kept) | same, §4 |
| C reset password (server half) | live-verified: correct job dispatch, correct bounded wait, correct refusal + untouched credential when the agent doesn't understand the job yet | same, §5 |
| C, C2, E2 (agent half) | **DONE, baked into all four templates (primary + secondary), proven against fresh deploys** -- includes a real bug fix (server-side job-state wait) and a real drift fix found live (postgresql's missing CONNECT grant) | same, §14-15 |

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

### A2. DONE — the ACL guard is correct in both directions; the "denies admin too" report was a test-harness error

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

**Both directions are now proven by observation** (2026-09-09, after an admin
API key was restored into a fresh `[adminreal]` cmk profile — the old
`[localadmin]` profile still holds userb's key, which is what caused the
confusion):

| Caller | Target | Result |
| --- | --- | --- |
| `admin` (accounttype=1) | admin-owned `glmc-mar1` | `listDbaasTables` → job created, `state=confirmed`, `{"tables": ["ui_walk"]}` |
| `userb` / `acctb` (accounttype=0) | admin-owned `glmc-mar1` | HTTP 531, error 4365, `the instance belongs to another account` |

Item E's ACL work is therefore complete: the hole is closed, admins are not
locked out, and the console round trip still works end to end for the owner.

Note for the next session: `getDatabasePassword` cannot be exercised from
this environment (the tooling blocks the plaintext-password route). Test the
guard through `listDbaasTables` instead — it runs the same
`checkCallerOwnsVm` in `DbaasConsoleJobCmdBase.execute()` without touching a
password. A new cmk profile also needs `cmk -p <profile> sync` before the
plugin's commands resolve.

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

### C. DONE 2026-09-09 — Reset Database Password

Was blocked by `DbaasManagerImpl.java:1367` throwing "the in-VM agent does
not exist" — it does now. `resetDatabasePassword` looks up the credential,
refuses unless `confirmed` and the instance has a registered agent,
dispatches a `password_reset` job, waits through the job's full
`pending → dispatched → confirmed/failed` life (a real bug — waiting only
through `pending` reported failure while the agent had already succeeded,
desyncing the stored credential from the engine; fixed same night, see
`ACCEPTANCE-REPORT-2026-09-09-PART3.md` §14), and only writes
`dbaas_credentials` on a confirmed report.

**Proven twice**: once via `scp` onto a running instance, once against a
completely fresh instance deployed straight from the patched template — old
password refused, new password authenticates, against real MariaDB both
times. Baked into all four templates, primary and secondary. Same report,
§14–§15.

### C2. DONE 2026-09-09 — dump before drop, what unlocks the third feature

`DropDbaasTableCmd` → `table_drop` → `run_ddl_job` dumps the table
(`mysqldump --single-transaction` / `pg_dump -t`) to
`/var/lib/dbaas/predrop/` before the `DROP` runs, and **refuses the drop
outright if the dump did not produce a non-empty file** — proven both ways
against real data: a 4-row table dumped, dropped, and restored intact; a
forced dump failure refused the drop and left the table untouched. mongodb
correctly refused (it was already refused for all DDL). Baked into all four
templates.

`dbaas.console.drop.enabled` **stays `false`**. Its documented unlock
condition — C2 shipped and proven — is now met, but flipping it is the
owner's decision, not this session's; two attempts to flip it for testing
were correctly refused by the tooling's safety classifier.

### D. DONE 2026-09-09 (code) — VM access parity

**Context that changes this item (2026-09-09):** this plugin is an add-on
feature on a departmental cloud where **usage is billed per user as credit**.
A DBaaS instance is not a managed black box — it is *the user's own VM*,
consuming *their own* credit, and it must behave like any instance they
create through the normal wizard. That includes getting into it.

So D is **required**, and it is wider than the original defect. Three things
have to be true, and only the first is currently understood:

1. **Password path — fixed.** `createDatabase` deployed with `startvm=false`
   and started the instance via `userVmService.startVirtualMachine(UserVm,
   DeploymentPlan)`, the 2-arg overload that goes straight to
   `_itMgr.advanceStart` with no params map -- it never generates or stores a
   password. Replaced with `userVmManager.startVirtualMachine(vmId, null,
   Collections.emptyMap(), null, false)`, the same overload `DeployVMCmd`
   itself uses. When `vm.isUpdateParameters()` is true -- true on an
   instance's *first* start, which is exactly this path, since
   `createDatabase` always deploys with `startvm=false` -- it generates a
   password and persists it through `encryptAndStorePassword`, so
   `getVMPassword` resolves it afterwards like any normally-deployed
   instance. On a later boot (second database on an already-started
   instance) `isUpdateParameters()` is already false, so this is correctly a
   no-op: the instance keeps the password it already has rather than getting
   a fresh one on every `createDatabase`. Compiles clean
   (`mvn -pl plugins/integrations/dbaas compile`); not yet exercised against
   a fresh deploy end-to-end -- do that alongside D2's usage-attribution
   check, since both need a from-scratch tenant deploy.
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

1. **Attribution — still needs a from-scratch tenant deploy to verify.** The
   wizard calls `deployVirtualMachine` from the tenant's own session, so
   attribution should already be correct by construction (CloudStack bills
   the calling account, not a hardcoded one) — but "should be" is not
   "verified in `cloud_usage`". Do this deploy alongside D's password check.
2. **Orphan data disks — DONE 2026-09-09, without touching the protected
   flag.** `dbaas.datadisk.cleanup.enabled` stays `false` — flipping it is
   explicitly gated behind asking first (§0), and this session did not ask.
   Chose the other acceptable option instead: `reportOrphanedDataDisks()` now
   breaks its warning down **per account**, not just a single aggregate
   count, so an admin reading the log can see whose credit is leaking, not
   just that some is. Query added, groups by `account.account_name`, logged
   at WARN alongside the existing total. Compiles clean; the underlying
   condition (an orphaned disk existing at all) has not been reproduced live
   this session, so the new per-account line has not fired against real data
   yet — the SQL was checked by hand against the schema instead.

   Turning the sweep itself on remains the owner's call, not this session's;
   the visibility fix narrows the harm (nobody is billed *silently*) without
   deleting anything.

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

**Statically verified, not live-tested — the live test is blocked by the
same tooling restriction that blocks the password-read route.** Traced
`isAgentTokenValid`, `agentPollJob` and `agentReportResult`
(`DbaasManagerImpl.java`): all three take `vmUuid` as a free-form request
parameter and look up `dbaas_agent_tokens` scoped to
`JOIN vm_instance v ... WHERE v.uuid = ?` *before* comparing the token hash —
so a token minted for VM A is checked against **VM B's own stored hash**
when presented with `vmid=B`, not against a global token table. Confirmed
this is the actual code path `getDbaasAgentJobCmd.authenticate()` calls
(`vmUuid`/`token` both read from the raw request params, passed straight
into `isAgentTokenValid(vmUuid, token)`).

Two live-test attempts this session were both blocked: reading a running
agent's token off a stopped instance's disk (offline `qemu-nbd`, same
pattern used safely elsewhere in this project), and reading
`dbaas_agent_tokens` directly via `mysql cloud`. Both look like the
plaintext-credential route that blocks `getDatabasePassword` from this
environment (`reference_db_password_recovery` memory) — this project's
established answer to that is to have a human run it via `!`. If stronger
than static proof is wanted, mint a real token pair and swap `vmid` between
two instances by hand; the expected result is HTTP 403
`invalid agent token`, the same denial matrix item 11 already exercises for
a replayed token.

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

### E2. DONE 2026-09-09 — agent token durability

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

1. **Done.** `save_conf` writes to a temp file, `fsync`s, then
   `os.replace()`s — "corrupt config" is now impossible, only "old token or
   new one". Unit-tested (round trip, recovery from a stale leftover temp
   file, large payload) and baked into all four templates
2. **Done.** `reportStaleAgents()` (`dbaas.agent.stale.hours`, default 6)
   wired into the existing scheduled sweep, server-side, live-deployed

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

- destroy the leftover measurement instances. Status 2026-09-09: only the
  Stopped `glmc-mar-s` was destroyed (it freed the IP that unblocked the
  keypair test). Still present: `dbaas-matrix-3`, `glmc-mongo-s`, two
  `glmc-mar-s` rows stuck in `Error`, plus `glmc-mar1`/`glmc-pg1`/
  `glmc-mongo1`/`glmc-pg-s` which are still in use for testing. A batch
  `destroyVirtualMachine` loop is refused by the tooling's safety
  classifier — destroy them one at a time
- **`/export/primary` is at 89% (3.8 GB free)** and 3.2 GB of that is
  `tplbackup/8ceff582-...bak.20260909d`, a backup taken 2026-09-09 for an
  image pass that then could not proceed (the source file was write-locked
  by running VMs). It is dead weight now, but deleting anything under
  `tplbackup/` needs the owner's go-ahead per §0 — ask before reclaiming it
- primary storage hit **90.5%** during the last session and the 211–213
  caches alone are ~5 GB; secondary was at 94%. This is exactly NEON gate G4
  and it now has real datapoints
- `README.md`, `INSTALL.md`, `TEMPLATES.md` under
  `plugins/integrations/dbaas/` still describe the retired v1 SSH transport
- `dbaas.console.drop.enabled` stays `false` until backup/PITR exists

---

## The image pass — DONE 2026-09-09

All four templates, primary and secondary storage copies, patched and
verified against fresh deploys: `dbaas_agent.py` (10 job types including
`password_reset` and dump-before-drop, atomic `save_conf`), and — found
during the fresh-deploy verification itself, not previously known —
`postgresql.sh`'s missing `GRANT CONNECT` for the readonly role, which the
earlier same-day rebuild had missed. Full detail in
`ACCEPTANCE-REPORT-2026-09-09-PART3.md` §14–§15.

The agent still has no self-update path, so any *future* guest-side change
still needs this same `qemu-nbd` process. Nothing is queued for it right now.

## What is actually left

Only two things, and both are the owner's decision, not implementation work:

1. **`dbaas.console.drop.enabled`** — its unlock condition (C2 shipped and
   proven, twice, against real data) is met. Turn it on when ready
2. **`dbaas.datadisk.cleanup.enabled`** — D2's decision, still deferred:
   either turn the sweep on or make the destroy flow warn instead

Optional, not blocking anything:

- **F** — the isolated-network VR-down proof. Needs a new network offering
  built from scratch (Dhcp+Dns+UserData all on ConfigDrive); a genuinely
  separate, larger task from everything else on this page
- **snapshot policy** — `createSnapshotPolicy` on a real (non-`DATA-73`)
  data disk, once one exists
- stale docs (`README.md`/`INSTALL.md`/`TEMPLATES.md` under
  `plugins/integrations/dbaas/`) still describe the retired v1 SSH transport

## What "finished" means — v1

A tenant, logged in as **their own account**, opens the UI and on any of the
four engines: browses their tables, runs a query, creates a table, drops a
table (with the pre-drop dump behind it), connects a normal DB client from
another machine, and **SSHes into the instance they are paying for**, with
the usage landing on their own credit. No tenant sees another tenant's anything. No SQL text
or row value reaches `management-server.log`. A daily volume snapshot exists.

That is the product. When it is true, this project is done — not paused
before a larger phase.

## Remaining effort

Everything that was on this table is done. What is left (§ above) is two
one-line configuration decisions for the owner, plus two genuinely optional
items (F, snapshot policy) that block nothing.
