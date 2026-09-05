# Overnight session prompt — DBaaS console (query / browse / create-drop table)

Paste everything below the line into the agent. It is written for a long
unattended run: nobody is awake to answer questions, so it says exactly what
you may decide alone, what you must record, and what you must not touch.

---

You are working alone, overnight, on an Apache CloudStack fork in
`/home/nacl/dbaas-v2`, branch `panabordee/dbaas-v2`. Nobody will answer
questions until morning. Decide within the boundaries below, record every
decision, and leave a report that can be audited without you.

## 1. Mission

Implement the DBaaS console described in `PLAN-DBAAS-CONSOLE.md`: three
tenant-facing capabilities — run a query, browse tables, create and drop
tables — plus the agent transport they need. Phases **C0 → C4**, in order.
C5 only if C0–C4 are complete and verified.

Plus three carry-over items from the last audit, listed in §4.

Connecting to a database with a normal client (`psql`, `mysql`, DBeaver) must
keep working untouched. That is not a feature to build; it is a property not
to break.

## 2. Read before writing any code

In this order:

1. `PLAN-DBAAS-CONSOLE.md` — the specification. §12 defines your report.
2. `AUDIT-VERIFY-FIXES-2026-09-05.md` — current state, verified. Contains the
   three conditions you must carry into this work.
3. `plugins/integrations/dbaas/PLAN.md` — the accurate architecture document.
4. `NEON-ROADMAP.md` §8 — why the agent-poll transport was chosen over three
   alternatives. Read it before proposing a different one.
5. `FIXES-REPORT-2026-09-05.md` — what the previous session did and why.

`README.md`, `INSTALL.md` and `TEMPLATES.md` under
`plugins/integrations/dbaas/` still describe the retired v1 SSH architecture.
**Do not follow them.**

## 3. Boundaries — read twice, this is an unattended run

**You may, without asking:**

- change anything in the repository
- create files, refactor within the plugin, the extensions scripts and the
  DBaaS parts of the UI
- run builds, unit tests, linters, `node --check`, `bash -n`
- commit, and push to `v2` and `ui` on the existing remote, as the previous
  session did
- decide implementation details the plan leaves open, provided you record the
  decision and its reason

**You must NOT, under any circumstance, without a human:**

- touch the live CloudStack: no `cmk update configuration`, no deploy, no
  destroy, no expunge, no template registration, no `dbaas.*` setting changes
- patch, mount or modify any template image, primary-storage file or
  secondary-storage file (`qemu-nbd`, `mount`, `cp` into an image)
- restart `cloudstack-management`, the agent, or any system VM
- write to `/usr/share/cloudstack-management/**` or any other host path
  outside the repository
- run anything against the production database except read-only `SELECT`
- `git push --force`, rewrite published history, or delete branches
- enable `dbaas.datadisk.cleanup.enabled` anywhere

If a task cannot progress without one of those, that is a report entry and you
move to the next unblocked task. There is always something in §4/§5 that does
not need the host.

## 4. Carry-over fixes — do these first, they are small

**CF-1 and CF-2 were completed in the round-2 session** (429/503 already used
`sendErrorQuietly`; the sweeper now deletes through `VolumeApiService`).
Confirm both are present at HEAD before you start — read the files, not the
commit diffs — and record them as done. If either is missing, that is the
first thing to fix.

Whatever you build below inherits the same two rules: **`sendError` for every
non-200 status** an authenticator returns, because `ApiServlet:415` overwrites
`resp.setStatus()` with a hardcoded `SC_OK`; and **never remove a database row
that represents a real resource without going through the service that owns
it**, or the storage file, the capacity accounting and the usage events are
all left behind.

**CF-3.** C0's read-only database role (`<user>_ro`, per
`PLAN-DBAAS-CONSOLE.md` §3) must be added to `mysql.sh`, `mariadb.sh`,
`postgresql.sh` and `mongodb.sh`, and the credential model must gain the
`db_role` column. The templates cannot be rebuilt by you — that is host work —
so implement it, verify what you can offline (`bash -n`, unit-level checks of
the generated SQL), and hand the rebuild to the human in your report.

## 5. Order of work

For each phase: implement, build, verify what can be verified without the
host, commit with a message that explains *why*, then move on.

- **CF-1, CF-2, CF-3** (§4)
- **C1** — agent token table, job and result tables with TTL and encryption,
  `getDbaasAgentJob` (long-poll) and `reportDbaasJobResult`, the sweeper, and
  the agent itself in `extensions/dbaas/`. Use the pattern that is now proven:
  a static-holder lookup, **not** `@Inject` on a command constructed by
  `newInstance()`, and `sendError` for every non-200 status. This is the
  foundation; do not shortcut it.
- **C2** — table browser: `listDbaasTables`, `describeDbaasTable`,
  `previewDbaasTable`, and the Tables tab.
- **C3** — SQL editor: `runDbaasQuery`, `getDbaasJobResult`, read-only by
  default, caps and statement timeout enforced in the agent.
- **C4** — schema management: create table from a validated column list with a
  per-engine type allowlist **in `config.json`, never in Java**, plus column
  and index operations. `dropDbaasTable` is implemented but
  `dbaas.console.drop.enabled` ships `false` and stays `false`.

If a phase cannot be finished, finish the part that is coherent, commit it,
and say precisely where you stopped. **Half a phase, clearly described, is
worth more than a phase that claims to be done.**

## 6. Build and verify

Run these; they are the substitute for the live system you cannot touch.

```bash
# plugin: compile + checkstyle (minutes)
mvn -pl plugins/integrations/dbaas -am install -DskipTests

# whole server build, once the plugin API surface changes (long, ~20-40 min)
mvn -T2 -DskipTests -Dnoredist install

# shell scripts
for f in extensions/dbaas/provisioning/*.sh; do bash -n "$f" || echo "FAIL $f"; done

# UI: syntax of every touched component, then the real build
#   node --check on the extracted <script> block of each .vue you changed
cd ui && npm ci && npm run build          # long (~10-20 min), must end clean
```

Rules about builds:

- **A build failure is a stop-and-fix, not a report-and-continue** — you broke
  it, so you own it. This is the one exception to "report rather than chase".
- Run the full server build at least once before you finish, even if nothing
  seems to need it. A plugin that compiles alone but breaks the server build
  is a broken night.
- Keep every build's tail output; the report must contain it.
- If `npm ci` cannot reach the network, say so and fall back to
  `node --check`; do not spend the night fighting a registry.
- `/` has ~3 GB free. Do not stage images or large artefacts there. If a build
  fails on disk space, stop and report it — do not start deleting things.

## 7. When to decide, when to record, when to stop

**Decide alone** when the plan is silent on a detail and any reasonable choice
is reversible: naming, error text, table column order, which helper to extract,
how to structure a Vue component.

**Decide and record prominently** when the choice has consequences someone
would want to review: a schema shape, an API parameter, a departure from
`PLAN-DBAAS-CONSOLE.md`, a security-relevant default. Write it in the report's
decisions log with the reason and the alternative you rejected.

**Stop the task and record a blocker** when:

- it needs the host, a template rebuild, a deploy, or a CloudStack restart
- the cause is in CloudStack core, the UI framework, or the network
- you have tried twice and it still fails, and the third attempt would be a
  different approach rather than a fix
- the change is spreading: a second file, then a third, then a refactor you
  did not plan
- something in the plan turns out to be wrong, and continuing means quietly
  redesigning it

Then move to the next unblocked item. If everything is blocked, write the
report and stop. **Do not fill the remaining hours with speculative work.**

## 8. Safety rules that survive fatigue

- Never delete or truncate anything outside your own build output.
- `DATA-73` is evidence. Do not touch it, do not clean it up, do not
  "helpfully" remove it from any list.
- Never log or commit a password, a token, or query text. The console handles
  tenant data; the whole design assumes none of it reaches
  `management-server.log`.
- Never weaken a default to make a test pass: `dbaas.console.enabled`,
  `dbaas.console.write.enabled`, `dbaas.console.drop.enabled` and
  `dbaas.datadisk.cleanup.enabled` all ship `false`.
- Before any `git` operation that discards work, run `git status` first.

## 9. The report — this is the deliverable

Write `CONSOLE-REPORT-<date>.md` in the repository root. It is what will be
audited in the morning; the code is only evidence for it. Follow
`PLAN-DBAAS-CONSOLE.md` §12 in full, and add the sections below.

**§12 requires (summarised, read the original):** what was built with
`command → file:line`; the live `SHOW CREATE TABLE` equivalents (here: the
migration SQL you wrote, since you cannot touch the database); the config keys
and their defaults; the §11 test matrix item by item as **pass / fail / not
run**; evidence for the things that are easy to get wrong (log hygiene, role
enforcement, delete-on-read, token rotation, encryption at rest); what was not
done; every departure from the plan; and the one-line summary in §12.5.

**Additionally, for this unattended run:**

1. **Timeline.** Roughly what you did in what order, with times. It is how the
   reviewer reconstructs a night they did not watch.
2. **Decisions log.** Every "decide and record" from §7: what you chose, why,
   what you rejected. One line each is fine.
3. **Blockers.** Each with: what you were doing, the exact error, your reading
   of the cause, what would be needed to fix it, and whether it blocks
   anything else.
4. **Build evidence.** The final lines of each build you ran —
   plugin, full server, UI — pasted, including the failures you fixed along
   the way. "It builds" without output is not evidence.
5. **What the human must do next.** Specifically: which templates need
   rebuilding for CF-3, what has to be deployed before C1 can be tested for
   real, and anything else you were forbidden to do.
6. **Untested claims.** Everything you believe works but could not verify
   without the host, listed plainly. The last audit round survived because the
   claims were specific enough to re-check; write yours the same way.

Two habits that make the difference: **"not run" is a valid and useful
answer**, and **a departure with a stated reason is fine — a silent one is
what makes an audit expensive**.

## 10. Before you finish

- every change committed, with messages that explain the why
- pushed to `v2` and `ui`
- the report written, including the sections above
- the working tree clean, or its remaining contents explained in the report
- no host state changed, no live setting altered, `DATA-73` untouched

If you finish everything early, do not start C5 or anything new: re-read your
own diff against `PLAN-DBAAS-CONSOLE.md` §11 and improve the report's evidence
instead. A quiet, well-evidenced night is the outcome being asked for.
