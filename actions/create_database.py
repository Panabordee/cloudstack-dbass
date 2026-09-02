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

import paramiko

from cs_api import CloudStackAPI

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

    try:
        _run_provisioning_script(vm_ip, engine["script"], config, {
            "db_name": db_name,
            "db_user": db_username,
            "db_password": db_password,
        })
    except Exception as e:
        logging.exception("provisioning script failed")
        return {"status": "failed", "message": f"provisioning failed: {e}"}

    # Never log db_password — everything above/below this line must stay silent on it.
    connection_info = {
        "engine": template_name,
        "host": vm_ip,
        "port": engine["port"],
        "database": db_name,
        "username": db_username,
        "password": db_password,
    }
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
        # /opt/dbaas/provision.sh regardless of what command we "request" here,
        # so exec_command's argument is effectively ignored server-side — but
        # we still pass it for clarity / local testing against a non-forced host.
        stdin, stdout, stderr = client.exec_command(f"/opt/dbaas/provision.sh {script_name}")
        stdin.write(json.dumps(db_payload))
        stdin.channel.shutdown_write()
        exit_status = stdout.channel.recv_exit_status()
        err = stderr.read().decode("utf-8", "replace")
        if exit_status != 0:
            raise RuntimeError(f"provision.sh exited {exit_status}: {err}")
    finally:
        client.close()
