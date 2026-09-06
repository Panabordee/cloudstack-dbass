# Host round — take the console from "code complete" to "observed working"

The code passed audit at `6280f8056a`. Every claim about it is still
design-level except the DB round-trip test and the 403→429 wire evidence.
This session is the one that touches the host: build, rebuild templates,
deploy, and run the acceptance matrix that is currently entirely "not run".

---

You are working on `cloudstackcve` in `/home/nacl/dbaas-v2`, branch
`panabordee/dbaas-v2`, at `6280f8056a` or later. Read
`AUDIT-CONSOLE-2026-09-06.md`, `AUDIT-FIXES-2026-09-06.md`,
`PLAN-DBAAS-CONSOLE.md` §11, and `RUNBOOK-PATCH-TEMPLATES-2026-09-05.md`
before starting.

## 1. What changed about the rules

Previous sessions forbade host changes. **This session is the host work**, so
the boundary moves:

**Allowed now**, because the task is impossible otherwise:

- full builds, hot-patching the management jar, restarting
  `cloudstack-management` and the agent
- patching and rebuilding the four DBaaS template images
- registering templates, deploying and destroying **instances you created
  yourself** for testing
- writing to the `dbaas_*` tables through the plugin's own code paths and
  through the round-trip test

**Still forbidden without asking:**

- deleting `/export/primary/tplbackup/**` — that is the template rollback path
- touching `DATA-73` — it is the evidence for the data-disk defect
- destroying or modifying `dbaas-final2` or any instance you did not create
- changing a `dbaas.*` global setting other than the ones this document names,
  and never to make a test pass
- enabling `dbaas.console.drop.enabled` or `dbaas.datadisk.cleanup.enabled`
- `git push --force`, history rewrites

Everything you change on the host goes in the report with the command used and
how to undo it. If you cannot state the undo, do not run the command.

## 2. Pre-flight

**Disk.** `/` was at 2.2 GB free and a full build will not fit. Reclaim space
before anything else, and report what you removed:

- safe: `~/.m2` snapshot jars you can re-download, `target/` directories,
  old build logs, `/tmp` leftovers, `__pycache__`
- **not safe without asking**: `/export/primary/tplbackup/**`, anything under
  `/export/secondary/template/**`, any `*.qcow2` in `/export/primary`

Check `df -h /` before and after and put both in the report.

**Build.** In this order, and each must be clean before the next:

```bash
mvn -pl plugins/integrations/dbaas -am install
mvn -T2 -DskipTests -Dnoredist install        # the full server build, ~20-40 min
cd ui && npm ci && npm run build              # ~10-20 min
```

A build failure is yours to fix. If it fails on disk space, stop and report —
do not start deleting to make room.

## 3. Template rebuild

Only template 211 (secondary) and the mariadb primary cache carry the current
`firstboot.sh`. Everything else is stale, and none of the four carries the
agent.

**Prove the whole path on mariadb first.** Rebuild one image, deploy, get the
console working end to end, and only then repeat for mysql, postgresql and
mongodb. Four half-built images teach you nothing; one working one teaches you
everything.

Each image needs:

| Path in image | Source | Mode |
| --- | --- | --- |
| `/opt/dbaas/firstboot.sh` | `extensions/dbaas/provisioning/` | 0755 |
| `/opt/dbaas/report-retry.sh` | same | 0755 |
| `/opt/dbaas/<engine>.sh`, `<engine>_reset.sh` | same (now with the `_ro` role) | 0755 |
| `/opt/dbaas/engine` | `<engine>.sh` | 0644 |
| `/opt/dbaas/agent/` | `extensions/dbaas/agent/` | 0755 dir, 0755 `dbaas_agent.py` |
| `/etc/systemd/system/dbaas-report-retry.{service,timer}` | same | 0644 |
| `/etc/systemd/system/dbaas-agent.service` | `extensions/dbaas/agent/` | 0644 |
| `/etc/systemd/network/99-dbaas-fallback.network` | `extensions/dbaas/provisioning/` | 0644 |

both units enabled (symlink into `timers.target.wants` /
`multi-user.target.wants`), and the engine's python client library present:

- mysql / mariadb: `python3-pymysql`
- postgresql: `python3-psycopg2`
- mongodb: `python3-pymongo`

**Installing packages is the part the offline `qemu-nbd` recipe cannot do.**
Two options, pick one and say which:

1. **Boot a VM from the template, install, re-template** — the v1 recipe in
   `TEMPLATES.md` §"Rebuilding a template". Correct, slower, and it gives you a
   live instance to test the agent on before templating.
2. **chroot into the mounted image** with `/dev/`, `/proc`, `/sys` and
   `/etc/resolv.conf` bind-mounted, then `apt-get install`. Faster, but it
   writes to the image with a package manager that thinks it is a running
   system — verify `dpkg -l` afterwards and check nothing started a service.

Either way: back up the image first (`/export/primary/tplbackup`), run
`qemu-img check` after, and patch **both** the secondary copy and the primary
cache. `sync` → `umount` → `qemu-nbd -d`, in that order, always.

## 4. Deploy and run the acceptance matrix

Enable the console feature for the test only:

```
dbaas.console.enabled = true        # record the old value; it must go back to false if you stop mid-way
```

Leave `dbaas.console.write.enabled` and `dbaas.console.drop.enabled` at
`false` until the read paths are proven, then turn write on for items 5 and 7
of the matrix and turn it off again afterwards.

Then work `PLAN-DBAAS-CONSOLE.md` §11 item by item. The order that finds
problems fastest:

1. deploy an instance, confirm `pending → confirmed` still works (the
   provisioning path must not have regressed)
2. confirm the agent checks in: `journalctl -u dbaas-agent` on the guest, and
   `last_seen_at` moving in `dbaas_agent_tokens`
3. matrix items 1–6 (functional: list, describe, preview, create, alter, query)
4. matrix items 7–14 (the ones that matter): write refused as `_ro`, drop
   confirmation, statement timeout kills a `SELECT pg_sleep(600)`, ACL denial
   across accounts, replayed token after rotation, result fetched twice,
   **log hygiene**, job expiry with the agent stopped
5. matrix item 15: repeat 3 and 4 with the virtual router stopped. This is the
   claim the whole architecture exists for — if it only works with the VR up,
   say so plainly.

For each item record: the command or UI action, the observed result, and for
failures the exact error. **"Not run" stays a valid answer** — an honest gap is
worth more than a tick you cannot defend.

Item 13 (log hygiene) is not a formality: run a query with a distinctive
literal, then `grep` `management-server.log` for that literal and for the row
values, and paste the command and its empty output.

## 5. If something is broken

You will find defects; that is what a first live run is for.

- **Fix it** when it is in the plugin, the scripts or the UI, you understand
  the cause, and the fix is contained. Commit it, rebuild what needs
  rebuilding, and note it in the report.
- **Report and move on** when it needs a core change, a different template
  build, or a redesign — or when the second attempt has failed. Then continue
  with the matrix items that are not blocked by it.
- **Stop and wait** if the management server or the zone is left unhealthy and
  you cannot restore it. Restoring service beats finishing the matrix.

Do not disable a check, widen a permission or relax a default to make an item
pass. An item that fails honestly is a result; one that passes because the
guard was removed is a lie that costs a day later.

## 6. Report

`ACCEPTANCE-REPORT-<date>.md`:

1. Disk: `df -h /` before and after, and exactly what you deleted
2. Builds: the tail of each of the three builds
3. Templates: which images were rebuilt, by which method (§3 option 1 or 2),
   the backup paths, and the `qemu-img check` output
4. Host changes: every command that changed host state, with its undo
5. The §11 matrix, item by item: **pass / fail / not run**, with evidence —
   commands, outputs, log lines pasted, not summarised
6. Item 15 (VR stopped) called out separately: it is the architecture's
   central claim
7. Defects found: what you fixed (with commits) and what you left, with the
   reason
8. Settings: proof that `dbaas.console.write.enabled`,
   `dbaas.console.drop.enabled` and `dbaas.datadisk.cleanup.enabled` are back
   to `false`, and what `dbaas.console.enabled` was left at
9. `DATA-73` and `tplbackup` untouched, confirmed
10. The one-line summary from `PLAN-DBAAS-CONSOLE.md` §12.5, answered from
    what you observed rather than from what the code should do

Finish by saying plainly whether a tenant can now, on a network the management
server cannot reach and with the virtual router stopped: browse their tables,
run a query, and create a table — and whether dropping a table is still
disabled.
