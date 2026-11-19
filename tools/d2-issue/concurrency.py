"""Exercise real issuance commands while observing a PostgreSQL lock barrier."""
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

import psycopg2


def connect():
    return psycopg2.connect("")


def require_target():
    if os.environ.get("PGDATABASE") != "msf_d2_issue_test":
        raise RuntimeError("Dedicated issuance database required")
    if os.environ.get("PGHOST") != "127.0.0.1":
        raise RuntimeError("Loopback isolated PostgreSQL required")
    if os.environ.get("D2_ISSUE_ACK") != "isolated-issuance-acceptance:msf_d2_issue_test":
        raise RuntimeError("Dedicated issuance acknowledgement required")


def create_tools(connection):
    prefix = "ISSUE-OVERLAP-" + str(uuid.uuid4())
    with connection.cursor() as query:
        query.execute("""
            insert into tool (
                tenant_id,id,version,created_at,updated_at,created_by_actor_id,
                updated_by_actor_id,code,name,custody_scope_id,organization_id)
            select t.tenant_id,gen_random_uuid(),1,now(),now(),t.created_by_actor_id,
                   t.created_by_actor_id,%s||'-'||i,'Parallel issuance probe',
                   t.custody_scope_id,t.organization_id
            from tool t cross join generate_series(1,3) i
            where t.code='ISSUE-D1-SCOPE-A-1'
            returning id,custody_scope_id
            """, (prefix,))
        tools = query.fetchall()
        assert len(tools) == 3
    connection.commit()
    return tools


def check_denied_commands(connection, ready):
    with connection.cursor() as query:
        for loan in ready["loans"] + [ready["selfLoan"]]:
            query.execute("select state,version from tool_loan where id=%s", (loan,))
            assert query.fetchone() == ("PENDING_ISSUE", 1)
            query.execute("""
                select count(*) from tool_loan_command_receipt
                where loan_id=%s and action='ISSUE'
                """, (loan,))
            assert query.fetchone()[0] == 0
            query.execute("select count(*) from workflow_binding where business_id=%s", (loan,))
            assert query.fetchone()[0] == 0


def observe_overlap(observer, blocker):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        with observer.cursor() as query:
            query.execute("""
                with recursive waits as (
                    select pid as origin,pid,pg_blocking_pids(pid) as blockers
                    from pg_stat_activity
                    where datname='msf_d2_issue_test' and wait_event_type='Lock'
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
    raise AssertionError("Two issuance transactions did not reach the lock barrier")


def verify_results(connection, result, tool_ids):
    with connection.cursor() as query:
        query.execute("""
            select count(*) from tool_loan_item
            where tool_id=any(%s::uuid[]) and issued_at is not null and settled_at is null
            """, (tool_ids,))
        assert query.fetchone()[0] == 1
    verification = Path(__file__).with_name("verify.sql").read_text()
    assertions = verification[verification.index("do $$"):verification.index("rollback;")]
    with connection.cursor() as query:
        for name in ("winner", "loser", "triple"):
            query.execute("select set_config(%s,%s,false)", ("test.issue_" + name, result[name]))
        query.execute(assertions)



def main():
    require_target()
    base = Path(os.environ["D2_ISSUE_EVIDENCE_DIR"]).resolve()
    base.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parents[2]
    lock_connection = connect()
    observer = connect()
    observer.autocommit = True
    process = None
    try:
        tools = create_tools(lock_connection)
        tool_ids = [str(row[0]) for row in tools]
        environment = dict(
            os.environ,
            D2_ISSUE_DATABASE="msf_d2_issue_test",
            D2_ISSUE_SCOPE_ID=str(tools[0][1]),
            D2_ISSUE_TOOL_IDS=json.dumps(tool_ids),
        )
        with (base / "concurrency-error.log").open("w") as errors:
            process = subprocess.Popen(
                ["node", "tools/d2-issue/issue-concurrency.mjs"],
                cwd=root, env=environment, stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=errors, text=True,
            )
            ready = json.loads(process.stdout.readline())
            assert ready["stage"] == "ready"
            check_denied_commands(observer, ready)
            with lock_connection.cursor() as query:
                query.execute("select id from tool where id=%s for update", (tool_ids[0],))
                query.execute("select pg_backend_pid()")
                blocker = query.fetchone()[0]
            process.stdin.write("go\n")
            process.stdin.flush()
            waiters = observe_overlap(observer, blocker)
            lock_connection.rollback()
            result = json.loads(process.stdout.readline())
            assert result["stage"] == "complete"
            assert process.wait(timeout=20) == 0
            verify_results(observer, result, tool_ids)
            result["lockWaitersObserved"] = waiters
            result["databaseProof"] = "PASS"
            (base / "concurrency-summary.json").write_text(json.dumps(result, indent=2))
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
