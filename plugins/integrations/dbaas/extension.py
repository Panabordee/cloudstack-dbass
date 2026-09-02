#!/usr/bin/env python3
"""
CloudStack Extensions Framework entrypoint.

CloudStack invokes this exactly as:
    extension.py <action> <payload_file> <timeout_seconds>

and expects a single JSON object printed to stdout as the response.
Nothing else should go to stdout — use the log file for debugging.
"""
import json
import os
import sys
import logging

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")


def load_config():
    with open(CONFIG_PATH) as f:
        return json.load(f)


def setup_logging(log_file):
    logging.basicConfig(
        filename=log_file,
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )


def fail(message):
    print(json.dumps({"status": "failed", "message": message}))
    sys.exit(0)  # CloudStack reads the JSON body for success/failure, not exit code


def main():
    if len(sys.argv) < 3:
        fail("extension.py invoked with too few arguments (expected: action payload_file timeout)")

    action_name = sys.argv[1]
    payload_file = sys.argv[2]
    # sys.argv[3] is the timeout in seconds if you need to self-enforce it

    try:
        config = load_config()
    except Exception as e:
        fail(f"could not load config.json: {e}")
        return

    setup_logging(config.get("log_file", "/tmp/dbaas-extension.log"))

    try:
        with open(payload_file) as f:
            payload = json.load(f)
    except Exception as e:
        logging.exception("failed to read payload file")
        fail(f"could not read payload file: {e}")
        return

    # Log the raw payload once so you can confirm real field names for your
    # CloudStack version before trusting the parser in actions/*.py.
    logging.info("action=%s payload=%s", action_name, json.dumps(payload))

    if action_name == "create_database":
        from actions.create_database import run as run_create_database
        result = run_create_database(payload, config)
    elif action_name == "reset_password":
        from actions.reset_database_password import run as run_reset_password
        result = run_reset_password(payload, config)
    else:
        result = {"status": "failed", "message": f"unknown action: {action_name}"}

    print(json.dumps(result))


if __name__ == "__main__":
    main()
