# Plan: replace SSH provisioning with config-drive / cloud-init

Status: proposal, nothing here is implemented yet (Phase 0 excepted — see below).
Written 2026-09-05 against `panabordee/dbaas-full` @ `9a1834005f` + uncommitted Phase 0 work.

## 1. Why

Provisioning today is a **push** from the management server: `DbaasManagerImpl`
runs `extension.py`, which opens an SSH connection to the instance and executes
`/opt/dbaas/provision.sh <engine>.sh` behind a forced-command key. The database
is only created if the management server can reach port 22 on the instance.

That single requirement is the root of most of the defects this plugin has had:

| Symptom | Cause |
| --- | --- |
| Provisioning budget had to be tuned against `dbaas.provision.timeout` three times (300 / 480 / 400 vs 600) | The retry loop must fit inside the timeout the management server kills the process at |
| Retries nest three deep (UI 8 attempts, Java 600s kill, python 3 attempts) | Every layer compensates for a flaky SSH path |
| A timeout after the engine was configured loses the password permanently | Only the management server holds the generated password, and it stores it after the SSH call returns |
| Show Password cannot tell "still provisioning" from "failed" | A failed run leaves no record at all, so `found=false` means both |
| Offerings must be ≥1 vCPU / 1 GB | A starved instance cannot answer the SSH banner in time |
| `No existing session` / `timed out` / `Unable to connect to port 22` failures | Any network without a route from the management server to the instance |
| Instance deployed onto the wrong network yields a running instance with no database | Same |

Fixing them one at a time has produced a working system, but each fix is a
workaround for the transport. Changing the transport removes the class.

## 2. Target architecture

**Pull instead of push.** The management server never connects to the instance.
It hands the instance everything it needs at boot, and the instance configures
itself.

Delivery uses **config drive**, not the virtual router's metadata service:
config drive attaches user data as a virtual CD-ROM, so provisioning works even
when the guest network, DHCP or the VR itself is broken — the failure mode we
actually hit. (Requires the network offering to list ConfigDrive as its UserData
provider; see §7.)

```
today   management server --ssh:22--> instance          (breaks with no route)
target  management server --user data--> config drive --> instance provisions itself
```

The end state (Phase 3) adds a small agent inside the template that polls for
work, which also covers the two flows cloud-init cannot: creating a database on
an instance that is already running, and resetting a password.

## 3. Phase 0 — stop the bleeding (done, pending commit)

Already implemented in the working tree:

- `ui/src/views/compute/CreateDatabaseInstance.vue` — `networkid` is required in
  advanced zones and auto-selected when the zone offers exactly one network.
  Leaving it empty made CloudStack create the account's default isolated
  network and put the instance behind a router the management server cannot
  reach, which is how the current outage started.
- `extensions/dbaas/actions/create_database.py` — `_failure_message()` turns a
  connectivity failure into an actionable message naming the instance IP and
  saying the instance is still usable, instead of a bare `timed out`.
- `ui/src/views/compute/DatabaseInstances.vue` — expunging an instance now also
  deletes its data disks (they were left allocated, and eventually blocked new
  deployments with "No destination found for a deployment"), and the destroy
  dialog says so.
- `ui/public/locales/en.json` — Show Password no longer claims the database "is
  still being provisioned" when it cannot know that; the exhausted state points
  at the Create Database action instead.

These stay useful whether or not the rest of this plan happens.

## 4. Phase 1 — config drive for the new-instance flow

Covers the wizard flow (deploy an instance and create its database), which is
the flow that fails today.

### Flow change

```
now:     UI deployVirtualMachine  ->  UI createDatabase  ->  plugin SSHes in
Phase 1: UI deployVirtualMachine (startvm=false)
         -> UI createDatabase
            -> plugin generates the password
            -> plugin writes user data onto the stopped instance
            -> plugin stores the credential
            -> plugin starts the instance
            -> plugin returns the credentials immediately
         -> instance boots, reads the config drive, configures its engine
```

The password never reaches the browser except in the response the user is meant
to see, and it is stored before the instance even starts — Show Password has
something to show from the first second, so the polling UX disappears.

### Work items

1. **Template**: bake a first-boot unit into each `dbaas-*` image that reads the
   config drive, extracts `db_name` / `db_user` / `db_password` (and optionally
   `vm_user` / `vm_password`), and runs the engine setup. The SQL logic moves
   almost verbatim out of `extensions/dbaas/provisioning/<engine>.sh`; only the
   "read JSON from stdin" plumbing is replaced by "read from the config drive".
2. **Capability flag**: add `"provision_mode": "cloudinit" | "ssh"` per engine in
   `config.json`'s `engines` map (never hardcoded in source, per the existing
   rule) and expose it through `listDbaasEngines` as `provisionmode`.
3. **Java** (`DbaasManagerImpl.createDatabase`): branch on the mode.
   `cloudinit` builds the user data document, calls `updateVirtualMachine` to
   attach it, stores the credential, then starts the instance. `ssh` keeps
   calling `extension.py` exactly as it does now.
   *To verify during implementation*: which service interface the plugin can
   inject for update/start (`UserVmService` / `UserVmManager`) in 4.22.
4. **UI**: the wizard sends `startvm: false` when the selected engine reports
   `provisionmode: cloudinit`, and keeps today's behaviour otherwise.
5. **Docs**: TEMPLATES.md gains the config-drive build steps.

### Acceptance

Deploy an instance onto a network the management server has **no route to**, on
a zone whose VR is broken, and still get a working database and a password
visible in Show Password.

### Effort

Code 1–2 days. Template rebuild and testing ~1 day for the first engine.

## 5. Phase 2 — real provisioning status

Today a failed provision leaves no trace, so the UI guesses. This phase makes
the instance report back.

1. **Schema** (needs explicit approval — touches `dbaas_credentials`): add
   `status` (`pending` / `confirmed` / `failed`) and `status_message`.
2. **Report-back API**: the instance POSTs its result using a one-time token
   that the plugin generated into the user data. The token is bound to one
   instance UUID, single use, short TTL, stored hashed, rate limited, and every
   call is logged.
   *Security note*: the instance holds no CloudStack credentials, so this
   endpoint accepts an unauthenticated caller validated only by the token. That
   is a new attack surface the current design does not have — it must be an
   explicit decision, not a side effect.
   Direction matters: instance → management server usually works (guests have
   egress through the VR) even when management server → instance does not.
3. **UI**: Show Password renders the real status instead of inferring it. The
   "still provisioning / probably failed" ambiguity disappears for good.

Effort: 1–2 days.

## 6. Phase 3 — in-VM agent, retire SSH

cloud-init only runs on first boot, so two flows still need a channel into a
running instance: **Create Database on an existing instance** and **Reset
Database Password**.

Add a small service to the template that polls its config source for pending
jobs, executes them, and reports the result through the Phase 2 endpoint.

Then delete the SSH transport entirely:

- `extensions/dbaas/cs_api.py` (60 lines)
- `extensions/dbaas/extension.py` (93 lines)
- the transport half of `actions/create_database.py` and
  `actions/reset_database_password.py` (~250 lines): paramiko, retry loop,
  `PROVISION_ATTEMPTS`, `TRANSIENT_CONNECT_ERRORS`, the budget calculation
- `provisioning/provision.sh` and its allowlist, the forced-command key,
  `authorized_keys.example`, `sudoers.d-dbaas-provisioner`
- `ssh_connect_timeout_seconds` and `dbaas.provision.timeout`
- the transient-error list in `ui/src/utils/dbaas.js` and the UI retry chain

Every defect in the §1 table closes with them.

Effort: 3–5 days including the remaining three engines.

## 7. Phase 4 — clear the user data

User data is stored by CloudStack and readable by the instance owner, so the
database password sits in the CloudStack database in cleartext until it is
removed. Once an instance reports `confirmed`, clear its user data
(`updateVirtualMachine userdata=""`). The credential remains available,
encrypted, through Show Password.

Effort: half a day.

## 8. What is *not* rewritten

- The five API commands, `DbaasResponse`, and the ACL model
- `dbaas_credentials` and `DBEncryptionUtil` storage (Phase 2 adds columns)
- Every UI view: the wizard, the Database section, Show Password, Create
  Database, Reset Password
- The per-engine SQL (create user, grant, verify login) — it moves into the
  template, largely unchanged

Rough size: ~400–500 lines deleted, ~100 lines changed in Java, ~150–250 lines
added (user data builder plus the in-template script).

## 9. Prerequisites and risks

- **Config drive must be enabled** on the network offering used by DBaaS
  networks (UserData provider = ConfigDrive). Without it, user data falls back
  to the VR metadata service and the VR becomes a dependency again.
- **`updateVirtualMachine userdata` on a stopped instance** must be available to
  the plugin's service context — verify before committing to the flow.
- **Password in user data** until Phase 4 clears it.
- **Unauthenticated report-back endpoint** in Phase 2 — decide deliberately.
- **Template rebuilds** are the real schedule risk, not the code.

## 10. Rollback

Both transports coexist from Phase 1 to Phase 3: the mode comes from
`config.json`, per engine. A template built the old way keeps working with the
SSH path, and reverting a misbehaving new template is a config edit, not a
deployment rollback.

## 11. Sequencing

1. Commit Phase 0 and build, so there is a working system to fall back to
2. Phase 1 on a separate branch, one engine (mysql) end to end
3. Phase 2 once the status question is worth solving properly
4. Phase 3 when the remaining engines are rebuilt
5. Phase 4 last

Total, all phases: roughly 1.5–2.5 weeks of focused work. Phase 1 alone removes
the network dependency from the flow that fails today.
