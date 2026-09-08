# Acceptance session — 2026-09-08 (host reboot recovery + first working console round trips)

Continuation of the 2026-09-06/07 overnight console work. This session found
and recovered from a host-level infrastructure failure, then proved the
console transport end to end for the first time, fixed two real bugs that
blocked it, and found two more issues that need a decision next session.

Commit: `2125b812e5` on `v2`/`ui`, pushed.

---

## 1. Host incident and recovery (not a DBaaS bug)

`nfs-server.service` had been stuck in `activating (start-pre)` for **20
hours** (since 2026-09-07 06:44 UTC, predating this session), deadlocked on
its own self-mounted NFS export (`exportfs -au` waiting on an NFS4 RPC call
to a server that could not respond because it was itself waiting to unexport).
Symptoms: load average 148, 14 zombie `find / -type d -name
vmware-vix-disklib-distrib` processes (leftover from earlier `-Dnoredist`
build attempts wandering into the same self-mount and hanging), every command
touching primary/secondary storage blocking indefinitely.

**Fix: full host reboot**, authorized explicitly by the user after confirming
the templates on disk (already `qemu-img check`-clean) would not be affected.
Recovery, in order:

1. Host back up in ~3 minutes, `nfs-server active`, load 3.46
2. System VMs (router, SSVM, CPVM) self-healed via CloudStack's HA framework
   without intervention
3. One test VM (not HA-enabled, as expected) needed a manual
   `startVirtualMachine` — its start job stalled at the CloudStack
   orchestration layer (`AsyncJobMonitor` itself flagged it: "pending for 95
   seconds"). Diagnosed as agent-side congestion from reconciling 4 VMs at
   once through only 5 worker threads, right after reconnect.
   **`systemctl restart cloudstack-agent`** cleared it in under a minute —
   targeted fix, no further host disruption.

No template, volume, or data was touched during this. `DATA-73` and
`tplbackup/` confirmed untouched throughout.

## 2. D3 — the actual blocker for every console job, found and fixed

Root cause, confirmed by reading both sides: `DbaasManagerImpl.agentPollJob`
returned `response.toString()` directly — a raw JSON object, not wrapped in
the command's name (`getdbaasagentjobresponse`) the way every other
CloudStack API response is. `dbaas_agent.py`'s `long_poll()` correctly
expected that wrapper and found an empty object every time, so it silently
treated every dispatched job as "no job" — no error anywhere, the job just
sat in `dispatched` state forever.

Reproduced live before fixing it: job `922abebd` (`table_list` on VM
`d8a41c83`) went `pending → dispatched → failed` with
`"job type table_list is not implemented on this engine"` — which was real
too (see §3) but masked the wrapper bug, since a job that never reaches the
agent's dispatcher can't report that error either. Fixed both:

- **Server**: wrap the response in `getdbaasagentjobresponse` before
  returning, matching convention
- **Agent** (defense in depth): accepts either the wrapped or unwrapped shape,
  so an unpatched management server degrades to "job type not implemented"
  instead of hanging forever

## 3. The whole C2–C4 job-type set had no agent-side implementation

`execute()` in `dbaas_agent.py` only ever handled `"sql"`. The nine job types
the overnight session built server+UI for — `table_list`, `table_describe`,
`table_preview`, `table_create`, `table_drop`, `column_add`, `column_drop`,
`index_create`, `index_drop` — had no handler at all. Every one failed with
`"job type X is not implemented on this engine"`.

Implemented all nine:

- **Six DDL types** (`table_create`, `table_drop`, `column_add`,
  `column_drop`, `index_create`, `index_drop`) share one handler
  (`run_ddl_job`): the server already builds the full SQL `statement`
  (`DbaasConsoleJobCmdBase` subclasses), so the agent just executes it as the
  **owner** role and reports success/failure. Refuses outright on mongodb —
  there is no generated DDL there, matching `PLAN-DBAAS-CONSOLE.md` §9's
  browse-only design for that engine.
- **`table_list`**: `information_schema.tables` (mysql/mariadb/postgresql),
  `list_collection_names()` (mongodb)
- **`table_describe`**: columns + indexes from `information_schema` per
  engine; mongodb samples one document and lists its indexes
- **`table_preview`**: `SELECT * FROM <quoted> LIMIT n OFFSET m`
  (mysql/mariadb/postgresql), `find().skip().limit()` (mongodb) — reuses the
  same row/byte-capping logic `run_sql_job` uses (factored out into
  `rows_from_cursor` so it isn't duplicated)

Identifier quoting mirrors the server's `quoteIdentifier` exactly (backticks
default, double quotes for postgresql); table names are re-validated against
the same `IDENTIFIER_RE` the server already checked, as the last line before
interpolation into SQL.

## 4. Config gap found while proving `table_create`: no type allowlist

`createDbaasTable` refused every column type with `"allowed are []"`.
`DbaasConsoleJobCmdBase.consoleTypeAllowlist` reads a `"types"` key from
`config.json` — the code was correct, the key had simply never been added,
neither in `extensions/dbaas/config.example.json` nor the deployed copy.

Added a per-engine allowlist (mysql, mariadb, postgresql — common types with
`(n)` parameterised entries for `VARCHAR`/`CHAR`/`NUMERIC`). **mongodb
intentionally has no entry**, consistent with `run_ddl_job` refusing DDL
there. Patched the deployed `/usr/share/cloudstack-management/extensions/dbaas/config.json`
live (backup: `config.json.bak.20260908`) and committed the same section to
`config.example.json` so the next deployment doesn't lose it.

## 5. Proven live, end to end, for the first time tonight

On a fresh instance from the patched `dbaas-mysql-v2` template (210, both
secondary and primary cache patched — backed up first, `qemu-img check` clean
both times):

```
deployVirtualMachine (startvm=false) → createDatabase → pending → confirmed
listDbaasTables                       → {"tables": []}
createDbaasTable widgets(id INT PRI, label VARCHAR(64))  → confirmed
listDbaasTables                       → {"tables": ["widgets"]}
describeDbaasTable widgets            → columns: id INT PRI NOT NULL, label VARCHAR(64) nullable
                                          indexes: PRIMARY (id), UNIQUE
getDbaasJobResult (2nd fetch, any job) → collected: true, result gone
```

This is the first time any job has gone from submission to a correct,
readable result. The transport (long-poll, dispatch-once, report,
delete-on-read) is now observed working, not assumed.

## 6. Matrix items — honest status

| § 11 item | Result |
| --- | --- |
| 1 list tables (empty) | **pass** — `{"tables": []}` on a fresh database |
| 4 create table + describe | **pass** — exact column/index shape returned |
| 6 query (`SELECT`) | **pass** — canary literal round-tripped correctly |
| 7 write refused while `write.enabled=false` | **pass** — refused at the API layer, before reaching the agent or the database |
| 7b write path itself, `write.enabled=true` | **environmental failure, not a code bug** — see §7 (OOM) |
| 8 `dropDbaasTable` confirm mismatch | **partial** — confirmed the master switch (`dbaas.console.drop.enabled=false`) correctly refuses before the confirm-string check is ever reached; the confirm-mismatch logic itself is not independently testable without enabling drop, which stays forbidden by design |
| 9 statement timeout kills a slow query, agent survives | **pass** — `SELECT SLEEP(60)` returned after ~28s reporting `1` (MySQL's own "interrupted" signal), consistent with the 30s `MAX_EXECUTION_TIME`; agent kept polling normally afterward |
| 10 ACL denial across accounts | **not run** — only one account (`admin`) exists on this system; creating a second account for this test was not authorized this session |
| 11 replayed agent token after rotation | **not run** |
| 12 result fetched twice | **pass** — second fetch returns `collected: true` with no result body, confirmed on two separate jobs |
| 13 log hygiene | **FAIL — real finding, see §8** |
| 14 job expiry with agent stopped | **not run** |
| 15 VR stopped | **not run** — needs a dedicated pass; not attempted tonight given time spent on host recovery |

## 7. OOM on the "Small Instance" offering (512 MB) — real, reproduced twice

The write-path test (`INSERT` via the owner role, `write.enabled=true`
temporarily) failed first with `(2013, 'Lost connection to MySQL server
during query')`, then on retry with `(2003, "Can't connect ... Connection
refused")`. Read the guest's journal offline (safe read-only `qemu-nbd` copy
of the running overlay, same method used earlier in the session):

```
Sep 08 03:29:21 dbaas-matrix-2 systemd[1]: mysql.service: A process of this unit has been killed by the OOM killer.
Sep 08 03:29:21 dbaas-matrix-2 systemd[1]: mysql.service: Failed with result 'oom-kill'.
Sep 08 03:29:32 dbaas-matrix-2 systemd[1]: mysql.service: A process of this unit has been killed by the OOM killer.
```

**Not a DBaaS code defect.** "Small Instance" is 512 MB total — mysqld +
the Python agent (with pymysql/psycopg2/pymongo importable) + OS overhead
does not fit. Confirmed by redeploying identically on "Medium Instance"
(1024 MB): provisioning, `table_list`, `table_create`, `table_describe`, and
the timeout test all succeeded cleanly with no memory pressure.

**Recommendation for next session:** document a minimum offering size for
DBaaS instances (1 GB, based on tonight's evidence), or tune `mysqld`'s
`innodb_buffer_pool_size` down in the template to fit 512 MB safely. Neither
was done tonight — out of scope for a live-instance session, needs a template
rebuild either way.

## 8. Log hygiene — real finding, needs a decision beyond this session

Ran a query with a distinctive literal (`SELECT 'zx9k_secret_marker_42' AS
canary`), then:

```
$ sudo grep -c "zx9k_secret_marker_42" management-server.log
4
$ sudo grep "zx9k_secret_marker_42" management-server.log
sql=SELECT 'zx9k_secret_marker_42' AS canary
sql=SELECT 'zx9k_secret_marker_42' AS canary
result={"columns": ["canary"], "rows": [["zx9k_secret_marker_42"]]}
result={"columns": ["canary"], "rows": [["zx9k_secret_marker_42"]]}
```

Both the SQL text and the full query result landed in
`management-server.log` in plaintext. This is exactly what
`PLAN-DBAAS-CONSOLE.md` §2.4 required never to happen.

**Cause, confirmed by reading the code and the deployed config**:
`RunDbaasQueryCmd` correctly declares `requestHasSensitiveInfo = true`;
`GetDbaasJobResultCmd` correctly declares `responseHasSensitiveInfo = true`.
Both annotations are present and correct. **They don't do what this needed**:
they mask the audit-event/API-response layer (what a caller sees back over
the API, what gets written to the `event`/audit tables) — they do **not**
redact `ApiServlet`'s raw DEBUG-level request/response logging, which logs
every HTTP parameter and every response body verbatim regardless of
sensitivity flags. This host's `/etc/cloudstack/management/log4j2.xml` has
`com.cloud` and `org.apache.cloudstack` both at `DEBUG`.

**This is not something to fix unilaterally.** It's either a host-wide
logging configuration change (drop those loggers to `INFO` — affects every
API command's debug visibility, not just DBaaS) or a code-level redacting
filter in `ApiServlet` or a log4j2 pattern (a CloudStack core change). Both
are bigger than this session's scope and need a decision, not a quick patch.
Recorded here with exact reproduction steps so it can be picked up directly.

## 9. Settings — final state

| Setting | Value | Note |
| --- | --- | --- |
| `dbaas.console.enabled` | `true` | left on, matching the prior session's choice — work continues |
| `dbaas.console.write.enabled` | `false` | turned on only for the write-path test (§7), turned back off immediately after |
| `dbaas.console.drop.enabled` | `false` | never touched |
| `dbaas.datadisk.cleanup.enabled` | `false` | never touched |

## 10. Cleanup and evidence preserved

- `DATA-73`: untouched, confirmed
- `tplbackup/`: only additions (backups of the 210 primary cache and
  secondary image before patching, `1d9e7b9c-....bak.20260908` and
  `97680484-....qcow2.bak.20260908`), nothing existing removed or modified
- Test instances `dbaas-matrix-1`/`-2` (OOM'd) destroyed and expunged after
  their evidence was extracted; `dbaas-matrix-3` on Medium Instance left
  running for the next session to continue matrix items 10/11/14/15 without
  redeploying
- `config.json.bak.20260908` kept next to the live, patched
  `/usr/share/cloudstack-management/extensions/dbaas/config.json`

## 11. What the next session should do, in order

1. **Item 15 (VR stopped)** — the architecture's central claim, still
   completely untested. Highest priority.
2. Item 10 (cross-account ACL) — needs a second account created first
3. Item 11 (token replay after rotation) and item 14 (job expiry, agent
   stopped)
4. Decide on §8 (log hygiene) — redacting filter vs. documented log-level
   requirement — and implement whichever is chosen
5. Decide on §7 (offering sizing) — minimum offering doc vs. template-level
   `innodb_buffer_pool_size` tuning
6. Only after 1–5: repeat template patching for 211/212/213 (mariadb,
   postgresql, mongodb) with the now-working agent, and run the same
   proof-of-round-trip on each

## 12. One-line summary (§12.5, answered from what was observed tonight)

A tenant on `dbaas-mysql-v2`, on the shared network with the virtual router
**up**, can browse their tables, create a table, and run a read-only query —
all proven live tonight, for the first time. **Not yet answered**: whether
any of that survives with the virtual router stopped (item 15, not run) or
across account boundaries (item 10, not run). Dropping a table is still
disabled by design. Log hygiene is currently **failing** — SQL text and
results are visible in the management log at this host's current logging
configuration, and that needs a decision before this goes anywhere near a
real tenant.
