"""Dedicated PostgreSQL + HTTPS acceptance of Flowable/business atomic rollback.

Requires psycopg2, Chrome and installed frontend dependencies. Secrets are read
from a caller-owned private JSON connection file and environment, never logged.
"""
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import uuid

import psycopg2
from psycopg2 import sql

DATABASE = "msf_d2_issue_test"
ROOT = Path(__file__).resolve().parents[2]


def run():
    if os.environ.get("D2_ISSUE_DATABASE") != DATABASE or os.environ.get("D2_ISSUE_ACK") != "isolated-issuance-acceptance:" + DATABASE:
        raise RuntimeError("Dedicated issuance acceptance target required")
    if not re.fullmatch(r"https://127\.0\.0\.1:\d+", os.environ.get("D2_ISSUE_ORIGIN", "")):
        raise RuntimeError("Loopback HTTPS origin required")
    connection_file = Path(os.environ["D2_ISSUE_PG_CONNECTION_FILE"]).resolve()
    if connection_file.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise RuntimeError("Private connection file required")
    target = json.loads(connection_file.read_text())
    if target.get("dbname") != DATABASE or target.get("host") != "127.0.0.1":
        raise RuntimeError("Dedicated loopback database required")
    tools = json.loads(os.environ["D2_ISSUE_TOOL_IDS"])
    if len(tools) != 3 or len(set(tools)) != 3:
        raise RuntimeError("Three distinct synthetic tools required")
    for tool in tools:
        uuid.UUID(tool)
    suffix = uuid.uuid4().hex
    name = "d2_issue_late_" + suffix
    connection = psycopg2.connect(**target)
    connection.autocommit = True
    process = None
    installed = False
    try:
        with connection.cursor() as query:
            query.execute("select current_database()")
            if query.fetchone()[0] != DATABASE:
                raise RuntimeError("Database mismatch")
        process = subprocess.Popen(["node", "tools/d2-issue/late-failure.mjs"], cwd=ROOT,
                                   env=os.environ.copy(), stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        ready = json.loads(process.stdout.readline())
        if ready.get("stage") != "ready":
            raise RuntimeError("Synthetic application was not created")
        loan = str(uuid.UUID(ready["loan"]))
        with connection.cursor() as query:
            query.execute(sql.SQL("""
                create function public.{}() returns trigger language plpgsql as $$
                begin
                    if new.id={}::uuid and new.state='ISSUED' then
                        raise exception 'Synthetic late issuance failure' using errcode='23514';
                    end if;
                    return new;
                end $$
                """).format(sql.Identifier(name), sql.Literal(loan)))
            installed = True
            query.execute(sql.SQL("create trigger {} before update on public.tool_loan for each row execute function public.{}()")
                          .format(sql.Identifier(name), sql.Identifier(name)))
        process.stdin.write("fail\n")
        process.stdin.flush()
        failed = json.loads(process.stdout.readline())
        if failed.get("stage") != "failed" or failed.get("status") != 503:
            raise RuntimeError("Expected injected failure was not observed")
        with connection.cursor() as query:
            query.execute("select state,version from tool_loan where id=%s", (loan,))
            if query.fetchone() != ("PENDING_ISSUE", 1):
                raise RuntimeError("Application changed after failed issuance")
            checks = [
                "select count(*) from tool_loan_revision where loan_id=%s and version>1",
                "select count(*) from tool_loan_item where loan_id=%s and issued_at is not null",
                "select count(*) from tool_loan_command_receipt where loan_id=%s and action='ISSUE'",
                "select count(*) from workflow_binding where business_id=%s",
                "select count(*) from audit_event where resource_id=%s and action='tool_loan.issue'",
            ]
            for check in checks:
                query.execute(check, (loan,))
                if query.fetchone()[0] != 0:
                    raise RuntimeError("Failed issuance retained a business effect")
            for table in ("act_ru_execution", "act_hi_procinst"):
                query.execute(sql.SQL("select count(*) from {} where business_key_=%s").format(sql.Identifier(table)), ("tool_loan:" + loan,))
                if query.fetchone()[0] != 0:
                    raise RuntimeError("Failed issuance retained a process")
            query.execute(sql.SQL("drop trigger {} on public.tool_loan").format(sql.Identifier(name)))
            query.execute(sql.SQL("drop function public.{}()").format(sql.Identifier(name)))
            installed = False
        process.stdin.write("retry\n")
        process.stdin.flush()
        done = json.loads(process.stdout.readline())
        if done.get("stage") != "complete" or process.wait(timeout=20) != 0:
            raise RuntimeError("Same-key retry failed")
        print(json.dumps(dict(done, rollbackProof="PASS", sameKeyRetry="PASS")))
    finally:
        if installed:
            with connection.cursor() as query:
                query.execute(sql.SQL("drop trigger if exists {} on public.tool_loan").format(sql.Identifier(name)))
                query.execute(sql.SQL("drop function if exists public.{}()").format(sql.Identifier(name)))
        connection.close()
        if process is not None and process.poll() is None:
            process.kill()
            process.wait()


if __name__ == "__main__":
    try:
        run()
    except Exception as failure:
        print(json.dumps({"status": "FAILED", "type": type(failure).__name__,
                          "sqlState": getattr(failure, "pgcode", None)}))
        raise SystemExit(1)
