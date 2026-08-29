#!/usr/bin/env bash
# Runs INSIDE the dbaas-mysql VM as root (via provision.sh). Reads JSON on
# stdin: {"db_name": "...", "db_user": "...", "db_password": "..."}
set -euo pipefail

payload=$(cat)
db_name=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_name"])')
db_user=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_user"])')
db_password=$(echo "$payload" | python3 -c 'import sys,json;print(json.load(sys.stdin)["db_password"])')

# Basic name validation — CloudStack-side input isn't necessarily sanitized
# by the time it reaches here, and these values get interpolated into SQL.
for val in "$db_name" "$db_user"; do
  if [[ ! "$val" =~ ^[A-Za-z][A-Za-z0-9_]{0,31}$ ]]; then
    echo "invalid identifier: $val" >&2
    exit 1
  fi
done

mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS \`${db_name}\`;
CREATE USER IF NOT EXISTS '${db_user}'@'%' IDENTIFIED BY '${db_password}';
GRANT ALL PRIVILEGES ON \`${db_name}\`.* TO '${db_user}'@'%';
FLUSH PRIVILEGES;
SQL

# Make sure mysqld is listening on the private interface, not left on
# 127.0.0.1-only (perimeter access control stays with CloudStack Security
# Groups / Network ACLs, not with this script).
if grep -q '^bind-address' /etc/mysql/mysql.conf.d/mysqld.cnf 2>/dev/null; then
  sed -i 's/^bind-address.*/bind-address = 0.0.0.0/' /etc/mysql/mysql.conf.d/mysqld.cnf
  systemctl restart mysql
fi

echo "ok"
