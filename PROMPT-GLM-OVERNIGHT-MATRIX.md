# Overnight run — finish the acceptance matrix, the remaining templates, and push

Unattended. Nobody will answer until morning. The review happens then, from
your report and from what is in git — so anything not committed did not happen.

---

## 1. Where things already stand (verified on the host, do not redo)

- `/` has 13 GB free; the monitoring stack is gone; `dbaas-final3` is expunged
- template **210 (mysql)** is patched — secondary **and** primary cache — and
  proven: `dbaas-accept-m2` deployed from it reports `status: confirmed`
  (`testdb1@10.60.0.77:3306`)
- **the agent is alive**: `getDbaasAgentJob` from 10.60.0.77, holding ~25 s per
  poll and looping. This is the first time the console transport has run on a
  real instance
- rate limiting works on the wire (`reportDbaasJobResult rate limited for
  /10.60.0.254 (>60 calls/minute)`)
- `dbaas.console.enabled=true`; `write`, `drop` and `datadisk.cleanup` are all
  still `false`
- the marvin build failure is fixed permanently — it was root-owned build
  output; see `BUILD-MARVIN-FIX.md`

**No matrix item has been run yet.** `listDbaasTables`, `runDbaasQuery`,
`createDbaasTable`, `getDbaasJobResult`: zero calls in the log. That is tonight's
work.

## 2. Do this first, before anything else (10 minutes)

1. `git checkout -- ui/public/config.json` — it is a `npm run build` artifact
   (`docHelpMappings`), not a change
2. commit and push the five untracked documents: `ACCEPTANCE-REPORT-2026-09-06.md`,
   `AUDIT-CONSOLE-2026-09-06.md`, `BUILD-MARVIN-FIX.md`,
   `PROMPT-GLM-HOST-ACCEPTANCE.md`, `PROMPT-GLM-HOST-ADDENDUM.md`, and this file
3. fix the agent's duplicate parameter: `extensions/dbaas/agent/dbaas_agent.py:43-44`
   sends `command` in **both** the query string and the POST body, which makes
   CloudStack log `Query parameter 'command' has multiple values` on every poll.
   Send it once. Commit.

**Push after each milestone below, not only at the end.** If the session dies at
03:00, whatever is pushed is what survives.

## 3. The main work: `PLAN-DBAAS-CONSOLE.md` §11 on mysql

Work the matrix on `dbaas-accept-m2` (or a fresh instance from template 210 if
you prefer a clean one). Order, because it finds problems fastest:

1. items 1–6 — list, describe, preview, create table, add column + index, query
2. items 7–14 — the ones that matter:
   - write refused while the session is `_ro` (the **database** must refuse it,
     not the UI)
   - `dropDbaasTable` with a mismatched `confirm`
   - statement timeout kills a deliberately slow query, and the agent survives
   - ACL denial across accounts
   - replayed agent token after rotation
   - a result fetched twice (second answer `collected`, row gone from
     `dbaas_job_results`)
   - **log hygiene**: run a query with a distinctive literal, then grep
     `management-server.log` for that literal and for the row values, and paste
     the command with its empty output
   - job expiry with the agent stopped
3. item 15 — **repeat a representative subset with the virtual router
   stopped.** This is the claim the whole architecture exists for. It has never
   been tested. If it fails, that is the single most valuable finding of the
   night; record exactly what failed and stop testing that axis rather than
   trying to force it.

For write-path items: turn `dbaas.console.write.enabled` on for those items
only, and **turn it back off afterwards**. `dbaas.console.drop.enabled` stays
`false` all night — test the refusal, not the deletion.

Record every item as **pass / fail / not run** with the command and the observed
output. "Not run" is a valid answer and is worth more than a tick you cannot
defend.

## 4. Then the remaining templates

Only after the mysql matrix is done — if it finds defects, the other images
would need rebuilding anyway.

Patch **211 (mariadb)**, then **212 (postgresql)**, then **213 (mongodb)**:
secondary and primary cache, same eight-file contract as 210, plus the engine's
python client (`python3-pymysql`, `python3-psycopg2`, `python3-pymongo`).
DNS inside the chroot does not work on this host — keep using the `.deb`-through-
the-host workaround you already established, and do not touch the image's
resolver.

Per engine afterwards: deploy one instance, confirm `confirmed`, confirm the
agent checks in, and run matrix items 1–3 plus one write item. The full matrix
does not need repeating four times; the transport does.

Mongo is browse-only by design (`PLAN-DBAAS-CONSOLE.md` §9) — do not treat a
missing SQL editor there as a defect.

## 5. Rules while nobody is watching

**You may:** build, patch images, register templates, deploy and destroy
instances you created, restart `cloudstack-management`, change the two console
settings named above, fix defects you find in the plugin, the scripts or the UI.

**You may not:** touch `DATA-73` or `/export/primary/tplbackup/**`; enable
`dbaas.console.drop.enabled` or `dbaas.datadisk.cleanup.enabled`; weaken a
default, a guard or a permission to make an item pass; `git push --force`;
delete anything under `/export/secondary/template/**`.

**Build discipline:** never `sudo mvn`, never build from a root shell. Before
each build run `find . -user root ! -path './.git/*' | head` — if it prints
anything, `chown -R nacl:nacl .` first. That is what caused every "marvin
error" so far.

**Stop and wait** if the management server or the zone ends up unhealthy and you
cannot restore it, or if `/` drops below 2 GB. Restoring service beats
finishing the matrix. Do not start deleting things to make room.

**Defects:** fix what is inside the plugin, the scripts or the UI when you
understand the cause and the fix is contained — commit it, rebuild what needs
rebuilding, re-run the affected item. Report and move on when it needs a core
change, a redesign, or when the second attempt has failed. Then continue with
the items that are not blocked.

## 6. Before you stop

- every setting back to where it was, except `dbaas.console.enabled` — say what
  you left it at and why
- `git status` clean; everything pushed to `v2` and `ui`
- report written: `ACCEPTANCE-REPORT-2026-09-06.md` updated in place (it is
  already structured for this) or a dated successor

The report must contain, beyond the matrix table:

1. a timeline of the night
2. every host change with its undo
3. defects found: fixed (with commit hashes) or left (with the reason)
4. build output tails
5. the §12.5 one-line answer, **from what you observed**: can a tenant, on a
   network the management server cannot reach and with the virtual router
   stopped, browse their tables, run a query and create a table — and is
   dropping a table still disabled?
6. what you did not test, listed plainly

If everything passes early, do not start C5 or anything new. Re-run item 15 on a
second engine instead, or improve the evidence in the report. A quiet, well
evidenced night is the outcome being asked for.
