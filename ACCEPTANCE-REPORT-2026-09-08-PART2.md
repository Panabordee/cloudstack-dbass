# Acceptance session — 2026-09-08, part 2 (offering filter, UI build, VR testing)

Continuation of `ACCEPTANCE-REPORT-2026-09-08.md` within the same session.
Covers: reworking the OOM fix per feedback, fixing the UI build, deploying
the UI live for the first time, and VR-down testing — which surfaced a
significant, previously-unknown bug unrelated to VR at all.

Commits: `4fb89ed0d2` (code), this file (docs). Both on `v2`/`ui`, pushed.

---

## 1. Reworked the OOM fix: filter, not refuse-after-the-fact

Part 1 closed the OOM finding with a server-side refusal in `createDatabase`
— correct as a last line of defense, but the wrong primary fix: a tenant
would fill out the whole wizard, submit, and only then learn the offering
was wrong. Per explicit feedback, reworked so the mistake isn't offered in
the first place:

- `DbaasEngineResponse` gains `minmemorymb`, read from an optional
  `"minmemorymb"` integer on each engine's `config.json` entry
  (`DbaasManagerImpl.engineMinMemoryMb`), falling back to the new
  `dbaas.offering.minmemory.mb` config key (default 1024) when an engine
  doesn't set one — config-driven per engine, never hardcoded, same rule the
  engines map itself already follows
- `requireMinOfferingMemory` in `createDatabase` reads the identical
  per-engine number, so a caller that bypasses the wizard (raw API call)
  still gets refused server-side — both checks read the same source, so they
  cannot drift apart
- `config.example.json` documents plainly that only `dbaas-mysql-v2` at
  1024 MB is actually measured; the other engines carry the same number as
  an *unverified* starting point, with an explicit warning not to change any
  of them without measuring the same way (deploy, run console load, watch
  `journalctl -u <engine>` for `oom-kill`)
- `CreateDatabaseInstance.vue`: the service offering field is disabled until
  an engine is picked, then filtered to offerings whose memory meets that
  engine's minimum. A customized offering (memory chosen at deploy time, not
  fixed) passes the filter since its eventual size isn't known at this point.
  A watcher clears a stranded offering selection if switching to an engine
  with a higher floor invalidates it. A message explains when nothing
  qualifies. Removed a stale, inaccurate comment above the field ("only
  offerings at or above Medium... sshd cannot answer in time") that
  pre-existed and didn't match what the code actually did.

**Verified live**, not just built:

```
listDbaasEngines → every engine now reports "minmemorymb": 1024
createDatabase on a Small Instance (512 MB) VM →
  Error: service offering Small Instance has 512 MB RAM, below the 1024 MB
  minimum for this engine: the database engine and the console agent have
  been observed OOM-killed under load below that threshold. Redeploy on a
  larger offering -- the wizard's offering list is filtered by this same
  minimum, so this should only be reachable by calling createDatabase directly
```

## 2. Fixed the UI build (was completely broken before tonight)

`npm run build` failed outright: webpack 4's internal `createHash('md4')`
call hits Node 22's OpenSSL 3, which dropped legacy MD4 support —
`ERR_OSSL_EVP_UNSUPPORTED`, a well-known, well-documented incompatibility.
Fixed by prefixing `build`/`serve`/`start`/`lint`/`i18n:report`/`test:unit`
in `package.json` with `NODE_OPTIONS=--openssl-legacy-provider` — the
standard fix, harmless on any Node version that predates the flag existing
(none in use here).

`npm run build` now completes cleanly and produces a `dist/` containing the
`DbaasConsole` component and every `dbaas.*` locale key (grepped to confirm,
not assumed).

## 3. The console UI is live for the first time tonight

Previously: everything tested this whole session was through raw API calls
(`cmk`), and the deployed webapp bundle (`app.36e483d8.js`, built 2026-09-05)
predated the console feature entirely — a browser session tonight would not
have shown a Console button at all.

Deployed properly:

1. Backed up the entire live webapp directory:
   `/usr/share/cloudstack-management/webapp.bak.20260908`
2. Copied the fresh `dist/` over `/usr/share/cloudstack-management/webapp/`
3. Confirmed `index.html` now points at the new bundle (`app.c4c369f1.js`)
   and that bundle contains `DbaasConsole`/`listDbaasTables`
4. Confirmed the new locale key (`message.dbaas.no.offering.fits`) is present
   in the deployed `locales/en.json`
5. Hot-patched `DbaasManagerImpl.class` and `DbaasEngineResponse.class` into
   the running jar (byte-identical to the build output, verified both ways),
   restarted `cloudstack-management`

**A browser session right now would see**: the Console row action on a
running DBaaS instance, and the service offering dropdown correctly disabled
until an engine is picked, then filtered. Not independently verified through
an actual browser click-through tonight — no browser access from this
session — but every server-side and bundle-content check that can be done
without one has been done.

## 4. VR testing — the shared network (where DBaaS actually deploys today)

`dbaas-network` (what the wizard and every DBaaS template target) is a
**Shared** network (`DefaultSharedNetworkOffering`). Confirmed live: on a
Shared network the virtual router is **not** the L3 gateway — guests and the
management server sit on the same flat L2 segment. Stopping the router:

```
r-63-VM stopped → ping to guest (10.60.0.77) still answers
createDbaasTable on the running dbaas-matrix-3 instance → confirmed
  (job dispatched, executed, reported, result correct)
```

**This is a genuine, correctly-scoped pass** for the network topology DBaaS
actually uses today. It does not, however, exercise the harder claim
PLAN.md's original language evokes (an isolated network where the VR is the
SourceNat gateway) — that is architecturally a different question, covered
next.

## 5. VR testing — isolated network, and what it actually revealed

Built the harder test properly: `DbaasIsolatedConfigDrive` network offering
already existed (from an earlier session) but had never been turned into an
actual network. Created one (`10.1.1.0/24`), deployed onto it, forced the
network's virtual router into existence (VRs on isolated networks are
created lazily on first VM start), then **stopped that VR before calling
`createDatabase`**.

**First finding: CloudStack's own orchestration won't let you keep the VR
down.** Starting a VM on an isolated network appears to trigger CloudStack to
ensure the network's control plane (its VR) is healthy first — the VR
auto-restarted as a side effect, observed live in the log
(`VpcVirtualNetworkApplianceManagerImpl ... Creating monitoring services on
VM ... Reapplying dhcp entries`) before the guest even finished booting. This
makes sense given `DbaasIsolatedConfigDrive`'s actual provider config: `Dhcp`
and `Dns` are still `VirtualRouter`, only `UserData` is `ConfigDrive` — the
offering was never finished to the spec `PLAN-DBAAS-CONSOLE.md`/
`ACCEPTANCE-FIX-2026-09-05.md` ("Fix 1b") actually called for (ConfigDrive as
Dhcp **and** Dns **and** UserData). Building that offering properly is real,
scoped, undone work — not something to improvise at 08:00 without discussion.

**Second finding, much bigger, found only because of this test: `createDatabase`
on an instance that has already booted once does not provision at all, silently.**

Sequence that exposed it: `startVirtualMachine` (forces the VR into
existence — a real boot, empty user-data) → `createDatabase` (stops the VM,
attaches the real request, restarts it — the exact "Create Database on a
running instance" flow `PLAN.md` §1a documents as supported and already
shipped). Evidence, all read directly from the guest's own files via the
same offline `qemu-nbd` read-only method used throughout this session (VM
never touched live):

- the config drive attached to the VM, read directly off its ISO, carries
  the **correct** full request: `write_files` for
  `/var/lib/dbaas/request.json` with the real `db_name`/`db_user`/
  `db_password`/tokens, and `runcmd: [/opt/dbaas/firstboot.sh]`
- cloud-init's own log for the second boot: `"Skipping modules
  'snap,keyboard,apt-pipelining,ntp,timezone,disable-ec2-metadata,runcmd'
  because no applicable config is provided"` — cloud-init concluded there
  was no `runcmd` to run, on the exact boot where the config drive plainly
  had one
- `/var/lib/dbaas/` on the guest is completely empty (directory timestamp
  still the template's original build date) — `request.json` was never
  written, `firstboot.sh` never ran
- the credential stayed `pending` indefinitely, with **no error anywhere** —
  not in the management log, not in the guest's own logs, nothing to signal
  that anything was wrong

**Cause, as far as this session got**: cloud-init's datasource caching
(`stages.py: restored from checked cache: DataSourceConfigDrive`) appears to
key on the instance's own identity rather than the config drive's content,
so a VM's second boot reuses the cached (empty) datasource object from its
first boot rather than re-reading the freshly re-attached ISO. Nothing in
this codebase currently invalidates that cache — `firstboot.sh` and
`DbaasManagerImpl`'s restart logic were both checked; neither touches
anything under `/var/lib/cloud/`.

**This is unrelated to the VR being down.** It would reproduce identically
with the VR fully healthy — the VR-down test just happened to be the first
scenario in this whole project that ever called `createDatabase` on an
instance that had booted before. Every other test tonight and in prior
sessions deployed with `startvm=false` and called `createDatabase`
immediately, which is always that instance's *first* boot — the one code
path that happens to avoid this entirely, by accident, not by verification.

**Severity**: this silently breaks a documented, shipped feature — "Create
Database on a running instance," the row action `DatabaseInstances.vue`
explicitly shows for both `Running` and `Stopped` instances. Any tenant who
deploys a plain instance, uses it, and *later* adds a database gets a
credential stuck `pending` forever with nothing telling them why.

**Not fixed tonight, deliberately.** The right fix touches either cloud-init
cache invalidation (fragile, version-dependent) or a redesign of how
`firstboot.sh` is triggered on a restart (e.g., a systemd unit that reads the
config drive directly on every boot rather than going through cloud-init's
`runcmd`/`write_files` modules, sidestepping this caching layer entirely —
closer to the project's existing instinct to avoid depending on
external-tool assumptions). Both are real design decisions, not a
20-minute patch, and this was found in the session's last hour.

## 6. Cleanup

- Test isolated network (`dbaas-isolated-vrtest`) and its instance
  (`dbaas-vrdown`) destroyed/expunged/deleted — served their purpose, kept
  no lingering state
- `dbaas-matrix-3` observed **Stopped** at the end of this session, unrelated
  to any of the above — a bare `cloudstack-management` service restart (not
  a host reboot) appears to trigger the same "mark non-HA VM as Stopped"
  reconciliation the earlier full host reboot did. Its disk (and the
  `widgets` table proven earlier) is untouched, just powered off. Left as-is
  rather than restarted, since it isn't needed for anything further tonight.
- `webapp.bak.20260908` and the jar backups from every hot-patch this
  session are all still in place
- `DATA-73` and `tplbackup/`'s pre-existing contents: untouched, confirmed
  again
- Disk: `/` 12G free, `/export/primary` 17G free, `/export/secondary` 5.0G
  free — all healthy
- Ownership: `find . -user root` returned nothing before every build tonight

## 7. Updated priority list for next session

1. **The cloud-init caching bug (§5)** — now the single most important open
   item. It silently breaks a shipped feature and has no visible symptom
   short of reading guest-internal logs. Needs a real design discussion:
   cache invalidation vs. moving `firstboot.sh` off cloud-init's module
   system entirely.
2. Finish the `DbaasIsolatedConfigDrive` offering to the actual spec
   (ConfigDrive as Dhcp **and** Dns, not just UserData) if the isolated,
   VR-independent scenario is still wanted — the offering exists but is
   incomplete
3. Verify the console through an actual browser (this session had none) —
   click through create table / query / describe, confirm the offering
   filter behaves as designed in the UI, not just via API
4. Matrix items 10 (cross-account ACL), 11 (token replay), 14 (job expiry) —
   still not run
5. The two carried-over decisions from part 1: log hygiene (SQL/results
   visible in `management-server.log`) and per-engine OOM measurement for
   mariadb/postgresql/mongodb beyond the one measured number

## 8. One-line summary, updated

On the network DBaaS actually deploys to today (Shared), the virtual router
being stopped changes nothing — proven live. On an Isolated network, the
harder claim remains unverified because the ConfigDrive offering built for
it is incomplete, and pursuing that test uncovered a real, separate,
higher-priority bug: **creating a database on an instance that has already
booted once does not work, silently, regardless of the virtual router's
state.** That is the finding to act on first.
