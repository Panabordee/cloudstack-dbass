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
    data = urllib.parse.urlencode(fields).encode()
    url = api_url + "?" + urllib.parse.urlencode({"command": fields["command"], "response": "json"})
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
    job = payload.get("getdbaasagentjobresponse", {})
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
    client = pymongo.MongoClient("mongodb://127.0.0.1:27017/", serverSelectionTimeoutMS=5000)
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


def run_sql_job(conf, job, role):
    engine = engine_name()
    payload = json.loads(job.get("payload", "{}"))
    sql = payload.get("sql", "")
    write = payload.get("write", False)
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
        columns = [column[0] for column in cursor.description]
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
                encoded = json.dumps([None if value is None else str(value) for value in row])
                bytes_used += len(encoded)
                rows.append([None if value is None else str(value) for value in row])
            if truncated:
                break
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


def execute(conf, job, role):
    job_type = job.get("type", "")
    if job_type == "sql":
        return run_sql_job(conf, job, role)
    return "failed", 0, False, "", "job type %s is not implemented on this engine" % job_type


def main():
    conf = load_conf()
    with open(ROLES_FILE) as handle:
        roles = json.load(handle)
    if "database" not in conf:
        conf["database"] = roles.get("database", "")
        save_conf(conf)
    log("agent starting for VM " + conf["vm_id"])
    while True:
        job, conf = long_poll(conf)
        if not job:
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
