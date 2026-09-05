# Neon-like DBaaS on CloudStack — feasibility and staged plan

Goal as stated: a service that feels like [Neon](https://neon.com/) — projects
with branchable databases, instant provisioning, scale-to-zero, point-in-time
restore, a connection-pooling endpoint and an HTTP query path — built on the
CloudStack DBaaS plugin in this repository.

This document is a plan and a feasibility assessment, not a commitment. Nothing
here is started; PLAN.md §9 (the three remaining engine templates and the Phase
B acceptance run) is still the active work and must land first, because every
item below assumes the config-drive path actually works.

## 1. What Neon is, and which part cannot be copied

Neon is not "managed Postgres on VMs". Its behaviour comes from replacing
Postgres's storage layer:

- **Pageserver + safekeepers.** Postgres's WAL is streamed to safekeepers and
  materialised by a pageserver that serves individual 8 KB pages on demand.
  Compute nodes hold no durable state.
- **Branches are copy-on-write at page level**, made by the pageserver from a
  point in the WAL history. A branch of a 1 TB database is created in
  milliseconds and costs nothing until written to.
- **Compute is stateless and disposable**, so scale-to-zero and cold start in
  the hundreds of milliseconds are possible, and billing is per second of
  compute.

None of that is reproducible on top of CloudStack volumes without writing a
storage engine. **What is reachable is the product surface — projects,
branches, restore points, pooled endpoints, an HTTP path — with coarser
granularity and much slower operations.** A branch here is minutes and a full
volume copy on most storage backends, not milliseconds and free. Any plan that
promises otherwise is lying about the substrate.

The honest framing for this project: *Neon-shaped API and workflow, VM-grade
timings.*

## 2. The substrate decision, which gates everything else

Two viable substrates, and this is the one choice that cannot be deferred:

**A. Keep VM-per-database (today's model).** Every capability below is built
from CloudStack primitives: volume snapshots, `createVolume` from a snapshot,
`scaleVirtualMachine`, start/stop. Reuses everything already built — templates,
config-drive provisioning, the credential store, the UI. Branch and wake
latencies are measured in minutes and tens of seconds respectively.

**B. Move to containers on CloudStack Kubernetes Service (CKS).** Run
[CloudNativePG](https://cloudnative-pg.io/) or similar: pods instead of VMs,
PVCs instead of ROOT volumes, an operator that already implements PITR, backup
to object storage, replicas and failover. Cold start becomes seconds, branching
becomes a storage-class clone (instant on Ceph RBD), and PITR is a feature of
the operator rather than something to build.

B is much closer to Neon and much less code to write for the hard parts. It
also throws away most of the v2 work — the templates, `firstboot.sh`, the
config-drive transport — and adds CKS, a CSI driver and an operator as
operating dependencies. **A is the pragmatic continuation; B is the one that
actually reaches the target behaviour.** Choosing A means accepting that
"branch" is a slow, heavyweight operation forever.

Everything below is written for **A**, since it is the continuation of this
repository. Where B would change the answer, it is noted.

## 3. Feature-by-feature, with honest fidelity

| Neon feature | On CloudStack (substrate A) | Fidelity |
| --- | --- | --- |
| Instant provisioning | Template + config drive (built) | Minutes; a pre-warmed pool of stopped instances would cut it to the boot time |
| Project / branch / endpoint model | New tables + API commands; no infrastructure change | Full — this is just a data model |
| Branch (same instance) | Postgres `CREATE DATABASE ... TEMPLATE <src>`, MySQL/MariaDB dump+restore, MongoDB `$out`/`mongodump` | Good for small data, same-instance only, blocks writes to the source on Postgres while copying |
| Branch (isolated instance) | `createSnapshot` on the data volume, `createVolume snapshotid=`, deploy a new instance from the same template, attach | Minutes to hours by data size; instant only on backends with real CoW clones (Ceph RBD) |
| Point-in-time restore | pgBackRest / WAL-G (Postgres), XtraBackup (MySQL/MariaDB) shipping to secondary storage or S3, plus scheduled `createSnapshotPolicy` | Achievable and genuinely useful; per-engine work, three separate implementations |
| Scale to zero | Idle detector in the instance, `stopVirtualMachine`; wake through a proxy that starts the instance on an inbound connection | Wake is 30–90 s. Postgres clients time out at their `connect_timeout` — the proxy must hold the socket and most drivers will not wait that long. This is the weakest item on substrate A |
| Autoscaling compute | `scaleVirtualMachine` (needs dynamic scaling on the template and offering), or stop → change offering → start | Live scaling only if the hypervisor and template support CPU/memory hotplug; otherwise a restart |
| Connection pooling | PgBouncer inside the instance, or a shared pooler in front | Full — this one is straightforward |
| HTTP / serverless driver | A small SQL-over-HTTP gateway per instance or shared | Reachable, but it is a new authenticated network surface and needs the same scrutiny the report endpoint got |
| Per-second billing | CloudStack usage records are per hour by default | Out of reach without usage-service work; not proposed |

## 4. Data model

Today the plugin stores one flat table, `dbaas_credentials`, keyed loosely by
instance, and inserts a new row per create/reset with only the newest read back
(PLAN.md §4). A Neon-shaped product needs a real hierarchy:

- **project** — the tenant-visible unit; owns quotas and default engine
- **branch** — belongs to a project, has a parent branch (null for `main`), a
  creation point (snapshot id or backup LSN/timestamp) and a lifecycle state
- **endpoint** — the thing that actually serves connections: an instance plus a
  port, attached to exactly one branch, with its own state (`active`, `idle`,
  `stopped`)
- **credential** — moves under endpoint, with its history preserved rather than
  the current "insert and read the newest" arrangement

This is the cheapest phase to get wrong and the most expensive to change later,
because every API below is shaped by it. It is also the only phase that
delivers nothing visible on its own.

## 5. Phases

Each phase is independently useful and independently abandonable. None starts
until PLAN.md §9 is finished.

### N0 — Substrate decision (blocking, no code)

Pick A or B from §2 with the branch and cold-start numbers written down and
accepted. If B, most of this file is rewritten and the v2 template work becomes
legacy. Acceptance: a written decision, and for A, measured timings from a real
snapshot-and-clone of a 10 GB data volume on this storage backend.

### N1 — Project / branch / endpoint model

Schema and API only: `createDbaasProject`, `listDbaasProjects`,
`createDbaasBranch` (initially recording only, no data copy), `listDbaasBranches`,
`listDbaasEndpoints`. Existing credentials migrate into the new shape as one
project, one `main` branch, one endpoint per instance. The UI's Database page
becomes a project list. Acceptance: everything that works today still works,
addressed through the new model.

### N2 — Same-instance branching and pooling

The cheap 80% of "branching": a branch of a database that lives on the same
instance, made with the engine's own copy mechanism (`CREATE DATABASE ...
TEMPLATE` on Postgres), plus PgBouncer in the template so a branch is a
connection string rather than a new machine. Needs the in-VM agent from PLAN.md
Phase D — this is the first feature that requires talking to a *running*
instance, the same gap that blocks Reset Password today. Acceptance: branch a
1 GB database, connect to both, confirm writes to one do not appear in the
other.

### N3 — Backups and point-in-time restore

Per-engine backup shipping (pgBackRest first, Postgres only) to secondary
storage or S3, a retention policy, `restoreDbaasBranch` to a timestamp into a
*new* branch, never in place. Acceptance: write, note the time, write again,
restore to the noted time into a new branch, confirm the second write is absent
and the first is present.

### N4 — Isolated branches by volume clone

The real thing: snapshot the source endpoint's data volume, create a volume
from the snapshot, deploy an endpoint on it. Independent compute, independent
data. Acceptance: branch a running database onto a separate instance, confirm
isolation, and record the wall-clock time honestly per data size.

### N5 — Idle suspend and wake

Idle detection in the agent (no client connections for N minutes → report
idle → plugin stops the instance), plus a wake path. The wake path is the
open design problem: a TCP proxy that accepts the connection, starts the
instance and holds the client for 30–90 s will exceed most drivers' timeouts,
so the honest options are (a) a documented "first connection after idle fails,
retry in a minute" contract, (b) wake triggered from the UI/API rather than the
connection, or (c) hypervisor-level suspend/resume if CloudStack exposes it to
the user API — **needs verification, do not assume it does**. Acceptance:
measure the wake time and decide whether the feature is honest to ship.

### N6 — HTTP SQL endpoint

A small authenticated SQL-over-HTTP service, statement allowlist or full
passthrough, rate limited, credential-scoped. Treat as a new externally
reachable surface: same review bar as `reportDbaasProvisioningResult`, plus
TLS, which the current design does not have anywhere yet.

## 6. To verify before committing to any of this

- Does the storage backend on `cloudstackcve` do CoW clones from a snapshot, or
  a full copy? This single answer decides whether N4 is a feature or a
  liability. Measure it, do not read it off a wiki.
- Is dynamic scaling (`scaleVirtualMachine` without a restart) available for
  KVM with these templates?
- Does CloudStack's user API expose any suspend/resume that is faster than
  stop/start? N5's viability depends on it.
- Secondary storage on this host was at 94% used with 17.55 GB total
  (project memory, 2026-09-02). N3 and N4 both consume storage aggressively;
  this needs resolving before either phase, not during.

## 7. Substrate comparison in detail

§2 named two options. There is a third that neither of them covers and that is
closer to how Neon actually *feels* to a user, so all three are compared here.

- **A — VM per database.** Today's model, continued.
- **B — Containers on CKS**, an operator per engine (CloudNativePG for
  Postgres).
- **C — Shared engine, database per tenant.** One (or a few) well-sized
  Postgres/MySQL instances; a tenant "database" is a database inside it, not a
  machine.

### 7.1 The thing that makes Neon fast is not containers

Worth stating before the table, because it changes what B is worth: Neon's
compute runs in **Firecracker microVMs**, not plain containers. Containers are
not the source of its speed — disaggregated storage is. Moving to Kubernetes
therefore does not buy Neon's behaviour; it buys a faster start unit, an
operator ecosystem, and cheaper density. The branch-in-milliseconds property
comes from the pageserver, and none of A, B or C has one.

### 7.2 Comparison

| Dimension | A — VM per DB | B — CKS + operator | C — shared engine |
| --- | --- | --- | --- |
| Reuse of v2 work | Everything: templates, config drive, `firstboot.sh`, credential store, UI | Templates, config drive and `firstboot.sh` become dead; API/UI/credential model survive | Templates and config drive become optional; the engine scripts survive almost unchanged |
| Provision a new database | 1–3 min (boot + first-boot script) | 10–40 s (pod schedule + Postgres start) | **< 1 s** (`CREATE DATABASE` + role) |
| Branch, same data set | Minutes (volume snapshot + clone + boot), or N2's in-instance copy | Seconds to minutes (storage-class clone + pod) | **Seconds** (`CREATE DATABASE ... TEMPLATE`), no new compute at all |
| Scale to zero | Stop VM; wake 30–90 s, exceeds driver timeouts | Scale deployment to 0; wake 5–20 s, at the edge of tolerable | Not applicable — nothing per-tenant to stop, which is either the best or the most dishonest answer depending on how you bill |
| PITR | Build it per engine (N3) | Operator feature for Postgres; per-engine again for the rest | Cluster-level restore is easy; **per-tenant restore is hard** — restoring one database means restoring the whole cluster elsewhere and copying one database out |
| HA / failover | Not built, and expensive to build | Operator feature (replicas, automatic failover) | Whatever the single cluster has; one failure hits every tenant |
| Multi-engine (4 engines today) | Uniform: one build recipe per engine, same plugin path | **Fragmented**: CloudNativePG (Postgres), MOCO/Percona (MySQL), a different operator for MongoDB — three ecosystems, three upgrade cadences | Uniform per engine, one cluster per engine |
| Tenant isolation | **Strongest** — separate kernel, separate disk, noisy neighbours impossible | Shared kernel; namespaces + quotas. Adequate for a trusted tenant base, not for hostile multi-tenancy without extra work | **Weakest** — shared process, shared memory, shared WAL. One tenant's runaway query degrades everyone |
| Density / cost per database | Worst: ~1 GB RAM floor per database, plus a whole OS | Good: pod overhead only | **Best**: one connection's worth |
| Blast radius of a mistake | One tenant | One node or namespace | **Everyone** |
| New operational surface | None — you already run this | CKS control plane, CSI driver, operator upgrades, cert rotation, etcd | One cluster to keep healthy, plus per-tenant quota/limit work that has no CloudStack primitive |
| Prerequisites on `cloudstackcve` | None beyond §6's storage headroom | A CKS-capable zone, its system template, control-plane VMs, a CSI-capable pool — on a host already at 94% secondary storage | One VM per engine; the cheapest of the three |
| Reversibility | Continues current path; abandoning later loses little | Hard to reverse once tenants live on it | Easy to start, **hard to leave** — moving a tenant out later means a real migration |
| Rough effort to first working demo | 2–4 weeks (mostly N1 + N2, needs the Phase D agent) | 6–12 weeks, most of it learning and operating CKS | **1–2 weeks** — the engine work already exists |

### 7.3 What each looks like in three months

**A.** Four engine templates working, a project/branch model over them, branch
by volume clone measured in minutes, no PITR yet, scale-to-zero either shipped
with an honest "first connection wakes it, retry" contract or dropped. A solid
managed-VM database service. Nobody will mistake it for Neon, and the demo
lands on "here is a database, provisioned automatically, with credentials the
management server never had to SSH for" — which is a real result.

**B.** A Kubernetes cluster you now operate, Postgres with genuine HA and PITR
from the operator, MySQL and MongoDB probably still on the old VM path because
their operators are a second and third project. Closer to a real product;
significantly more of your time spent on Kubernetes rather than on DBaaS.

**C.** A demo that *feels* like Neon — create a database instantly, branch it
instantly, connect through a pooler, all in front of a user in a few seconds —
sitting on an architecture that cannot isolate tenants and cannot restore one
of them alone.

### 7.4 Recommendation

**It depends on which of two goals is real, and they pull in opposite
directions.**

If the goal is *a proof of concept that demonstrates Neon-like UX*, C is the
only option that gets there, because the entire feel of Neon is "no machine
was booted for me". Provisioning and branching in seconds are not reachable on
A at all, and only marginally on B.

If the goal is *a service that could carry real tenants*, C is disqualified by
isolation and per-tenant restore, and the choice is A (continue, accept slow
branches) or B (rebuild on operators, gain HA/PITR, lose the v2 transport work
and take on Kubernetes).

The combination worth considering, and the one recommended here: **finish A to
the Phase B acceptance line, then add C as a second tier rather than a
replacement.** The project/branch/endpoint model of N1 is substrate-agnostic
by design — an endpoint can be "a VM of your own" (A, the *dedicated* tier) or
"a database on a shared cluster" (C, the *shared* tier), decided per project.
That yields the instant-provisioning, instant-branch demo without giving up
the isolation story, reuses the engine scripts on both paths, and keeps B open
as a future migration for the shared tier specifically, since a shared tier is
exactly what an operator manages well.

The cost of this recommendation is honest: two provisioning paths to maintain
instead of one, and N1's data model has to be right, because it is what makes
the two tiers look the same to the UI and the API.

## 8. Query console, table browser and schema management

Wanted: browse the tables inside a provisioned instance, run queries, and
manage the schema — create and drop tables — from the UI, the way Neon's SQL
editor and table view work. This subsumes N6 (§5) and is written out here
because it is the one feature that argues directly with v2's founding
decision.

### 8.1 The architectural problem

**The management server has no route to the instance, on purpose.** That is
what the whole config-drive rewrite bought (§1 of PLAN.md): provisioning
survives a broken VR, an unreachable guest network and a wrong network choice.
A SQL console needs a live, low-latency, bidirectional data path to a running
database — exactly the dependency that was removed. Any design that opens a
connection from the management server to the guest gives that property back
and reintroduces v1's dominant failure mode, just for a different feature.

So the transport is the whole decision; the SQL part is easy.

### 8.2 Transport options

**T1 — Management server connects to the database (JDBC).** Cheapest to build:
credentials are already stored and decryptable, and the plugin already knows
the host and port. Costs: the guest network must be routable from the
management server again; the management server becomes a SQL client to
arbitrary tenant databases (thread pool exhaustion on a slow query, a
connection per open console, tenant data flowing through and possibly into MS
logs); and the "works with the VR down" property is lost for this feature.
Fastest path, worst architectural fit.

**T2 — Agent exposes an HTTP endpoint in the instance.** The browser or the
management server calls into the guest. Same routing requirement as T1 plus a
new listening service on every tenant instance, needing its own TLS and
authentication. No advantage over T1 unless the browser can reach the guest
directly, which in this lab it cannot.

**T3 — Agent polls for query jobs (recommended).** The Phase D agent already
has to poll the management server for pending work; a query is just another
job type. Nothing listens in the guest, no inbound route is needed, and the
feature keeps working on an isolated network with the VR down — the same
property the provisioning path has. Costs: latency is the poll interval
(1–3 s, so it feels like a batch tool, not a live console); results have to
travel back through the management server and be held somewhere until the UI
collects them; and result rows are tenant data transiting CloudStack's own
database, which needs a deliberate answer (§8.5).

**T4 — Do not build it: give the tenant a port forward.** A firewall/port
forward rule and the credential already shown in Show Password lets the tenant
point pgAdmin, DBeaver or `psql` at their own database. Zero new surface, real
tool, no capability inside the management server. This is genuinely the right
answer for "I want to query my database" and should be documented regardless
of whether the console is built.

### 8.3 Scope: three tiers, increasing risk

Three features often conflated, with very different risk:

**Table browser (structured, recommended first).** The agent runs a *fixed set*
of catalogue queries — list schemas, list tables with row estimates and size,
describe columns/indexes, preview the first N rows of a chosen table with
`LIMIT`. No user-supplied SQL crosses any boundary; the API takes identifiers,
which are validated the same way `db_name`/`db_user` already are. Bounded
result size by construction, and it covers most of what "ดู table ใน VM"
actually means.

**Schema management (structured DDL — create and drop tables).** Also
identifier-driven rather than SQL-driven: create a table from a column list
(name, type from a per-engine allowlist, nullability, default, primary key),
add or drop a column, create or drop an index, drop a table. The API builds the
statement server-side from validated parts, so no client SQL is executed
anywhere — the same discipline `db_name`/`db_user` already follow. The type
allowlist is per engine and lives in `config.json`, not in the Java source —
the same rule the engines map already follows.

This tier is where destruction becomes possible, so it carries rules the
read-only tiers do not:

- **`DROP TABLE` is unrecoverable.** There is no backup path today (N3 is not
  built), no undo, and no snapshot taken first. Until N3 exists, the UI must
  say exactly that, and require typing the table name to confirm — the pattern
  CloudStack itself uses for destroying instances. Do not offer a bare
  "Delete" button on a data-bearing object with no restore path behind it.
- Every DDL statement is audited with who, which endpoint, and the full
  statement the server built (a `CREATE TABLE` is not sensitive data, unlike a
  `WHERE` predicate — see §8.5).
- Drops are refused on anything outside the tenant's own database, which falls
  out of connecting as the database's own owner role rather than a superuser.

Structured DDL covers table/column/index work and stops there. Views, triggers,
functions, partitioning and constraints beyond a primary key are not worth
modelling as forms — those are the point at which the free-form console below
is the honest answer.

**SQL console (free-form).** Strictly more useful and strictly more dangerous.
Needs, at minimum: a statement timeout, a hard row cap with a truncation
notice, a byte cap on the result, one in-flight query per endpoint, and a
decision about DDL/DML. Recommended default: the console runs as a **read-only
role**, with write access an explicit per-session opt-in that is audited.

### 8.4 Two roles, one per tier

Provision time already creates the database owner. Add a second role,
`<db_user>_ro`, with `CONNECT` + `SELECT` only (Postgres: `GRANT
pg_read_all_data` or schema-level `SELECT`; MySQL/MariaDB: `GRANT SELECT`;
MongoDB: the `read` role):

- **browser and console default to `_ro`** — a compromised or mistaken console
  session cannot drop a table
- **structured DDL uses the owner role**, which is scoped to the tenant's own
  database and is not a superuser, so the blast radius stops at that database
- write access in the free-form console is a per-session opt-in that switches
  to the owner role and is audited as such

Cheap to add — a few lines in engine scripts that already exist — but it must
land *when the templates are rebuilt* (PLAN.md §9), not afterwards, or every
instance built before it is left with no read-only role and the browser has to
fall back to the owner credential.

### 8.5 Tenant data on the management server

With T3, query results pass through and rest in CloudStack's database until the
UI fetches them. Non-negotiable rules if this is built:

- results are stored with a short TTL (minutes) and deleted on read, swept by
  the same kind of job the credential sweeper already runs
- result rows are never written to `management-server.log`, and the query text
  is not logged either — a `WHERE ssn = '...'` predicate is as sensitive as the
  row it returns
- results are encrypted at rest with the existing `DBEncryptionUtil` path, the
  same as credentials
- every query is recorded as an `@ActionEvent` with *who*, *which endpoint* and
  *how many rows* — never the payload. This is PLAN.md Phase E's audit item,
  which the console makes mandatory rather than nice to have

### 8.6 Shape of the work

- [ ] Decide T1 vs T3 (or T4 and stop). Recommendation: T3, because it is the
      only one that preserves the property the rewrite was for
- [ ] Read-only role in the engine scripts, folded into the §9 template rebuild
- [ ] Phase D agent (already required for Reset Password) gains a job loop
- [ ] Job/result tables with TTL, encryption and a sweeper
- [ ] Read APIs: `listDbaasSchemas`, `listDbaasTables`, `describeDbaasTable`,
      `previewDbaasTable` — identifiers only, no SQL from the client
- [ ] DDL APIs: `createDbaasTable` (column list validated against a per-engine
      type allowlist in `config.json`), `dropDbaasTable`, `addDbaasColumn`,
      `dropDbaasColumn`, `createDbaasIndex`, `dropDbaasIndex` — statements
      built server-side from validated parts, executed as the owner role,
      every one an `@ActionEvent`
- [ ] UI: a Tables tab on the Database page — table list, column view, row
      preview with paging, a create-table form, and a drop confirmation that
      requires typing the table name and states plainly that there is no
      restore path. An explicit "running…" state throughout, because results
      arrive on the poll interval, not instantly
- [ ] Only then, if still wanted: the free-form console, read-only by default,
      with timeout/row cap/byte cap and audited write opt-in

### 8.7 Honest assessment

The table browser and structured DDL over T3 are a good fit: bounded, useful,
and they keep the no-inbound-route property. Create/drop table in particular
suffers nothing from the 1–3 s poll latency — a schema change is not something
anyone types twenty of per minute, and the operation is naturally
job-shaped ("submitted… applied").

The free-form console is the piece that will feel sluggish on T3 and will be
compared unfavourably with just connecting a real client over a port forward.
If an interactive console is the actual requirement, T4 plus good documentation
beats building T1, and building T1 means accepting that this feature does not
work on the networks v2 was designed to survive.

One dependency that is easy to miss: **`DROP TABLE` should not ship before
N3 (backups/PITR).** Every other item here is recoverable by rebuilding an
instance; dropping a table with no backup is the first genuinely irreversible
thing this product would offer a tenant, and the ordering should reflect that.

## 9. Explicit non-goals

Page-level copy-on-write storage, sub-second cold start, per-second billing,
multi-tenant shared compute, cross-region replication, and any claim that this
is Neon. It is a Neon-shaped workflow on VM-grade infrastructure, and the
documentation should say so to whoever uses it.
