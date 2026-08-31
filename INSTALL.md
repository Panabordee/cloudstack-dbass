# DBaaS on CloudStack — what it adds, what it needs, how to install

Self-service databases inside the CloudStack UI: pick an engine, get an
instance with MySQL / PostgreSQL / MongoDB on it and a set of credentials,
without touching SSH.

## What this adds

Four things appear in the CloudStack UI once this is installed:

| Where | Action | What it does |
| --- | --- | --- |
| Instances toolbar → `Add Instance ▾` | **Create Database Instance** | Deploys an instance from a `dbaas-*` template and provisions a database on it in one step |
| Header `Create ▾` | **Database** | Same dialog, reachable from anywhere |
| Instance detail page | **Create Database** | Adds another database + user to an instance that already exists |
| Instance detail page | **Reset Database Password** | Rotates the password of a database user |

Generated passwords are shown once and stored nowhere, so the reset action is
the way back in after one is lost.

Two API commands back these: `createDatabase` and `resetDatabasePassword`. Both
derive the owner from the instance, so a caller only reaches instances their own
account owns.

## Requirements

**Management server**

- Apache CloudStack **4.22.2.0** (this plugin is built against that line; for
  4.22.1.1 use the `main` branch instead of `panabordee/dbaas-4.22.2`)
- Java 17 and Maven 3.8+ to build the plugin jar
- The three `dbaas-*` templates registered and in `Download Complete` state

**Templates** — each `dbaas-*` image must carry, under `/opt/dbaas`:

- `provision.sh` whose `ALLOWED` list includes both the create and the reset
  script for its engine
- `<engine>.sh` and `<engine>_reset.sh`
- a `dbaas-provisioner` account whose `authorized_keys` forces every connection
  into `provision.sh`
- MongoDB images additionally need `admin_credentials.json` and the
  `dbaas-rotate-admin-password` service

`TEMPLATES.md` covers building and patching these.

**Sizing** — use `Medium Instance` (1 GHz / 1 GB) or larger. On `Small
Instance` the engine starves the vCPU badly enough that sshd cannot answer in
time and provisioning fails intermittently; `TEMPLATES.md` has the measurements.

**Permissions** — `createDatabase` and `resetDatabasePassword` are denied by
default for non-admin roles. Default roles cannot be edited, so clone one:

```bash
cmk create role name="DBaaS User" roleid=<User-role-id> \
  description="Standard user plus the DBaaS provisioning APIs"
cmk create rolepermission roleid=<new-role-id> rule=createDatabase        permission=allow
cmk create rolepermission roleid=<new-role-id> rule=resetDatabasePassword permission=allow
```

## Installing

### 1. The management-server side (Python)

```bash
sudo mkdir -p /usr/share/cloudstack-management/extensions/dbaas
sudo cp -r extension.py cs_api.py actions provisioning \
          /usr/share/cloudstack-management/extensions/dbaas/
sudo cp config.example.json /usr/share/cloudstack-management/extensions/dbaas/config.json
sudo chown -R cloud:cloud /usr/share/cloudstack-management/extensions/dbaas
sudo chmod 600 /usr/share/cloudstack-management/extensions/dbaas/config.json
```

Fill in `config.json`: the CloudStack API URL and keys, and the path to the
provisioning SSH private key. The management server runs as `cloud`, so that
user has to be able to read the config and the key.

### 2. The API plugin (Java)

```bash
# collect the jars the running management server was built from
D=/tmp/cs-jars && mkdir -p $D
for a in cloud-api cloud-utils cloud-server cloud-framework-config; do
  cp ~/.m2/repository/org/apache/cloudstack/$a/4.22.2.0-SNAPSHOT/$a-4.22.2.0-SNAPSHOT.jar $D/
done
for j in log4j-api-2.19.0.jar gson-2.10.1.jar joda-time-2.12.5.jar javax.inject-1.jar; do
  cp /usr/share/cloudstack-agent/lib/$j $D/
done

cd plugin && mvn -B clean package -Dcs.lib=$D
sudo install -o root -g root -m 644 \
  target/cloud-plugin-dbaas-*.jar /usr/share/cloudstack-management/lib/
```

Remove any older `cloud-plugin-dbaas-*.jar` first — two versions in `lib/`
means both get loaded.

### 3. Restart

```bash
sudo systemctl restart cloudstack-management
```

The UI actions come from the CloudStack UI build, not from this repository. If
the buttons do not appear, the management server is serving a UI that does not
have them; see `BUILD-DBAAS.md` in the CloudStack fork.

## After every management-server restart

The KVM host agent and both system VM agents do not reconnect on their own —
they sit `Disconnected` until restarted by hand:

```bash
sudo systemctl restart cloudstack-agent

# link-local addresses come from: cmk list systemvms
for ip in <consoleproxy-link-local> <ssvm-link-local>; do
  sudo -u cloud ssh -i /var/lib/cloudstack/management/.ssh/id_rsa -p 3922 \
    -o StrictHostKeyChecking=no root@$ip 'systemctl restart cloud'
done
```

Skipping the system VM half is what leaves **View Console** broken.

## Checking it works

```bash
cmk list apis name=createDatabase          # the command is registered
cmk create database virtualmachineid=<uuid> dbname=demo dbusername=demo
mysql -h <instance-ip> -u demo -p demo     # the credentials it returned
```

A first attempt right after an instance boots can fail with `No existing
session` or `Can't connect to local MySQL server` — the engine is still
starting. The UI retries these automatically; a script calling the API needs
its own retry.
