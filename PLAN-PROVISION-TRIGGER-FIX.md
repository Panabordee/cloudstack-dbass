# Plan — make provisioning fire on every new request, not just the first boot

Fixes the bug found on 2026-09-08 (`ACCEPTANCE-REPORT-2026-09-08-PART2.md`
§5): `createDatabase` on an instance that has already booted once does
nothing at all, silently, and the credential sits `pending` forever.

Not started. Bundle this with the template rebuild that is already pending
for other reasons (see §6) — it needs one rebuild, and so do they.

---

## 1. What is actually broken

**Two independent layers, both of which must be fixed.** Fixing only one
leaves the feature just as broken, which is why this is a plan and not a
patch.

**Layer 1 — cloud-init never re-reads the config drive.**
`createDatabase` on a running instance stops it, attaches a new config drive
carrying the request, and starts it again. On that second boot cloud-init
logs:

```
stages.py[DEBUG]: restored from checked cache: DataSourceConfigDrive [net,ver=2][source=/dev/sr1]
modules.py[INFO]: Skipping modules '...,runcmd' because no applicable config is provided
```

It reuses the cached datasource object from the instance's *first* boot
rather than re-reading the ISO, so `write_files` never writes
`/var/lib/dbaas/request.json` and `runcmd` never runs `firstboot.sh`.
Verified by reading the attached ISO directly — it contained the correct
`write_files` + `runcmd` the whole time.

**Layer 2 — `firstboot.sh` refuses a second request anyway.**
`firstboot.sh:103`:

```sh
if [[ -e "$DONE_MARKER" ]]; then      # $DONE_MARKER = /var/lib/dbaas/provisioned
    log "already provisioned, nothing to do"
    exit 0
fi
```

The marker is a boolean "has this VM ever provisioned", so even with layer 1
fixed, an instance that already has one database can never accept another
request. Any fix that only addresses cloud-init hits this immediately.

## 2. Approach: own the trigger, drop the cloud-init dependency

Stop routing provisioning through cloud-init's `runcmd`/`write_files`
modules. Ship a systemd unit that runs on **every** boot, reads the config
drive itself, and decides what to do by comparing the *request*, not by
asking "have I ever run".

This is the pattern this project already uses successfully everywhere else —
`dbaas-agent.service` polls on its own, `dbaas-report-retry.timer` retries on
its own, `/opt/dbaas/engine` is its own marker. Each of those exists because
depending on an external tool's assumptions went badly. cloud-init's cache is
the same lesson again.

Rejected alternatives, with reasons, so this is not relitigated later:

| Alternative | Why not |
| --- | --- |
| Clear `/var/lib/cloud/` before restart | Architecturally impossible: the management server has no path into the guest, by design. That is the whole point of v2. |
| Change the instance-id so cloud-init sees a "new" instance | Requires patching CloudStack core (`ConfigDriveBuilder`), and would make cloud-init regenerate SSH host keys, reset the hostname and re-run every per-instance module on a VM a tenant is actively using. |
| Drop support for create-on-running-instance | Abandons a shipped, documented feature (`PLAN.md` §1a; the row action shows for `Running`), and nothing stops a direct API call anyway. |

## 3. The change

### 3.1 New unit: `dbaas-provision.service`

Lives beside the existing units in `extensions/dbaas/provisioning/`.

- `Type=oneshot`, `RemainAfterExit=no`, enabled in the image
- `After=` the engine's unit and `network-online.target` — same ordering
  `firstboot.sh` already needs for its readiness wait
- `ExecStart=/opt/dbaas/firstboot.sh`
- No `ConditionPathExists` on the request file: the script now sources the
  request itself (§3.2), so the unit must run unconditionally each boot and
  let the script decide

Keep cloud-init's `runcmd` entry in `buildUserData()` as well — harmless,
and it makes the very first boot fire immediately rather than waiting on
unit ordering. The script's idempotency (§3.3) makes a double invocation a
no-op, and that belt-and-braces is worth more than the microseconds saved.

### 3.2 `firstboot.sh` reads the config drive itself

New step before anything else: if `/var/lib/dbaas/request.json` is absent or
stale, extract it from the config drive rather than depending on cloud-init
having written it.

- locate the drive by label, the same way everything else in this project
  does: `blkid -t LABEL='config-2' -o device` (cloud-init logged
  `source=/dev/sr1`, so do not hardcode `/dev/sr0`)
- mount read-only to a temp dir, read `openstack/latest/user_data`
- the payload is the cloud-config this project writes in
  `DbaasManagerImpl.buildUserData()`, so its shape is known exactly: pull
  `write_files[0].content` out of it with a small `python3` snippet
  (`python3` is already a hard requirement of these scripts)
- write it to `$REQUEST_FILE` with the same `0600 root:root` the current
  `write_files` block sets, unmount, and carry on with the existing flow

### 3.3 Replace the boolean marker with a request hash

Delete `DONE_MARKER` (`/var/lib/dbaas/provisioned`) and the `:103` early
exit. Replace with `/var/lib/dbaas/processed.sha256`, holding the SHA-256 of
the request that was last provisioned successfully.

Logic on each run: compute the hash of the request just read; if it matches
the stored one, log "request already provisioned" and exit 0; otherwise
provision, and write the new hash only on success.

This is what makes every case come out right, in one mechanism:

| Case | Behaviour |
| --- | --- |
| first boot | no stored hash → provision |
| plain reboot, no new request | hash matches → skip (does not re-run engine SQL over a tenant's live database) |
| `createDatabase` on an instance already in use | hash differs → provision — **the bug** |
| a second database on the same instance later | hash differs → provision — **layer 2** |
| provisioning failed last time, same request retried | no stored hash (only written on success) → retry, which is the behaviour we want |

Keep writing `result.json` exactly as now — the retry timer and any operator
reading the instance depend on it.

### 3.4 Interaction with the report retry timer

`dbaas-report-retry.timer` keys off `/var/lib/dbaas/request.json` existing.
Now that the request file is (re)created from the config drive on every boot,
confirm the timer's `ConditionPathExists` still means what it did — a request
that was already reported must not cause the timer to re-report. The hash
file is the right thing to gate on there too; check this when implementing
rather than assuming.

## 4. Acceptance

Each of these must be observed, not reasoned about. The first two are the
actual bug; the rest are the regressions this change could plausibly cause.

1. **The bug**: deploy an instance, start it, let it boot fully, *then*
   `createDatabase` → reaches `confirmed`, `/var/lib/dbaas/request.json`
   present, database and both roles exist in the engine
2. **Second database on the same instance**: `createDatabase` again with a
   different name → provisions, does not touch the first database
3. **Plain reboot**: reboot a provisioned instance → engine SQL does *not*
   re-run (check the engine's log for a second `CREATE USER`), existing data
   intact, `processed.sha256` unchanged
4. **First-boot path still works**: the normal wizard flow (deploy
   `startvm=false` → `createDatabase`) behaves exactly as today — this is the
   path that currently works and must not regress
5. **Failure retry**: force the engine script to fail once, confirm no hash
   is written and the next boot retries rather than skipping
6. `journalctl -u dbaas-provision` on the guest tells a readable story in
   each case

## 5. Risk

- The parse in §3.2 handles a payload this project generates itself, so the
  shape is fixed — but it is still parsing, and a malformed or truncated ISO
  read must fail loudly into `result.json` rather than silently doing nothing.
  That silent-nothing failure mode is exactly the bug being fixed; do not
  reintroduce it in the fix.
- Mounting the config drive inside the guest on every boot is new I/O on a
  path that previously only cloud-init touched. Unmount reliably (trap), and
  never leave it mounted — a leaked mount on a tenant instance is its own
  small mess.
- Dropping `DONE_MARKER` changes the meaning of an on-disk file that existing
  instances already have. Instances built from older templates will still
  have `provisioned` lying around; the new script must simply ignore it
  (do not migrate, do not delete — it is inert once nothing reads it).

## 6. Sequencing — bundle with the rebuild already owed

Templates 210/211/212/213 already need a rebuild for work that is finished in
the repo but not in any image:

- the `_ro` read-only role in the engine scripts (console C0)
- the current `dbaas_agent.py` (the 9 job-type handlers, only 210 has it)
- `report-retry.sh` + its unit
- and now this fix

Doing them as one rebuild is the difference between one careful pass over
four images and four separate ones. Order:

1. Implement §3, verify what can be verified offline (`bash -n`, the parse
   against a real ISO copied off the host — that can be tested without a
   rebuild)
2. Rebuild 210 first, run §4 acceptance end to end on it
3. Only then 211/212/213, with the per-engine smoke test (deploy →
   `confirmed` → one console job) each

## 7. Note for whoever picks this up

The reason this went unnoticed for so long is worth keeping: every test in
this project, across every session, deployed with `startvm=false` and called
`createDatabase` immediately — always that instance's *first* boot. That one
path is the only one that sidesteps both layers, and it was the only one ever
exercised. When adding tests for this, add the *second*-boot case explicitly;
it is not covered by any existing scenario.
