# Audit — console overnight run (2026-09-06)

Reviewed at HEAD `274313fdd8`, reading the files rather than the commit diffs.
Working tree clean, 5 commits, pushes match the report.

**Headline: the console cannot work at runtime as written.** Every database
read in the new code executes its query before binding its parameters, and one
of them never binds at all. Nothing offline could have caught it — it compiles,
checkstyle passes, `py_compile` and `node --check` pass — which is exactly why
it matters that it was found before the template rebuild rather than after.

---

## BLOCKER-1 — every console query runs with unbound parameters

The new code consistently uses this shape:

```java
try (PreparedStatement pstmt = txn.prepareStatement(find); ResultSet rs = pstmt.executeQuery()) {
    pstmt.setLong(1, vmId);      // ← too late, the query already ran
    if (rs.next()) { ... }
```

`executeQuery()` sits in the try-with-resources header, so it runs **before**
the setters on the next line. MySQL Connector/J answers that with
`SQLException: No value specified for parameter 1`. Each site catches the
exception, logs a warning and returns an empty/false result, so the failure
does not surface as an error — it surfaces as "no job", "invalid token",
"result not found".

Affected sites, all in `DbaasManagerImpl.java`:

| line | query | effect when it fails |
| --- | --- | --- |
| 552 | `SELECT account_id FROM dbaas_jobs WHERE uuid = ?` | job ownership lookup |
| 722 | agent poll: resolve VM by uuid | agent poll returns nothing |
| 748 | find pending job for VM | **no job is ever dispatched** |
| 830 | `reportDbaasJobResult` job lookup (3 params) | **no result is ever accepted** |
| 891 | `getDbaasJobResult` status (2 params) | UI never sees a result |
| 917 | fetch the encrypted result | delete-on-read never runs |

The correct shape is already in this same file, in the older code
(`:1292-1301`): prepare, bind, then open the `ResultSet` in its own
try-with-resources. Use that everywhere.

## BLOCKER-2 — `isAgentTokenValid` never binds its parameter at all

`DbaasManagerImpl:630-640`:

```java
String sql = "SELECT t.id, t.token_hash FROM dbaas_agent_tokens t"
        + " JOIN vm_instance v ON v.id = t.vm_id WHERE v.uuid = ? AND v.removed IS NULL";
try (PreparedStatement pstmt = txn.prepareStatement(sql); ResultSet rs = pstmt.executeQuery()) {
    if (!rs.next()) {
        return false;
```

There is no `setString(1, vmUuid)` anywhere in the method — reordering alone
does not fix this one; the bind has to be added. Consequence: **every agent
poll is rejected with 403**, and because the rejection is generic, the agent's
log would read "invalid agent token" while the token is perfectly valid. This
is the single hardest symptom to debug on a live instance, so it is worth
fixing with a test that asserts a known-good token validates.

## MEDIUM-3 — the two new unauthenticated endpoints have no rate limit, and the long-poll pins a servlet thread

`reportDbaasProvisioningResult` is rate limited (`dbaas.report.rate.limit`).
`getDbaasAgentJob` and `reportDbaasJobResult` are equally unauthenticated and
have none.

`getDbaasAgentJob` also holds the request for up to
`dbaas.agent.longpoll.seconds` (25) inside a `while … Thread.sleep(500)` loop
(`DbaasManagerImpl:743-770`), on the Jetty worker thread. Token validation
happens first, so an anonymous flood is cheap to reject — but every legitimate
instance holds one worker thread continuously, and the pool is shared with the
entire management API. Twenty database instances is twenty threads permanently
parked.

Wanted before this ships:

- the same per-IP rate limiter on both new endpoints
- a cap on concurrent long-poll waiters (config, with a sane default), so
  reaching it returns immediately rather than queueing
- the poll interval and hold time reconsidered together once the cap exists —
  25 s × N instances is the number to design against, not the 500 ms sleep

## MEDIUM-4 — `en.json` was re-serialised: 4461 lines changed to add 17 keys

Semantically it is clean: 17 keys added, **0 removed, 0 values changed**
(verified by parsing both revisions). But the whole file was re-emitted with
2-space indentation and normalised `": "` spacing, so every line shows as
changed.

`AUDIT-v2.md` recorded this specific hazard — the v1 whole-file reformat caused
a merge conflict, and the sessions since kept the diff to single-digit lines on
purpose. Re-emit the file preserving the original formatting so the diff is the
17 lines it should be.

## What is right

- all four flags ship `false`: `dbaas.console.enabled`,
  `dbaas.console.write.enabled`, `dbaas.console.drop.enabled`,
  `dbaas.datadisk.cleanup.enabled`
- agent tokens stored as SHA-256 only; job payloads and results encrypted with
  `DBEncryptionUtil`; results deleted on read plus a TTL sweeper
- ACL: 10 user-facing commands all inherit `getEntityOwnerId` from
  `DbaasConsoleJobCmdBase`, resolved from the target VM
- both agent endpoints use the static-holder lookup and `sendErrorQuietly` for
  every non-200 — the two rules carried over from the last round are respected
- the agent enforces row limit, byte limit and a per-engine statement timeout,
  and logs only job uuid, type and role: no SQL text, no rows, no credentials
- `DATA-73` untouched, no host state changed, working tree clean

## Order of work from here

1. Fix BLOCKER-1 and BLOCKER-2. They are mechanical, but every one of the six
   sites must be checked individually — the pattern was copied.
2. Add a test that exercises one round trip against a real database
   (`createConsoleJob` → poll → report → fetch), even as an integration test
   that is skipped when no database is present. The whole class of bug above is
   invisible to every check currently in the loop.
3. Then MEDIUM-3, then MEDIUM-4.
4. Only then the host work: full server build, template rebuild (Step 3.5 plus
   the agent, the `_ro` role scripts and the python libraries), deploy, and the
   §11 test matrix — which is still entirely "not run", correctly reported.

Do not start C5. The transport has not yet run once.
