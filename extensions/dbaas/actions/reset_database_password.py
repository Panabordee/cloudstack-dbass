"""
Handler for the "reset_password" custom action.

Rotates the password of a database user that already exists on a VM built from
one of the dbaas-* templates. Looks up the VM's IP, SSHes in with the same
forced-command key create_database uses, runs the matching
provisioning/<engine>_reset.sh, and returns the new password for CloudStack to
display once.
"""
import json
import logging

from cs_api import CloudStackAPI

# The SSH plumbing and the password generator are the ones create_database has
# been exercised with; reimplementing either here would only add a second thing
# that can be wrong.
from actions.create_database import (
    generate_password,
    _run_provisioning_script,
)

# Engine detection stays keyed on the template name, exactly as it is for
# create_database, and comes from the same config.json "engines" map — so a
# VM only ever gets the reset script for its own engine, and adding an engine
# never touches this file.
def detect_engine(payload, config):
    vm_details = payload.get("cloudstack.vm.details", {}) or {}
    template_name = (
        payload.get("externaldetails", {}).get("virtualmachine", {}).get("templatename")
        or vm_details.get("templatename")
    )
    entry = config.get("engines", {}).get(template_name)
    if not entry:
        return template_name, None
    return template_name, {"script": entry["reset_script"], "port": entry["port"]}


def extract_param(payload, name):
    for container_key in ("parameters", "customparameters", "externaldetails"):
        container = payload.get(container_key)
        if isinstance(container, dict) and name in container:
            return container[name]
    return payload.get(name)


def run(payload, config):
    vm_id = payload.get("virtualmachineid")
    if not vm_id:
        return {"status": "failed", "message": "payload had no virtualmachineid"}

    db_username = extract_param(payload, "db_username")
    if not db_username:
        return {"status": "failed", "message": "db_username is required"}

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
        # The reset scripts refuse a user that is not already there, so a typo
        # in the name fails loudly instead of quietly creating an account.
        _run_provisioning_script(vm_ip, engine["script"], config, {
            "db_user": db_username,
            "db_password": db_password,
        })
    except Exception as e:
        logging.exception("reset script failed")
        return {"status": "failed", "message": f"password reset failed: {e}"}

    # Never log db_password — everything above/below this line must stay silent on it.
    connection_info = {
        "engine": template_name,
        "host": vm_ip,
        "port": engine["port"],
        "username": db_username,
        "password": db_password,
    }
    return {
        "status": "success",
        "message": json.dumps(connection_info),
        "printmessage": "true",
    }
