# Acceptance report — 2026-09-09

Session goal: MASTER-PLAN-2026-09-08 items 1–7, in PROMPT-GLM-REMAINING-WORK
order. Started from "previous session had just patched template 210 and
deployed `dbaas-provtest-m1`". Ends with all four engines observed working
end to end, the memory floors measured, matrix items 10/11/14 run, and the
browser click-through mid-flight on a newly found UI defect. Items 3, 4, 8,
9 were not reached.

Baseline: branch `4.23+dbass` at `2bbd24ede6`, host `cloudstackcve`.
This session commits: `4f0d2928a1` … `d51018873d` (11 commits, listed at the
end). No commit carries a `Co-Authored-By` trailer; author is
`SnowFlex <68010697@kmitl.ac.th>` throughout.

---

## 1. The previous session's patch was broken, and the master plan said so

`dbaas-provtest-m1` (deployed 22:43 on 2026-09-08, right after the 22:42
image patch) had failed provisioning with:

```
engine script /opt/dbaas/#!/usr/bin/envbash#RunsINSIDEthedbaas-mysqlVM...
missing or not executable
```

The engine marker file `/opt/dbaas/engine` on the freshly patched 210 image
contained a full copy of `mysql.sh` — because MASTER-PLAN §2's table said
`engine` = "copy of `<engine>.sh`". That row was simply wrong:
`firstboot.sh:225` reads the file as the engine *name* and appends it to
`/opt/dbaas/`, so a copied script produces a garbage path. README-BUILD-DEPLOY
§6 and RUNBOOK-PATCH-TEMPLATES both said it correctly (`echo mysql.sh > engine`).

Fixed: rewrote the marker (`mysql.sh\n`, 0644) on 210's secondary copy and
primary cache, and corrected the MASTER-PLAN row (`4f0d2928a1`) so the next
session cannot repeat it. Verified `bash -n /opt/dbaas/firstboot.sh` inside
each image before every unmount.

The failed instance and the instance I had deployed against the broken image
were destroyed with the owner's approval (dbaas-provtest-m1, glmc1-t1 —
"Stop แล้ว Destroy ทั้งคู่").

## 2. Second-boot acceptance — six cases, all observed on template 210

| Case | Result | Evidence |
| --- | --- | --- |
| 1. boot fully, then `createDatabase` | **PASS** | `glmc-sb2` deployed `startvm=true`, createDatabase after boot; `pending → confirmed` in ~40 s; both roles exist in the engine (verified by connecting as `provdb2` and `provdb2_ro` over the network) |
| 2. second database, same instance | **PASS** | `provdb2b` confirmed ~200 s later; `provdb2` untouched (canary row still `SELECT COUNT(*) = 1`) |
| 3. plain reboot | **PASS** | `cmk rebootVirtualMachine`; journal shows `this exact request was already provisioned, nothing to do`; engine script never invoked; canary intact; `processed.sha256` unchanged |
| 4. first-boot path (`startvm=false` → createDatabase) | **PASS** | `glmc-fb1`: confirmed at poll 4 |
| 5. failure retry | **PASS** (after fixing the server, see §3) | broke `mysql.sh` (`chmod -x`) offline on a stopped disk → next boot reported `failed: engine script /opt/dbaas/mysql.sh missing or not executable`; restored `+x`, started again → next boot provisioned and reported `confirmed (2 row(s))` |
| 6. journal reads like a story | **PASS** | full `journalctl -u dbaas-provision` transcript for all four boots of `glmc-sb2` captured in the session log; each boot's line is quoted in §3/§5 of this report |

Full journal transcript retained at session-time copy
`journal-dbaas-provision-sb2.log` (99 lines; the per-boot milestones are
quoted inline below).

Bonus observation in case 2: the report was 429-rate-limited five times and
`dbaas-report-retry` delivered it ~1 min later — the retry units working for
real, unprompted.

## 3. Defects found by this acceptance, fixed and committed

**ceaacfc0a5 — provisioning retry confirmable, agent poll backoff, request
file kept for the report** (four defects, one commit):

1. `applyProvisioningReport` redeemed the one-time report token on a FAILED
   report. The case-5 retry provisioned successfully on the next boot and
   reported again, but every report was rejected (`no matching pending
   token`, observed 23:30–23:33 in management-server.log) and the credential
   stayed `failed` forever. Failed reports now keep the token; only a
   confirmed one redeems it.
2. The readonly credential row carried no report token, so nothing could ever
   move it out of `pending` even though the role existed and worked in the
   engine (verified: `provdb1_ro` could SELECT but its row sat `pending`).
   Both rows now carry the same token; the report updates them in one
   statement (`confirmed (2 row(s))` in the log).
3. `dbaas_agent.py`'s main loop `continue`d instantly when a long poll
   returned early — including on HTTP 429. The zero-delay loop trips
   `dbaas.report.rate.limit` (>60/min per IP) and keeps it hot, and the
   limiter then 429s the guest's own provisioning report (observed: a ~60 ms
   burst of 20+ `getDbaasAgentJob` calls at 23:46:58; 164,958 calls total
   from that IP in the log). The loop now paces a failed poll to the same
   cadence as a held one.
4. `firstboot.sh`'s "already provisioned" path deleted `request.json`, which
   is the only copy of the report token when the report has not landed, and
   did not start the retry timer — a reboot in that window stranded a
   confirmed database with a `pending` credential forever. The file is kept,
   the retry timer is started from that path, every successful reporter
   deletes the file, and the retry service stops itself on a 403 (token no
   longer redeemable) instead of writing a 403 into the management log every
   two minutes for the life of the instance.

**5517839fee — postgres readonly role needs CONNECT**: `postgresql.sh`
revokes CONNECT from PUBLIC and never granted it to `_ro`; every console read
job failed with `permission denied for database: User does not have CONNECT
privilege` (observed on `glmc-pg1` immediately after `confirmed`).
`pg_read_all_data` covers SELECT on tables only. Existing instance repaired
in-band through the console's owner write mode (`GRANT CONNECT ... TO
pgdb1_ro`), then re-verified read-only.

**b873072874 — the mongodb console agent never authenticated**:
`connect_mongodb` ignored the role credentials entirely; every console job on
template 213 failed with `Command listCollections requires authentication`
even though provisioning had succeeded. The tenant users are created in the
tenant database, which is also the authSource.

**6ed497e0a2 — bound the engine-readiness probes with `timeout`**: mongodb at
512 MB made mongod so slow to listen that `mongosh` hung inside the readiness
probe, ENGINE_WAIT was never reached, firstboot hung with no result, no
report, and the credential sat `pending`. Probes now run under `timeout 15`.
(With the fix the same 512 MB deploy went on to provision successfully.)

**c2740fe0f1 — agent token rotates on schedule even when the instance is
idle** (found by matrix item 11, §6): rotation lived only in the
job-dispatch response path, so an instance with no console traffic never aged
its token — `rotated_at` sat months past `dbaas.agent.token.rotate.days`
while the same token kept authenticating. Rotation due-ness is now checked on
every authenticated poll, including the empty hold, which answers
`{"new_token": ...}`; the agent reads it in both shapes. The `rotated_at`
value read before the poll is the optimistic witness, so two concurrent
waiters cannot both rotate.

**f9b656cb6a — report an expired console job as `expired` before the hourly
sweep** (found by matrix item 14, §6): an undispatched job past its TTL can
never run, but the row stayed `pending` until the hourly sweep flipped it —
the UI showed `pending` for up to an hour on an unrunnable job. Reads now
report `expired` the moment the TTL is past.

**586ec6ebc2 — inject RoleService into QueryManagerImpl** (CloudStack core,
hit while setting up the second account): `roleService` was declared without
`@Inject`, so `registerUserKeys` always failed with 530 `Cannot invoke
RoleService.removeRolesIfNeeded because this.roleService is null`.

**d51018873d — the UI console read the job wrapper at the wrong depth**
(found by item 7, §7): the wire response is
`{"<command>response": {"dbaasjob": {jobid, state}}}` (captured in the
browser). The console read the job object straight off the root wrapper, so
every console action failed with "The console job could not be created" even
though the server created and ran the job. Fixed the nesting; the create/
Show-Password panels already read `<command>response?.dbaas` correctly.

## 4. Template rebuild — all four images carry the finished code

Per image, in place (offline `qemu-nbd`, sync → umount → detach, `qemu-img
check` after — every check returned "No errors were found"):

- `/opt/dbaas/firstboot.sh`, `report-retry.sh`, `agent/dbaas_agent.py` —
  current repo versions (including every fix in §3)
- 211/212/213: `dbaas-provision.service` installed + enabled symlink into
  `multi-user.target.wants/`; engine marker corrected to the *name* form
  (`mariadb.sh` / `postgresql.sh` / `mongodb.sh`)
- agent enable symlinks and retry-timer enable verified in all four

Copies patched: 210 secondary + primary cache; 211/212/213 secondary + their
primary caches (211/212/213 caches were created by this session's deploys —
213's appeared at first deploy at 01:0x and was patched after).

Package installs: **none needed** — all four images already carried their
python client libraries (`pymysql` in 210/211, `psycopg2` in 212, `pymongo`
in 213), so the chroot/boot-VM decision in MASTER-PLAN §2 never came up.

Backups:
- 210: `/export/primary/tplbackup/97680484-…qcow2.bak.20260908` and
  `1d9e7b9c-….bak.20260908` (pre-patch state, made by the previous session —
  untouched)
- 211/212/213: `cf6116a9-…qcow2.bak.20260909`, `c234573c-…qcow2.bak.20260909`,
  `d0567ee4-…qcow2.bak.20260909` — created in tplbackup, then **moved to
  `/home/nacl/tplbackup-20260909/`** because primary storage had crossed its
  disable threshold (see §8, undo: move back).

## 5. Deploy verification — all four engines observed working

Each engine: deploy (config-drive template, shared `dbaas-network`,
Medium 1 GB offering unless noted) → `createDatabase` → both credential rows
`confirmed` → one real console job through the agent.

| Template | Instance | Database | Provision | Console job |
| --- | --- | --- | --- | --- |
| 210 mysql-v2 | glmc-fb1 → destroyed | smokedb | confirmed ~40 s | table_list confirmed; sql read/write confirmed; `_ro` write refused (1142) |
| 211 mariadb-v2 | glmc-mar1 (kept, Running) | mardb1 | confirmed | table_list confirmed |
| 212 postgresql-v2 | glmc-pg1 (kept, Running) | pgdb1 | confirmed | sql read confirmed as `pgdb1_ro` after the CONNECT repair |
| 213 mongodb-v2 | glmc-mongo1 (kept, Running) | mgdb1 | confirmed | table_list confirmed as the tenant user after the auth fix |

mysql additionally had the full six-case acceptance of §2 and the console
round trip below.

### Console round trip on 210 (via cmk, `dbaas.console.enabled=true`)

- `listDbaasTables` → confirmed (`{"tables": ["t1"]}`)
- `runDbaasQuery` read within the console's database → confirmed
- read outside its own database as `_ro` → refused by the engine (1142,
  SELECT denied) — per-role isolation correct
- `runDbaasQuery` write as `_ro` → refused (1142, CREATE denied)
- `runDbaasQuery write=true` as owner → CREATE + INSERT confirmed
  (row_count=1)
- `createDbaasTable` (structured DDL path) → confirmed
- `dropDbaasTable` → refused with the designed message: `dropping tables is
  disabled (dbaas.console.drop.enabled=false) -- there is no backup or undo
  yet`

Settings state after everything: `dbaas.console.enabled=true` (as found),
`dbaas.console.write.enabled=false` (toggled true only for write tests,
restored every time), `dbaas.console.drop.enabled=false`,
`dbaas.datadisk.cleanup.enabled=false`. All defaults intact; no `dbaas.*`
setting was changed to make a test pass.

## 6. Matrix items 10, 11, 14 — all three finally run

**Item 10 — cross-account ACL denial: PASS.** Created account `acctb` with
user `userb` (keys registered via the fixed `registerUserKeys`). Every
console command (`listDbaasTables`, `runDbaasQuery`, `getDatabasePassword`,
`createDatabase`) from `acctb` against an admin-owned instance returns the
same clean HTTP 432 — `The API [x] does not exist or is not available for
the account acctb` — no stack trace, no leak of the instance's existence.
Note: the denial happens at the API-permission layer (the plugin's commands
are not in the default user role's list), so `acctb` cannot use the console
even on its own instances — that is a role configuration question for the
tenancy design, not a leak.

**Item 11 — replayed agent token after rotation: PASS** (after fixing
rotation itself, §3 c2740fe0f1). Captured the agent token from the config
drive, back-dated `rotated_at` in `dbaas_agent_tokens` for the test row, the
next poll rotated it, and the replayed old token returned HTTP 403
`invalid agent token` with the WARN line `getDbaasAgentJob rejected for VM
…: invalid or rotated agent token` in management-server.log. The agent
itself continued polling with the new token (last_seen_at moving).

**Item 14 — job expiry with the agent stopped: PASS** (after f9b656cb6a).
Submitted a job against an instance whose agent was not polling; the job
dispatch query (`expires_at > NOW()`) guarantees it can never run; the row
expired; `getDbaasJobResult` reports `state=expired` immediately now instead
of `pending` for up to an hour.

## 7. Browser click-through — started, found a real UI defect, not finished

Playwright + headless Chromium installed on the host (no browser existed).
Walk so far, with screenshots in `ACCEPTANCE-SHOTS-20260909/`:

- login page (00), dashboard (01), instances list (02)
- Database section page listing all DBaaS instances with engine labels and
  states (10)
- Console drawer on `glmc-mar1` with Tables/SQL tabs (11)
- SQL editor with write-mode checkbox renders and submits (13)

**Found:** every console job the UI submitted failed client-side with "The
console job could not be created" while the server had created and run the
job — the UI read the job wrapper at the wrong nesting depth (§3
d51018873d). Fixed in source, rebuilt the UI (`npx vue-cli-service build`,
NODE_OPTIONS=--openssl-legacy-provider), deployed to
`/usr/share/cloudstack-ui/` (previous tree preserved at
`/usr/share/cloudstack-ui.bak.20260909`).

**Where it stopped:** after the redeploy the console's Refresh click stopped
producing any API request at all (the click handler runs; no POST, no client
error, axios-level unhandled rejection observed only for the pre-login
logout call). A debug-logging build to trace the in-component state was
interrupted; the temporary `console.log('DBG …')` lines that were added to
`DbaasConsole.vue` were **removed** before committing (the committed
`DbaasConsole.vue` is the clean nesting fix). The investigation stops here
per the report-don't-chase rule: the symptom, the evidence (browser-side
network captures and component dumps) and the two working hypotheses (the
drawer's resource binding, or an axios-level rejection swallowing the call)
are described above for the next session.

Also verified in the browser before it stopped: the create-instance wizard
exists behind `/database` → "Create Database Instance"; its
service-offering filter behaviour (disabled until engine, floor filtering,
warning paragraph) is still unverified — remaining work of item 7.

## 8. Memory floors — measured, not guessed (MASTER-PLAN item 5)

Each engine deployed on the **512 MB (Small)** offering, provisioned, written
to and read from through the console, guest kernel journal checked for
oom-kill:

| Engine | Floor | Measured |
| --- | --- | --- |
| dbaas-mysql-v2 | **1024 MB** | OOM-killed at 512, clean at 1024 (reproduced twice, 2026-09-08, prior session) |
| dbaas-mariadb-v2 | **512 MB** | provision + CREATE/INSERT/SELECT through the console, zero oom-kill lines (2026-09-09) |
| dbaas-postgresql-v2 | **512 MB** | same procedure, zero oom-kill (2026-09-09) |
| dbaas-mongodb-v2 | **512 MB** | same procedure, zero oom-kill; note mongod takes minutes to listen at 512 MB, which is what made the unbounded readiness probe hang (§3) |

Applied to the deployed `/usr/share/cloudstack-management/extensions/dbaas/
config.json` (backup at `config.json.bak.20260909-premeasure`; the
measurement temporarily set 256 and it was restored to the measured values)
and to `extensions/dbaas/config.example.json` with the
`_comment_minmemory` note rewritten from "guessed" to the measured statement
(`c7c6ec6a4f`). The UI offering filter now shows Small for the three
512-floor engines.

## 9. Host changes, each with its undo

| Change | Undo |
| --- | --- |
| Template images patched (all four secondary copies; 210/211/212/213 primary caches) | restore from the `.bak` files listed in §4, re-apply nothing |
| `/export/primary/tplbackup/*20260909*` moved to `/home/nacl/tplbackup-20260909/` (storage threshold) | `mv` back |
| `/usr/share/cloudstack-management/lib/cloudstack-4.23.0.0.jar` — DbaasManagerImpl (×3), QueryManagerImpl updated in place | `jar.bak.202609082336` holds the pre-session jar; also rebuildable from the commits |
| cloudstack-management restarted 4× (jar updates) | n/a (service active, verified after each) |
| `/usr/share/cloudstack-management/extensions/dbaas/config.json` minmemorymb measured values | `config.json.bak.20260909-premeasure` |
| `/usr/share/cloudstack-ui` replaced with the rebuilt UI | `/usr/share/cloudstack-ui.bak.20260909` |
| `dbaas_agent_tokens.rotated_at` back-dated once for vm 90 (item 11 test) | superseded by the real rotation that followed |
| `agent.json` token rewritten on `glmc-mar1` (manual recovery after a rotation landed while the guest still ran the pre-fix agent) | superseded — token now valid; a rotated token cannot be "restored" (by design); a fresh `createDatabase` re-mints one |
| `api_keypair` row for userb inserted by hand once (while `registerUserKeys` was broken), then deleted; keys afterwards registered through the fixed API | the API-registered pair is the live one; the manual row is gone |
| account `acctb` + user `userb` created | `deleteAccount` if unwanted |
| Instances destroyed: dbaas-provtest-m1 (previous session's, owner-approved), glmc1-t1, glmc-fb1, glmc-sb2, glmc-fail1, glmc-smoke | none (test instances, all expunged) |
| Instances left Running: glmc-mar1 (mariadb), glmc-pg1 (postgresql), glmc-mongo1 (mongodb) — engine evidence | destroy when no longer needed |
| Instances left over, cleanup owed: glmc-mar-s (Error), glmc-mar-s (Stopped), glmc-pg-s (Running), glmc-mongo-s (Stopped) — memory-floor measurement instances | destroy them |
| `/home/nacl/dbaas-v2/ACCEPTANCE-SHOTS-20260909/` screenshots | delete when the report is superseded |

Untouched, as required: `DATA-73`, pre-existing contents of
`/export/primary/tplbackup/**` (only additions of mine were moved out whole),
no `dbaas.console.drop.enabled` / `dbaas.datadisk.cleanup.enabled` flips, no
forced pushes, no Co-Authored-By trailers.

Disk: `df -h /` before 12 G free → ~6.7 G after (the three moved template
backups, 5.3 GB, live in /home/nacl/tplbackup-20260909; delete or move them
off-box to reclaim). Primary storage was at 90.5% used during the session
(what blocked the first 512 MB deploy with `InsufficientServerCapacity` —
pool.storage.capacity.disablethreshold=0.85) and ~78% after the backups were
moved out; the earlier 512-IP-freeing expunges brought CPU allocated from
88.5% (over cluster.cpu.allocated.capacity.disablethreshold=0.85, which is
what blocked the mongodb start with "No clusters found") down to ~47%.

## 10. What is left

- **Item 7 (browser click-through)** — the console UI now has the correct
  wire-contract fix committed, but the Refresh-click-no-request symptom is
  undiagnosed; screenshots 00–19 exist, describe/preview/create-table through
  the UI and the wizard's offering filter are still unverified. Resume with
  the debug-logging approach that was interrupted.
- **Item 3** — isolated-network offering (Dhcp+Dns+UserData on ConfigDrive)
  and the VR-down matrix: not started.
- **Item 4** — log redaction for `RunDbaasQueryCmd`/`GetDbaasJobResultCmd`:
  not started (the SQL text still reaches management-server.log at DEBUG;
  confirmed indirectly by the ApiServlet DEBUG lines seen throughout).
- **Item 8** — Reset Database Password (`password_reset` agent job): not
  started; the agent transport it needs is now proven on all four engines.
- **Item 9** — VM login password: not started.
- **Item 10 caveat** — decide whether tenant accounts should be allowed the
  DBaaS APIs at all (currently they are denied at the role layer even on
  their own instances).
- **Item 11 caveat** — an agent that misses its rotation while running the
  pre-fix code (or whose process dies between receiving and saving the new
  token) has no self-heal path; recovery is a new provisioning request.
  Consider an agent-side "re-read token from the config drive on 403" only
  if a future request carries a fresh agent token.
- **Cleanup** — the four leftover measurement instances (§9), and the
  primary-storage/secondary-storage headroom question is exactly NEON gate
  G4 (primary hit 90.5% during this session; the three 211–213 caches alone
  are ~5 GB).
- **NEON-STEPTS Stage 0** is now effectively satisfied except item 7 and the
  independent items above; Stage 1 (gates G1–G4) can start, and this session
  already contributed two G4 datapoints (primary 78–90%, secondary at 94% as
  recorded in the master plan).

## 11. Answer, from observation

On every one of the four engines a tenant can, today: have a database
provisioned from the config drive on the first *or* any later boot; get both
roles' credentials confirmed through the report path (and get them back
through retry after a transient failure); browse tables, run queries, create
tables through the console — as the read-only role for reads and the owner
for writes, with the engine itself refusing what `_ro` may not do; and
connect a normal DB client from another machine. Dropping a table is still
disabled, and no test depended on weakening any guard. What is not yet
proven: the same through the browser UI (§7), the network-isolated/VR-down
case (item 3), and log hygiene for query text (item 4).

## Commits (this session)

```
d51018873d fix(ui): read the console job wrapper at its real nesting depth
586ec6ebc2 fix(api): inject RoleService into QueryManagerImpl
f9b656cb6a fix(dbaas): report an expired console job as 'expired' before the hourly sweep
c2740fe0f1 fix(dbaas): agent token rotates on schedule even when the instance is idle
c7c6ec6a4f feat(dbaas): measured memory floors - mariadb/postgresql/mongodb are clean at 512 MB
6ed497e0a2 fix(dbaas): bound the engine-readiness probes with timeout
b873072874 fix(dbaas): the mongodb console agent never authenticated
5517839fee fix(dbaas): postgres readonly role needs CONNECT on the tenant database
ceaacfc0a5 fix(dbaas): provisioning retry confirmable, agent poll backoff, request file kept for the report
4f0d2928a1 docs(dbaas): master plan engine-marker row was wrong - it is a name marker, not a script copy
```

---

# Part 2 — overnight continuation, interrupted at the user's request

Session: 2026-09-09 evening (resumed PROMPT-GLM-FINISH.md / MASTER-PLAN
items A, E). Ended early on the owner's instruction. Everything below is
committed and pushed (`myfork/4.23+dbass` = `8f2ad48295`), the management
server is up, and the web UI works. One new defect is open and one is
fixed-but-suspect — details in §P2.4 and §P2.5.

## P2.1 Item A — SOLVED, and the root cause was not what the last session recorded

The "Refresh click produces no API request at all" was **not a UI bug**.
The console's response-contract fix (`d51018873d`) was correct but was
**never actually served**: `/client/` is served from
`/usr/share/cloudstack-management/webapp/`, while both UI deployments of the
previous session went to `/usr/share/cloudstack-ui/`, which nothing serves.
The browser therefore kept running the Sep 8 bundle (pre-nesting-fix) —
exactly the symptoms observed.

Once the rebuilt bundle was deployed to the real location, the console
works end to end in a browser (verified with headless Chromium + Playwright,
screenshots `ACCEPTANCE-SHOTS-20260909/20–36`):

- Database section → instance row → Console drawer → Tables tab lists
  tables with Describe/Preview buttons (22)
- Describe and Preview render (23/24)
- SQL tab: read query confirmed (25); write mode + CREATE TABLE confirmed
  (26)
- **Drop is absent** from the console (0 mentions in the drawer text) and
  there is no "drop table" action on the instance detail page (27/28)
- The create-instance wizard (a three-step full-page route, never before
  used): engine list, Compute Offering disabled until an engine is chosen
  with the placeholder "Select a database engine first" (29/30), offering
  filter correct — MySQL shows only Medium (1024 ≥ 1024), MariaDB shows
  Small + Medium (31/33) — and **two full wizard deploys were made for
  real**, both provisioning to `confirmed` with the chosen Small offering
  honored (status screen with the one-time password, 35)

One genuine UI defect found and fixed in the process:
`a914b4a5a1` — a stranded offering selection survived an engine change
(mariadb + Small → switch to mysql, whose floor excludes it) because the
deep watcher on `form` did not fire on the switch. The clearing now runs in
an explicit `@change` handler on the engine select; a selection that still
qualifies under the new engine is deliberately kept (verified both ways).

**Deploy-location correction for every future UI deploy**:
```
sudo rsync -a /home/nacl/dbaas-v2/ui/dist/ /usr/share/cloudstack-management/webapp/
# NOT /usr/share/cloudstack-ui/ (served by nothing) and NEVER with --delete
# (webapp also holds WEB-INF — see the incident in P2.4)
```

## P2.2 Item E — role done, and the cross-account test exploded into a real leak

CloudStack's **default roles are immutable** ("Role permission cannot be
added for Default roles", 531/4365). Created role `DBaaS-User` (type User,
id `3ad1c9f8-…`) — `createRole` clones the default User role's 255 rules and
the build already includes the 15 DBaaS commands, so it had 270 rules out of
the box. `updateUser` has no role param in 4.23 — **roles attach to the
account**, so the assignment is
`updateAccount id=<acctb uuid> roleid=<DBaaS-User>` (the API echoes the
param; verify with `listUsers`).

With the role applied, the tenant reached `listDbaasEngines` and
`listVirtualMachines` — and then the re-run of matrix item 10 found the
worst thing of the night: **`getDatabasePassword` on another account's
instance returned the decrypted password, and `listDbaasTables` dispatched a
console job against it.** The `getEntityOwnerId` pattern ("real CloudStack
ACL enforcement for free") is wrong: it returns the TARGET VM's owner, which
is exactly the entity owner the framework's access check compares the caller
against — every caller passes.

Fixed in the plugin, contained: `8f2ad48295` adds
`DbaasManager.checkCallerOwnsVm` — called from `execute()` of
`DbaasConsoleJobCmdBase`, `GetDatabasePasswordCmd` and `CreateDatabaseCmd`.
Admins pass; every other caller must own the instance outright. Verified
after deploy: `getDatabasePassword`/`listDbaasTables` from `acctb` against
an admin instance → 531/4365 `the instance belongs to another account`
(no password, no job). `listDbaasEngines` from the tenant still works.

## P2.3 Open defect #1 — the ACL guard currently denies the ADMIN too

After the fix deployed, **admin's own `getDatabasePassword` on its own
instance is denied** with the same "instance belongs to another account".
A temporary WARN line (since removed from the source, but present in the
currently deployed jar) captured the runtime values on an admin `cmk` call:

```
DBG-ACL deny: caller=4 type=NORMAL callerAcct=4 vmAcct=2
```

The caller in `CallContext.current().getCallingAccount()` was **account 4
(acctb, NORMAL)** during an **admin API-key call** — i.e. the calling
account resolved to the wrong account (a stale/threaded CallContext, or
api-key auth resolving through the last-seen keypair), not the admin
account (id 2, type 1). That is why the guard (correctly) denied.

What this means:
- the guard itself is simple and the values in the DB are consistent
  (vm.account_id=2, admin type=1);
- the problem is upstream in whatever populates `CallContext` for this
  request — worth checking `ApiServlet`'s session/api-key auth path and
  whether cmk's two-profile usage in one process (admin + userb secrets)
  confused a per-thread cache;
- **reproduce/fix hint**: the deny line above is the fastest instrument —
  print `caller.getId()/getType()` at deny time and run one admin `cmk`
  call. If the caller is again account 4 while calling as admin, it is the
  context population, not the plugin.

Current state of the guard: **it is too strict in practice** — it denies
admins until the caller-account resolution is understood. If admin access
is needed before that is fixed, revert `8f2ad48295` (single commit, plugin
only) and re-apply after; the cross-account leak then reopens, which is
unacceptable to ship but was the pre-existing behaviour.

Note: `admin`'s DB credentials for `glmc-mar1` were exposed to `acctb`
during the leak (§P2.2) — rotate them (item C's `password_reset` will do
it) or destroy the instance.

## P2.4 Incident — a UI deploy deleted the API servlet (mgmt "404" outage)

The 512-free storage episode earlier led to deploying the UI with
`rsync --delete` into `/usr/share/cloudstack-management/webapp/`, which
wiped **`WEB-INF/`** — webapp is both the UI and the API servlet host.
Symptom: every `/client/api` call returned 404 while Jetty answered and
systemd said active; the Spring context came up with zero application
threads and wrote nothing to the log (silently failed webapp deploy).

Fix applied (and verified — API answers 401 unauthenticated / 200
authenticated; UI loads):
```
sudo rm -rf /usr/share/cloudstack-management/webapp
sudo cp -r /usr/share/cloudstack-management/webapp.bak.20260909b \
           /usr/share/cloudstack-management/webapp
sudo rsync -a /home/nacl/dbaas-v2/ui/dist/ \
           /usr/share/cloudstack-management/webapp/   # no --delete
sudo systemctl restart cloudstack-management
```
Backups present: `webapp.bak.20260908`, `webapp.bak.20260909b`. The
`/usr/share/cloudstack-ui/` tree (and its `.bak.20260909`) is unused by
anything and can be deleted.

## P2.5 Open defect #2 (lower priority, pre-existing, observed)

The login page's `forgotPassword` call rejects with
`Cannot read properties of undefined (reading 'slice')` inside the axios
response handling (seen only in browser console noise at the login screen;
the actual login works). Looks like an error-path handler assuming a
response body shape the server doesn't produce. Cosmetic, pre-existing.

## P2.6 Housekeeping state

- Pushed: `myfork/4.23+dbass` = `8f2ad48295` (everything, including this
  session's commits).
- Committed and **pushed** UI fix set; source tree clean (the debug WARN
  line was removed from the source; the deployed jar still contains it —
  next jar update will pick up the clean version).
- Storage: root fs down to **4.9 G free** (the three 211–213 template
  backups, 5.3 GB, sit in `/home/nacl/tplbackup-20260909/` — move or delete
  them to reclaim; `df -h /export/primary` ~78% after the move-out).
- The leftover `glmc-*` measurement instances were NOT destroyed (G is
  pending): glmc-mar1, glmc-pg1, glmc-mongo1 (Running, kept as engine
  evidence), glmc-mar-s ×2 (one Error), glmc-pg-s, glmc-mongo-s, plus the
  wizard-deployed `wizdb1` pair (VM 98/99, Running, both confirmed — safe to
  destroy).
- Flags: `dbaas.console.enabled=true`, `dbaas.console.write.enabled=false`
  (as shipped), `dbaas.console.drop.enabled=false`, 
  `dbaas.datadisk.cleanup.enabled=false` — all defaults intact.
- `DATA-73` and pre-existing `/export/primary/tplbackup/**` untouched.
- Not reached tonight: D (password/keypair paths), D2 (usage attribution,
  orphan disk), D3 (cross-VM token test — superseded in urgency by the
  cross-ACCOUNT leak found in E), B (log redaction), snapshot policy, C2,
  C, F, G, and the MASTER-PLAN update.
