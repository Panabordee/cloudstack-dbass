#!/usr/bin/env python3
# The DBaaS console agent. Ships in the template at /opt/dbaas/agent/,
# runs as a systemd service (dbaas-agent.service, Restart=always).
#
# Transport (PLAN-DBAAS-CONSOLE.md section 1): the agent polls the management
# server with a long-poll; the management server never dials in. One job at a
# time per instance; a job that arrives is executed against the local engine
# over the local socket with the credential the job's db_role selects from
# /var/lib/dbaas/roles.json (0600) -- the agent never receives a password over
# the wire. Results are capped (rows, bytes), reported once, and never logged.
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

STATE_DIR = "/var/lib/dbaas"
AGENT_CONF = os.path.join(STATE_DIR, "agent.json")
# MASTER-PLAN item C2 (2026-09-09): where table_drop's pre-drop dump lands,
# and how many to keep. This is the cheap alternative to full backup/PITR --
# it covers a console mis-click, not a rewind to an arbitrary point in time.
PREDROP_DIR = os.path.join(STATE_DIR, "predrop")
PREDROP_KEEP = 20
ROLES_FILE = os.path.join(STATE_DIR, "roles.json")
ENGINE_FILE = "/opt/dbaas/engine"
POLL_HOLD_DEFAULT = 25
REPORT_TRIES = 3

# Same shape DbaasManagerImpl.validateIdentifier enforces server-side before a
# job carrying a table/column name is ever created. Checked again here as the
# last line before the value is interpolated into a quoted SQL identifier --
# defense in depth, not the primary guard.
IDENTIFIER_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,31}$")


def log(message):
    print("[dbaas-agent] " + message, flush=True)


def load_conf():
    with open(AGENT_CONF) as handle:
        return json.load(handle)


def save_conf(conf):
    # MASTER-PLAN item E2 (2026-09-09): the previous O_TRUNC-in-place write
    # left a truncated or empty agent.json if the guest lost power or was
    # force-stopped mid-write -- not just the last rotated token, the whole
    # config, and force-stopping a VM is a routine operator action, not a
    # rare fault. Write to a temp file in the same directory (so the final
    # rename is on the same filesystem and therefore atomic), fsync it, then
    # os.replace() over the real path -- a reader always sees either the old
    # complete file or the new complete file, never a partial one.
    tmp_path = AGENT_CONF + ".tmp"
    fd = os.open(tmp_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as handle:
        json.dump(conf, handle)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(tmp_path, AGENT_CONF)


def api_post(api_url, fields, hold=None):
    # command/response go in the query string only -- sending them again in the
    # POST body makes CloudStack reject the poll with
    # "Query parameter 'command' has multiple values".
    query = {"command": fields["command"], "response": "json"}
    body = {k: v for k, v in fields.items() if k not in ("command", "response")}
    data = urllib.parse.urlencode(body).encode()
    url = api_url + "?" + urllib.parse.urlencode(query)
    request = urllib.request.Request(url, data=data)
    try:
        with urllib.request.urlopen(request, timeout=(hold or 10) + 10) as response:
            body = response.read().decode()
            return response.status, body
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()
    except Exception as error:
        log("api call failed: " + repr(error))
        return 0, ""


def long_poll(conf):
    status, body = api_post(conf["api_url"], {
        "command": "getDbaasAgentJob",
        "response": "json",
        "vmid": conf["vm_id"],
        "token": conf["token"],
    }, hold=conf.get("longpoll", POLL_HOLD_DEFAULT))
    if status != 200 or not body:
        return None, conf
    try:
        payload = json.loads(body)
    except ValueError:
        return None, conf
    # Token rotation now also arrives with no job attached (an idle agent
    # still rotates on schedule -- the server returns {"new_token": ...} for
    # an otherwise empty hold), so read it before deciding there is no job.
    new_token = payload.get("new_token")
    # The server wraps the response in the command's name, like every other
    # CloudStack API call -- but a 2026-09-06 bug shipped it unwrapped for a
    # while and silently dropped every dispatched job. Fixed server-side; this
    # fallback only guards against talking to an unpatched management server.
    job = payload.get("getdbaasagentjobresponse") or (payload if "jobid" in payload else {})
    if not job:
        if new_token:
            conf["token"] = new_token
            save_conf(conf)
            log("agent token rotated")
        return None, conf
    # Token rotation: a fresh token replaces the old one on the next call.
    new_token = job.get("new_token") or new_token
    if new_token:
        conf["token"] = new_token
        save_conf(conf)
        log("agent token rotated")
    return job, conf


def report(conf, job_uuid, status, row_count, truncated, result, error):
    fields = {
        "command": "reportDbaasJobResult",
        "response": "json",
        "vmid": conf["vm_id"],
        "token": conf["token"],
        "jobid": job_uuid,
        "status": status,
        "rowcount": str(max(row_count, 0)),
        "truncated": "true" if truncated else "false",
        "result": result or "",
        "error": (error or "")[:1000],
    }
    for attempt in range(1, REPORT_TRIES + 1):
        code, _ = api_post(conf["api_url"], fields)
        if code == 200:
            return True
        log("report attempt %d/%d failed (HTTP %s)" % (attempt, REPORT_TRIES, code))
        time.sleep(attempt * 2)
    return False


def connect_mysql(role, database):
    import pymysql
    return pymysql.connect(host="127.0.0.1", user=role["user"], password=role["password"],
                           database=database, charset="utf8mb4",
                           cursorclass=pymysql.cursors.Cursor)


def connect_postgresql(role, database):
    import psycopg2
    return psycopg2.connect(host="127.0.0.1", user=role["user"], password=role["password"],
                            dbname=database, connect_timeout=5)


def connect_mongodb(role):
    import pymongo
    # mongod runs with auth enabled; the tenant users live in the tenant
    # database (mongodb.sh creates them with getSiblingDB(db_name)), so the
    # database is also the authSource. Connecting unauthenticated fails every
    # job with "Command ... requires authentication" -- observed on template
    # 213 right after provisioning reached confirmed (2026-09-09).
    client = pymongo.MongoClient(
        "mongodb://127.0.0.1:27017/",
        username=role.get("user") or None,
        password=role.get("password") or None,
        authSource=role.get("database") or None,
        serverSelectionTimeoutMS=5000)
    database = client[role["database"]]
    return client, database


def engine_name():
    with open(ENGINE_FILE) as handle:
        return handle.read().strip().removesuffix(".sh")


def sql_statement_timeout(conn, engine, seconds):
    cursor = conn.cursor()
    if engine == "mysql":
        cursor.execute("SET SESSION MAX_EXECUTION_TIME=%d" % (seconds * 1000))
    elif engine == "mariadb":
        cursor.execute("SET SESSION max_statement_time=%d" % seconds)
    elif engine == "postgresql":
        cursor.execute("SET statement_timeout = '%ds'" % seconds)
    conn.commit()
    cursor.close()


def quote_identifier(engine, name):
    # Mirrors DbaasConsoleJobCmdBase.quoteIdentifier server-side: double
    # quotes for PostgreSQL, backticks for MySQL/MariaDB. The name reaching
    # here was already checked against IDENTIFIER_RE by the caller.
    if engine == "postgresql":
        return '"' + name + '"'
    return "`" + name + "`"


def rows_from_cursor(cursor, row_limit, bytes_limit):
    # Shared by run_sql_job and run_table_preview_job: fetch in batches,
    # stringify every value (the wire format is JSON text, not typed SQL),
    # and stop at whichever cap is hit first.
    columns = [column[0] for column in cursor.description] if cursor.description else []
    rows = []
    truncated = False
    bytes_used = 0
    while True:
        batch = cursor.fetchmany(200)
        if not batch:
            break
        for row in batch:
            if len(rows) >= row_limit or bytes_used > bytes_limit:
                truncated = True
                break
            stringified = [None if value is None else str(value) for value in row]
            bytes_used += len(json.dumps(stringified))
            rows.append(stringified)
        if truncated:
            break
    return columns, rows, truncated


def run_sql_job(conf, job, role):
    engine = engine_name()
    payload = json.loads(job.get("payload", "{}"))
    sql = payload.get("sql", "")
    row_limit = job.get("row_limit", 1000)
    bytes_limit = job.get("bytes_limit", 1048576)
    timeout = job.get("timeout_seconds", 30)
    conn = None
    try:
        if engine in ("mysql", "mariadb"):
            conn = connect_mysql(role, conf["database"])
            sql_statement_timeout(conn, engine, timeout)
        elif engine == "postgresql":
            conn = connect_postgresql(role, conf["database"])
            sql_statement_timeout(conn, engine, timeout)
        else:
            return "failed", 0, False, "", "free-form query is not offered on this engine"
        cursor = conn.cursor()
        cursor.execute(sql)
        if cursor.description is None:
            conn.commit()
            return "confirmed", cursor.rowcount, False, json.dumps({"columns": [], "rows": []}), ""
        columns, rows, truncated = rows_from_cursor(cursor, row_limit, bytes_limit)
        conn.commit()
        result = json.dumps({"columns": columns, "rows": rows})
        return "confirmed", len(rows), truncated, result, ""
    except Exception as error:
        try:
            conn.rollback()
        except Exception:
            pass
        return "failed", 0, False, "", str(error)[:1000]
    finally:
        if conn is not None:
            conn.close()


def dump_table_before_drop(engine, role, database, table):
    # Best-effort has no place here: a failed dump must refuse the drop, so
    # every failure path below removes whatever partial file it left and
    # returns None -- the caller treats None as "do not drop".
    os.makedirs(PREDROP_DIR, exist_ok=True)
    os.chmod(PREDROP_DIR, 0o700)
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    dump_path = os.path.join(PREDROP_DIR, "%s.%s.sql" % (table, stamp))
    env = dict(os.environ)
    try:
        if engine in ("mysql", "mariadb"):
            env["MYSQL_PWD"] = role["password"]
            cmd = ["mysqldump", "--single-transaction", "--no-tablespaces",
                   "-h", "127.0.0.1", "-u", role["user"], database, table]
        elif engine == "postgresql":
            env["PGPASSWORD"] = role["password"]
            cmd = ["pg_dump", "-h", "127.0.0.1", "-U", role["user"],
                   "-d", database, "-t", table]
        else:
            # mongodb never reaches here -- run_ddl_job refuses it earlier --
            # this branch exists only so a future engine addition fails loud
            # instead of silently skipping the dump.
            return None, "no pre-drop dump support for engine %s" % engine
        with open(dump_path, "wb") as handle:
            proc = subprocess.run(cmd, stdout=handle, stderr=subprocess.PIPE, env=env, timeout=120)
        if proc.returncode != 0:
            _remove_quietly(dump_path)
            return None, "dump command failed (rc=%d): %s" % (
                proc.returncode, proc.stderr.decode("utf-8", "replace")[:500])
        if os.path.getsize(dump_path) == 0:
            _remove_quietly(dump_path)
            return None, "dump command produced an empty file"
        os.chmod(dump_path, 0o600)
        _prune_old_dumps()
        return dump_path, ""
    except Exception as error:
        _remove_quietly(dump_path)
        return None, str(error)[:500]


def _remove_quietly(path):
    try:
        os.remove(path)
    except OSError:
        pass


def _prune_old_dumps():
    # Best-effort and non-fatal: a pruning failure must never block a drop
    # that already dumped successfully.
    try:
        files = sorted(
            (os.path.join(PREDROP_DIR, f) for f in os.listdir(PREDROP_DIR)),
            key=os.path.getmtime,
        )
        for stale in files[:-PREDROP_KEEP]:
            _remove_quietly(stale)
    except Exception:
        pass


def run_ddl_job(conf, job, role):
    # table_create / table_drop / column_add / column_drop / index_create /
    # index_drop all arrive the same way: the management server already built
    # the full statement server-side (DbaasConsoleJobCmdBase subclasses), so
    # the agent's only job is to run it as the owner role and report whether
    # it succeeded -- there is no result set to shape. table_drop is the one
    # exception: it dumps the table first (MASTER-PLAN item C2) and refuses
    # the DROP outright if that dump did not succeed.
    engine = engine_name()
    payload = json.loads(job.get("payload", "{}"))
    statement = payload.get("statement", "")
    if not statement:
        return "failed", 0, False, "", "empty statement"
    if engine == "mongodb":
        return "failed", 0, False, "", "schema DDL is not offered on mongodb"

    dump_path = None
    if job.get("type") == "table_drop":
        table = payload.get("table", "")
        if not table:
            return "failed", 0, False, "", "drop refused: no table name in the job payload to dump first"
        dump_path, dump_error = dump_table_before_drop(engine, role, conf["database"], table)
        if dump_path is None:
            return "failed", 0, False, "", "drop refused: pre-drop dump failed (%s)" % dump_error

    conn = None
    try:
        if engine in ("mysql", "mariadb"):
            conn = connect_mysql(role, conf["database"])
        elif engine == "postgresql":
            conn = connect_postgresql(role, conf["database"])
        else:
            return "failed", 0, False, "", "unsupported engine"
        cursor = conn.cursor()
        cursor.execute(statement)
        conn.commit()
        result = {"columns": [], "rows": []}
        if dump_path:
            result["predrop_dump"] = dump_path
        return "confirmed", 0, False, json.dumps(result), ""
    except Exception as error:
        try:
            conn.rollback()
        except Exception:
            pass
        return "failed", 0, False, "", str(error)[:1000]
    finally:
        if conn is not None:
            conn.close()


def run_table_list_job(conf, job, role):
    engine = engine_name()
    row_limit = job.get("row_limit", 1000)
    conn = None
    client = None
    try:
        if engine in ("mysql", "mariadb"):
            conn = connect_mysql(role, conf["database"])
            cursor = conn.cursor()
            cursor.execute(
                "SELECT table_name FROM information_schema.tables"
                " WHERE table_schema = %s ORDER BY table_name", (conf["database"],))
            tables = [row[0] for row in cursor.fetchall()]
        elif engine == "postgresql":
            conn = connect_postgresql(role, conf["database"])
            cursor = conn.cursor()
            cursor.execute(
                "SELECT table_name FROM information_schema.tables"
                " WHERE table_schema = 'public' ORDER BY table_name")
            tables = [row[0] for row in cursor.fetchall()]
        elif engine == "mongodb":
            client, database = connect_mongodb(role)
            tables = sorted(database.list_collection_names())
        else:
            return "failed", 0, False, "", "unsupported engine"
        truncated = len(tables) > row_limit
        tables = tables[:row_limit]
        return "confirmed", len(tables), truncated, json.dumps({"tables": tables}), ""
    except Exception as error:
        return "failed", 0, False, "", str(error)[:1000]
    finally:
        if conn is not None:
            conn.close()
        if client is not None:
            client.close()


def run_table_describe_job(conf, job, role):
    engine = engine_name()
    payload = json.loads(job.get("payload", "{}"))
    table = payload.get("table", "")
    if not IDENTIFIER_RE.match(table):
        return "failed", 0, False, "", "invalid table name"
    conn = None
    client = None
    try:
        if engine in ("mysql", "mariadb"):
            conn = connect_mysql(role, conf["database"])
            cursor = conn.cursor()
            cursor.execute(
                "SELECT column_name, column_type, is_nullable, column_key"
                " FROM information_schema.columns WHERE table_schema = %s AND table_name = %s"
                " ORDER BY ordinal_position", (conf["database"], table))
            columns = [{"name": r[0], "type": r[1], "nullable": r[2], "key": r[3]} for r in cursor.fetchall()]
            cursor.execute(
                "SELECT index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')"
                " FROM information_schema.statistics WHERE table_schema = %s AND table_name = %s"
                " GROUP BY index_name, non_unique", (conf["database"], table))
            indexes = [{"name": r[0], "type": r[2], "nullable": "", "key": "" if r[1] else "UNIQUE"}
                       for r in cursor.fetchall()]
        elif engine == "postgresql":
            conn = connect_postgresql(role, conf["database"])
            cursor = conn.cursor()
            cursor.execute(
                "SELECT column_name, data_type, is_nullable,"
                " CASE WHEN EXISTS (SELECT 1 FROM information_schema.table_constraints tc"
                "   JOIN information_schema.key_column_usage k ON k.constraint_name = tc.constraint_name"
                "   WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = c.table_name"
                "     AND k.column_name = c.column_name)"
                " THEN 'PRI' ELSE '' END"
                " FROM information_schema.columns c WHERE table_schema = 'public' AND table_name = %s"
                " ORDER BY ordinal_position", (table,))
            columns = [{"name": r[0], "type": r[1], "nullable": r[2], "key": r[3]} for r in cursor.fetchall()]
            cursor.execute(
                "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = %s",
                (table,))
            indexes = [{"name": r[0], "type": r[1], "nullable": "",
                        "key": "UNIQUE" if "UNIQUE INDEX" in r[1].upper() else ""}
                       for r in cursor.fetchall()]
        elif engine == "mongodb":
            client, database = connect_mongodb(role)
            sample = database[table].find_one() or {}
            columns = [{"name": k, "type": type(v).__name__, "nullable": "", "key": "PRI" if k == "_id" else ""}
                       for k, v in sample.items()]
            indexes = [{"name": idx.get("name", ""), "type": str(idx.get("key", "")), "nullable": "",
                        "key": "UNIQUE" if idx.get("unique") else ""}
                       for idx in database[table].list_indexes()]
        else:
            return "failed", 0, False, "", "unsupported engine"
        result = json.dumps({"columns": columns, "indexes": indexes})
        return "confirmed", len(columns), False, result, ""
    except Exception as error:
        return "failed", 0, False, "", str(error)[:1000]
    finally:
        if conn is not None:
            conn.close()
        if client is not None:
            client.close()


def run_table_preview_job(conf, job, role):
    engine = engine_name()
    payload = json.loads(job.get("payload", "{}"))
    table = payload.get("table", "")
    if not IDENTIFIER_RE.match(table):
        return "failed", 0, False, "", "invalid table name"
    limit = int(payload.get("limit", 100))
    offset = int(payload.get("offset", 0))
    row_limit = job.get("row_limit", 1000)
    bytes_limit = job.get("bytes_limit", 1048576)
    conn = None
    client = None
    try:
        if engine in ("mysql", "mariadb", "postgresql"):
            if engine in ("mysql", "mariadb"):
                conn = connect_mysql(role, conf["database"])
            else:
                conn = connect_postgresql(role, conf["database"])
            cursor = conn.cursor()
            cursor.execute("SELECT * FROM %s LIMIT %%s OFFSET %%s" % quote_identifier(engine, table),
                           (min(limit, row_limit), offset))
            columns, rows, truncated = rows_from_cursor(cursor, min(limit, row_limit), bytes_limit)
            conn.commit()
            return "confirmed", len(rows), truncated, json.dumps({"columns": columns, "rows": rows}), ""
        elif engine == "mongodb":
            client, database = connect_mongodb(role)
            cursor = database[table].find().skip(offset).limit(min(limit, row_limit))
            docs = list(cursor)
            columns = sorted({key for doc in docs for key in doc.keys()})
            rows = [[str(doc.get(c, "")) for c in columns] for doc in docs]
            truncated = len(docs) >= min(limit, row_limit)
            return "confirmed", len(rows), truncated, json.dumps({"columns": columns, "rows": rows}), ""
        else:
            return "failed", 0, False, "", "unsupported engine"
    except Exception as error:
        try:
            if conn is not None:
                conn.rollback()
        except Exception:
            pass
        return "failed", 0, False, "", str(error)[:1000]
    finally:
        if conn is not None:
            conn.close()
        if client is not None:
            client.close()


# Job type -> handler. table_create/table_drop/column_add/column_drop/
# index_create/index_drop all carry a pre-built `statement` and share
# run_ddl_job; the three read types build their own catalogue queries.
# MASTER-PLAN item C (2026-09-09): where the provisioning scripts live on
# the guest, and the resulting <engine>_reset.sh path -- same directory
# firstboot.sh uses (RUNBOOK-PATCH-TEMPLATES-2026-09-05.md's file table).
RESET_SCRIPT_DIR = "/opt/dbaas"


def run_password_reset_job(conf, job, role):
    # Unlike the SQL/DDL jobs, this does not go through the Python DB driver
    # with a role's own credentials -- <engine>_reset.sh runs as root (the
    # agent's own privilege level) and authenticates to the engine as its
    # administrative user, the same as firstboot.sh's first-boot path, because
    # only an admin connection can ALTER another user's password. `role` is
    # accepted for JOB_HANDLERS' uniform (conf, job, role) signature but is
    # not used here.
    payload = json.loads(job.get("payload", "{}"))
    db_user = payload.get("db_user", "")
    db_password = payload.get("db_password", "")
    if not db_user or not db_password:
        return "failed", 0, False, "", "password_reset job missing db_user or db_password"
    engine = engine_name()
    script = os.path.join(RESET_SCRIPT_DIR, "%s_reset.sh" % engine)
    if not os.path.isfile(script):
        return "failed", 0, False, "", "no reset script installed for engine %s" % engine
    stdin_payload = json.dumps({"db_user": db_user, "db_password": db_password}).encode("utf-8")
    try:
        proc = subprocess.run(["bash", script], input=stdin_payload,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    except Exception as error:
        return "failed", 0, False, "", "reset script did not run: %s" % str(error)[:500]
    if proc.returncode != 0:
        return "failed", 0, False, "", "reset script failed (rc=%d): %s" % (
            proc.returncode, proc.stderr.decode("utf-8", "replace")[:500])
    return "confirmed", 0, False, json.dumps({"columns": [], "rows": []}), ""


JOB_HANDLERS = {
    "sql": run_sql_job,
    "table_list": run_table_list_job,
    "table_describe": run_table_describe_job,
    "table_preview": run_table_preview_job,
    "table_create": run_ddl_job,
    "table_drop": run_ddl_job,
    "column_add": run_ddl_job,
    "column_drop": run_ddl_job,
    "index_create": run_ddl_job,
    "index_drop": run_ddl_job,
    "password_reset": run_password_reset_job,
}


def execute(conf, job, role):
    job_type = job.get("type", "")
    handler = JOB_HANDLERS.get(job_type)
    if handler is None:
        return "failed", 0, False, "", "job type %s is not implemented on this engine" % job_type
    return handler(conf, job, role)


def main():
    conf = load_conf()
    with open(ROLES_FILE) as handle:
        roles = json.load(handle)
    if "database" not in conf:
        conf["database"] = roles.get("database", "")
        save_conf(conf)
    log("agent starting for VM " + conf["vm_id"])
    while True:
        started = time.time()
        job, conf = long_poll(conf)
        if not job:
            # A long poll is supposed to hold the connection for ~POLL_HOLD
            # seconds, so an immediate return means the call failed -- refused,
            # rate limited (429), server restarting. Retrying instantly would
            # hammer the API and keep any per-IP rate limiter hot, and the
            # limiter then 429s this guest's own provisioning report too
            # (observed 2026-09-08: a 429 storm made a confirmed credential
            # unreportable). Pace the retry to the same cadence as a held
            # poll that returned nothing.
            time.sleep(max(1.0, POLL_HOLD_DEFAULT - (time.time() - started)))
            continue
        job_uuid = job.get("jobid", "")
        job_type = job.get("type", "")
        log("job %s (%s) dispatched as %s" % (job_uuid, job_type, job.get("db_role")))
        role = roles.get("owner", {})
        if job.get("db_role") == "readonly":
            role = roles.get("readonly", role)
        role = dict(role)
        role["database"] = conf.get("database", "")
        if not role.get("user"):
            report(conf, job_uuid, "failed", 0, False, "", "no credential for role " + job.get("db_role", ""))
            continue
        status, row_count, truncated, result, error = execute(conf, job, role)
        delivered = report(conf, job_uuid, status, row_count, truncated, result, error)
        log("job %s %s (report %s)" % (job_uuid, status, "delivered" if delivered else "PENDING-RETRY"))


if __name__ == "__main__":
    main()
