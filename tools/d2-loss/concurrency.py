"""Verify overlapping return and receipt commands through real HTTPS and PostgreSQL."""
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

import psycopg2


def observe_waiters(observer, blocker):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        with observer.cursor() as query:
            query.execute("""
                with recursive waits as (
                    select pid as origin,pid,pg_blocking_pids(pid) as blockers
                    from pg_stat_activity
                    where datname='msf_d2_loss_test' and wait_event_type='Lock'
                    union all
                    select w.origin,a.pid,pg_blocking_pids(a.pid)
                    from waits w join pg_stat_activity a on a.pid=any(w.blockers)
                )
                select distinct origin from waits where %s=any(blockers)
                """, (blocker,))
            blocked = query.fetchall()
        if len(blocked) >= 2:
            return len(blocked)
        time.sleep(0.025)
    raise AssertionError("Two command transactions did not reach the loan lock barrier")


def create_tools(connection):
    with connection.cursor() as query:
        query.execute("""
            insert into tool (
                tenant_id,id,version,created_at,updated_at,created_by_actor_id,
                updated_by_actor_id,code,name,custody_scope_id,organization_id)
            select t.tenant_id,gen_random_uuid(),1,now(),now(),t.created_by_actor_id,
                   t.created_by_actor_id,%s||'-'||i,'Parallel loss probe',
                   t.custody_scope_id,t.organization_id
            from tool t cross join generate_series(1,4) i
            where t.code='D2-UI-LOSS-01'
            returning id,custody_scope_id
            """, ("LOSS-OVERLAP-" + str(uuid.uuid4()),))
        tools = query.fetchall()
        assert len(tools) == 4
    connection.commit()
    return tools


def verify_race(connection, event):
    loan = event["loan"]
    loss_won = event["stage"] == "loss-won"
    with connection.cursor() as query:
        query.execute("select state,version from tool_loan where id=%s", (loan,))
        assert query.fetchone() == ("ISSUED", 4 if event["stage"] == "race-ready" else 5)
        query.execute("select count(*) from tool_loan_item where loan_id=%s and issued_at is not null and settled_at is null", (loan,))
        assert query.fetchone()[0] == (2 if event["stage"] == "race-ready" else 1)
        query.execute("select count(*) from tool_loan_loss where loan_id=%s", (loan,))
        assert query.fetchone()[0] == (1 if event["stage"] == "race-ready" else 2 if loss_won else 1)
        query.execute("select count(*) from tool_return_request_item where loan_id=%s and received_at is null and superseded_at is null", (loan,))
        assert query.fetchone()[0] == (1 if event["stage"] == "race-ready" else 0)
        query.execute("""
            select task.task_def_key_ from workflow_binding b
            join act_ru_task task on task.proc_inst_id_=b.process_instance_id
            where b.business_id=%s
            """, (loan,))
        assert query.fetchall() == [("receive" if event["stage"] == "race-ready" else "returnRequest",)]


def verify_final(connection, result):
    loan = result["loan"]
    loss_count = 3 if result["lossWon"] else 2
    with connection.cursor() as query:
        for loan_id, version, conclusions in [(loan, 7, loss_count), (result["directLoan"], 3, 1)]:
            query.execute("select state,version from tool_loan where id=%s", (loan_id,))
            assert query.fetchone() == ("SETTLED", version)
            query.execute("select count(*) from tool_loan_item where loan_id=%s and issued_at is not null and settled_at is null", (loan_id,))
            assert query.fetchone()[0] == 0
            query.execute("select count(*) from tool_loan_loss where loan_id=%s", (loan_id,))
            assert query.fetchone()[0] == conclusions
            query.execute("""
                select count(*) from tool_loan_command_receipt r
                join audit_event a on a.tenant_id=r.tenant_id and a.id=r.event_id and a.resource_id=r.loan_id
                join tool_loan_revision v on v.tenant_id=r.tenant_id and v.loan_id=r.loan_id
                    and v.command_id=r.command_id and v.version=r.result_version
                where r.loan_id=%s and a.result='SUCCESS'
                """, (loan_id,))
            assert query.fetchone()[0] == version
            query.execute("select count(*) from tool_loan_revision where loan_id=%s", (loan_id,))
            assert query.fetchone()[0] == version
            query.execute("""
                select count(*),count(history.end_time_) from workflow_binding b
                join act_hi_procinst history on history.proc_inst_id_=b.process_instance_id
                where b.business_id=%s
                """, (loan_id,))
            assert query.fetchone() == (1, 1)
            query.execute("""
                select count(*) from workflow_binding b join act_ru_task task
                  on task.proc_inst_id_=b.process_instance_id where b.business_id=%s
                """, (loan_id,))
            assert query.fetchone()[0] == 0
        query.execute("select count(*) from tool_return_request where loan_id=%s", (result["directLoan"],))
        assert query.fetchone()[0] == 0
        query.execute("""
            select count(*) from tool_return_request_item r
            join tool_loan_loss loss on loss.tenant_id=r.tenant_id and loss.loan_id=r.loan_id
                and loss.tool_id=r.tool_id and loss.command_id=r.superseded_by_command_id
            where r.loan_id=%s and r.superseded_at=loss.occurred_at
              and r.received_at is null and r.outcome is null and r.command_id is null
            """, (loan,))
        assert query.fetchone()[0] == (2 if result["lossWon"] else 1)
        query.execute("select id,item_state from tool where id=any(%s::uuid[])", (result["toolIds"] + [result["directToolId"]],))
        assert {str(row[0]): row[1] for row in query.fetchall()} == {
            result["toolIds"][0]: "LOST",
            result["toolIds"][1]: "LOST" if result["lossWon"] else "NORMAL",
            result["toolIds"][2]: "LOST",
            result["directToolId"]: "LOST",
        }


def main():
    if os.environ.get("PGDATABASE") != "msf_d2_loss_test" or os.environ.get("PGHOST") != "127.0.0.1":
        raise RuntimeError("Dedicated loopback return database required")
    if os.environ.get("D2_LOSS_ACK") != "isolated-loss-acceptance:msf_d2_loss_test":
        raise RuntimeError("Dedicated return acknowledgement required")
    root = Path(__file__).resolve().parents[2]
    evidence = Path(os.environ["D2_LOSS_EVIDENCE_DIR"]).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    lock_connection = psycopg2.connect("")
    observer = psycopg2.connect("")
    observer.autocommit = True
    process = None
    try:
        tools = create_tools(lock_connection)
        environment = dict(os.environ, D2_LOSS_SCOPE_ID=str(tools[0][1]),
                           D2_LOSS_TOOL_IDS=json.dumps([str(row[0]) for row in tools[:3]]),
                           D2_LOSS_DIRECT_TOOL_ID=str(tools[3][0]))
        with (evidence / "command-error.log").open("w") as errors:
            process = subprocess.Popen(["node", "tools/d2-loss/concurrent-loss.mjs"],
                                       cwd=root, env=environment, stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=errors, text=True)
            event = json.loads(process.stdout.readline())
            assert event["stage"] == "race-ready"
            verify_race(observer, event)
            with lock_connection.cursor() as query:
                query.execute("select id from tool_loan where id=%s for update", (event["loan"],))
                query.execute("select pg_backend_pid()")
                blocker = query.fetchone()[0]
            process.stdin.write("go\n")
            process.stdin.flush()
            waiters = observe_waiters(observer, blocker)
            lock_connection.rollback()
            raced = json.loads(process.stdout.readline())
            assert raced["stage"] in ("loss-won", "receipt-won")
            verify_race(observer, raced)
            process.stdin.write("go\n")
            process.stdin.flush()
            result = json.loads(process.stdout.readline())
            assert result["stage"] == "complete"
            assert process.wait(timeout=20) == 0
            verify_final(observer, result)
            result["lockWaitersObserved"] = waiters
            result["databaseProof"] = "PASS"
            (evidence / "summary.json").write_text(json.dumps(result, indent=2))
            print(json.dumps(result))
    finally:
        lock_connection.rollback()
        lock_connection.close()
        observer.close()
        if process is not None and process.poll() is None:
            process.kill()
            process.wait()


if __name__ == "__main__":
    main()
