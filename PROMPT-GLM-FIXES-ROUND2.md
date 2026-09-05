# Fix prompt (round 2) — close out the audit round

Send this before the overnight console prompt. Short session: commit what you
already have, verify two things, hand one back. **Do not start
`PLAN-DBAAS-CONSOLE.md` here** — that is the next prompt.

---

FIX-1 is verified and passing: a bogus token answers **403** with a rejection
logged, the `UnsatisfiedDependencyException` count stopped rising at 6, and a
real deploy produced `provisioning report accepted for VM 3698af43-…`. The
static-holder approach and the `sendError` insight are correct and are kept.

**One correction to the audit you were sent:** it claimed the 429 and 503
paths were still downgraded to 200. That was wrong — you had already fixed
them in `14c61ca2df`, and the audit had read the diff of the previous commit
plus a partial view of the file. The claim is withdrawn; `sendErrorQuietly` on
all three paths is correct as it stands. Nothing to do there beyond the
verification in F1 below.

## F1 — verify the rate-limit path on the wire (verification only, no code change expected)

The code is right; what has never been observed is the 429 actually arriving.
Confirm it, since this is the one status a client would see under load and it
costs one loop:

```bash
for i in $(seq 1 70); do
  curl -sS -o /dev/null -w "%{http_code} " -X POST http://10.60.0.254:8080/client/api \
    --data-urlencode command=reportDbaasProvisioningResult --data-urlencode response=json \
    --data-urlencode vmid=00000000-0000-0000-0000-000000000000 \
    --data-urlencode token=bogus --data-urlencode status=failed --data-urlencode message=rl
done; echo
```

Paste the status sequence: expect 403s, then 429s once the per-minute limit is
crossed. **Do not change `dbaas.report.rate.limit` to make this easier** — that
is a host change and it is not allowed in this session.

The 503 path cannot be exercised without stopping the plugin, which you may not
do. Cover it with a unit test or record it as **not run**, with the reason.

## F2 — commit and push the volume-service fix you already wrote

`DbaasManagerImpl.java` in your working tree has the corrected sweeper —
`VolumeApiService` injected, `deleteVolume(volumeId, caller)` per candidate,
the row left visible when the call fails or the owner account cannot be
resolved. **It is 58 uncommitted insertions.** HEAD (`14c61ca2df`) still
contains the old `UPDATE volumes SET removed = NOW()` version, so as far as
anyone else is concerned the fix does not exist.

Do, in this order:

1. `git status` first, then commit that work with a message explaining why the
   raw `UPDATE` was wrong (row removed, file left behind, `listVolumes` handle
   lost, capacity accounting and usage events bypassed) and push to `v2`
2. build: `mvn -pl plugins/integrations/dbaas -am install`, then one full
   `mvn -T2 -DskipTests -Dnoredist install`
3. re-read your own committed version and confirm in the report, with
   `file:line`: every guard still holds (unattached, marker present, instance
   expunged, past the 24 h grace period), `dbaas.datadisk.cleanup.enabled` is
   still `false`, and a failed delete leaves the row visible

The signature you are calling, for reference:

```java
// api/src/main/java/com/cloud/storage/VolumeApiService.java:111
boolean deleteVolume(long volumeId, Account caller);
// :192 — the fuller form, if you ever need expunge control
Volume destroyVolume(long volumeId, Account caller, boolean expunge, boolean forceExpunge, Boolean countDisplayFalseInResourceCount);
```

Live behaviour cannot be tested without enabling the flag, which you may not
do. Say that plainly rather than implying it was tested.

**`DATA-73` is evidence: do not touch it, do not clean it up, do not remove it
from any listing.** It carries no marker, so the sweeper cannot match it —
confirm that in the report.

## H1 — hand back, do not do

Only template 211 (secondary) and the mariadb primary cache carry the current
`firstboot.sh` and the retry unit. **210 / 212 / 213 and the mysql cache still
run the previous client code** — no retry timer, no "200 plus success payload"
check — so anything tested on those engines is testing stale client code.

Repatching is host work and needs the human
(`RUNBOOK-PATCH-TEMPLATES-2026-09-05.md` Step 3.5). List it in the report as
required-before-next-acceptance; do not attempt it.

## Rules for this session

- Repository changes are yours. **No host changes at all**: no
  `cmk update configuration`, no deploy/destroy/expunge, no template or image
  work, no service restarts, no writes outside the repository. Read-only
  queries and plain HTTP calls to the API are fine.
- Do not weaken a default to make anything pass.
- A build failure is yours to fix, not to report.
- Nothing may be left uncommitted at the end. That is the lesson of F2.

## Report

`FIXES-REPORT-ROUND2-<date>.md`:

1. F1: the pasted status sequence from the rate-limit run and the matching log
   lines; the 503 coverage (test, or "not run" with the reason)
2. F2: the commit hash, the guards re-confirmed with `file:line`, how a failed
   delete is handled, and the explicit statement that the flag stayed `false`
   and `DATA-73` was untouched
3. Build output tails for both builds
4. `git status` at the end, showing a clean tree — or an explanation of
   whatever remains
5. Anything you chose not to do, and every departure from this document, with
   reasons

When this is reported, the next prompt starts the console work. Both fixes
appear there as already-done carry-overs — mark them so rather than doing them
twice.
