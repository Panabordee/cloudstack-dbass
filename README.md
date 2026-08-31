# CloudStack DBaaS Extension — Skeleton

Self-service "Create Database" action that runs natively inside the CloudStack
UI (Extensions Framework, CloudStack >= 4.22) on an already-deployed VM,
provisions MySQL / PostgreSQL / MongoDB on it, and hands the caller back a
connection string + generated credentials.

> **Installing?** See [INSTALL.md](INSTALL.md) — what it adds, what it needs, and the steps.

## How the flow actually works (important)

CloudStack's Extensions Framework runs **custom actions against an existing
resource** (a VM), it does not let an extension create-and-configure a brand
new VM in one click. So the real user flow is two steps, both inside the
native CloudStack UI:

1. User deploys a VM the normal way, picking one of the prebuilt templates:
   `dbaas-mysql`, `dbaas-postgresql`, or `dbaas-mongodb` (see
   `provisioning/`, these templates ship the DB engine installed but
   unconfigured, plus a locked-down provisioning SSH key).
2. Once the VM is `Running`, a new action **"Create Database"** appears in
   that VM's action menu (added by this extension). User fills in
   `db_name` and `db_username`. CloudStack invokes `extension.py`, which
   SSHes into the VM with a *forced-command* key, runs the matching
   `provisioning/<engine>.sh`, generates a random password, and returns it
   to the UI (`printmessage: true`) — shown once, like AWS RDS does.

This matches how the framework's `runCustomAction` actually works (action +
`resourceid`), instead of assuming it can spawn new VMs — that part needs
the normal CloudStack "Deploy VM" wizard.

## Files

- `extension.py` — entrypoint CloudStack invokes: `extension.py <action> <payload_file> <timeout>`
- `actions/create_database.py` — the `create_database` action handler
- `cs_api.py` — minimal signed CloudStack API client (used to look up the VM's IP)
- `provisioning/mysql.sh`, `postgresql.sh`, `mongodb.sh` — run **inside the
  target VM** over SSH; each reads a JSON payload on stdin and creates the
  database + user
- `register_extension.sh` — `cloudmonkey` commands to register the extension
  and the custom action
- `config.example.json` — copy to `config.json`, fill in real values,
  **never commit `config.json`**

## Setup order

1. Build the three templates (`dbaas-mysql`, `dbaas-postgresql`,
   `dbaas-mongodb`): install the engine and **leave it enabled and running** —
   a VM deployed from the template has to come up with a working database
   server. Drop `provisioning/provision.sh` and the matching
   `provisioning/<engine>.sh` into `/opt/dbaas/` on the image, and add an
   `authorized_keys` entry for a dedicated `dbaas-provisioner` user that is
   **forced** to only run that script:
   ```
   command="sudo -n /opt/dbaas/provision.sh",no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty ssh-ed25519 AAAA... dbaas-extension
   ```
   This means even if the extension's private key ever leaks, it can only
   ever run that one provisioning script — it cannot get a shell.

   The forced command goes through `sudo` because all three engine scripts
   need root. Pair it with `provisioning/sudoers.d-dbaas-provisioner`, which
   pins the command to that exact path with no arguments and keeps
   `SSH_ORIGINAL_COMMAND` across the `sudo` env reset. `provision.sh` reads the
   engine script name from `$SSH_ORIGINAL_COMMAND` and validates it against its
   own allowlist — sshd never passes it as `$1`.

   See `TEMPLATES.md` for the full per-image contents, the generalize steps
   before `createTemplate`, and how to patch a template offline.

2. Copy this whole folder to every management server at
   `/usr/share/cloudstack-management/extensions/dbaas/`, owned by
   `cloud:cloud`, `chmod +x extension.py`.

3. Fill in `config.json` (CloudStack API endpoint + keys, path to the SSH
   private key for `dbaas-provisioner`).

4. Run `register_extension.sh` (edit the `CS_URL` / credentials at the top
   first) to create the extension and the `create_database` custom action.

5. Deploy a test VM from `dbaas-mysql`, wait for `Running`, then trigger
   **Create Database** from the VM's action menu and confirm you get a
   password back. Confirm it by actually logging in with the returned
   credentials — two of the three engine scripts have historically exited 0
   while silently failing to create the user.

## Status of the UI action on 4.22.1.1

Step 5 does not work on a normal KVM cluster, and the limitation is in
CloudStack, not in this code.

`Extension.Type` defines only `Orchestrator`, and CloudStack resolves the
extension for a VM through the VM's **cluster**
(`ExtensionResourceMap.ResourceType` is `Cluster` only,
`ExtensionHelper.getExtensionForCluster`). `registerExtension` refuses a
CloudManaged KVM cluster:

    Cluster ID: 1 is not of KVM hypervisor type

and with no cluster registered, `runCustomAction` against a KVM VM fails:

    ERROR [o.a.c.f.e.m.ExtensionsManagerImpl] Invalid VM {...} specified as
    VM resource for running Extension Custom Action {...}

So the **Create Database** button only appears for VMs on a cluster of
hypervisor type `External` — VMs whose entire lifecycle the extension itself
orchestrates. Wiring it up would mean implementing prepare/create/start/stop/
delete in `extension.py` and running the database VMs on an External cluster
rather than as ordinary KVM instances.

What does work today, tested end to end: invoking the action on the management
server as the `cloud` user, exactly the way CloudStack would.

    sudo -u cloud python3 \
      /usr/share/cloudstack-management/extensions/dbaas/extension.py \
      create_database /path/to/payload.json 120

with a payload of the shape CloudStack sends:

    {
      "virtualmachineid": "<vm uuid>",
      "externaldetails": {"virtualmachine": {"templatename": "dbaas-mysql"}},
      "parameters": {"db_name": "shop", "db_username": "shopuser"}
    }

`register_extension.sh` still registers the extension and the enabled
`create_database` action — both show up under **Extensions** in the UI — it just
stops short at the cluster registration step.

## Security notes baked into this skeleton (don't remove them)

- Password is generated **server-side** with `secrets.token_urlsafe`, never
  accepted as user input — avoids weak/reused passwords.
- The SSH credential the extension uses is restricted to one forced command
  via `authorized_keys`, not a general-purpose login key.
- The generated password is sent to the VM over SSH as JSON on stdin, never
  as a CLI argument (CLI args leak into `ps`/shell history on the VM).
- Nothing is logged with the password in it — check before you add logging.
- `allowedroletypes` includes `User` so tenants can self-serve, but you
  should still confirm CloudStack's RBAC scopes `resourceid` to VMs the
  caller actually owns (test this before going to production).

## What's still a TODO for you

- `cs_api.py`'s IP lookup assumes a single default NIC; adjust for your
  networking (VPC tiers, multiple NICs).
- Firewall/reachability for the DB port is intentionally **not** opened by
  the provisioning scripts — bind the engine to the VM's private IP and
  control external reachability with CloudStack Security Groups / Network
  ACLs per tenant, not with a script running as root inside the VM.
- Add a `delete_database` / `reset_password` custom action following the
  same pattern once `create_database` is confirmed working end to end.
- Confirm the exact shape of `externaldetails`/custom-parameter fields in
  the payload against your real 4.22.1.1 install — the docs note "the
  schema varies by resource and action". `extension.py` already logs the
  raw payload to `/var/log/cloudstack/management/dbaas-extension.log` on
  first run so you can check the real field names before trusting the
  parser in `actions/create_database.py`.
