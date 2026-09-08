# Take the DBaaS plugin from "code complete" to "observed working on all four engines"

Everything in this session's list is either finished code that has never run
on real hardware, or a test nobody has been able to reach yet. There is no
design work here and no open question about *what* to build. The work is:
get the finished code into the four template images, then find out what is
actually true.

Read `MASTER-PLAN-2026-09-08.md` first — it is the full catalog and each item
there is written to be self-contained. This prompt is the order to do them in
and the rules while doing it.

---

You are working on host `cloudstackcve`, in `/home/nacl/dbaas-v2`, on branch
`4.23+dbass` at commit `2bbd24ede6` or later. Also worth reading before you
start: `PLAN-PROVISION-TRIGGER-FIX.md` (§3.5 has the exact deploy commands),
`RUNBOOK-PATCH-TEMPLATES-2026-09-05.md` (the `qemu-nbd` recipe),
`PLAN-DBAAS-CONSOLE.md` §11 (the acceptance matrix).

## 1. Rules

This session touches the host, so builds, template rebuilds, and deploying
and destroying **instances you created yourself** are all allowed.

**Never, without asking first:**

- deleting or modifying `/export/primary/tplbackup/**` — that is the template
  rollback path, and its existing contents are not yours
- touching `DATA-73` — it is the evidence for the data-disk defect
- destroying or modifying any instance you did not create
- setting `dbaas.console.drop.enabled` or `dbaas.datadisk.cleanup.enabled` to
  `true`. Both ship `false` deliberately
- changing any other `dbaas.*` global setting except the ones §4 names, and
  never to make a test pass
- `git push --force`, or any history rewrite

**Always:**

- engine, script and port mappings live in `extensions/dbaas/config.json`,
  never in a dict or a switch statement in `.py` or `.java`. Adding an engine
  must stay a config edit
- **no `Co-Authored-By` trailer on any commit.** Author stays
  `SnowFlex <68010697@kmitl.ac.th>` or the Panabordee identity. This is a
  hard requirement from the repo owner, not a preference
- every host change goes in the report with the command used and how to undo
  it. If you cannot state the undo, do not run the command

Do not disable a check, widen a permission or relax a default to make an item
pass. An item that fails honestly is a result. One that passes because the
guard was removed is a lie that costs a day later.

## 2. When the work grows: report, do not chase

This is the rule that matters most for an unattended run.

- **Fix it** when the defect is in the plugin, the scripts or the UI, you
  understand the cause, and the fix is contained. Commit it, rebuild what
  needs rebuilding, note it in the report, carry on
- **Report and move on** when it needs a CloudStack core change, a different
  template build, a redesign — or when your second attempt has failed. Write
  down what you found, what you tried, and why you stopped, then continue
  with the items that are not blocked by it
- **Stop and wait** if the management server or the zone is left unhealthy
  and you cannot restore it. Restoring service beats finishing the list

A precise "here is what I found and why I stopped" is worth more than a
half-finished second feature. Nobody is going to be annoyed that you stopped;
they will be annoyed to discover a rewrite they did not ask for.

## 3. The main task: rebuild all four templates, in one pass

Four separate pieces of finished work exist in the repo and in no image. Each
alone would justify a rebuild, so do them together:

- `dbaas-provision.service` + the rewritten `firstboot.sh` (the second-boot
  provisioning bug — `MASTER-PLAN` §1)
- the `_ro` read-only console role in the engine scripts
- the current `dbaas_agent.py` — the 9 job-type handlers and the response
  wrapper fix exist only on template 210
- `report-retry.sh` and its systemd units — same gap, only 210 has them

`MASTER-PLAN-2026-09-08.md` §2 has the complete per-image file table, the
enable-symlink list, the per-engine Python client packages, and the two
options for installing packages into an image. Follow it exactly.

**Rebuild 210 (mysql) first and prove it completely before touching the other
three.** Its earlier "proven" status does not survive the rebuild — the image
changed. Four half-built images teach you nothing; one working one teaches
you everything.

Order inside the `qemu-nbd` recipe is not negotiable: back up the image,
connect, mount, copy, then **`sync` → `umount` → `qemu-nbd -d`**. The wrong
order corrupts the image. `qemu-img check` after. Patch both the secondary
copy and the primary cache.

## 4. Then run the acceptance that has been blocked on this

### 4a. The second-boot bug — never once tested by anyone

Every test in this project's history deployed with `startvm=false` and called
`createDatabase` immediately, always that instance's first boot. That is the
one path that sidesteps the bug. Test all six cases in `MASTER-PLAN` §1, and
case 1 (boot fully, *then* `createDatabase`) is the one that matters.

### 4b. The console matrix

Enable the console for the test only:

```
dbaas.console.enabled = true
```

Record the old value. Leave `dbaas.console.write.enabled` at `false` until
the read paths are proven, then turn it on for the write items and **off
again afterwards**. `dbaas.console.drop.enabled` stays `false` throughout.

Then `PLAN-DBAAS-CONSOLE.md` §11, item by item, in the order that finds
problems fastest: provisioning still reaches `confirmed` → agent checks in
(`journalctl -u dbaas-agent` on the guest, `last_seen_at` moving in
`dbaas_agent_tokens`) → functional items → the items that matter (write
refused as `_ro`, statement timeout kills a `SELECT pg_sleep(600)`, result
fetched twice, job expiry with the agent stopped) → the three that have never
run at all: **item 10** (cross-account ACL — create a second account),
**item 11** (replayed token after rotation), **item 14** (job expiry).

For each: the command or UI action, the observed result, and for failures the
exact error. **"Not run" stays a valid answer.**

### 4c. Per-engine memory floors — measure, do not guess

`minmemorymb: 1024` is real only for mysql. mariadb, postgresql and mongodb
carry it as an unverified copy, and the UI now filters the service-offering
dropdown by that number, so a wrong value either hides valid offerings or
lets a tenant pick one that OOMs. For each rebuilt engine: deploy on the
smallest offering, provision, run a real console query, watch
`journalctl -u <engine>` and `dmesg` for `oom-kill`, step up until clean.
Put the measured numbers in `config.example.json`.

### 4d. The isolated-network claim

Currently untestable, not merely untested: `DbaasIsolatedConfigDrive` has
Dhcp and Dns on `VirtualRouter`, so CloudStack auto-heals the VR on any VM
start and "VR down" never actually happens. `ConfigDriveNetworkElement`
advertises Dhcp and Dns capabilities, so a correct offering is constructible;
a network offering's services cannot be edited after creation, so make a new
one with Dhcp + Dns + UserData all on ConfigDrive (SourceNat, Firewall and
PortForwarding stay on VirtualRouter). Build a network from it, deploy onto
it, stop the VR, repeat the matrix.

This is the claim the whole architecture exists for. If it only works with
the VR up, say so plainly — that is a real finding, not a failure.

### 4e. Click it in a browser

Every test so far went through `cmk`. Nobody has opened the page. For one
instance: instance detail → console tab → table list → describe → preview →
query → create table → confirm Drop is absent or disabled. Screenshot each.

Watch the service-offering filter on the create wizard specifically: disabled
until an engine is chosen, only offerings with memory ≥ the engine's floor,
a stranded selection cleared when the engine changes, warning paragraph when
nothing qualifies.

## 5. If time remains — independent code items

These need no template and block nothing. In this order:

1. **Log hygiene** (`MASTER-PLAN` §4). Query text and result rows appear in
   plaintext in `management-server.log`, because `requestHasSensitiveInfo`
   only masks the audit/API layer, not `ApiServlet`'s DEBUG logging. Build
   the **redacting filter scoped to `RunDbaasQueryCmd` and
   `GetDbaasJobResultCmd`**, not a blanket log-level drop — do not trade the
   platform's debuggability away to fix one leak. Prove it with a canary
   literal and a `grep` that returns nothing
2. **Reset Database Password** (§8). `DbaasManagerImpl.java:1367` still
   throws an exception whose message says the in-VM agent does not exist.
   It does now, and it works. Add a `password_reset` job type to
   `JOB_HANDLERS` in `extensions/dbaas/agent/dbaas_agent.py:414`, generate
   the password server-side, update `dbaas_credentials` only after the agent
   confirms, never log the plaintext. The `<engine>_reset.sh` scripts already
   have the SQL shape
3. **VM login password** (§9). Fully diagnosed in
   `VM-PASSWORD-DEFECT-2026-09-05.md`, never implemented

Do not start §5 until §3 and §4 are either done or reported as blocked.

## 6. Report

Write `ACCEPTANCE-REPORT-<date>.md` and commit it.

1. `df -h /` before and after, and exactly what you deleted to make room
2. The tail of each build
3. Templates: which images were rebuilt, by which package-install method,
   the backup paths, the `qemu-img check` output
4. Every host change, with its undo
5. The six second-boot cases from `MASTER-PLAN` §1: pass / fail / not run
6. The §11 matrix item by item: pass / fail / not run, with evidence —
   commands and outputs pasted, not summarised
7. The measured memory floor per engine, and how you measured it
8. The isolated-network / VR-down result, called out separately
9. Browser screenshots
10. Defects: what you fixed (with commit hashes) and what you left, with the
    reason you left it
11. Proof that `dbaas.console.write.enabled`, `dbaas.console.drop.enabled`
    and `dbaas.datadisk.cleanup.enabled` are all back to `false`, and what
    `dbaas.console.enabled` was left at
12. `DATA-73` and `/export/primary/tplbackup/**` untouched, confirmed
13. Confirmation that no commit you made carries a `Co-Authored-By` trailer

Finish by answering, from what you observed rather than from what the code
should do: on each of the four engines, can a tenant browse their tables, run
a query, create a table, and connect with a normal DB client — and is
dropping a table still disabled?
