# Plan — make provisioning fire on every new request, not just the first boot

Fixes the bug found in the 2026-09-08 acceptance session: `createDatabase`
on an instance that has already booted once does nothing at all, silently,
and the credential sits `pending` forever.

**Code done, not yet baked into any image.** `firstboot.sh` and the new
`dbaas-provision.service` are written and committed (§3.1–§3.3 below reflect
what actually shipped, not just the design). What's left is entirely host
work: bake both files into the four templates and run §4's acceptance cases
for real — see §3.5 for exact commands. Bundle with the rebuild already
pending for other reasons (§6) — it needs one rebuild, and so do they.

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

### 3.1 New unit: `dbaas-provision.service` — done

Lives beside the existing units in `extensions/dbaas/provisioning/`.

- `Type=oneshot`, `ExecStart=/opt/dbaas/firstboot.sh`
- `After=network-online.target` only — **deliberately not** ordered after any
  specific engine's systemd unit. `firstboot.sh`'s own `engine_ready()` poll
  (already there, up to 120s) is the readiness wait; naming one engine's unit
  in a file shared by all four templates would be exactly the kind of
  hardcoding the engines map in `config.json` already exists to avoid
- no `ConditionPathExists` on the request file: the script sources the
  request itself (§3.2) and decides what to do, so the unit must run
  unconditionally every boot

Kept cloud-init's `runcmd` entry in `buildUserData()` as-is — harmless, and
it makes the very first boot fire immediately rather than waiting on unit
ordering. The script's idempotency (§3.3) makes a double invocation a no-op,
and that belt-and-braces is worth more than the microseconds saved.

### 3.2 `firstboot.sh` reads the config drive itself — done

Runs unconditionally at the top, before the old marker check ever did:
`refresh_request_from_configdrive()` is best-effort (returns non-zero, never
fatal — an instance deployed without a database legitimately has no config
drive data to find).

- locates the drive by label: `blkid -t LABEL=config-2 -o device` (not
  hardcoded to `/dev/sr0` — cloud-init's own log this session showed
  `source=/dev/sr1`, so the label lookup is the only reliable way)
- mounts read-only (`mount -t iso9660 -o ro`) to a `mktemp -d` dir, reads
  `openstack/latest/user_data`
- extracts the request with a small `python3` regex matched against the
  exact, fixed shape `DbaasManagerImpl.buildUserData()` emits (single
  6-space-indented line after `content: |` — verified against the real
  generated format, not guessed), rather than pulling in a YAML parser for
  one known field
- **validates the extraction parses as JSON before trusting it** — a
  truncated or malformed read fails loudly into the normal "no request"
  path instead of leaving a broken file every later step assumes is
  well-formed. This mirrors the exact failure mode being fixed: a step that
  silently does nothing is the bug, so the fix must not have one either
- writes `$REQUEST_FILE` at `0600 root:root`, unmounts, cleans up the temp dir

### 3.3 Replaced the boolean marker with a request hash — done

`DONE_MARKER` (`/var/lib/dbaas/provisioned`) is gone. In its place,
`/var/lib/dbaas/processed.sha256` holds the SHA-256 of the request that was
last provisioned successfully.

On each run: compute the hash of the request just read; if it matches the
stored one, log it and exit 0 (also removing the freshly-extracted
`request.json`, which would otherwise sit on disk holding a cleartext
password for no reason on every uneventful reboot); otherwise provision, and
write the new hash only on success.

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

### 3.4 Interaction with the report retry timer — checked, no change needed

`dbaas-report-retry.timer` is not enabled at boot by default; `firstboot.sh`
only starts it explicitly (`systemctl start dbaas-report-retry.timer`) on
the branch where reporting failed, and `report-retry.sh` disables and stops
itself once it succeeds. The success path in `firstboot.sh` never starts it.
On a plain reboot, `refresh_request_from_configdrive()` may recreate
`request.json`, but the hash-match branch (§3.3) removes it again before the
retry timer could ever see it, and that branch is reached before anything
resembling a report attempt happens. No change needed there.

### 3.5 Deploying it — host work, not started

Same offline `qemu-nbd` pattern as `dbaas-report-retry.*` in
`RUNBOOK-PATCH-TEMPLATES-2026-09-05.md`. Per template:

```bash
sudo cp "$REPO/extensions/dbaas/provisioning/firstboot.sh" \
  /mnt/tplpatch/opt/dbaas/firstboot.sh
sudo chmod 0755 /mnt/tplpatch/opt/dbaas/firstboot.sh

sudo cp "$REPO/extensions/dbaas/provisioning/dbaas-provision.service" \
  /mnt/tplpatch/etc/systemd/system/dbaas-provision.service

sudo ln -sf /etc/systemd/system/dbaas-provision.service \
  /mnt/tplpatch/etc/systemd/system/multi-user.target.wants/dbaas-provision.service
```

Verify before unmounting: the enable symlink resolves, and
`sudo chroot /mnt/tplpatch bash -n /opt/dbaas/firstboot.sh` parses clean.
Remove the old `/var/lib/dbaas/provisioned` file from any already-deployed
instance's disk only if you want to tidy it — nothing reads it, so leaving
it is equally correct
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
