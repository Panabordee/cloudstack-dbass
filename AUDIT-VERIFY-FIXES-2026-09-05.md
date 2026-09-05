# Verification of FIX-1 / FIX-2 (2026-09-05, independent re-check)

Checked against the running system and the pushed code (`14c61ca2df`), not
against the report's narrative.

## FIX-1 — verified, all three acceptance steps reproduce

| Step | Result |
| --- | --- |
| bogus token from the host | **HTTP 403** (was an empty 200) |
| reject logged | `WARN [c.d.ReportProvisioningResultCmd] reportDbaasProvisioningResult rejected for VM 00000000-…` at 18:00:35, from this probe |
| `UnsatisfiedDependencyException` count | **6, unchanged** — the baseline stopped rising |
| real deploy | `INFO [c.d.DbaasManagerImpl] provisioning report accepted for VM 3698af43-…` at 17:41:28 |
| `DATA-73` | untouched, still `Ready`, still unattached |

The implementation matches what was asked: `@Inject DbaasManager` is gone,
`DbaasManagerImpl.getRunningManager()` supplies it, a missing manager answers
503 with a log line instead of a silent 200, and `getAuthCommands()` /
`@APICommand` / the rate limit / the generic rejection are untouched. The
`sendError` detail is correct and worth keeping: `ApiServlet:415` writes the
authenticator's output with a hardcoded `SC_OK`, so a status set through
`resp.setStatus()` inside `authenticate()` is always overwritten — committing
the response is the only way a non-200 survives.

### Correction (2026-09-05, later): the 429/503 finding was wrong

This audit originally claimed the 429 rate-limit path and the 503
manager-down path still went through `serialize()` and were therefore
downgraded to 200. **That is not true and the claim is withdrawn.**
`ReportProvisioningResultCmd` at HEAD uses `sendErrorQuietly` on all three
paths — 429 (:120), 503 (:133) and 403 (:150).

How the error happened, because it is worth not repeating: the review read the
diff of `49026d8dbb` — where the 503 path was indeed added using
`serialize()` — and then re-read the file starting at line 135, below the
region that `14c61ca2df` had since corrected. `14c61ca2df` is labelled as a
docs commit but also carries +37 lines in that file. **Read the file at HEAD,
not the diff of the commit you happened to open.**

## FIX-2 — code present, acceptance not run, one design problem

The three steps are implemented as described, the marker moves to after the
first attach (a sensible departure, correctly recorded), the UI merges both
lookups, and `dbaas.datadisk.cleanup.enabled` defaults to `false` with a
24 h grace period. `DATA-73` cannot match, as intended.

**Update (2026-09-05, later): fixed in the working tree, not yet committed.**
The finding below is accurate against **HEAD** (`14c61ca2df`), where
`volumeApiService.deleteVolume` does not appear at all. The working tree now
carries the fix — `VolumeApiService` injected, `deleteVolume(volumeId, caller)`
called per candidate, the row left visible when the call fails or the owner
account cannot be resolved — as 58 uncommitted insertions in
`DbaasManagerImpl.java`. **Uncommitted and unpushed work is not delivered:**
commit it before anything else, because the report's "push แล้ว" does not
cover it and a lost working tree loses the fix.

**The problem it fixes.** At HEAD, `cleanupOrphanedDataDisks()` runs

```sql
UPDATE volumes SET removed = NOW() WHERE id = ?
```

and logs that "the primary storage file must still be reclaimed by an admin".
`VolumeApiService` is not injected anywhere in the plugin, so no orchestration
runs. Consequences if the flag is ever turned on:

- the qcow2 file stays on primary storage — the disk-space leak, which is the
  entire point of the fix, is not addressed
- the row disappears from `listVolumes`, so the admin loses the only handle
  that could find the file afterwards. A visible leak becomes an invisible one
- capacity accounting, resource counts and usage events are all bypassed,
  because `deleteVolume`'s orchestration never runs

Fix before that flag is ever enabled: delete through the volume service
(`VolumeApiService.deleteVolume` / `destroyVolume`) so the file, the
accounting and the row are handled together, and keep the row visible if the
delete fails. Until then the setting must stay `false`, and the sweeper's
report-only behaviour is the honest one.

## State of the templates

Only template 211 (secondary) and the mariadb primary cache carry the current
`firstboot.sh` + retry unit. **210 / 212 / 213 and the mysql cache still run
the previous `firstboot.sh`**, without the retry timer and without the
"200 plus success payload" check. Any acceptance on those engines is testing
old client code. Repatch per `RUNBOOK-PATCH-TEMPLATES-2026-09-05.md` Step 3.5
before they are used for anything that matters — host work, needs approval.

## Verdict on starting the console work

**Clear to start C0/C1 of `PLAN-DBAAS-CONSOLE.md`.**

The reason the previous audit said "not yet" was that the console's transport
is built on `PluggableAPIAuthenticator` + `ComponentContext.inject`, and that
path returned an empty 200 for every call. It now returns 403 for a rejected
token and accepts a real one end to end, verified independently above. The
foundation is observed, not assumed.

Conditions to carry into that work:

1. the agent's two endpoints must use the same pattern that now works — the
   static-holder lookup, not `@Inject` on a command constructed by
   `newInstance()`, and `sendError` for any non-200 status
2. do not enable `dbaas.datadisk.cleanup.enabled` until the sweeper deletes
   through the volume service
3. C0's read-only database role has to ride the same template rebuild that
   repatches 210 / 212 / 213 — one rebuild, not two
