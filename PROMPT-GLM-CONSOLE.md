# Task prompt — DBaaS console (query / browse tables / create-drop table)

Paste everything below the line to the coding agent.

---

You are implementing a feature in an Apache CloudStack fork. Work in
`/home/nacl/dbaas-v2`, branch `panabordee/dbaas-v2`.

## What to build

Follow **`PLAN-DBAAS-CONSOLE.md`** in the repository root. It is the
specification; read it in full before writing code. Three tenant-facing
capabilities, plus the transport they need:

1. run a SQL query against your own database from the UI
2. browse tables — list, describe columns/indexes, preview rows
3. create and drop tables

Implement phases **C0 → C4** in that order. C5 (reset password over the agent)
only if C0–C4 are finished and verified.

Do not start with the UI. The order in the plan exists because C1 (agent +
job pipeline) is what everything else stands on, and it is the part that can
be proven without any UI at all.

## Rules that are not negotiable

- **The management server must never open a connection to a guest instance.**
  Every exchange is initiated by the in-guest agent. This is the whole point
  of the architecture; a design that dials into the guest is wrong even if it
  works.
- **No hardcoded engine/type/port mappings in Java or Python.** They live in
  `config.json`, like the existing `engines` map. This rule has been broken
  before in this project and was expensive to undo.
- **Nothing that can log tenant data may log tenant data.** Not query text,
  not result rows, not passwords — see the plan §2.4.
- `dropDbaasTable` ships **disabled by default** (`dbaas.console.drop.enabled`
  = false). Do not enable it. There is no backup system yet, so it is the
  first irreversible operation this product would expose.
- Do not touch anything on the live host — templates, images, CloudStack
  configuration, running VMs — without asking the human first. Repository
  changes are yours to make; host changes are not.

## When you hit a bug or an obstacle: report, do not chase

This matters more than finishing fast.

**Fix it yourself only when all three are true:** it is inside the code you
are already writing, the fix is under ~20 lines, and you understand the cause.

**Otherwise stop and write it down.** Specifically, stop and report when:

- the cause is in CloudStack core, the UI framework, the network, the
  templates, or anywhere outside this feature
- fixing it needs a template rebuild, a host change, a schema migration of an
  existing table, or a CloudStack restart
- you have tried twice and it is still failing
- the fix is growing: a second file, then a third, then a refactor
- something contradicts `PLAN-DBAAS-CONSOLE.md`, and you are about to
  "just adjust" the design to make progress

For each of those, write an entry in the report with: what you were doing,
what happened (exact error), what you think the cause is, what you would need
in order to fix it, and whether it blocks the rest of the work. Then move on
to the next item that is not blocked, or stop if everything is blocked.

**A precise report about three blockers is a better outcome than a half-fixed
codebase with four new problems in it.** Scope creep is the failure mode being
guarded against here — the previous rounds of this project lost the most time
to exactly that.

## Context you need

- The DBaaS plugin is `plugins/integrations/dbaas/`; provisioning scripts are
  `extensions/dbaas/provisioning/`; UI is `ui/src/views/compute/` plus
  `ui/src/utils/dbaas.js`.
- `plugins/integrations/dbaas/PLAN.md` is the accurate architecture document.
  `README.md`, `INSTALL.md` and `TEMPLATES.md` in that directory still
  describe the retired v1 SSH architecture — **do not follow them**.
- `NEON-ROADMAP.md` §8 records why the agent-poll transport was chosen over
  three alternatives. Read it before proposing a different one.
- The one-time report token (`ReportProvisioningResultCmd`,
  `DbaasManagerImpl.applyProvisioningReport`) is the model to copy for the
  agent's authentication: hashed at rest, scoped to one instance, rate
  limited, every accept and reject logged. The agent token is long-lived, so
  it is a separate table and a separate lifecycle — do not reuse the
  provisioning token.
- Build the plugin with
  `mvn -pl plugins/integrations/dbaas -am install`. Check UI files with
  `node --check` on the extracted `<script>` block; there is no full UI build
  in the loop.
- The acceptance environment is a single-host CloudStack (`cloudstackcve`).
  There is an open blocker there, unrelated to this feature: the guest's
  provisioning report does not always reach the management server. If you need
  a working instance and cannot get one, that is a report entry, not a
  detour.

## Already handled — do not redo

- `firstboot.sh`, `mysql.sh`, `mariadb.sh` were fixed on 2026-09-05
  (silent `grep`/`pipefail` death, empty failure messages, engine readiness
  marker, report retry). See `ACCEPTANCE-FIX-2026-09-05.md`.
- The Show Password dialog's overflow was fixed in
  `ui/src/views/compute/ShowDatabasePassword.vue`.
- VM login password (the tenant cannot choose one, and does not receive one)
  is a **separate known defect with a diagnosis already written**. It is not
  part of this task. Do not fix it here.

## What to deliver when you are done

A report file `CONSOLE-REPORT-<date>.md` in the repository root, following
**`PLAN-DBAAS-CONSOLE.md` §12** exactly. That section lists what has to be in
it: what was built (with `command → file:line`), the evidence that it works
(the §11 test matrix, item by item, pass/fail/not-run), the evidence about the
things that are easy to get wrong (log hygiene, role enforcement,
delete-on-read, token rotation, encryption at rest), what was not done, and
the one-line summary in §12.5.

Two things that section asks for and that reports usually skip, so they are
repeated here:

- **Say what you did not test.** "Not run" is a valid, useful answer.
  A test claimed as passing that was not actually run costs more than an
  admitted gap.
- **List every departure from the plan and why.** A departure with a stated
  reason is fine. A silent one makes the review expensive.

Include the blocker entries described above in a section called
"Blockers and things I chose not to fix".
