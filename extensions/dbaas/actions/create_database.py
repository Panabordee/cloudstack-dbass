"""
Handler for the "create_database" custom action.

Runs against an already-deployed VM (built from one of the dbaas-* templates).
Looks up the VM's IP, SSHes in with a forced-command key, runs the matching
provisioning/<engine>.sh with a generated password, and returns connection
details for CloudStack to display once.
"""
import json
import logging
import secrets
import string
import time

import paramiko

from cs_api import CloudStackAPI

# A freshly deployed instance needs time before sshd answers (Debian cloud
# images: roughly 45-60s to first ssh banner). The retry lives HERE, on the
# server, so it survives whatever the browser does -- closing the dialog,
# navigating away or even a full page refresh -- instead of depending on a
# javascript timer in a component that may already be unmounted.
TRANSIENT_CONNECT_ERRORS = (
    "NoValidConnectionsError",
    "Unable to connect to port 22",
    "Connection refused",
    "No route to host",
    "Error reading SSH protocol banner",
    "No existing session",
    "No existing session",
    "timed out",
    "timeout",
)
PROVISION_ATTEMPTS = 3
PROVISION_RETRY_SLEEP_SECONDS = 15
# Wall-clock budget for the whole retry loop, derived from the timeout the
# management server actually runs us under (dbaas.provision.timeout, passed
# through config["provision_timeout_seconds"] by extension.py) -- hardcoding
# it here would drift the day an admin raises that setting. Worst
# per-attempt cost is ssh_connect_timeout_seconds (config.json) plus the
# engine's own internal wait (MongoDB's rotation marker: up to 120s); the
# loop checks the elapsed time before starting another attempt, so it always
# stops itself before the Java-side kill switch fires mid-provision.
DEFAULT_PROVISION_TIME_BUDGET_SECONDS = 400
BUDGET_RATIO = 2 / 3          # 400/600: leave the Java kill switch its margin
BUDGET_FLOOR_SECONDS = 60
BUDGET_CEILING_SECONDS = 480

# Template name -> provisioning script / port comes entirely from config.json's
# "engines" map (see config.example.json) — never hardcode it here. Adding a
# new engine means adding a config entry and dropping the script into every
# dbaas-* template's /opt/dbaas/, not touching this file.


def generate_password(length=24):
    # Alphanumeric only, avoids shell/quoting issues on the remote engine's
    # CREATE USER statement; length 24 is plenty of entropy for a DB password.
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


def detect_engine(payload, config):
    vm_details = payload.get("cloudstack.vm.details", {}) or {}
    template_name = (
        payload.get("externaldetails", {}).get("virtualmachine", {}).get("templatename")
        or vm_details.get("templatename")
    )
    engines = config.get("engines", {})
    return template_name, engines.get(template_name)


def extract_param(payload, name):
    # Custom action parameter values — field name/location not yet confirmed
    # against a live 4.22.1.1 payload. Check the logged raw payload and fix
    # this if needed; the common candidates are covered here first.
    for container_key in ("parameters", "customparameters", "externaldetails"):
        container = payload.get(container_key)
        if isinstance(container, dict) and name in container:
            return container[name]
    return payload.get(name)


def provisioning_time_budget(config):
    timeout = config.get("provision_timeout_seconds")
    try:
        budget = int(int(timeout) * BUDGET_RATIO)
    except (TypeError, ValueError):
        return DEFAULT_PROVISION_TIME_BUDGET_SECONDS
    return max(BUDGET_FLOOR_SECONDS, min(budget, BUDGET_CEILING_SECONDS))


def _failure_message(error, vm_ip):
    """Turns a provisioning exception into something a tenant can act on.

    A database that failed because the management server cannot reach the
    instance reads as "timed out" or "Unable to connect to port 22" -- true,
    but it looks like the instance is broken when the instance is fine and
    the path to it is not (an instance deployed onto a network the management
    server has no route to is the usual cause). Say which one it is, and say
    that the instance is still usable, because nothing else in the flow tells
    the user that the database can simply be created again later.
    """
    message = str(error)
    if any(t in message for t in TRANSIENT_CONNECT_ERRORS):
        return (
            f"the management server could not reach the instance at {vm_ip}:22 "
            f"({message}). The instance itself is running and was not deleted, "
            f"but its database was not created: check that its network is "
            f"reachable from the management server, then run Create Database "
            f"on the instance again."
        )
    return f"provisioning failed: {message}"


def run(payload, config):
    vm_id = payload.get("virtualmachineid")
    if not vm_id:
        return {"status": "failed", "message": "payload had no virtualmachineid"}

    db_name = extract_param(payload, "db_name")
    db_username = extract_param(payload, "db_username")
    if not db_name or not db_username:
        return {"status": "failed", "message": "db_name and db_username are required"}

    template_name, engine = detect_engine(payload, config)
    if not engine:
        return {
            "status": "failed",
            "message": f"could not determine DB engine from template (got {template_name!r}); "
                       f"was this VM deployed from one of {list(config.get('engines', {}))}?",
        }

    try:
        cs = CloudStackAPI(
            config["cloudstack_api_url"],
            config["cloudstack_api_key"],
            config["cloudstack_secret_key"],
            timeout=config.get("cloudstack_api_timeout_seconds", 15),
        )
        vm_ip = cs.get_vm_primary_ip(vm_id)
    except Exception as e:
        logging.exception("failed to resolve VM IP")
        return {"status": "failed", "message": f"could not resolve VM IP: {e}"}

    db_password = generate_password()

    provisioned = False
    last_error = None
    loop_start = time.monotonic()
    time_budget = provisioning_time_budget(config)
    for attempt in range(1, PROVISION_ATTEMPTS + 1):
        if attempt > 1 and time.monotonic() - loop_start > time_budget:
            logging.warning("provisioning budget (%ss) exhausted after %d attempts", time_budget, attempt - 1)
            break
        try:
            _run_provisioning_script(vm_ip, engine["script"], config, {
                "db_name": db_name,
                "db_user": db_username,
                "db_password": db_password,
            })
            provisioned = True
            break
        except Exception as e:
            last_error = e
            message = str(e)
            if attempt < PROVISION_ATTEMPTS and any(t in message for t in TRANSIENT_CONNECT_ERRORS):
                logging.warning("provisioning attempt %d/%d failed (transient): %s",
                                attempt, PROVISION_ATTEMPTS, message)
                time.sleep(PROVISION_RETRY_SLEEP_SECONDS)
                continue
            logging.exception("provisioning script failed")
            return {"status": "failed", "message": _failure_message(e, vm_ip)}
    if not provisioned:
        logging.exception("provisioning failed after %d attempts", PROVISION_ATTEMPTS)
        return {"status": "failed", "message": _failure_message(last_error, vm_ip)}

    # Best-effort tenant shell access, and deliberately gated: the login
    # password is only set when the caller asked for it (reset_vm_password,
    # sent by the first wizard deployment on a fresh instance). A later
    # createDatabase on the same VM must never rotate the OS password the
    # tenant may already be using -- the generated replacement would leave
    # them locked out with no way to learn it. vmaccess.sh also only exists
    # in templates built after it was added, and any vm-access failure must
    # never fail the database that provisioned successfully: on any problem
    # the vm_* fields are left out of the response and the warning is logged.
    vm_access = {}
    if str(extract_param(payload, "reset_vm_password") or "").lower() in ("true", "1"):
        vm_user = config.get("vm_ssh_user", "debian")
        vm_password = generate_password()
        try:
            _run_provisioning_script(vm_ip, "vmaccess.sh", config, {
                "vm_user": vm_user,
                "vm_password": vm_password,
            })
            vm_access = {"vm_username": vm_user, "vm_password": vm_password}
        except Exception as e:
            logging.warning("vm access setup skipped: %s", e)

    # Never log db_password — everything above/below this line must stay silent on it.
    connection_info = {
        "engine": template_name,
        "host": vm_ip,
        "port": engine["port"],
        "database": db_name,
        "username": db_username,
        "password": db_password,
    }
    connection_info.update(vm_access)
    return {
        "status": "success",
        "message": json.dumps(connection_info),
        "printmessage": "true",
    }


def _run_provisioning_script(vm_ip, script_name, config, db_payload):
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    # TODO: AutoAddPolicy trusts on first use — fine for freshly-deployed
    # ephemeral template VMs, but if that's not acceptable in your environment
    # pin known_hosts per template image instead.
    try:
        # paramiko caps the banner read and the auth exchange with their own
        # 15s defaults, so passing only `timeout` leaves both untouched — a VM
        # whose sshd is slow to answer still fails at 15s no matter how large
        # ssh_connect_timeout_seconds is. Give all three the same budget.
        connect_timeout = config.get("ssh_connect_timeout_seconds", 15)
        client.connect(
            hostname=vm_ip,
            username=config["ssh_user"],
            key_filename=config["ssh_private_key_path"],
            timeout=connect_timeout,
            banner_timeout=connect_timeout,
            auth_timeout=connect_timeout,
        )
        # authorized_keys on the VM forces this connection straight into
        # <dbaas_dir>/provision.sh regardless of what command we "request"
        # here, so exec_command's argument is effectively ignored server-side
        # — but we still pass it for clarity / local testing against a
        # non-forced host. dbaas_dir only needs changing for an image that
        # installs the scripts somewhere other than the default.
        dbaas_dir = config.get("dbaas_dir", "/opt/dbaas").rstrip("/")
        stdin, stdout, stderr = client.exec_command(f"{dbaas_dir}/provision.sh {script_name}")
        stdin.write(json.dumps(db_payload))
        stdin.channel.shutdown_write()
        exit_status = stdout.channel.recv_exit_status()
        err = stderr.read().decode("utf-8", "replace")
        if exit_status != 0:
            raise RuntimeError(f"provision.sh exited {exit_status}: {err}")
    finally:
        client.close()
