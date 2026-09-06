# Addendum to the host round — disk, cleanup, and two decisions

Applies on top of `PROMPT-GLM-HOST-ACCEPTANCE.md`. The owner has authorised
aggressive cleanup of this machine, including removing the monitoring stack.
Order matters: **preserve first, then delete.**

## A. Preserve before deleting — this one is irreversible if you get it wrong

`/home/nacl/cloudstack-ui-src` (4.0 GB) is the **only place the v1 code still
exists**. Verified today:

```
git ls-remote --heads myfork   →  backup, main, ui, v2      (that is all)
```

The v1 tip `0d98c736b0` is on the local branches `panabordee/dbaas-v1-archive`
and `panabordee/dbaas-full` only; the GitHub branch that used to hold it was
deleted. `PLAN.md` §7 exists specifically to stop that code vanishing.

So, before that directory is touched at all:

```bash
cd /home/nacl/cloudstack-ui-src
git push myfork panabordee/dbaas-v1-archive        # 0d98c736b0 — the v1 archive
git push myfork panabordee/dbaas-ux-fixes          # no upstream at all
git push myfork panabordee/dbaas-full              # it is [ahead 1]
git ls-remote --heads myfork                       # confirm all three landed
```

**Only after `ls-remote` shows them** may the directory be considered
reclaimable — and even then, do not delete it unless disk actually blocks a
build. It is the fallback if anything about v2 has to be compared against v1.

## B. Remove the monitoring stack (authorised)

Nothing in CloudStack depends on any of it:

```bash
sudo systemctl disable --now alloy loki prometheus-node-exporter 2>/dev/null
sudo apt-get purge -y alloy loki prometheus-node-exporter prometheus-node-exporter-collectors
sudo apt-get autoremove -y && sudo apt-get clean
sudo rm -rf /var/lib/alloy /etc/alloy /var/lib/loki /etc/loki
sudo journalctl --vacuum-size=100M
```

`loki` was the thing writing 8.4 GB/day of debug output into syslog, so this
also removes the cause rather than the symptom. Record the package list in the
report; the undo is `apt-get install` of the same names.

## C. Safe to delete now

- `/home/nacl/dbaas-deb` and `/home/nacl/dbaas-v2-deb` (816 MB of old build
  output — the `.deb`s are rebuildable)
- `target/` directories once a build has been consumed
- `/root/.npm`, `/root/.cache`, `~/.npm` (caches; `npm ci` will refill what it
  needs)
- `__pycache__`, `/tmp` leftovers

**Not while a build is running:** `~/.m2` and `/root/.m2` (1.9 GB combined).
After the builds finish they can be pruned, but prefer removing only
`SNAPSHOT` directories — a full wipe costs a long re-download on the next
build.

**Never, without asking:** `/export/primary/tplbackup/**`, anything under
`/export/secondary/template/**`, `DATA-73`, `/root/.zcode` and `~/.claude`
(other tools' state, not yours).

## D. Decision taken for you: expunge `dbaas-final3`

`i-2-74-VM` (`dbaas-final3`) runs on an overlay whose backing file is
`8ceff582…`, the primary cache of template 211 — confirmed today with
`qemu-img info`. That is what blocks patching the mariadb cache.

**Patching a backing file underneath a live overlay is unsafe even with the VM
stopped**: the overlay stores only changed clusters and reads everything else
straight from the backing file, so rewriting it changes what the guest sees for
every untouched cluster — inode tables, journal, the lot. Stopping the VM
moves when the corruption shows up, not whether it happens.

`dbaas-final3` has already served its purpose and the evidence is durable:
`provisioning report accepted for VM 3698af43-…` at 17:41:28 in
`management-server.log`, plus the round-trip in `AUDIT-FIXES-2026-09-06.md`.

**Expunge it**, then patch the 211 cache normally. This is irreversible; it is
authorised here explicitly so you do not have to ask again. Everything else in
the "do not touch" list stands.

If for any reason you want to keep it, the alternative is to register the
patched mariadb image as a **new** template (`dbaas-mariadb-v3`) so it gets its
own cache — but that costs ~3.2 GB on `/export/secondary`, which has 5 GB free,
so check before choosing it.

## E. Git discipline — do not leave work only on this machine

At the end of the session, and again if you stop early:

- commit and push everything to `v2` and `ui`, including the documents that are
  currently untracked in `dbaas-v2`: `ACCEPTANCE-REPORT-2026-09-06.md`,
  `AUDIT-CONSOLE-2026-09-06.md`, `PROMPT-GLM-HOST-ACCEPTANCE.md`, and this file
- **`ui/public/config.json` is a build artifact, not a change**: `npm run build`
  regenerates its `docHelpMappings` (149 lines). Revert it rather than
  committing it — `git checkout -- ui/public/config.json`
- `git status` at the end must be clean, or the report explains what remains
  and why

The rule from the last round still applies and is the reason it is repeated
here: **uncommitted work is not delivered.** The volume-service fix sat in a
working tree for a day while everyone believed it was pushed.

## F. Then carry on

Disk sits at 4.5 GB free on `/` right now. Sections B and C should return
roughly 1–1.5 GB without touching anything sensitive, and section A makes
another 4 GB available if it is ever needed.

After that, continue exactly where the acceptance prompt left off: finish the
builds, patch 210 (done) then 211/212/213, deploy, and work the §11 matrix in
the "finds problems fastest" order. Report `df -h /` at the start and end.
