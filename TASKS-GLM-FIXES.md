# Fixes to do before the console work starts

Hand this to the implementing agent together with
`AUDIT-2026-09-05-EVENING.md` (the evidence behind every item here).

Scope: two defects. Nothing else. Do **not** start
`PLAN-DBAAS-CONSOLE.md` until FIX-1 is verified on a real deploy — its
transport is built on the mechanism FIX-1 repairs.

---

## FIX-1 (blocking) — `reportDbaasProvisioningResult` fails before it runs

**Symptom:** every call answers HTTP 200 with an empty body, the plugin logs
nothing, and credentials stay `pending` forever.

**Cause:** `APIAuthenticationManagerImpl.getAPIAuthenticator()` constructs
`ReportProvisioningResultCmd` and calls `ComponentContext.inject()` on it. The
injection of the `dbaasManager` field throws
`NoSuchBeanDefinitionException: No qualifying bean of type
'com.dbaas.DbaasManager'` — the bean lives in the `dbaas` module context and
is not resolvable from the context that inject uses. `ApiServlet` swallows the
exception and writes an empty 200.

Registration itself is fine; do not change `getAuthCommands()`, the
`@APICommand` annotation, or anything in CloudStack core.

**What to do:** remove the dependency on Spring resolving the manager into a
freshly constructed command.

- `DbaasManagerImpl.configure()` (or `start()`) publishes `this` into a static
  holder on the class.
- `ReportProvisioningResultCmd.authenticate()` reads the manager from that
  holder instead of the injected field.
- If the holder is empty — the plugin genuinely is not up — answer with a real
  error status and log it. Never return a bare 200 for a call that did not run.

Keep the existing behaviour intact: rate limit first, generic identical
outcome for every rejection reason, 403 on reject, 200 + success payload only
on a real accept, and a log line for both accept and reject.

**Acceptance (all three, in order):**

1. bogus token from the host answers **403**, not an empty 200:
   ```bash
   curl -sS -m 10 -w '\nHTTP=%{http_code}\n' -X POST http://10.60.0.254:8080/client/api \
     --data-urlencode command=reportDbaasProvisioningResult \
     --data-urlencode response=json \
     --data-urlencode vmid=00000000-0000-0000-0000-000000000000 \
     --data-urlencode token=bogus --data-urlencode status=failed --data-urlencode message=probe
   ```
2. `management-server.log` gains a rejection line for that call and **no**
   `UnsatisfiedDependencyException` (there are 6 so far — the count must stop
   rising)
3. deploy one instance from `dbaas-mariadb-v2`: the credential flips
   `pending → confirmed` on its own, and the guest's
   `journalctl -u cloud-final | grep dbaas-firstboot` shows
   `provisioning result reported: confirmed`

Step 3 needs the templates repatched with the current `firstboot.sh` and the
new retry unit (`RUNBOOK-PATCH-TEMPLATES-2026-09-05.md` Step 3.5). **That is
host work — ask the human, do not run it yourself.**

---

## FIX-2 — an expunged instance leaves its data disk behind

**Symptom, live right now:** `DATA-73`, 5 GB, `Ready`, attached to nothing,
belonging to no instance. `listVolumes listall=true type=DATADISK` shows it.

**Cause:** the UI collects data-disk ids *before* the destroy by calling
`listVolumes virtualmachineid=<vm> type=DATADISK`
(`DatabaseInstances.vue:278`). A data disk requested at deploy time on an
instance created with `startvm=false` is not linked to that instance until it
is first attached at start — so for an instance destroyed before it ever
started, that lookup returns an empty list and nothing is deleted. After the
expunge the volume names no instance either, so nothing can find it afterwards.

Second, structural weakness: the deletion runs inside `$pollJob`'s
`successMethod`, so it only happens if the browser stays on the page until the
destroy job completes. Closing the tab leaks the disk silently.

**What to do — in this order, and stop at the first one that is enough:**

1. **Mark the disk at creation.** When the wizard deploys with a data disk,
   set a volume detail (e.g. `dbaas.instance=<vm uuid>`) on it. This makes an
   orphan identifiable later regardless of attachment state, and it is the
   prerequisite for anything server-side.
2. **Make the UI lookup not depend on attachment.** Resolve ids by the marker
   from (1) as well as by `virtualmachineid`, so a never-started instance's
   disk is still found before the destroy.
3. **Do not** make the browser the only cleanup path. Extend the existing
   sweeper in `DbaasManagerImpl` — which today only *logs* orphaned data
   disks, deliberately — to delete a volume only when **all** of these hold:
   unattached, its instance is expunged, it carries the `dbaas.instance`
   marker, it is older than a grace period, and
   `dbaas.datadisk.cleanup.enabled` is true. **That setting ships `false`.**
   Deleting tenant data without a human saying so was rejected once already
   and stays rejected as a default.

Do not delete `DATA-73` yourself. It is the only live specimen; the human
decides when it goes.

**Acceptance:** create an instance with a data disk, destroy+expunge it
*without* waiting on the page, and confirm no unattached DATADISK is left —
first with the flag off (the sweeper logs it, the UI path removed it), then
with the flag on (the sweeper removes one that the UI missed).

---

## Already fixed — do not redo, do not revert

| Fix | Where |
| --- | --- |
| Cancelled request no longer reported as a failed database (new `detached` info step) | `CreateDatabaseInstance.vue`, 2 new `en.json` keys |
| Show Password dialog overflow (`table-layout: fixed`, 560 px) | `ShowDatabasePassword.vue` |
| `request.json` kept until the report is actually accepted | `firstboot.sh` |
| Report retry timer until delivery, then self-disable | `report-retry.sh`, `dbaas-report-retry.service`, `.timer` |
| A report counts as delivered only on 200 **with** a success payload | `firstboot.sh`, `report-retry.sh` |
| curl's error and HTTP code are logged instead of discarded | `firstboot.sh` |
| Report URL moved to the guests' own subnet | global setting `dbaas.report.api.url` |
| `grep`/`pipefail` silent death after the database was created | `mysql.sh`, `mariadb.sh` |
| Engine readiness marker (`.sh` suffix) | `firstboot.sh` |

## Out of scope for this round

- VM login password (tenant cannot choose one, never receives one) — diagnosed
  in `VM-PASSWORD-DEFECT-2026-09-05.md`. Not now.
- The read-only database role — it belongs to the console work's C0, folded
  into the next template rebuild.
- `README.md` / `INSTALL.md` / `TEMPLATES.md` still describing the v1 SSH
  architecture.

## How to work

- Repository changes are yours. **Host changes — templates, images, CloudStack
  settings, running VMs — are not: ask first.**
- Fix it yourself only if it is inside the code you are already touching, is
  under ~20 lines, and you understand the cause. Otherwise write it down and
  move on: cause outside this feature, needs a rebuild or a host change, two
  failed attempts, or the fix spreading to a third file.
- A precise report about three blockers beats a half-fixed tree with four new
  problems in it.

## What to hand back

`FIXES-REPORT-<date>.md` containing:

1. FIX-1: what changed (`file:line`), and the output of all three acceptance
   steps — including the actual HTTP status and the log lines, pasted, not
   summarised
2. FIX-2: which of the three steps you implemented and why you stopped there;
   the acceptance run with the flag off and on; the state of `DATA-73`
   (untouched, unless the human said otherwise)
3. Anything you chose not to fix, with the reason
4. Every departure from this document, with the reason
5. Whether, in your judgement, the transport is now proven well enough to
   start `PLAN-DBAAS-CONSOLE.md` — and if not, what is still missing
