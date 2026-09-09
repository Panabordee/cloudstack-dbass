# Finish it — overnight run, one report at the end

The 2026-09-09 session (`bca61f6720`) did the hard part: all four template
images rebuilt and verified, all four engines provisioning and running
console jobs, memory floors measured, matrix items 10/11/14 run, eleven
defects fixed. What is left is 16–23 hours of work with one genuine blocker
in it.

This is an **unattended overnight run**. Nobody is watching. Work straight
through the list, keep going past anything that stalls, and write **one**
report at the end. Do not stop to ask a question you can answer by testing.

Read `MASTER-PLAN-2026-09-08.md` first — it is the living plan, every item
below is item A–G there, and each is written to be self-contained. This file
is the order, the rules, and what to report.

Work on `cloudstackcve`, `/home/nacl/dbaas-v2`, branch `4.23+dbass`, at
`44e49cfa67` or later.

---

## 0. The context that changes several old decisions

This plugin is an add-on to a **departmental cloud that bills usage as
per-user credit**. That single fact drives three things people got wrong
before, including in earlier versions of this plan:

- a DBaaS instance is **the user's own VM, paid for out of their own
  credit** — not a managed black box. Everything they can do with a normally
  created instance (SSH in, reset the password, attach a keypair) must work
  here too
- the tenant has to **create it themselves**, or the usage lands on the
  wrong account. Tenant self-service is structural, not a preference
- a leftover volume is **not clutter, it is a charge** against a user for
  storage they can no longer see

## 1. Rules

**Never, without asking first:** delete or modify
`/export/primary/tplbackup/**`; touch `DATA-73`; destroy or modify an
instance you did not create (the `glmc-*` measurement instances *are* yours,
see G); weaken a default, widen a permission or disable a check to make a
test pass; `git push --force`.

**Two flags are different tonight.** `dbaas.console.drop.enabled` may be set
to `true` — but **only after C2 ships and is proven**, never before.
`dbaas.datadisk.cleanup.enabled` is a decision in D2; if you turn it on, say
so loudly in the report with the evidence behind it.

**Always:** engine/script/port mappings stay in
`extensions/dbaas/config.json`, never a dict or switch in `.py`/`.java`.
**No `Co-Authored-By` trailer on any commit** — author stays
`SnowFlex <68010697@kmitl.ac.th>`. Hard requirement from the repo owner.
Every host change is reported with its undo; if you cannot state the undo,
do not run the command.

**When the work grows: report, do not chase.** Fix it when the cause is
understood and the fix is contained. Report and move on when it needs a
CloudStack core change or a redesign, or when your second attempt failed —
then carry on with what is not blocked. Stop and wait only if the zone is
left unhealthy and you cannot restore it: restoring service beats finishing
the list.

**Do not build anything from `NEON-ROADMAP.md`.** All seven phases are cut,
with reasons recorded in `MASTER-PLAN-2026-09-08.md`. If an item here looks
like it wants branching or PITR to be done properly, it does not — read C2.

## 2. The batching rule

The agent has **no self-update path**, so any change to `dbaas_agent.py` or
the engine scripts costs a four-image `qemu-nbd` pass — the most expensive
and most error-prone operation in this project, and it has already broken
twice. There is **exactly one image pass tonight**, in step 6. Do not make a
guest-side change before it. Everything else is server-side, UI-side or
zone-side.

---

## 3. Step one: item A — make the console work in a browser

**The only item that decides whether the product is usable at all.**
Everything proven so far was proven through `cmk`. Do this first.

Symptom from last session: after the nesting-depth fix (`d51018873d`) was
deployed, the console's **Refresh click produces no API request at all** —
the handler runs, no POST is sent, no client error surfaces. Two hypotheses
were recorded and neither was tested: the drawer's resource binding, or an
axios-level rejection swallowing the call.

Playwright and headless Chromium are installed. Rollback tree at
`/usr/share/cloudstack-ui.bak.20260909`. Screenshots so far in
`ACCEPTANCE-SHOTS-20260909/` (00–14) — continue the numbering.

Resume the interrupted approach: instrument the component, rebuild
(`npx vue-cli-service build`, `NODE_OPTIONS=--openssl-legacy-provider`),
deploy, capture browser-side network activity, and find out which hypothesis
is true — or that both are wrong. **Remove the debug lines before
committing.**

Then finish the click-through, screenshot each step: table list → describe →
preview → run a query → create a table → confirm Drop is absent or disabled
(it is still `false` at this point). And the wizard's service-offering
filter: disabled until an engine is chosen, only offerings at or above the
engine's floor (mysql 1024 MB, the other three 512 MB), stranded selection
cleared when the engine changes, warning paragraph when nothing qualifies.

If A defeats you after a genuine effort, **write down exactly where you got
to and move on to step 4** — the rest of the list does not depend on it.

## 4. Step two: VM access parity — D, D2, D3

This is the block that the new context added. None of it touches an image.

**D — the user must be able to get into their own VM.** Two paths, and only
the first is understood:

1. *Password — known broken.* `createDatabase` deploys with `startvm=false`
   and the plugin starts the instance through the internal two-argument
   path, so `Param.VmPassword` is never populated. All four templates
   already report `passwordenabled=true`, so the template side is fine. Fix
   the plugin's start call (`resetPasswordForVirtualMachine`, or set
   `Param.VmPassword`) while the instance is Stopped.
2. *SSH keypair — untested, test it before writing any code.* The wizard
   already sends `keypairs` to `deployVirtualMachine`
   (`CreateDatabaseInstance.vue:491`) and cloud-init injects keys from the
   config drive's `meta_data.json` — but all four templates report
   `sshkeyenabled=false`, and nobody has ever tried. **Deploy with a keypair
   and actually `ssh` in.** If it works, that is a result and there is
   nothing to build. If it does not, it is a template fix, not a plugin fix.

Done when both paths let a user into their own instance, proven by a real
login.

**D2 — usage and credit correctness.** "It works" is not enough when credit
is the unit.

1. Deploy as a **tenant** (needs E, so do E first or interleave), then
   confirm in `cloud_usage` that usage records for the VM *and* its data
   disk carry that tenant's account — not the admin's. Verify it, do not
   reason about it
2. **The orphan data disk is a billing bug.** With
   `dbaas.datadisk.cleanup.enabled=false`, destroying a DBaaS instance
   leaves the data disk: the sweep reports it but does not delete it, so the
   user keeps being charged for storage they can no longer see or use. The
   sweep already identifies exactly these disks (unattached, `dbaas.instance`
   marker, instance expunged, older than 24 h).

   Decide and implement one of: turn the sweep on, or make the destroy flow
   warn the user that the volume survives and must be deleted manually.
   Silently charging for an invisible disk is the one outcome that is
   clearly wrong. Say which you chose and why.

**D3 — the tenant has root in their own VM.** That follows from D and makes
one assumption worth testing rather than trusting: take an agent token out of
one instance and try to poll for, and answer, a job belonging to a
**different** instance. The lookup is keyed on `vm_id`
(`DbaasManagerImpl.java:781`) so it should hold — prove it, and add it to the
matrix beside item 11. Everything else a root tenant can reach in that VM is
their own data and is fine.

## 5. Step three: the rest of the server-only work — B, E, E2, snapshots

Independent of each other; any order.

**E — tenant self-service.** Structural, per §0: credit only lands on the
right account if the tenant makes the call. The plugin's commands are not in
the default User role's list, so a tenant is refused at the API-permission
layer even on its own instances. Add the DBaaS commands to the User role.

Then **re-run matrix item 10 properly** — its earlier pass was for the wrong
reason. A tenant must now reach **its own** instances and still be refused on
**another account's**. The plugin's `getEntityOwnerId` ACL checks exist but
have never once executed, because the role layer refused first. **That first
real ACL run is the main risk of the night.** If a tenant reaches another
account's anything, stop that thread, write it up as a finding, and continue
with the rest — do not paper over it.

**B — log redaction.** Query text and result rows still reach
`management-server.log`: `requestHasSensitiveInfo` masks the audit/API layer,
not `ApiServlet`'s DEBUG logging, and this host runs `com.cloud` at DEBUG.
Build a **redacting filter scoped to `RunDbaasQueryCmd` and
`GetDbaasJobResultCmd`** — not a blanket log-level drop, which trades the
platform's debuggability away to fix one leak. Prove it with a canary
literal: `grep` for the literal *and* the returned row values, paste the
command and its empty output.

**E2 monitoring.** Alert when `last_seen_at` in `dbaas_agent_tokens` stops
moving for N hours (pick N, say why). A stuck agent is recoverable by
re-provisioning; the real problem is that nobody finds out.

**Snapshot policy.** `createSnapshotPolicy` on the data volume — daily
volume snapshots using CloudStack's own feature. About an hour. This plus C2
is the whole of the data-loss story; PITR is not being built.

## 6. Step four: the one image pass — C2, E2 atomic write, C

Only after steps 3–5 are done or written off. All three changes go in
together. Follow `RUNBOOK-PATCH-TEMPLATES-2026-09-05.md` and
`MASTER-PLAN-2026-09-08.md` §2's file table: back up first, **`sync` →
`umount` → `qemu-nbd -d` in that order**, `qemu-img check` after, patch both
the secondary copy and the primary cache.

**C2 — dump before drop. This is what unlocks the third feature.**
`DropDbaasTableCmd` → `table_drop` → `run_ddl_job` is complete and working;
the only thing holding it is `dbaas.console.drop.enabled=false`, because a
drop is the first irreversible thing this product would offer a tenant.

Before the `DROP` executes, dump that one table
(`mysqldump --single-transaction <db> <table>`, `pg_dump -t`,
`mongodump --collection`) to a timestamped file under
`/var/lib/dbaas/predrop/` on the instance's own disk, and **refuse the job if
the dump fails**. Add a typed confirmation of the table name in the UI. Keep
the last N dumps; say what N is and why.

Then, and only then, set `dbaas.console.drop.enabled=true` and prove the
round trip: drop a table, confirm it is gone, confirm the dump exists and
restores.

**E2 atomic write.** `save_conf` (`dbaas_agent.py:44`) opens `O_TRUNC` and
writes in place, so a force-stop mid-write costs the agent its whole config,
not just its token. Temp file in the same directory → `fsync` →
`os.replace()`. Four lines.

**C — Reset Database Password.** `DbaasManagerImpl.java:1367` still throws an
exception whose message says the in-VM agent does not exist; it does, and it
is proven on all four engines. Add a `password_reset` job type to
`JOB_HANDLERS`, generate the password server-side, update
`dbaas_credentials` **only after the agent confirms**, never log the
plaintext. `<engine>_reset.sh` already has the SQL shape. Done when a reset
is followed by a successful connection with the new password and a refused
one with the old.

## 7. Step five: G, and F only if the night is going well

**G — housekeeping.** Destroy the four leftover `glmc-*` instances. Record
`df -h` for primary and secondary storage before and after (primary hit 90.5%
last session, secondary 94%). Update `README.md`, `INSTALL.md` and
`TEMPLATES.md` under `plugins/integrations/dbaas/`, which still describe the
retired v1 SSH transport.

**F — the VR-down proof, optional.** `DbaasIsolatedConfigDrive` still has
Dhcp and Dns on `VirtualRouter`, so CloudStack auto-heals the VR on any VM
start and "VR down" never actually happens. Services cannot be edited after
creation, so build a **new** offering with Dhcp + Dns + UserData all on
ConfigDrive (SourceNat, Firewall, PortForwarding stay on VirtualRouter),
build a network, deploy onto it, stop the VR, repeat the matrix. This is
proof, not function — the Shared-network path DBaaS actually uses is already
verified. Skip it without guilt if the night ran short.

---

## 8. Report

**One report at the end.** Update `MASTER-PLAN-2026-09-08.md` in place;
do **not** add another dated plan file — this project was already cleaned of
twenty-one superseded documents and should not re-accumulate them.

`ACCEPTANCE-REPORT-<date>.md`:

1. **A first and in the most detail**: what the Refresh-no-request bug
   actually was, how you proved it, and the full click-through with
   screenshots. If it beat you, exactly where you stopped and what to try next
2. D: the password login and the keypair login, both shown working — or the
   keypair result if it already worked untouched
3. D2: the `cloud_usage` rows proving tenant attribution, and which
   orphan-disk option you implemented, with your reasoning
4. D3: the cross-VM token test and its result
5. E: the role change, plus item 10 re-run — tenant reaching its own
   instance, and refused on another account's, both shown
6. B: the canary command and its empty output, pasted
7. E2: the alert and the N you chose; the atomic-write verification
8. Snapshot policy: what you configured
9. C2: the drop round trip — dropped, dump exists, dump restores — and the
   exact moment `dbaas.console.drop.enabled` went to `true`
10. C: reset works, old password refused
11. The image pass: which images, backups, `qemu-img check` output
12. F and G: results, or why not reached
13. Every host change with its undo
14. Defects: fixed (with commit hashes) or left, with the reason
15. Final flag state: `dbaas.console.write.enabled`,
    `dbaas.console.drop.enabled`, `dbaas.datadisk.cleanup.enabled`,
    `dbaas.console.enabled` — each with what it is and why
16. `DATA-73` and `/export/primary/tplbackup/**` untouched, confirmed
17. No commit carries a `Co-Authored-By` trailer

Finish by answering, from what you observed rather than from what the code
should do:

> Can a user of this cloud, logged in as **their own account**, create a
> database instance on each of the four engines, browse its tables, run a
> query, create a table, drop a table and get it back, reset the database
> password, connect a normal DB client, and **SSH into the instance they are
> paying for** — with the usage landing on their own credit, and without
> seeing anything belonging to another user?

If that is yes on all four engines, the project is finished.
