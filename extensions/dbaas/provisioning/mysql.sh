#!/usr/bin/env bash
# Runs INSIDE the dbaas-mysql VM as root (via provision.sh). Reads JSON on
# stdin: {"db_name": "...", "db_user": "...", "db_password": "..."}
set -euo pipefail

payload=$(cat)
db_name=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_name"])')
db_user=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_user"])')
db_password=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_password"])')

for val in "$db_name" "$db_user"; do
  if [[ ! "$val" =~ ^[A-Za-z][A-Za-z0-9_]{0,31}$ ]]; then
    echo "invalid identifier: $val" >&2
    exit 1
  fi
done

# Refuse outright if the user already exists — same class of bug fixed in
# postgresql.sh/mongodb.sh: CREATE USER IF NOT EXISTS silently skipped
# creation on a repeat request but still reported ok with a password that
# was never applied.
USER_EXISTS=$(mysql --protocol=socket -uroot -N -B -e \
  "SELECT COUNT(*) FROM mysql.user WHERE user='${db_user}' AND host='%'")
if [[ "$(echo "$USER_EXISTS" | tr -d '[:space:]')" != "0" ]]; then
  echo "user already exists: ${db_user}@%" >&2
  exit 1
fi

mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS \`${db_name}\`;
CREATE USER '${db_user}'@'%' IDENTIFIED BY '${db_password}';
GRANT ALL PRIVILEGES ON \`${db_name}\`.* TO '${db_user}'@'%';
FLUSH PRIVILEGES;
SQL

# Make sure mysqld is listening on the private interface, not left on
# 127.0.0.1-only (perimeter access control stays with CloudStack Security
# Groups / Network ACLs, not with this script).
#
# Only touch bind-address (and restart) the first time — repeat provisioning
# calls on a VM that already has other tenants' databases must not disrupt
# their live connections with an unnecessary mysqld restart.
#
# The file that carries bind-address differs per packaging (Debian's own
# mysql-server, Oracle's mysql-community-server, RHEL, Percona all disagree),
# so find the one that actually sets it instead of naming a path that is only
# right for one of them. No match means the build already listens on every
# interface — Oracle's package ships no bind-address line at all — and there
# is nothing to rewrite.
CONF_FILE=$(grep -rls '^bind-address' /etc/mysql /etc/my.cnf /etc/my.cnf.d 2>/dev/null | head -1)
if [[ -n "$CONF_FILE" ]]; then
  CURRENT_BIND=$(grep '^bind-address' "$CONF_FILE" | head -1)
  if [[ "$CURRENT_BIND" != *"0.0.0.0"* ]]; then
    sed -i 's/^bind-address.*/bind-address = 0.0.0.0/' "$CONF_FILE"
    # Whichever unit this packaging calls the server, restart the one that is
    # actually running rather than assuming the name.
    for unit in mysql mysqld mariadb; do
      if systemctl is-active --quiet "$unit"; then
        systemctl restart "$unit"
        break
      fi
    done
    for i in $(seq 1 10); do
      mysqladmin --protocol=socket -uroot ping >/dev/null 2>&1 && break
      sleep 1
    done
  fi
fi

# Don't trust exit code alone — verify the new credential actually
# authenticates, same discipline applied to postgresql.sh / mongodb.sh
# after their equivalent bugs.
#
# The password goes through MYSQL_PWD rather than -p: -p makes the client
# print "Using a password on the command line interface can be insecure" on
# stderr, which 2>&1 folds into VERIFY_OUTPUT and breaks the comparison below,
# and it would also expose the password in `ps` on the VM.
VERIFY_OUTPUT=$(MYSQL_PWD="${db_password}" mysql -h127.0.0.1 -u"${db_user}" "${db_name}" -N -B -e "SELECT 1" 2>&1) \
  || { echo "post-create login verification failed: ${VERIFY_OUTPUT}" >&2; exit 1; }

if [[ "$(echo "$VERIFY_OUTPUT" | tr -d '[:space:]')" != "1" ]]; then
  echo "post-create login verification did not return expected result: ${VERIFY_OUTPUT}" >&2
  exit 1
fi

echo "ok"
