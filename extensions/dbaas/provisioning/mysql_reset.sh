#!/usr/bin/env bash
# Runs INSIDE the dbaas-mysql VM as root (via firstboot.sh, which pipes the config-drive request into stdin). Reads JSON on
# stdin: {"db_user": "...", "db_password": "..."}
#
# Resets an existing user's password. Unlike mysql.sh this must find the user
# already there: creating one here would silently hand out access to a name the
# caller only meant to rotate.
set -euo pipefail

payload=$(cat)
db_user=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_user"])')
db_password=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_password"])')

if [[ ! "$db_user" =~ ^[A-Za-z][A-Za-z0-9_]{0,31}$ ]]; then
  echo "invalid identifier: $db_user" >&2
  exit 1
fi

USER_EXISTS=$(mysql --protocol=socket -uroot -N -B -e \
  "SELECT COUNT(*) FROM mysql.user WHERE user='${db_user}' AND host='%'")
if [[ "$(echo "$USER_EXISTS" | tr -d '[:space:]')" == "0" ]]; then
  echo "user does not exist: ${db_user}@%" >&2
  exit 1
fi

mysql --protocol=socket -uroot <<SQL
ALTER USER '${db_user}'@'%' IDENTIFIED BY '${db_password}';
FLUSH PRIVILEGES;
SQL

# Same discipline as mysql.sh: prove the new credential authenticates before
# reporting success, and keep the password out of the process list by going
# through MYSQL_PWD rather than -p.
VERIFY_OUTPUT=$(MYSQL_PWD="${db_password}" mysql -h127.0.0.1 -u"${db_user}" -N -B -e "SELECT 1" 2>&1) \
  || { echo "post-reset login verification failed: ${VERIFY_OUTPUT}" >&2; exit 1; }

if [[ "$(echo "$VERIFY_OUTPUT" | tr -d '[:space:]')" != "1" ]]; then
  echo "post-reset login verification did not return expected result: ${VERIFY_OUTPUT}" >&2
  exit 1
fi

echo "ok"
