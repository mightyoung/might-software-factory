"""Inject one tagged late loss failure through real HTTPS and verify atomic rollback."""
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import uuid
import psycopg2
from psycopg2 import sql


def run():
    database = "msf_d2_loss_test"
    if os.environ.get("D2_LOSS_ACK") != "isolated-loss-acceptance:" + database:
        raise RuntimeError("Dedicated return target required")
    if not re.fullmatch(r"https://127\.0\.0\.1:\d+", os.environ.get("D2_LOSS_ORIGIN", "")):
        raise RuntimeError("Loopback HTTPS required")
    secret = Path(os.environ["D2_LOSS_PG_CONNECTION_FILE"]).resolve()
    if secret.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise RuntimeError("Private connection file required")
    target = json.loads(secret.read_text())
    if target.get("dbname") != database or target.get("host") != "127.0.0.1":
        raise RuntimeError("Dedicated loopback database required")
    root = Path(__file__).resolve().parents[2]
    name = "d2_receive_late_" + uuid.uuid4().hex
    connection = psycopg2.connect(**target)
    connection.autocommit = True
    installed = False
    process = None
    try:
        process = subprocess.Popen(["node", "tools/d2-loss/late-loss.mjs"], cwd=root,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, text=True)
        ready = json.loads(process.stdout.readline())
        if ready.get("stage") != "ready":
            raise RuntimeError("Return request setup failed")
        loan = str(uuid.UUID(ready["loan"]))
        with connection.cursor() as query:
            query.execute("select process_instance_id from workflow_binding where business_id=%s", (loan,))
            process_id = query.fetchone()[0]
            query.execute("select id_,task_def_key_,assignee_ from act_ru_task where proc_inst_id_=%s", (process_id,))
            original_tasks = query.fetchall()
            if len(original_tasks) != 1 or original_tasks[0][1] != ready["taskKey"]:
                raise RuntimeError("Expected physical loss task")
            query.execute(sql.SQL("""
                create function public.{}() returns trigger language plpgsql as $$
                begin
                    if new.id={}::uuid and new.state='SETTLED' then
                        raise exception 'Synthetic late loss failure' using errcode='23514';
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
        if failed.get("stage") != "failed":
            raise RuntimeError("Expected failure not observed")
        with connection.cursor() as query:
            query.execute("select state,version from tool_loan where id=%s", (loan,))
            if query.fetchone() != ("ISSUED", ready["version"]):
                raise RuntimeError("Loss changed application")
            query.execute("select id_,task_def_key_,assignee_ from act_ru_task where proc_inst_id_=%s", (process_id,))
            if query.fetchall() != original_tasks:
                raise RuntimeError("Loss failed to restore original task")
            for statement in [
                f"select count(*) from tool_loan_revision where loan_id=%s and version>{int(ready['version'])}",
                "select count(*) from tool_loan_item where loan_id=%s and settled_at is not null",
                "select count(*) from tool_loan_loss where loan_id=%s",
                "select count(*) from tool_return_request_item where loan_id=%s and superseded_at is not null",
                "select count(*) from tool_loan_command_receipt where loan_id=%s and action='CLOSE_LOST'",
                "select count(*) from audit_event where resource_id=%s and action='tool_loan.close_lost'",
                "select count(*) from tool t join tool_loan_item i on i.tenant_id=t.tenant_id and i.tool_id=t.id where i.loan_id=%s and t.item_state<>'NORMAL'",
            ]:
                query.execute(statement, (loan,))
                if query.fetchone()[0] != 0:
                    raise RuntimeError("Failed loss retained effects")
            query.execute(sql.SQL("drop trigger {} on public.tool_loan").format(sql.Identifier(name)))
            query.execute(sql.SQL("drop function public.{}()").format(sql.Identifier(name)))
            installed = False
        process.stdin.write("retry\n")
        process.stdin.flush()
        done = json.loads(process.stdout.readline())
        if done.get("stage") != "complete" or process.wait(timeout=20) != 0:
            raise RuntimeError("Same-key loss retry failed")
        print(json.dumps(dict(done, rollbackProof="PASS", restoredOriginalTask=True)))
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
