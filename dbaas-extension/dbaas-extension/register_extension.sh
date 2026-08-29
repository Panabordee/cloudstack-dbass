#!/usr/bin/env bash
# Run once (per management server cluster) after copying this extension to
# /usr/share/cloudstack-management/extensions/dbaas/ and setting up config.json.
# Requires cloudmonkey configured with an admin profile: `cmk set profile <name>`.
set -euo pipefail

EXT_NAME="dbaas"
EXT_PATH="dbaas/extension.py"   # relative to the extensions root dir

echo "== creating extension =="
CREATE_OUT=$(cmk createextension name="${EXT_NAME}" path="${EXT_PATH}")
echo "$CREATE_OUT"

EXT_ID=$(echo "$CREATE_OUT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["extension"]["id"])')
echo "extension id: ${EXT_ID}"

echo "== adding create_database custom action =="
cmk addcustomaction \
  extensionid="${EXT_ID}" \
  name="create_database" \
  description="Provision a database + user on this VM" \
  allowedroletypes="Admin,ResourceAdmin,User" \
  timeout=120 \
  parameters='[{"name":"db_name","type":"STRING","required":true},{"name":"db_username","type":"STRING","required":true}]' \
  successmessage="Database created on {{resourceName}}: {{message}}" \
  errormessage="Failed to create database on {{resourceName}}: {{message}}"

echo "done. Deploy a test VM from a dbaas-* template, then trigger 'Create Database' from its action menu."
