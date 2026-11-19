"""AU-06. Exercise live ledger and loan commands in the dedicated V33 fixture."""

import os
import re
import time
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import psycopg2
import requests


DATABASE = "msf_d2_transfer_test"
ORIGIN = os.environ["D2_TRANSFER_ORIGIN"]
if (
    os.environ.get("PGDATABASE") != DATABASE
    or os.environ.get("PGHOST") != "127.0.0.1"
    or os.environ.get("D2_TRANSFER_ACK") != "isolated:" + DATABASE
    or not re.fullmatch(r"https://127\.0\.0\.1:\d+", ORIGIN)
):
    raise RuntimeError("Dedicated loopback transfer fixture required")
LEDGER = "/api/v1/apps/tooling/entities/tool"
LOANS = "/api/v1/tooling/loan-applications"


def login(actor):
    session = requests.Session()
    session.trust_env = False
    session.verify = os.environ["D2_TRANSFER_CA"]
    session.get(ORIGIN + "/api/v1/auth/csrf", timeout=10).raise_for_status()
    post(
        session,
        "/api/v1/auth/login",
        {
            "tenantCode": "d1seed",
            "loginKey": "d1seed-" + actor,
            "password": os.environ["D2_PASSWORD_" + actor.upper()],
        },
    ).raise_for_status()
    session.get(ORIGIN + "/api/v1/auth/csrf", timeout=10).raise_for_status()
    return session


def post(session, path, body):
    return session.post(
        ORIGIN + path,
        json=body,
        timeout=30,
        headers={"X-XSRF-TOKEN": session.cookies["XSRF-TOKEN"], "Idempotency-Key": str(uuid4())},
    )


def result(response, status=200):
    assert response.status_code == status, (response.status_code, response.text)
    return response.json()


def setup(query):
    query.execute("""select t.id,s.id,s.organization_id,b.id,k.id,m.id
        from tenant t join tool_custody_scope s on s.tenant_id=t.id and s.code='D1-SCOPE-A'
        join org_unit b on b.tenant_id=t.id and b.code='D1-ORG-B'
        join tenant_member k on k.tenant_id=t.id join account ka on ka.id=k.account_id and ka.login_key='d1seed-keeper'
        join tenant_member m on m.tenant_id=t.id join account ma on ma.id=m.account_id and ma.login_key='d1seed-reader'
        where t.code='d1seed'""")
    tenant, source, org_a, org_b, keeper, reader = query.fetchone()
    target = str(uuid4())
    query.execute(
        """insert into tool_custody_scope(tenant_id,id,organization_id,code,name)
        values(%s,%s,%s,%s,'跨组织调拨验收')""",
        (tenant, target, org_b, "AU06-" + target),
    )
    query.execute(
        """insert into scope_org(tenant_id,scope_rule_id,org_id)
        select distinct rp.tenant_id,rp.scope_rule_id,%s::uuid from role_permission rp
        join role r on r.tenant_id=rp.tenant_id and r.id=rp.role_id
        join permission p on p.id=rp.permission_id
        where r.tenant_id=%s and r.code='D1-keeper' and p.code in ('tool.read','tool.update')
        on conflict do nothing""",
        (org_b, tenant),
    )
    query.execute(
        """insert into role_permission(tenant_id,role_id,permission_id,scope_rule_id)
        select r.tenant_id,r.id,p.id,rp.scope_rule_id from role r join role_permission rp on rp.role_id=r.id and rp.tenant_id=r.tenant_id
        join permission src on src.id=rp.permission_id and src.code='tool.read'
        cross join permission p where r.tenant_id=%s and r.code='D1-keeper' and p.code='tool_loan.receive'
        on conflict do nothing""",
        (tenant,),
    )
    query.execute(
        """update member_org set valid_to=now() where tenant_id=%s and member_id=%s
        and org_id<>%s and valid_to is null""",
        (tenant, reader, org_a),
    )
    query.execute(
        """insert into member_org(tenant_id,member_id,org_id,kind,valid_from)
        select %s,%s,%s,'PRIMARY',now() where not exists
        (select 1 from member_org where tenant_id=%s and member_id=%s and org_id=%s and valid_to is null)""",
        (tenant, reader, org_a, tenant, reader, org_a),
    )
    return tenant, source, org_a, org_b, keeper, target


def wait_for_waiters(query, blocker, count):
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        query.execute(
            """with recursive chain(origin,pid) as (
            select pid,pid from pg_stat_activity where datname=current_database() and wait_event_type='Lock'
            union select origin,unnest(pg_blocking_pids(pid)) from chain where pid<>%s)
            select count(distinct origin) from chain where pid=%s""",
            (blocker, blocker),
        )
        if query.fetchone()[0] >= count:
            return
        time.sleep(0.03)
    raise AssertionError("API transactions did not reach the PostgreSQL lock barrier")


def race(query, tenant, tool, first, second):
    lock = psycopg2.connect("")
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            try:
                with lock.cursor() as barrier:
                    barrier.execute(
                        "select id from tool where tenant_id=%s and id=%s for update", (tenant, tool)
                    )
                    assert barrier.fetchone()
                a = pool.submit(first)
                wait_for_waiters(query, lock.get_backend_pid(), 1)
                b = pool.submit(second)
                wait_for_waiters(query, lock.get_backend_pid(), 2)
            finally:
                lock.rollback()
            return a.result(), b.result()
    finally:
        lock.close()


def main():
    db = psycopg2.connect("")
    db.autocommit = True  # Observe each lock graph and each committed API result afresh.
    with db.cursor() as q:
        with db:
            tenant, source, org_a, org_b, keeper_id, target = setup(q)
        borrower, keeper, mover = login("reader"), login("keeper"), login("keeper")

        for use, required in [("CREATE", True), ("EDIT", False)]:
            definition = result(mover.get(ORIGIN + LEDGER + "/definition", params={"use": use}, timeout=10))
            assert definition["data"]["definition"]["inputFields"]["custodyScopeId"]["required"] is required

        def prepare():
            code = "AU06-" + str(uuid4())
            tool = result(
                post(
                    mover,
                    LEDGER + "/records",
                    {
                        "definitionVersion": "1",
                        "values": {"name": code, "code": code, "custodyScopeId": source},
                    },
                )
            )["data"]["id"]
            loan = result(
                post(
                    borrower,
                    LOANS,
                    {
                        "definitionVersion": "1",
                        "organizationId": org_a,
                        "custodyScopeId": source,
                        "purpose": code,
                        "toolIds": [tool],
                    },
                )
            )["data"]["id"]
            return tool, loan

        def transfer(tool):
            return post(
                mover,
                LEDGER + "/records/" + tool + "/update",
                {
                    "definitionVersion": "1",
                    "expectedVersion": 1,
                    "values": {"name": "调拨验收工装", "custodyScopeId": target},
                },
            )

        def issue(loan):
            return post(
                keeper, LOANS + "/" + loan + "/issue", {"definitionVersion": "1", "expectedVersion": 1}
            )

        def facts(tool, loan):
            q.execute(
                """select t.custody_scope_id,t.organization_id,t.version,l.custody_scope_id,l.state,
                (select count(*) from tool_loan_item i where i.tenant_id=t.tenant_id and i.tool_id=t.id
                 and i.issued_at is not null and i.settled_at is null),
                (select count(*) from ledger_record_revision r where r.tenant_id=t.tenant_id and r.record_id=t.id and r.action='UPDATE')
                from tool t join tool_loan l on l.tenant_id=t.tenant_id where t.tenant_id=%s and t.id=%s and l.id=%s""",
                (tenant, tool, loan),
            )
            return q.fetchone()

        tool, loan = prepare()
        denied = transfer(tool)
        assert {"field": "custodyScopeId", "reason": "reference_not_visible"} in result(denied, 400)[
            "fieldErrors"
        ]
        assert facts(tool, loan) == (source, org_a, 1, source, "PENDING_ISSUE", 0, 0)
        q.execute(
            "insert into tool_custodian(tenant_id,custody_scope_id,member_id,valid_from) values(%s,%s,%s,now())",
            (tenant, target, keeper_id),
        )
        first, second = race(q, tenant, tool, lambda: issue(loan), lambda: transfer(tool))
        result(first)
        assert result(second, 409)["code"] == "TOOL_IN_USE"
        assert facts(tool, loan) == (source, org_a, 1, source, "ISSUED", 1, 0)
        result(
            post(
                borrower,
                LOANS + "/" + loan + "/return-requests",
                {"definitionVersion": "1", "expectedVersion": 2, "toolIds": [tool]},
            )
        )
        assert result(transfer(tool), 409)["code"] == "TOOL_IN_USE"
        result(
            post(
                keeper,
                LOANS + "/" + loan + "/receive",
                {
                    "definitionVersion": "1",
                    "expectedVersion": 3,
                    "items": [{"toolId": tool, "condition": "NORMAL"}],
                },
            )
        )
        result(transfer(tool))
        assert facts(tool, loan) == (target, org_b, 2, source, "SETTLED", 0, 1)
        tool, loan = prepare()
        first, second = race(q, tenant, tool, lambda: transfer(tool), lambda: issue(loan))
        result(first)
        assert result(second, 409)["code"] == "TOOL_UNAVAILABLE"
        assert facts(tool, loan) == (target, org_b, 2, source, "PENDING_ISSUE", 0, 1)
        print(
            "AU-06 PASS: unauthorized destination, two ordered lock races, pending return, settlement, history"
        )
    db.close()


if __name__ == "__main__":
    main()
