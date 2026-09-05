# Plan — DBaaS console: query, browse tables, create/drop table

Scope, exactly three tenant-facing capabilities plus the transport they need:

1. **Query** — run SQL against your own database from the UI
2. **Browse** — list tables, see columns/indexes, preview rows
3. **Manage schema** — create a table, drop a table (and columns/indexes)

Connecting to the database with a normal client (`psql`, DBeaver, `mysql`)
must keep working exactly as it does today and is not touched by any of this.

Prerequisite, non-negotiable: PLAN.md §9 (three remaining templates + the
Phase B acceptance run) is finished and green. Everything below assumes an
instance that provisions and reports back reliably.

Background and the options that were rejected: `NEON-ROADMAP.md` §8.

---

## 1. Transport: the agent polls, nothing dials in

The management server has no route to the instance and must not gain one —
that property is what the v2 rewrite bought. So the instance drives every
exchange:

```
UI ──createDbaasJob──▶ MS ──(job row, state=pending)──┐
                                                      │
guest agent ──getDbaasAgentJob (long-poll ≤25s)──────▶┘  returns job
guest agent ── executes locally ──▶ engine over the local socket
guest agent ──reportDbaasJobResult──▶ MS   (result row, TTL)
UI ──getDbaasJobResult (poll)──▶ MS  → rows, then the result row is deleted
```

Nothing listens inside the guest. No inbound firewall rule, no route from the
management server, and the whole path keeps working on an isolated network
with the virtual router down — the same property provisioning has.

**Long-poll, not fixed-interval poll.** The agent's `getDbaasAgentJob` holds
the HTTP request open for up to `dbaas.agent.longpoll.seconds` (25) and
returns the moment a job is queued. Perceived latency is then network
round-trip plus execution, not half a poll interval. A fixed 2 s poll would
average 1 s of dead time per statement and feel like a batch tool.

The agent is the same one PLAN.md Phase D already requires for Reset Database
Password. Build it once, here, and reset password becomes another job type.

### 1.1 Agent authentication

`reportDbaasProvisioningResult` uses a **one-time** token; an agent needs a
**long-lived** one. New table, new lifecycle, deliberately separate from the
provisioning token so a leaked agent token cannot confirm a provisioning
result or vice versa:

- minted at `createDatabase`, delivered on the config drive with the
  provisioning request
- stored **hashed** (SHA-256), raw value never persisted, same as today
- scoped to one instance UUID; every call carries `vmid` + `token`
- rotated by the agent itself: each successful poll may return a new token
  which replaces the old one on next use (`dbaas.agent.token.rotate.days`,
  default 7)
- revoked when the instance is expunged, by the existing sweeper
- rate limited per source IP exactly like the report endpoint

Both agent endpoints are registered through `PluggableAPIAuthenticator`, the
mechanism `reportDbaasProvisioningResult` already uses.

---

## 2. Data model

### 2.1 `dbaas_agent_tokens`

| column | type | note |
| --- | --- | --- |
| id | bigint pk | |
| vm_id | bigint, unique | one live token per instance |
| token_hash | char(64) | SHA-256 of the raw token |
| created_at / rotated_at / last_seen_at | datetime | `last_seen_at` drives the "agent online" indicator |

### 2.2 `dbaas_jobs`

| column | type | note |
| --- | --- | --- |
| id | bigint pk | |
| uuid | varchar(40) | the id the UI polls with |
| vm_id | bigint | target instance |
| account_id | bigint | who asked — for ACL and audit |
| type | varchar(32) | `sql`, `table_list`, `table_describe`, `table_preview`, `table_create`, `table_drop`, `password_reset` |
| db_role | varchar(16) | `readonly` or `owner` — which credential the agent uses |
| payload | text | encrypted (`DBEncryptionUtil`); SQL text or the structured request |
| state | varchar(16) | `pending`, `dispatched`, `done`, `failed`, `expired` |
| created_at / dispatched_at / finished_at | datetime | |
| expires_at | datetime | `dbaas.job.ttl`, default 120 s — a job nobody picks up dies |
| row_count | int | for the audit trail; never the rows themselves |
| truncated | tinyint | result hit a cap |
| error | varchar(1024) | truncated server-side, like `status_message` |

### 2.3 `dbaas_job_results`

| column | type | note |
| --- | --- | --- |
| job_id | bigint pk | |
| result | mediumtext | encrypted JSON `{columns:[...], rows:[[...]]}` |
| created_at | datetime | swept after `dbaas.job.result.ttl` (300 s) |

Split from `dbaas_jobs` so **delete-on-read is one statement on one row**, and
so the job's audit trail survives after the tenant data is gone.

### 2.4 Rules about tenant data on the management server

These are requirements, not preferences:

- results are encrypted at rest with the same `DBEncryptionUtil` path as
  credentials
- results are deleted **on read**, and swept by TTL if never read
- neither result rows nor query text is ever written to
  `management-server.log` — a `WHERE` predicate is as sensitive as the row it
  returns. Log the job uuid, type, account, instance, row count, duration
- every job is an `@ActionEvent` (PLAN.md Phase E's audit item becomes
  mandatory here)

---

## 3. Roles inside the database

Provisioning creates the owner today. Add a second role at provision time:

| engine | read-only role |
| --- | --- |
| PostgreSQL | `CREATE ROLE <user>_ro LOGIN PASSWORD ...` + `GRANT pg_read_all_data` |
| MySQL / MariaDB | `CREATE USER '<user>_ro'@'%'` + `GRANT SELECT ON <db>.*` |
| MongoDB | user with the `read` role on the database |

- **browse and query default to `_ro`**
- **DDL uses the owner role**, which is scoped to the tenant's own database
  and is not a superuser
- writing from the SQL editor is a per-session opt-in that switches to owner
  and is audited as such

Store it as a second `dbaas_credentials` row with a new `db_role` column
(`owner` / `readonly`), so the existing "newest row per instance" read path
becomes "newest row per (instance, role)".

**This must land in the engine scripts during the PLAN.md §9 template
rebuild.** Adding it afterwards means every instance built before it has no
read-only role and the console has to fall back to the owner credential —
which is precisely the risk this exists to remove.

---

## 4. API surface

All user-facing commands are ACL'd to the instance owner via
`getEntityOwnerId`, identical to the existing DBaaS commands.

### 4.1 Job submission (user → MS)

| command | parameters | returns |
| --- | --- | --- |
| `listDbaasTables` | `virtualmachineid` | jobid |
| `describeDbaasTable` | `virtualmachineid`, `table` | jobid |
| `previewDbaasTable` | `virtualmachineid`, `table`, `limit` (≤ cap), `offset` | jobid |
| `createDbaasTable` | `virtualmachineid`, `table`, `columns` (JSON) | jobid |
| `dropDbaasTable` | `virtualmachineid`, `table`, `confirm` | jobid |
| `addDbaasColumn` / `dropDbaasColumn` | `virtualmachineid`, `table`, `column`, `type` | jobid |
| `createDbaasIndex` / `dropDbaasIndex` | `virtualmachineid`, `table`, `columns`, `name` | jobid |
| `runDbaasQuery` | `virtualmachineid`, `sql`, `write` (bool, default false) | jobid |
| `getDbaasJobResult` | `jobid` | state + result once, then deleted |

`runDbaasQuery` is `requestHasSensitiveInfo = true`: the SQL text can contain
literals as sensitive as the data.

**No client SQL reaches the database except through `runDbaasQuery`.** Every
other command takes identifiers and the server builds the statement.

### 4.2 Validation, server-side, before a job is created

- table/column/index names: the existing `IDENTIFIER_PATTERN`
  (`^[A-Za-z][A-Za-z0-9_]{0,31}$`)
- column types: matched against a per-engine allowlist read from
  `config.json` (`"types": { "mysql": ["INT", "BIGINT", "VARCHAR(n)", ...] }`)
  — never a hardcoded list in Java, same rule as the engines map
- `limit` clamped to `dbaas.console.row.limit`
- `dropDbaasTable` requires `confirm` to equal the table name exactly
- `runDbaasQuery` with `write=true` is refused unless
  `dbaas.console.write.enabled` is true

### 4.3 Agent endpoints (guest → MS, PluggableAPIAuthenticator)

| command | parameters | behaviour |
| --- | --- | --- |
| `getDbaasAgentJob` | `vmid`, `token` | long-poll ≤ 25 s; returns one job and marks it `dispatched`, or 204-equivalent empty |
| `reportDbaasJobResult` | `vmid`, `token`, `jobid`, `status`, `rowcount`, `truncated`, `result`, `error` | writes the result row, sets `done`/`failed` |

A job is dispatched **once**. If the agent dies mid-execution, the job expires
by TTL and the UI reports a timeout — no silent retry that could run a
statement twice.

---

## 5. The agent

Ships in the template at `/opt/dbaas/agent/`, runs as a systemd unit
(`dbaas-agent.service`, `Restart=always`, `After=network-online.target` and
the engine's unit).

Responsibilities, and nothing else:

1. long-poll `getDbaasAgentJob`
2. resolve which credential to use from the job's `db_role`, reading it from a
   root-only file written at provision time (`/var/lib/dbaas/roles.json`,
   0600) — the agent never receives a password over the wire
3. execute with a hard statement timeout (`SET statement_timeout` on
   Postgres, `MAX_EXECUTION_TIME` on MySQL/MariaDB, `maxTimeMS` on MongoDB)
4. cap the result while streaming: stop at `row.limit` rows or `bytes.limit`
   bytes, whichever comes first, and set `truncated`
5. report the outcome; on failure report the engine's error message,
   truncated to 1000 chars like every other message in this system
6. one job at a time per instance; a second concurrent dispatch is refused

Written in Python 3 (already required by `firstboot.sh`), using the engine's
own client libraries or its CLI, no new packaging burden beyond
`python3-psycopg2` / `python3-pymysql` / `python3-pymongo` per image.

---

## 6. Configuration keys

| key | default | meaning |
| --- | --- | --- |
| `dbaas.console.enabled` | `false` | master switch; ships off |
| `dbaas.console.row.limit` | `1000` | rows per result |
| `dbaas.console.bytes.limit` | `1048576` | 1 MB per result |
| `dbaas.console.statement.timeout` | `30` | seconds |
| `dbaas.console.write.enabled` | `false` | allows `runDbaasQuery write=true` |
| `dbaas.console.drop.enabled` | `false` | allows `dropDbaasTable` (see §8) |
| `dbaas.agent.longpoll.seconds` | `25` | server-side hold time |
| `dbaas.agent.token.rotate.days` | `7` | |
| `dbaas.job.ttl` | `120` | seconds before an undispatched job expires |
| `dbaas.job.result.ttl` | `300` | seconds before an uncollected result is swept |

---

## 7. UI

A **Tables** tab and a **SQL** tab on the Database page.

Tables tab:
- table list (name, estimated rows, size) with a refresh action
- selecting a table shows columns, types, nullability, keys, indexes
- row preview with paging, capped and labelled when truncated
- "Create table" form: table name + repeatable column rows (name, type from
  the allowlist the backend reports, nullable, default, primary key). It shows
  the generated statement before submitting — the tenant should always be able
  to see the SQL that will run
- "Drop table": type-the-name confirmation, and a plain statement that there
  is no restore path (see §8)

SQL tab:
- editor, run button, result grid, elapsed time, row count, truncation notice
- a visible **read-only / write** switch, defaulting to read-only
- every submission shows a "queued → running → done" state, because results
  arrive on the agent's return, not synchronously

Both tabs must handle "agent has not checked in" (`last_seen_at` older than
2 × long-poll) with a clear message rather than a spinner that never ends.

---

## 8. Drop table has a prerequisite

`DROP TABLE` is the first irreversible thing this product would hand a tenant.
There is no backup, no snapshot-before-DDL and no undo today (`NEON-ROADMAP.md`
N3 is not built).

Therefore: **`dbaas.console.drop.enabled` ships `false` and stays false until
per-database backup exists.** Until then, dropping a table is possible only by
typing `DROP TABLE` in the SQL editor with write mode explicitly enabled — a
deliberate act, not a button next to a table name.

When it is enabled, the flow must take a backup of that table first
(`CREATE TABLE _dbaas_backup_<name>_<ts> AS SELECT * FROM <name>` where the
engine supports it) or refuse.

---

## 9. Engine coverage

| capability | PostgreSQL | MySQL / MariaDB | MongoDB |
| --- | --- | --- | --- |
| list / describe / preview | full (`information_schema`, `pg_indexes`) | full (`information_schema`) | collections, sampled field map, document preview |
| create / drop table | full | full | create / drop collection |
| free-form query | full | full | **not offered** — no SQL; a Mongo shell path is a separate design |

Mongo gets the browse tier and nothing else in this plan. Say so in the UI
rather than showing an editor that rejects everything.

---

## 10. Phases

Each phase ends in a state that is shippable and independently auditable.

### C0 — read-only role in the templates
- [ ] `_ro` role added to `mysql.sh`, `mariadb.sh`, `postgresql.sh`,
      `mongodb.sh`
- [ ] `db_role` column on `dbaas_credentials`; credential read path becomes
      per (instance, role)
- [ ] folded into the PLAN.md §9 template rebuild — **not a separate rebuild**

### C1 — agent and job pipeline (no user-facing feature yet)
- [ ] `dbaas_agent_tokens`, `dbaas_jobs`, `dbaas_job_results` + sweeper
- [ ] `getDbaasAgentJob` / `reportDbaasJobResult` with long-poll, rate limit,
      one-dispatch semantics
- [ ] agent in the image, systemd unit, token rotation
- [ ] acceptance: a hand-inserted `table_list` job returns a result within
      1 s, on a network with the VR stopped

### C2 — table browser (feature 3)
- [ ] `listDbaasTables`, `describeDbaasTable`, `previewDbaasTable`
- [ ] Tables tab in the UI
- [ ] acceptance: browse a database with 50 tables including one with 1 M rows;
      preview is capped, labelled, and returns in under 3 s

### C3 — SQL editor (feature 1)
- [ ] `runDbaasQuery` + `getDbaasJobResult`, read-only by default
- [ ] SQL tab in the UI with the read-only/write switch
- [ ] acceptance: caps, timeout and role enforcement all demonstrated (§11)

### C4 — schema management (feature 2)
- [ ] `createDbaasTable`, `addDbaasColumn`, `dropDbaasColumn`,
      `createDbaasIndex`, `dropDbaasIndex`, type allowlist in `config.json`
- [ ] create-table form with statement preview
- [ ] `dropDbaasTable` implemented but **disabled by default** per §8

### C5 — reset password over the agent
- [ ] `password_reset` job type; `resetDatabasePassword` stops throwing;
      the UI action returns. Closes PLAN.md Phase D's open item at nearly zero
      extra cost, since the transport already exists

---

## 11. Test matrix

Functional, per engine (Postgres, MySQL, MariaDB; Mongo for browse only):

1. list tables on an empty database → empty result, not an error
2. describe a table with a composite primary key and two indexes
3. preview a table with 1 M rows → exactly `row.limit` rows, `truncated=true`
4. create a table with every allowed column type, then describe it
5. add a column, create an index, drop both
6. query returning 0 rows; query returning NULLs; query returning a 10 MB
   text column → byte cap enforced, not an OOM

Negative and security, all of which must be demonstrated, not asserted:

7. `runDbaasQuery` with `write=false` running `INSERT` → refused by the
   database because the session is `_ro`, not merely by the UI
8. `dropDbaasTable` with a mismatched `confirm` → refused
9. a query that runs forever (`pg_sleep(600)`) → killed at the statement
   timeout, job reported `failed`, agent still healthy afterwards
10. account B calling any command against account A's instance → ACL denial
11. replaying a captured agent token after rotation → rejected
12. a job result fetched twice → second fetch returns "already collected", and
    the row is gone from `dbaas_job_results`
13. `management-server.log` after the whole matrix → **no SQL text, no result
    rows, no passwords** anywhere in it
14. agent stopped, then a job submitted → job expires by TTL, UI says so
15. VR stopped → every one of C2/C3/C4 still works

---

## 12. What to report back when this is done, so it can be audited

Write the report as a file in the repo (`CONSOLE-REPORT-<date>.md`) and hand
back the items below. The point of each is that it can be **checked**, not
taken on trust — the previous audit round found that everything claimed as
fixed was fixed, but only because the claims were specific enough to verify.
Where something was not done or not tested, say so explicitly; an honest gap
is worth more than a green tick that has to be re-earned later.

### 12.1 What was built

1. Commit range implementing this plan, and for each phase C0–C5: **done /
   partial / not started**.
2. For each API command in §4: the file and line of its `execute()` and of the
   validation it performs. A table of `command → file:line` is enough.
3. Schema: the actual `CREATE TABLE` statements that ran, and the output of
   `SHOW CREATE TABLE dbaas_jobs` / `dbaas_job_results` /
   `dbaas_agent_tokens` from the live database.
4. Config keys from §6 that exist, with their live values
   (`listConfigurations name=dbaas.`) — including which ones ship `false`.

### 12.2 Evidence that it works

5. The test matrix in §11, item by item: **pass / fail / not run**, each with
   the command issued and the observed result. Items 7–15 are the ones that
   matter most; a report that only covers 1–6 has not tested anything
   dangerous.
6. Timing on a real instance: submit → result for (a) a table list, (b) a
   1000-row preview, (c) a trivial `SELECT 1`. Report the median of five, not
   a single best case. If the long-poll path is not actually delivering
   sub-second latency, say what it is instead.
7. The VR-stopped run (item 15): which commands were exercised and what the
   guest's `journalctl -u dbaas-agent` showed during it.

### 12.3 Evidence about the things that are easy to get wrong

8. **Log hygiene** (item 13): the exact grep used over
   `management-server.log` and its output. E.g. searching for a distinctive
   literal used in a test query, and for the test row values. Empty output is
   the result being claimed — show the command that produced it.
9. **Role enforcement** (item 7): the error the *database* returned, not the
   UI's message. It must be a permission error from the engine.
10. **Delete-on-read** (item 12): `SELECT count(*) FROM dbaas_job_results`
    before and after the second fetch.
11. **Token rotation** (item 11): the rejected call's log line, and
    confirmation that the raw token appears nowhere in the database
    (`SELECT token_hash` only).
12. **Encryption at rest**: a raw `SELECT payload FROM dbaas_jobs` and
    `SELECT result FROM dbaas_job_results` showing ciphertext, for a job whose
    plaintext you know.

### 12.4 What was not done

13. Anything in §10 left unbuilt, and anything in §11 left un-run — listed,
    not omitted.
14. Known defects found while building and consciously not fixed, with the
    reason.
15. Every place the implementation departed from this plan, and why. A
    departure with a stated reason is fine; a silent one is what makes an
    audit expensive.

### 12.5 The one-line summary that must be in the report

State plainly whether a tenant can, on a network the management server cannot
reach, with the virtual router stopped: browse their tables, run a query, and
create a table — and whether dropping a table is still disabled. That sentence
is what the whole plan is for.
