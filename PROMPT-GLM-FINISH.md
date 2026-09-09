# Finish it — close every remaining item and report once at the end

The 2026-09-09 session (`bca61f6720`, `ACCEPTANCE-REPORT-2026-09-09.md`) got
the hard part done: all four template images rebuilt and verified, all four
engines provisioning and running console jobs, memory floors measured, matrix
items 10/11/14 run, eleven defects found and fixed.

What is left is smaller than what is behind you, and one item is worth more
than all the others combined.

Read `MASTER-PLAN-2026-09-08.md` first — it is the living plan and every item
below is item A–G there, written to be self-contained. This prompt is the
order, the rules, and what to report.

Work on `cloudstackcve`, in `/home/nacl/dbaas-v2`, branch `4.23+dbass`, at
`a9651b8674` or later.

---

## 1. Rules

**Never, without asking first:** delete or modify
`/export/primary/tplbackup/**`; touch `DATA-73`; destroy or modify an
instance you did not create (the `glmc-*` measurement instances are yours to
destroy, see G); set `dbaas.console.drop.enabled` or
`dbaas.datadisk.cleanup.enabled` to `true`; weaken a default, widen a
permission or disable a check to make a test pass; `git push --force`.

**Always:** engine/script/port mappings stay in
`extensions/dbaas/config.json`, never a dict or switch in `.py`/`.java`.
**No `Co-Authored-By` trailer on any commit** — author stays
`SnowFlex <68010697@kmitl.ac.th>`; this is a hard requirement from the repo
owner, not a preference. Every host change is reported with its undo; if you
cannot state the undo, do not run the command.

**When the work grows: report, do not chase.** Fix it when the cause is
understood and the fix is contained. Report and move on when it needs a
CloudStack core change or a redesign, or when your second attempt failed —
then continue with what is not blocked by it. Stop and wait only if the zone
is left unhealthy and you cannot restore it.

## 2. The batching rule — read before touching anything in a guest

The agent has **no self-update path**. Any change to `dbaas_agent.py` or the
engine scripts costs another four-image `qemu-nbd` pass, which is the most
expensive and most error-prone operation in this project — it has already
broken twice (the engine-marker bug, and the stale-agent gap).

So there is **exactly one image pass in this session**, in step 5, carrying
both guest-side changes together. Do not do a guest-side change before it.
Everything else here is server-side, UI-side or zone-side and needs no image
pass at all.

---

## 3. Step one: item A — make the console work in a browser

**This is the only item that decides whether the product is usable.**
Everything proven so far was proven through `cmk`. Do this first and do not
move on until it works or you have genuinely exhausted it.

The symptom, from the last session: after the nesting-depth fix
(`d51018873d`) was deployed, the console's **Refresh click produces no API
request at all** — the handler runs, no POST is sent, no client error
surfaces. Two hypotheses were recorded and neither was tested: the drawer's
resource binding, or an axios-level rejection swallowing the call. A
debug-logging build was interrupted; its temporary lines were removed before
committing, so the committed `DbaasConsole.vue` is the clean fix only.

Available to you: Playwright and headless Chromium are installed on the host.
The previous UI tree is preserved at `/usr/share/cloudstack-ui.bak.20260909`
— that is your rollback. Screenshots so far are in
`ACCEPTANCE-SHOTS-20260909/` (00–14); continue the numbering.

Resume with the debug-logging approach that was interrupted: instrument the
component, rebuild
(`npx vue-cli-service build`, `NODE_OPTIONS=--openssl-legacy-provider`),
deploy, capture the browser-side network activity, and find out which of the
two hypotheses is true — or that both are wrong. **Remove the debug lines
before committing**, exactly as the last session did.

Then finish the click-through that was never completed, with a screenshot for
each: table list → describe → preview rows → run a query → create a table →
confirm Drop is absent or disabled. And the create-instance wizard's
service-offering filter: disabled until an engine is chosen, only offerings
at or above the engine's floor (mysql 1024 MB, the other three 512 MB), a
stranded selection cleared when the engine changes, and the warning
paragraph when nothing qualifies.

## 4. Step two: the server-only batch — B, D, E, E2-monitoring

None of these touch an image. Do them in whatever order suits you; they are
independent.

**B — log redaction.** Query text and result rows still reach
`management-server.log`, because `requestHasSensitiveInfo` only masks the
audit/API layer, not `ApiServlet`'s DEBUG logging, and this host runs
`com.cloud` at DEBUG. Build a **redacting filter scoped to `RunDbaasQueryCmd`
and `GetDbaasJobResultCmd`** — not a blanket log-level drop, which trades the
whole platform's debuggability away to fix one leak. Prove it: run a query
containing a distinctive canary literal, then `grep` the log for that literal
*and* for the returned row values, and paste the command with its empty
output.

**D — VM login password.** Fully diagnosed in
`VM-PASSWORD-DEFECT-2026-09-05.md`. `createDatabase` deploys with
`startvm=false` and starts the instance through the internal two-argument
path, so `Param.VmPassword` is never populated. Call
`resetPasswordForVirtualMachine` (or set `Param.VmPassword` on the plugin's
own start call) before the config-drive attach, while the instance is
Stopped. Done when a tenant retrieves the VM login password through the
normal CloudStack path and it actually logs in.

**E — tenant self-service.** The owner has decided tenants use DBaaS
themselves. The plugin's commands are not in the default User role's list, so
a tenant is refused at the API-permission layer even on its own instances.
Add the DBaaS commands to the User role.

Then **re-run matrix item 10 properly**, because its earlier pass was for the
wrong reason: a tenant must now reach **its own** instances and still be
refused on **another account's**. The plugin's `getEntityOwnerId` ACL checks
exist but have never once executed, because the role layer refused first.
**That first real ACL run is the risk in this whole session** — treat a
tenant reaching another account's data as a stop-and-report finding, not a
small bug.

**E2 monitoring.** Alert when `last_seen_at` in `dbaas_agent_tokens` stops
moving for N hours (pick N and say why). A stuck agent is recoverable by
re-provisioning; the actual problem is that nobody finds out it is stuck.

## 5. Step three: the one image pass — C and E2's atomic write

Only after steps 3 and 4 are done or reported as blocked. Both changes go in
together; follow `RUNBOOK-PATCH-TEMPLATES-2026-09-05.md` and
`MASTER-PLAN-2026-09-08.md` §2's file table. Back up, `sync` → `umount` →
`qemu-nbd -d` in that order, `qemu-img check` after, patch both the secondary
copy and the primary cache.

**C — Reset Database Password.** `DbaasManagerImpl.java:1367` still throws an
exception whose message says the in-VM agent does not exist; it does now, and
it is proven on all four engines. Add a `password_reset` job type to
`JOB_HANDLERS` in `extensions/dbaas/agent/dbaas_agent.py`, generate the
password server-side, update `dbaas_credentials` **only after the agent
confirms**, and never log the plaintext. The `<engine>_reset.sh` scripts
already carry the SQL shape. Done when a reset from the UI is followed by a
successful connection with the new password and a refused one with the old.

**E2 atomic write.** `save_conf` (`dbaas_agent.py:44`) opens `O_TRUNC` and
writes in place, so a force-stop mid-write costs the agent its whole config,
not just its token. Write to a temp file in the same directory, `fsync`,
then `os.replace()`. Four lines. Verify by killing the agent mid-write if you
can contrive it, or at minimum by confirming the file is never observed
truncated across a forced stop.

## 6. Step four: F and G — only if the above is genuinely finished

**F — the VR-down proof.** `DbaasIsolatedConfigDrive` still has Dhcp and Dns
on `VirtualRouter`, so CloudStack auto-heals the VR on any VM start and "VR
down" never actually happens. A network offering's services cannot be edited
after creation, so create a new offering with Dhcp + Dns + UserData all on
ConfigDrive (SourceNat, Firewall, PortForwarding stay on VirtualRouter),
build a network from it, deploy onto it, stop the VR, and repeat the console
matrix. This is **proof, not function** — the Shared-network path DBaaS
actually deploys to is already verified. If it turns out not to work with the
VR down, say so plainly; that is a real finding.

**G — housekeeping.** Destroy the four leftover `glmc-*` measurement
instances. Record `df -h` for primary and secondary storage before and after
(primary hit 90.5% last session, secondary was at 94% — these numbers are
NEON gate G4 and the next phase needs them). Update `README.md`,
`INSTALL.md` and `TEMPLATES.md` under `plugins/integrations/dbaas/`, which
still describe the retired v1 SSH transport. Leave
`dbaas.console.drop.enabled` at `false`.

---

## 7. Report

**One report at the end, not one per step**, and update
`MASTER-PLAN-2026-09-08.md` in place rather than adding another dated plan
file — the project has already been cleaned of twenty-one superseded
documents and should not re-accumulate them.

`ACCEPTANCE-REPORT-<date>.md` covering:

1. **Item A first and in the most detail**: what the Refresh-no-request bug
   actually was, how you proved it, and the full click-through with
   screenshots. If it beat you, say exactly where you stopped and what the
   next person should try
2. B: the canary command and its empty output, pasted
3. D: the password retrieved and used to log in
4. E: the role change, and the re-run of matrix item 10 — tenant reaching its
   own instance, and being refused on another account's, both shown
5. E2: the alert (and what N you chose), and the atomic-write verification
6. C: reset from the UI, new password works, old password refused
7. The image pass: which images, backups, `qemu-img check` output
8. F: the offering you built and the VR-down result, or why it was not reached
9. G: `df -h` before and after, instances destroyed, docs updated
10. Every host change with its undo
11. Defects found: fixed (with commit hashes) or left, with the reason
12. Proof that `dbaas.console.write.enabled`, `dbaas.console.drop.enabled`
    and `dbaas.datadisk.cleanup.enabled` are all `false`, and what
    `dbaas.console.enabled` was left at
13. `DATA-73` and `/export/primary/tplbackup/**` untouched, confirmed
14. Confirmation that no commit carries a `Co-Authored-By` trailer

Finish by answering, from what you observed rather than from what the code
should do:

> Can a tenant — logged into the UI as their own account, not as admin —
> create a database on each of the four engines, browse its tables, run a
> query, create a table, reset their password, and connect a normal client;
> is dropping a table still disabled; and does any tenant see any other
> tenant's anything?

If that answer is yes on all four engines, this phase of the project is
finished, and `PLAN-NEON-STEPS.md` Stage 1 is the next thing.
