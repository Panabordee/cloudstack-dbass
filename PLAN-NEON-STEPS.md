# Step-by-step: from today to "Neon work finished"

Written 2026-09-08, from `4.23+dbass` at `c1d7bdc702`.

`NEON-ROADMAP.md` says *what* each phase is and argues the trade-offs. This
file says *what to do next, in order*, from where the project actually stands
today through to the end of the Neon work. Read the roadmap for the reasoning;
read this for the sequence.

## Where we actually are

Zero of the seven Neon phases (N0–N6) are done. What exists is the **query
console** — roadmap §8, not a phase — plus provisioning, credentials, and the
in-VM agent transport. The last of those matters far more than it looks:
"talk to an instance that is already running" was the single blocker under
N2, N5 and Reset Password, and as of 2026-09-08 it is proven working.

Before any of this starts, `MASTER-PLAN-2026-09-08.md` has to be finished.
That is Stage 0 and it is not optional — most of it is finished code sitting
in no image, and every Neon phase builds on those images.

## The honest frame, kept from the roadmap

This is a **Neon-shaped workflow on VM-grade infrastructure**. Page-level
copy-on-write storage, sub-second cold start, per-second billing and
multi-tenant shared compute are explicit non-goals. Nothing below should be
described to anyone as "we built Neon". When a stage produces a number that
is worse than Neon's, the number goes in the document as measured.

---

## Stage map

```
Stage 0  finish MASTER-PLAN                    ── gate for everything
   │
Stage 1  measurement gates (no code)           ── kills or keeps stages 4,5,7
   │
Stage 2  N1  project/branch/endpoint model     ── invisible, and the one that
   │                                              is most expensive to redo
   ├─────────────┬──────────────────────────┐
Stage 3  N2      Stage 4  N3                 │
 branch on the    backup + PITR              │
 same instance    (unblocks DROP TABLE)      │
   │                    │                    │
   └────────┬───────────┘                    │
Stage 5  N4 isolated branch by volume clone   │  (only if Stage 1 gate G1 passes)
   │                                          │
Stage 6  shared tier (substrate C)  ◀─────────┘  decision point, optional
   │
Stage 7  N5 idle suspend + wake                ── only if gate G3 passes
   │
Stage 8  N6 HTTP SQL endpoint                  ── needs TLS, which exists nowhere yet
```

Stages 3 and 4 are independent of each other and can run in parallel if there
is more than one person. Everything else is a chain.

---

## Stage 0 — Finish what is already owed

**Goal:** the four template images carry the finished code, and the acceptance
matrix has real results instead of "not run".

Full detail: `MASTER-PLAN-2026-09-08.md`, items 1–11. Handoff prompt:
`PROMPT-GLM-REMAINING-WORK.md`.

Short version, in order:

1. Rebuild all four templates in one pass, carrying the provisioning-trigger
   fix, the `_ro` role, the current `dbaas_agent.py` and the retry units.
   Prove template 210 completely before touching 211/212/213
2. Run the second-boot acceptance (six cases) and the console matrix
3. Measure the real memory floor per engine; replace the three guessed 1024s
4. Build the log-redacting filter for `RunDbaasQueryCmd` /
   `GetDbaasJobResultCmd`
5. Reset Database Password — cheap now the agent works
6. Fix the isolated-network offering and run the VR-down test
7. Click the console through in a browser once

**Acceptance:** a tenant can create a database on any of the four engines,
browse tables, run a query, create a table, and connect a normal client. No
SQL text or row value in `management-server.log`.

**Do not start Stage 1 until this is true.** Every phase below assumes a
working agent on all four images; three of the four do not have one today.

---

## Stage 1 — The measurement gates (no code, roughly one session)

Roadmap §6. Four questions whose answers decide whether later stages are
features or liabilities. Every one of them must be **measured on this host**,
not read off a wiki or inferred from the storage type's name.

### G1 — Does the storage backend do CoW clones, or a full copy?

The single most important number in this document. It decides whether Stage 5
(N4, isolated branch) is a feature or a trap.

1. Create a data volume, fill it with ~10 GB of real data
2. `createSnapshot` on it — record wall-clock time and the space consumed
3. `createVolume snapshotid=` from that snapshot — record wall-clock time
4. Repeat at ~1 GB and ~50 GB if storage allows, so the shape of the curve is
   known, not just one point

**Gate:** if the clone is a full copy and 10 GB takes tens of minutes, Stage 5
is not "slow branching", it is a feature nobody will use twice. Say so in
writing and either cut it or reduce it to an explicitly-documented "clone a
small database" operation. Do not build it and then discover this.

### G2 — Is live scaling available?

Does `scaleVirtualMachine` work without a restart for KVM with these
templates? Test it. If not, "autoscaling compute" is stop → change offering →
start, and must be described that way everywhere.

### G3 — Is there any suspend/resume faster than stop/start?

Check whether CloudStack's **user** API exposes one (not just the admin or
hypervisor layer). Stage 7 (N5) lives or dies here. If the only path is
stop/start, wake is 30–90 s and the honest options shrink to the three the
roadmap lists — pick one at that point, do not defer it into implementation.

### G4 — Storage headroom

Secondary storage was at 94% used of 17.55 GB. Stages 4 and 5 both consume
storage aggressively. **Resolve this before either stage, not during.** Either
expand it or write down the retention ceiling that the available space
implies, and size Stage 4's retention policy to fit inside it.

**Acceptance for Stage 1:** a short document — `NEON-GATES-<date>.md` — with
four measured answers, and for each, what it changes about the plan. This is
the cheapest stage here and the one that saves the most wasted work.

---

## Stage 2 — N1: the project / branch / endpoint model

**Goal:** the data model every later stage is shaped by.

Today: one flat `dbaas_credentials` table, keyed loosely by instance, one row
inserted per create/reset with only the newest read back. That cannot express
a branch, and every API below needs to.

Target hierarchy (roadmap §4):

- **project** — tenant-visible unit, owns quota and default engine
- **branch** — belongs to a project, has a parent (null for `main`), a
  creation point (snapshot id or backup timestamp) and a lifecycle state
- **endpoint** — what actually serves connections: instance + port, attached
  to exactly one branch, with its own state (`active`, `idle`, `stopped`)
- **credential** — moves under endpoint, history preserved

### Steps

1. Write the schema as a migration in `db/`, following the existing
   `schema-dbaas-console.sql` pattern. Every table gets `uuid`, `account_id`,
   `domain_id`, `created`, `removed` — the ACL and the soft-delete convention
   the rest of CloudStack uses
2. Write the **forward migration for existing rows** and test it against a
   copy of the live database: each existing instance becomes one project, one
   `main` branch, one endpoint, and its credentials move under that endpoint.
   Nothing is deleted
3. New API commands: `createDbaasProject`, `listDbaasProjects`,
   `deleteDbaasProject`, `createDbaasBranch` (recording only at this stage,
   no data copy), `listDbaasBranches`, `listDbaasEndpoints`
4. Existing commands keep working unchanged, resolving through the new model
   underneath. This is the compatibility line — do not break the console that
   Stage 0 just proved
5. UI: the Database page becomes a project list; an instance detail page
   becomes an endpoint under a branch

**Acceptance:** everything that works after Stage 0 still works, addressed
through the new model, with the migration run on a copy of the real database
and the row counts before and after recorded.

**Note:** this is the only stage that delivers nothing visible on its own, and
the one that is most expensive to get wrong — every API above it is shaped by
it. Resist the urge to skip straight to Stage 3.

**Substrate-agnostic on purpose:** an endpoint must be able to be *either* "a
VM of your own" or "a database on a shared cluster" (Stage 6). Design the
endpoint row for that now even though only the first exists yet; it costs a
column today and a rewrite later.

---

## Stage 3 — N2: branch on the same instance, plus pooling

**Goal:** the cheap 80% of branching. A branch is a second database inside the
same instance, made with the engine's own copy mechanism.

Per engine:

- Postgres: `CREATE DATABASE ... TEMPLATE <src>` — note it blocks writes to
  the source while copying
- MySQL / MariaDB: dump and restore
- MongoDB: `$out` or `mongodump`

### Steps

1. New agent job type `branch_create` in `dbaas_agent.py`'s `JOB_HANDLERS`,
   with the per-engine SQL living in the engine scripts, not in the Python.
   The config-driven rule applies here exactly as everywhere else
2. Server side: `createDbaasBranch` stops recording-only and dispatches the
   job, creating the endpoint and credential rows on confirmation
3. PgBouncer into the template so a branch is a connection string rather than
   a new machine — **this needs a template rebuild** (see "Guest-side work"
   below before scheduling it)
4. Quota and guard rails: a branch consumes the source instance's disk. Decide
   the limit before shipping, not after a tenant fills a volume

**Acceptance:** branch a 1 GB database, connect to both, confirm a write to
one does not appear in the other. Record how long the copy took and how long
writes to the source were blocked.

---

## Stage 4 — N3: backups and point-in-time restore

**Goal:** the first genuinely durable thing this product offers. Also the
prerequisite for enabling `DROP TABLE`.

Prerequisite: gate **G4** (storage headroom) resolved.

### Steps

1. Postgres first, with pgBackRest, shipping to secondary storage or S3. One
   engine done properly beats three done partially
2. Retention policy sized to the answer from G4, plus CloudStack's own
   `createSnapshotPolicy` for the volume-level tier
3. `restoreDbaasBranch` restores to a timestamp **into a new branch, never in
   place**. In-place restore is how a tenant loses the data they were trying
   to recover
4. Then XtraBackup for MySQL/MariaDB, then MongoDB — three separate
   implementations, no shortcuts between them
5. Only once restore is proven per engine: **enable `dbaas.console.drop.enabled`.**
   That flag has been `false` this whole time waiting for exactly this

**Acceptance:** write a row, note the time, write a second row, restore to the
noted time into a new branch, confirm the second row is absent and the first
present. Per engine.

---

## Stage 5 — N4: isolated branch by volume clone

**Goal:** the real thing — independent compute, independent data.

**Only start if gate G1 says the clone is fast enough to be worth it.** If G1
failed, skip to Stage 6 and record why.

### Steps

1. `createSnapshot` on the source endpoint's data volume
2. `createVolume snapshotid=` from it
3. Deploy a new endpoint from the same engine template, attach the volume
4. Provision credentials on the new endpoint through the path Stage 0 fixed —
   this is a second-boot provisioning case, which is precisely the bug that
   went unnoticed for months, so test it deliberately here
5. Lifecycle: deleting a branch deletes its volume and its snapshot, and the
   tenant is told what that costs before they confirm

**Acceptance:** branch a running database onto a separate instance, confirm
isolation, and record the wall-clock time **honestly, per data size**. If it
is twenty minutes for 10 GB, the documentation says twenty minutes.

---

## Stage 6 — The shared tier (substrate C) — decision point

Roadmap §7.4's recommendation, and the only path to "no machine was booted for
me". This is where a genuine choice happens rather than a next step.

**The question:** is the goal a proof of concept that *feels* like Neon, or a
service that could carry real tenants?

- Feels like Neon → the shared tier is the only way there. Provisioning and
  branching in seconds are not reachable on VM-per-database at all
- Carries real tenants → the shared tier is disqualified by isolation and
  per-tenant restore, and Stage 6 is skipped

The recommended answer is **both, as two tiers**: keep VM-per-database as the
*dedicated* tier and add shared as a second tier, chosen per project. Stage 2's
model was designed to make them look identical to the UI and the API, which is
what makes this possible without a rewrite.

The cost is stated plainly: two provisioning paths to maintain forever.

### Steps if taken

1. One well-sized engine instance per zone, managed as infrastructure rather
   than as a tenant instance
2. A tenant "database" becomes a database inside it, with role-based isolation
   and per-database resource limits
3. Provisioning becomes an SQL operation — seconds, no boot
4. Branching becomes `CREATE DATABASE ... TEMPLATE` — seconds
5. Document the isolation difference between the tiers **to the tenant**, not
   just internally. A shared tier that a tenant believes is dedicated is worse
   than no shared tier

---

## Stage 7 — N5: idle suspend and wake

**Only if gate G3 produced something better than stop/start.** If it did not,
read the acceptance criterion below before writing any code — the honest
outcome may be "do not ship this".

### Steps

1. Idle detection in the agent: no client connections for N minutes → report
   idle → the plugin stops the endpoint. This half is straightforward
2. The wake path is the open design problem. A TCP proxy that accepts a
   connection, starts the instance and holds the client for 30–90 s will
   exceed most drivers' timeouts. The three honest options:
   - (a) a documented contract: "the first connection after idle fails, retry
     in a minute"
   - (b) wake from the UI or API rather than from the connection
   - (c) hypervisor suspend/resume, if G3 found one
3. Pick one **before** implementing, and write down which and why

**Acceptance:** measure the wake time and then decide whether the feature is
honest to ship. "We measured it and chose not to ship it" is a valid and
complete outcome for this stage.

---

## Stage 8 — N6: HTTP SQL endpoint

**Goal:** the serverless-driver shape — SQL over HTTP.

Treat it as what it is: a **new externally reachable authenticated surface**.
Same review bar as `reportDbaasProvisioningResult` got, plus TLS.

### Steps

1. **TLS first.** There is no TLS anywhere in this design today. That is
   acceptable for an agent that only ever dials outward to the management
   server on a trusted network; it is not acceptable for an endpoint a tenant's
   application calls over the internet. This is a prerequisite, not a follow-up
2. Authentication scoped to a credential, rate limited, statement allowlist or
   documented full passthrough
3. Reuse the console's existing job/result plumbing rather than opening a
   second path into the guest

**Acceptance:** a third-party application authenticates, runs a query, is rate
limited when it should be, and the whole surface has been through a security
review that is written down.

---

## Cross-cutting: guest-side work needs template rebuilds — batch them

Three separate stages put new software **inside the image**:

- Stage 3: PgBouncer
- Stage 4: pgBackRest / XtraBackup
- Stage 7: the idle detector

Each rebuild is a careful four-image pass (Stage 0 is the current one, and it
is not small). Design all three guest-side pieces before scheduling the next
rebuild and land them together, rather than paying that cost three times. The
agent itself is the exception — it self-updates well enough that agent-only
changes do not need a rebuild.

## Cross-cutting: what the UI owes each stage

- Stage 2: project list, branch tree, endpoint detail. The information
  architecture changes here, once, and everything later hangs off it
- Stage 3 and 5: a "Branch" action whose dialog states the cost honestly —
  *how long it will take* and *what it consumes* — before the tenant confirms
- Stage 4: a restore dialog that makes "into a new branch" visibly the only
  option, not a checkbox someone can uncheck
- Stage 6: the tier chooser, with the isolation difference readable by a
  tenant who has not read any of this

## Cross-cutting: documentation

`README.md`, `INSTALL.md` and `TEMPLATES.md` still describe the retired v1 SSH
architecture (MASTER-PLAN item 11). Fix that during Stage 2 — a new data model
is the natural moment, and every later stage would otherwise inherit
documentation that is wrong on the first page.

---

## Definition of done for "the Neon work"

A tenant can: create a project; get a database in it; browse and query it from
the UI; branch it; restore it to a point in time into a new branch; connect a
normal client or an HTTP driver; and see, before they confirm any of those,
how long it will take and what it costs.

And the documentation says plainly which parts are seconds and which are
minutes, and that this is a Neon-shaped workflow on VM infrastructure rather
than a copy of Neon.

## Realistic shape of the effort

Stated as relative weight, since dates would be invented:

- Stage 0 is roughly the same size as everything already built this month
- Stage 1 is one session, and is the highest-value session in this document
- Stage 2 is small in code and large in consequence
- Stages 3 and 4 are each comparable to the whole console feature
- Stage 5 is small **if** G1 passes and pointless if it does not
- Stage 6 is a second product tier, not a feature
- Stages 7 and 8 are each new externally-visible surfaces with their own
  security review

Anyone who reads this as a two-week plan has misread it.
