# Master plan — everything outstanding

Living document. Rewritten 2026-09-09 after the acceptance session at
`bca61f6720` closed most of it. Do not start a new dated plan file; update
this one.

Baseline: branch `4.23+dbass`, commit `bca61f6720`, host `cloudstackcve`,
repo `/home/nacl/dbaas-v2`.

---

## 0. Standing rules

**Never, without asking first:** delete or modify
`/export/primary/tplbackup/**`; touch `DATA-73`; destroy or modify an
instance you did not create; set `dbaas.console.drop.enabled` or
`dbaas.datadisk.cleanup.enabled` to `true`; weaken a default, widen a
permission or disable a check to make a test pass; `git push --force`.

**Always:** engine/script/port mappings live in
`extensions/dbaas/config.json`, never in a dict or switch in `.py`/`.java`.
Commits carry **no `Co-Authored-By` trailer**; author stays
`SnowFlex <68010697@kmitl.ac.th>` or the Panabordee identity. Every host
change is reported with its undo. When scope balloons: **report it, do not
chase it**.

GitHub cleanup is closed: `4.23+dbass` is the working branch; `main` and
`panabordee/dbaas-v1-archive` stay by decision.

---

## Done — verified by observation, not by claim

| Was item | What is now true | Evidence |
| --- | --- | --- |
| 1 provisioning on second boot | all six cases observed on 210, including the case never tested before and the failure→retry case | `ACCEPTANCE-REPORT-2026-09-09.md` §2 |
| 2 rebuild four templates | all four images rebuilt 2026-09-09 07:51, secondary + primary cache, backups taken, `qemu-img check` clean | §4, image mtimes |
| — deploy on all four engines | mysql/mariadb/postgresql/mongodb all provision to `confirmed` and run a real console job | §5 |
| 5 memory floors | measured, not guessed: mariadb/postgresql/mongodb clean at **512 MB**, mysql stays **1024 MB**; `config.example.json` updated | §8 |
| 6 matrix 10/11/14 | cross-account denial clean, replayed token 403 + logged, job expiry reported immediately | §6 |
| — 11 defects | found and fixed this session, `4f0d2928a1`–`d51018873d` | §3 |

The console works end to end **through the API** on all four engines, as
`_ro` for reads and owner for writes, with the engine itself refusing what
`_ro` may not do. A normal DB client connects from another machine.

---

## Remaining work

### A. The console UI does not work in a browser — the only real blocker

Everything above was proven through `cmk`. In the browser, after the
nesting-depth fix (`d51018873d`) was deployed, the console's Refresh click
produces **no API request at all**: the handler runs, no POST is sent, no
client error appears. Two working hypotheses recorded: the drawer's resource
binding, or an axios-level rejection swallowing the call. A debug-logging
build was interrupted; the temporary lines were removed before committing.

Also unverified in the browser: describe / preview / create-table, and the
create-wizard's service-offering filter (disabled until an engine is chosen,
floor filtering, stranded selection cleared, warning paragraph).

Screenshots so far: `ACCEPTANCE-SHOTS-20260909/` (00–14). Playwright and
headless Chromium are installed on the host. Previous UI tree preserved at
`/usr/share/cloudstack-ui.bak.20260909`.

**This is the difference between "the API works" and "a tenant can use it".**

### B. Log hygiene — query text still reaches `management-server.log`

`requestHasSensitiveInfo` / `responseHasSensitiveInfo` mask the audit and
API-response layer, not `ApiServlet`'s DEBUG request/response logging, and
this host logs `com.cloud` at DEBUG. Build a **redacting filter scoped to
`RunDbaasQueryCmd` and `GetDbaasJobResultCmd`** — not a blanket log-level
drop, which trades the whole platform's debuggability for one leak.

Done when a query with a canary literal, then a `grep` for that literal *and*
for the returned row values, produces empty output — command and empty output
pasted.

### C. Reset Database Password

`DbaasManagerImpl.java:1367` still throws an exception whose message says the
in-VM agent does not exist. It does, and it is now proven on all four
engines. Add a `password_reset` job type to `JOB_HANDLERS`
(`extensions/dbaas/agent/dbaas_agent.py`), generate the password
server-side, update `dbaas_credentials` only after the agent confirms, never
log the plaintext. `<engine>_reset.sh` already has the SQL shape.

**Guest-side change → needs an image pass. See "the batching rule" below.**

### D. VM login password

Diagnosed in `VM-PASSWORD-DEFECT-2026-09-05.md`, never implemented.
`createDatabase` deploys with `startvm=false` and starts the instance through
the internal two-argument path, so `Param.VmPassword` is never populated.
Fix: `resetPasswordForVirtualMachine` (or set `Param.VmPassword` on the
plugin's own start call) before the config-drive attach, while Stopped.

### E. Two decisions only the owner can make

1. **Should tenant accounts be allowed the DBaaS APIs at all?** They are
   currently denied at the role layer even on their own instances. Matrix
   item 10 passed *because* of this, which is a pass for the wrong reason if
   tenants are supposed to have access.
2. **Agent token self-heal.** An agent that misses its rotation, or whose
   process dies between receiving and saving a new token, has no recovery
   path except a new provisioning request. Worth an agent-side "re-read the
   token from the config drive on 403" only if a future request would carry
   a fresh token.

### F. The isolated-network / VR-down claim

`DbaasIsolatedConfigDrive` still has Dhcp and Dns on `VirtualRouter`, so
CloudStack auto-heals the VR on any VM start and "VR down" never actually
happens. `ConfigDriveNetworkElement` advertises Dhcp and Dns capabilities, so
a correct offering is constructible; services cannot be edited after
creation, so make a new offering with Dhcp + Dns + UserData on ConfigDrive
(SourceNat/Firewall/PortForwarding stay on VirtualRouter), build a network,
deploy onto it, stop the VR, repeat the matrix.

**This is proof, not function.** DBaaS deploys to a Shared network today and
that path is already verified. Worth doing before claiming the architecture
survives a broken VR — not worth blocking a demo on.

### G. Housekeeping

- destroy the four leftover measurement instances (`glmc-*`)
- primary storage hit **90.5%** during the last session and the 211–213
  caches alone are ~5 GB; secondary was at 94%. This is exactly NEON gate G4
  and it now has real datapoints
- `README.md`, `INSTALL.md`, `TEMPLATES.md` under
  `plugins/integrations/dbaas/` still describe the retired v1 SSH transport
- `dbaas.console.drop.enabled` stays `false` until backup/PITR exists

---

## The batching rule — the single biggest efficiency lever

The agent has **no self-update path**. Any change to `dbaas_agent.py` or the
engine scripts means another four-image `qemu-nbd` pass, which is the most
expensive and most error-prone operation in this project (it has broken twice:
the engine-marker bug, and the stale-agent gap).

So: **do not do a guest-side change alone.** Collect them and pay for one
pass. Currently queued for the next one:

- C (`password_reset` job type)
- E2 (agent token self-heal), if the owner says yes
- anything Neon Stage 3/4 needs in the guest (PgBouncer, pgBackRest) — see
  `PLAN-NEON-STEPS.md`

Everything else on this page — A, B, D, F, G — is server-side, UI-side or
zone-side and needs **no image pass at all**.

## Order

```
A (browser UI)          ← blocker; nothing else makes the product usable
   │
B (log redaction)       ← server-only, small, security
D (VM login password)   ← server-only, small
E (two decisions)       ← zero work once answered
   │
one image pass: C (+E2 if yes)
   │
F (VR-down proof)  ·  G (housekeeping)  ← both optional for a working product
```

## What "finished" means here

A tenant opens the UI, creates a database on any of the four engines, browses
tables, runs a query, creates a table, connects a normal client, and resets
their password. No SQL text or row value in `management-server.log`. Dropping
a table is still disabled.

After that, `PLAN-NEON-STEPS.md` Stage 1 (the four measurement gates) is the
next thing, and it is one session.
