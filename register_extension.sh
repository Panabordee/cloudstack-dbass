#!/usr/bin/env bash
# Run once (per management server cluster) after copying this extension to
# /usr/share/cloudstack-management/extensions/dbaas/ and setting up config.json.
# Requires cloudmonkey configured with an admin profile: `cmk set profile <name>`.
set -euo pipefail

EXT_NAME="dbaas"
EXT_PATH="dbaas/extension.py"   # relative to the extensions root dir

# cmk's default output is a table; every parse below needs JSON.
cmkj() { command cmk -o json "$@"; }

echo "== creating extension =="
# type is mandatory and Extension.Type only defines Orchestrator in 4.22.
CREATE_OUT=$(cmkj createextension \
  name="${EXT_NAME}" \
  type=Orchestrator \
  path="${EXT_PATH}" \
  description="DBaaS provisioning for VMs built from the dbaas-* templates")
echo "$CREATE_OUT"

EXT_ID=$(echo "$CREATE_OUT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["extension"]["id"])')
echo "extension id: ${EXT_ID}"

echo "== adding create_database custom action =="
# enabled=true is not optional: addcustomaction defaults to disabled, and a
# disabled action never appears in the UI. `parameters` is a CloudStack map, so
# it has to be passed as parameters[i].key=value pairs — a JSON array string is
# rejected with "invalid value ... for parameter parameters".
cmkj addcustomaction \
  extensionid="${EXT_ID}" \
  name="create_database" \
  description="Provision a database + user on this VM" \
  resourcetype=VirtualMachine \
  allowedroletypes="Admin,ResourceAdmin,User" \
  enabled=true \
  timeout=120 \
  parameters[0].name=db_name parameters[0].type=STRING parameters[0].required=true \
  parameters[1].name=db_username parameters[1].type=STRING parameters[1].required=true \
  successmessage="Database created on {{resourceName}}: {{message}}" \
  errormessage="Failed to create database on {{resourceName}}: {{message}}"

echo "== registering the extension on the clusters that host dbaas VMs =="
# CloudStack resolves a VM's extension through its cluster
# (ExtensionHelper.getExtensionForCluster), so the action only shows up on VMs
# whose cluster is registered here.
for CLUSTER_ID in $(cmkj list clusters | python3 -c \
    'import sys,json;[print(c["id"]) for c in json.load(sys.stdin).get("cluster",[])]'); do
  echo "  cluster ${CLUSTER_ID}"
  cmkj registerextension extensionid="${EXT_ID}" resourceid="${CLUSTER_ID}" resourcetype=Cluster
done

echo "done. Deploy a test VM from a dbaas-* template, then trigger 'Create Database' from its action menu."
