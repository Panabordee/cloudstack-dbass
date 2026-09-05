# Audit — DBaaS v2 state before starting the console work (2026-09-05, evening)

Audience: the agent that will implement `PLAN-DBAAS-CONSOLE.md`, and whoever
decides when that work starts. Everything below was checked against the
running system on `cloudstackcve`, not inferred from the code.

**Verdict up front: do not start the console work yet.** One defect
(§3) breaks the exact mechanism the console's transport is built on. Fix it,
close the acceptance run, then start. Reasoning in §7.

---

## 1. What is confirmed working

| Claim | Evidence |
| --- | --- |
| Templates patched (4 secondary + 2 primary caches) | `qemu-img check` clean, markers verified in-image |
| Guest gets an address | `tcpdump` shows DHCP Request/Reply; the `99-dbaas-fallback.network` file fixed the silent NIC |
| Database is created and reachable from outside | `SELECT 1` over TCP from another host; `testdb1` present on accept3/accept4 |
| Engine readiness wait runs | guest log `engine ready (waited 0s)` with the `.sh`-suffix fix in place |
| System VMs upgraded | `listSystemVms` → 4.23.0.0 for SSVM and CPVM |
| Report URL reaches guests' subnet | `dbaas.report.api.url` = `http://10.60.0.254:8080/client/api`, in a global setting, not hardcoded |

## 2. Bug — expunge leaves the data disk behind (still open)

**Reproduced now, not historical.** `listVolumes listall=true type=DATADISK`
returns `DATA-73`, 5 GB, `state=Ready`, with **no `vmname`, no
`virtualmachineid`, no `attached`** — an unattached data disk belonging to no
instance, created 16:30:12 today.

What the log shows around the destroy that produced it:

```
16:30:47  GET  virtualmachineid=94fba457-...&type=DATADISK&listall=true&command=listVolumes
16:30:47  POST command=destroyVirtualMachine
```

so the UI's cleanup path **did** run its pre-fetch — this was a destroy from
the Database page, not from the generic Instances page — and the destroy job
was polled to completion (`queryAsyncJobResult` ×12 in that window). But **no
`deleteVolume` was called at any point after 14:32**, and the last two
`deleteVolume` calls in the whole log are 01:57 and 14:32.

Most likely cause, and the thing to verify first: `fetchDataDiskIds()`
(`DatabaseInstances.vue:278`) looks the disk up **by
`virtualmachineid`**, and a data disk requested through `deployVirtualMachine`
on an instance deployed with `startvm=false` is not linked to that instance
until it is first attached at start. The wizard's flow destroys instances that
may never have started, so the lookup legitimately returns an empty list and
there is nothing to delete. The disk is then unreachable by any DBaaS code
path, because after expunge it names no instance either.

Secondary weakness in the same design: the deletion runs in `$pollJob`'s
`successMethod`, so it only happens if the browser stays on the page until the
destroy job finishes. Any navigation, reload or closed tab leaks the disk
silently — the same class of failure as §4.

Recommended direction (not implemented; this is a design decision):
server-side cleanup in the plugin sweeper, which today only **logs** orphaned
data disks by deliberate choice (PLAN.md Phase A). Deleting tenant data
automatically was rejected then and should stay rejected in that form, but a
narrow rule is defensible: delete only volumes that are unattached **and**
whose instance is expunged **and** that carry a DBaaS marker (a volume detail
set at create) **and** are older than a grace period — behind
`dbaas.datadisk.cleanup.enabled`, default false. Until such a rule exists, the
UI path cannot be relied on and the leak should be documented rather than
half-fixed.

## 3. Bug — the provisioning report never reaches the plugin (root cause found; **not** what was assumed)

The earlier hypothesis was that the API framework binds the command name to
the `DbaasManagerImpl` bean and calls a default empty `authenticate()`. **That
is not what happens.** `APIAuthenticationManagerImpl.start()` (:57-64) iterates
the classes returned by `getAuthCommands()` and registers *the command class*
(`ReportProvisioningResultCmd`, which does implement `APIAuthenticator` and
does carry `@APICommand(name = "reportDbaasProvisioningResult")`). The
registration is correct.

The failure is one step later, in `getAPIAuthenticator()` (:107-108):

```java
apiAuthenticator = (APIAuthenticator) s_authenticators.get(name).newInstance();
apiAuthenticator = ComponentContext.inject(apiAuthenticator);
```

The injection throws, every single time:

```
ERROR [c.c.a.ApiServlet] unknown exception writing api response
org.springframework.beans.factory.UnsatisfiedDependencyException:
  Error creating bean with name 'com.dbaas.ReportProvisioningResultCmd':
  Unsatisfied dependency expressed through field 'dbaasManager';
  NoSuchBeanDefinitionException: No qualifying bean of type 'com.dbaas.DbaasManager' available
```

`ApiServlet` swallows it and answers **HTTP 200 with an empty body**, which is
why the guest believed it had reported and the plugin logged nothing.

Verified live: a POST to the endpoint from the host raised occurrence **#6** of
that exception in `management-server.log` within two seconds of the call.
`DbaasManagerImpl` itself is running (its sweeper logs on schedule) and the
module loads (`Loaded module context [dbaas]`, `module.properties`:
`name=dbaas, parent=api`), so the bean exists in the dbaas module context but
is not resolvable from the context `ComponentContext.inject()` uses.

**Useful discriminator for any future debugging:** a *rejected* report is
answered with **403** (`ReportProvisioningResultCmd.authenticate` →
`serialize(resp, SC_FORBIDDEN, false, ...)`). An empty **200** is never a
rejection — it is the server having failed before the command ran.

Recommended fix, cheapest first: stop depending on Spring resolving the
manager into a freshly constructed command. Have `DbaasManagerImpl.configure()`
publish itself into a static holder and have `authenticate()` read it from
there, or resolve it lazily inside `authenticate()` with an explicit lookup and
an error response if it is genuinely unavailable. Either removes the
context-visibility question entirely. Do not add another core patch for this.

### Client-side hardening already applied

`firstboot.sh` and `report-retry.sh` now require **200 *and* a success payload**
before treating a report as delivered. Without that, the moment the URL fix
made the endpoint reachable, every guest would have received the empty 200,
concluded success, deleted `request.json` — and destroyed the one-time token,
making the credential permanently unconfirmable. That would have converted a
server bug into unrecoverable per-instance damage.

## 4. Bug — a cancelled request was reported as a failed database (fixed)

Navigating away from the Database page while `createDatabase` was in flight
showed the red alert *"Instance created — database was not"* with the detail
`canceled`, while the database was in fact being created normally.

`provision()` passes `ignoreCancelToken: true`, but when the cancellation lands
anyway the only thing lost is the *answer*. Fixed in
`CreateDatabaseInstance.vue`: cancellations are now detected
(`ERR_CANCELED` / `__CANCEL__` / message `canceled`) and render a new
`detached` step — an **info** alert, "Database request submitted", pointing at
Show Password — instead of an error. Two new `en.json` keys, both present and
the file still valid (4551 keys).

## 5. Also fixed this round

- Show Password dialog overflowed its own modal: `a-descriptions` renders a
  table with automatic layout, and the connect command is one unbreakable
  token. Now `table-layout: fixed`, label column pinned at 34%,
  `overflow-wrap: anywhere` on values, modal 450 → 560 px.
- `firstboot.sh` keeps `request.json` until the report is actually accepted,
  and a `dbaas-report-retry` timer re-delivers it until it lands, then removes
  the request and disables itself.

## 6. Known environment traps (carried forward)

- the pod allocator can take addresses out of the guest range (s-68 took .77)
- do **not** add a `Password` row to a network's service map: 4.23's
  `Network.Service` has no such value and it NPEs `listNetworks`
- MySQL/MariaDB clients that force TLS need `--ssl-mode=DISABLED` against these
  images
- `/` is at 93% (3.1 GB free); `/export/secondary` at 71% (5 GB free). Do not
  stage images in either

## 7. Decision: start the console (Neon-like) work now?

**No. Fix §3 first, then finish the acceptance run, then start.**

The reason is specific, not caution in general. `PLAN-DBAAS-CONSOLE.md` §1.1
builds the agent's two endpoints (`getDbaasAgentJob`,
`reportDbaasJobResult`) on **exactly the mechanism that is broken right now**:
`PluggableAPIAuthenticator` + `getAuthCommands()` + `ComponentContext.inject`.
Building a long-poll job pipeline on top of a path that currently returns an
empty 200 for every call would mean debugging the new feature and this defect
at the same time, through a symptom (200 with no body) that looks like success
from the client side. That is how a two-hour fix becomes a two-day one.

The sequence that keeps each failure legible:

1. fix the injection defect (§3) — small, local to the plugin
2. redeploy, deploy one instance, confirm the credential flips
   `pending → confirmed`. That single result validates the transport the
   console depends on
3. decide what to do about the orphaned data disk (§2) — at minimum record it;
   the console work does not depend on it
4. **then** start `PLAN-DBAAS-CONSOLE.md` at C0/C1

Steps 1 and 2 are hours, not days, and they turn the console work's foundation
from "assumed" into "observed". Starting now saves nothing and costs the
ability to tell which layer is failing.
