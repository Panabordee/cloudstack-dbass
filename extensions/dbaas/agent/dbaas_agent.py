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
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

STATE_DIR = "/var/lib/dbaas"
AGENT_CONF = os.path.join(STATE_DIR, "agent.json")
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
    fd = os.open(AGENT_CONF, os.O_WRONLY | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as handle:
        json.dump(conf, handle)


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
    # The server wraps the response in the command's name, like every other
    # CloudStack API call -- but a 2026-09-06 bug shipped it unwrapped for a
    # while and silently dropped every dispatched job. Fixed server-side; this
    # fallback only guards against talking to an unpatched management server.
    job = payload.get("getdbaasagentjobresponse") or (payload if "jobid" in payload else {})
    if not job:
        return None, conf
    # Token rotation: a fresh token replaces the old one on the next call.
    new_token = job.get("new_token")
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


def run_ddl_job(conf, job, role):
    # table_create / table_drop / column_add / column_drop / index_create /
    # index_drop all arrive the same way: the management server already built
    # the full statement server-side (DbaasConsoleJobCmdBase subclasses), so
    # the agent's only job is to run it as the owner role and report whether
    # it succeeded -- there is no result set to shape.
    engine = engine_name()
    payload = json.loads(job.get("payload", "{}"))
    statement = payload.get("statement", "")
    if not statement:
        return "failed", 0, False, "", "empty statement"
    if engine == "mongodb":
        return "failed", 0, False, "", "schema DDL is not offered on mongodb"
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
        return "confirmed", 0, False, json.dumps({"columns": [], "rows": []}), ""
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
