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
                    where datname='msf_d2_return_test' and wait_event_type='Lock'
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
                   t.created_by_actor_id,%s||'-'||i,'Parallel return probe',
                   t.custody_scope_id,t.organization_id
            from tool t cross join generate_series(1,3) i
            where t.code='D2-UI-RETURN-01'
            returning id,custody_scope_id
            """, ("RETURN-OVERLAP-" + str(uuid.uuid4()),))
        tools = query.fetchall()
        assert len(tools) == 3
    connection.commit()
    return tools


def verify_barrier(connection, stage, loan):
    with connection.cursor() as query:
        query.execute("select state,version from tool_loan where id=%s", (loan,))
        assert query.fetchone() == ("ISSUED", 2 if stage == "return-ready" else 4)
        query.execute("""
            select count(*) from tool_loan_item where loan_id=%s
              and issued_at is not null and settled_at is null
            """, (loan,))
        assert query.fetchone()[0] == 3
        query.execute("select count(*) from tool_return_request_item where loan_id=%s and received_at is null", (loan,))
        assert query.fetchone()[0] == (0 if stage == "return-ready" else 2)
        query.execute("""
            select task.task_def_key_ from workflow_binding b
            join act_ru_task task on task.proc_inst_id_=b.process_instance_id
            where b.business_id=%s
            """, (loan,))
        assert query.fetchall() == [("returnRequest" if stage == "return-ready" else "receive",)]


def verify_final(connection, result):
    loan = result["loan"]
    with connection.cursor() as query:
        query.execute("select state,version from tool_loan where id=%s", (loan,))
        assert query.fetchone() == ("SETTLED", 7)
        query.execute("""
            select count(*) from tool_loan_item where loan_id=%s
              and issued_at is not null and settled_at is null
            """, (loan,))
        assert query.fetchone()[0] == 0
        query.execute("select count(*) from tool_return_request where loan_id=%s", (loan,))
        assert query.fetchone()[0] == 3
        query.execute("select outcome,count(*) from tool_return_request_item where loan_id=%s group by outcome", (loan,))
        assert dict(query.fetchall()) == {"NORMAL": 2, "DAMAGED": 1}
        query.execute("select id,item_state from tool where id=any(%s::uuid[])", (result["toolIds"],))
        assert {str(row[0]): row[1] for row in query.fetchall()} == {
            result["toolIds"][0]: "NORMAL",
            result["toolIds"][1]: "NORMAL",
            result["toolIds"][2]: "DAMAGED",
        }
        query.execute("""
            select count(*) from tool_loan_command_receipt r
            join audit_event a on a.tenant_id=r.tenant_id and a.id=r.event_id and a.resource_id=r.loan_id
            join tool_loan_revision v on v.tenant_id=r.tenant_id and v.loan_id=r.loan_id
                and v.command_id=r.command_id and v.version=r.result_version
            where r.loan_id=%s and a.result='SUCCESS'
            """, (loan,))
        assert query.fetchone()[0] == 7
        query.execute("select count(*) from tool_loan_revision where loan_id=%s", (loan,))
        assert query.fetchone()[0] == 7
        query.execute("""
            select count(*),count(history.end_time_) from workflow_binding b
            join act_hi_procinst history on history.proc_inst_id_=b.process_instance_id
            where b.business_id=%s
            """, (loan,))
        assert query.fetchone() == (1, 1)
        query.execute("""
            select count(*) from workflow_binding b join act_ru_task task
              on task.proc_inst_id_=b.process_instance_id where b.business_id=%s
            """, (loan,))
        assert query.fetchone()[0] == 0


def main():
    if os.environ.get("PGDATABASE") != "msf_d2_return_test" or os.environ.get("PGHOST") != "127.0.0.1":
        raise RuntimeError("Dedicated loopback return database required")
    if os.environ.get("D2_RETURN_ACK") != "isolated-return-acceptance:msf_d2_return_test":
        raise RuntimeError("Dedicated return acknowledgement required")
    root = Path(__file__).resolve().parents[2]
    evidence = Path(os.environ["D2_RETURN_EVIDENCE_DIR"]).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    lock_connection = psycopg2.connect("")
    observer = psycopg2.connect("")
    observer.autocommit = True
    process = None
    try:
        tools = create_tools(lock_connection)
        environment = dict(os.environ, D2_RETURN_SCOPE_ID=str(tools[0][1]),
                           D2_RETURN_TOOL_IDS=json.dumps([str(row[0]) for row in tools]))
        with (evidence / "command-error.log").open("w") as errors:
            process = subprocess.Popen(["node", "tools/d2-return/concurrent-returns.mjs"],
                                       cwd=root, env=environment, stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=errors, text=True)
            observations = []
            for stage in ("return-ready", "receive-ready"):
                event = json.loads(process.stdout.readline())
                assert event["stage"] == stage
                verify_barrier(observer, stage, event["loan"])
                with lock_connection.cursor() as query:
                    query.execute("select id from tool_loan where id=%s for update", (event["loan"],))
                    query.execute("select pg_backend_pid()")
                    blocker = query.fetchone()[0]
                process.stdin.write("go\n")
                process.stdin.flush()
                observations.append({"stage": stage, "waiters": observe_waiters(observer, blocker)})
                lock_connection.rollback()
            result = json.loads(process.stdout.readline())
            assert result["stage"] == "complete"
            assert process.wait(timeout=20) == 0
            verify_final(observer, result)
            result["lockObservations"] = observations
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
