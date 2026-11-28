"""AU-08 live HTTPS and PostgreSQL acceptance using an owned synthetic database."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack, closing, contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import time
from uuid import uuid4

# The acceptance scenarios use assert for their pass/fail predicates.
if not __debug__:
    raise SystemExit("AU-08 requires Python assertions; do not use optimized mode.")

import psycopg2
import requests

from fixture_scope import fixture_transaction, synthetic_tenant_id
from hosted_bootstrap import connect
from hosted_contract import DATABASE
from hosted_observation import FIXTURE_ROLE

LOANS = "/api/v1/tooling/loan-applications"


def configure(origin, ca, evidence, actors):
    global ORIGIN, CA, STATE, ACTORS
    if not re.fullmatch(r"https://127\.0\.0\.1:[0-9]{1,5}", origin):
        raise ValueError("An explicit loopback HTTPS origin is required")
    ORIGIN, CA, STATE, ACTORS = origin, str(ca), Path(evidence) / "scenario.json", actors


def configure_local():
    if (os.environ.get("PGDATABASE") != DATABASE or os.environ.get("PGHOST") != "127.0.0.1"
            or os.environ.get("AU08_ACK") != "isolated:" + DATABASE):
        raise RuntimeError("An explicit owned loopback AU-08 database is required")
    configure(os.environ["AU08_ORIGIN"], os.environ["AU08_CA"], os.environ["AU08_EVIDENCE"],
              {actor: os.environ["D2_PASSWORD_" + actor.upper()] for actor in ("reader", "keeper")})


@contextmanager
def transaction(db, owned):
    if owned is None:
        with db, db.cursor() as query:
            yield query
    else:
        with fixture_transaction(db, owned) as query:
            yield query


def post(session, path, body):
    return session.post(ORIGIN + path, json=body, timeout=30, headers={
        "X-XSRF-TOKEN": session.cookies["XSRF-TOKEN"], "Idempotency-Key": str(uuid4())})


def result(response, status=200):
    assert response.status_code == status, (response.status_code, response.text)
    return response.json()


def login(actor):
    session = requests.Session()
    session.trust_env = False
    session.verify = CA
    session.get(ORIGIN + "/api/v1/auth/csrf", timeout=10).raise_for_status()
    post(session, "/api/v1/auth/login", {"tenantCode": "d1seed",
        "loginKey": "d1seed-" + actor, "password": ACTORS[actor]}).raise_for_status()
    session.get(ORIGIN + "/api/v1/auth/csrf", timeout=10).raise_for_status()
    return session


def snapshot(db, loan, owned):
    with transaction(db, owned) as q:
        q.execute("""select jsonb_build_object('loan',to_jsonb(l),
          'revision',(select jsonb_agg(r order by version) from tool_loan_revision r where loan_id=l.id),
          'receipt',(select jsonb_agg(r order by result_version) from tool_loan_command_receipt r where loan_id=l.id),
          'items',(select jsonb_agg(r order by version,line_no) from tool_loan_item r where loan_id=l.id),
          'returns',(select jsonb_agg(r order by request_id,tool_id) from tool_return_request_item r where loan_id=l.id),
          'tasks',(select jsonb_agg(t order by t.id_) from workflow_binding b join act_ru_task t on t.proc_inst_id_=b.process_instance_id where b.business_id=l.id),
          'successAudit',(select count(*) from audit_event a where a.resource_id=l.id and a.result='SUCCESS'))
          from tool_loan l where l.id=%s""", (loan,))
        return q.fetchone()[0]


def guard(q, tenant):
    key = int.from_bytes(hashlib.sha256(("msf.authorization.v1/tenant/" + tenant).encode()).digest()[:8], "big", signed=True)
    q.execute("select pg_advisory_xact_lock_shared(%s,%s)", (0x4D534647, 1))
    q.execute("select pg_advisory_xact_lock(%s)", (key,))


def prepare():
    with login("reader") as reader, login("keeper") as keeper:
        assert result(reader.get(ORIGIN + LOANS + "/definition", timeout=10))["data"]["definitionVersion"] == "1"
        candidates = result(reader.get(ORIGIN + LOANS + "/candidates", timeout=10))["data"]
        org = next(x["id"] for x in candidates["departments"] if "D1-ORG-A" in x["label"])
        scope = next(x["id"] for x in candidates["scopes"] if "D1-SCOPE-A" in x["label"])
        tools = []
        for index in range(2):
            tools.append(result(post(keeper, "/api/v1/apps/tooling/entities/tool/records", {
                "definitionVersion": "1", "values": {"code": "AU08-" + uuid4().hex,
                "name": "旧单授权验收" + str(index), "custodyScopeId": scope}}))["data"]["id"])
        body = {"definitionVersion": "1", "organizationId": org,
                "custodyScopeId": scope, "purpose": "AU08 当前授权与损坏归还", "toolIds": tools}
        loan = result(post(reader, LOANS, body))["data"]
        STATE.parent.mkdir(parents=True, exist_ok=True)
        STATE.write_text(json.dumps({"id": loan["id"], "tools": tools}))
        return "1"


def verify(owned=None, fixture_password=None, app_name=None):
    scenario = json.loads(STATE.read_text())
    loan, tools = scenario["id"], scenario["tools"]
    base = LOANS + "/" + loan
    checks = []
    with ExitStack() as resources:
        def open_actor(actor):
            return resources.enter_context(login(actor))

        sessions = {actor: open_actor(actor) for actor in ("reader", "keeper")}
        db = resources.enter_context(closing(psycopg2.connect("") if owned is None
                                             else connect(owned, FIXTURE_ROLE, fixture_password)))
        with transaction(db, owned) as q:
            q.execute("""select t.id::text,m.id::text,a.login_key from tenant t
                join tenant_member m on m.tenant_id=t.id join account a on a.id=m.account_id
                where t.code='d1seed' and a.login_key in ('d1seed-reader','d1seed-keeper')""")
            rows = q.fetchall()
            actors = {r[2].removeprefix("d1seed-"): r[:2] for r in rows}
            assert len(rows) == 2 and actors.keys() == sessions.keys()
            if owned:
                assert all(tenant == synthetic_tenant_id(owned.instance) for tenant, _ in actors.values())
        definition = result(sessions["reader"].get(ORIGIN + LOANS + "/definition", timeout=10))
        assert definition["data"]["definitionVersion"] == "2"

        def denied(actor, action, body, kind):
            tenant, member = actors[actor]
            before = snapshot(db, loan, owned)
            committed = False
            try:
                with ThreadPoolExecutor(max_workers=1) as executor:
                    pending = None
                    with transaction(db, owned) as q:
                        guard(q, tenant)
                        if kind == "permission":
                            code = "tool_loan." + ("create" if action == "return-requests" else action)
                            if owned:
                                q.execute("""select g.role_id,g.member_id from role_grant g
                                    where g.tenant_id=%s and g.role_id in (select r.role_id from role_grant r
                                        where r.tenant_id=%s and r.member_id=%s)""", (tenant, tenant, member))
                                assignments = q.fetchall()
                                assert len(assignments) == 1 and assignments[0][1] == member
                                role = assignments[0][0]
                                q.execute("""delete from role_permission rp using permission p
                                    where p.id=rp.permission_id and p.code=%s and rp.tenant_id=%s and rp.role_id=%s
                                    returning rp.tenant_id,rp.role_id,rp.permission_id,rp.scope_rule_id""",
                                          (code, tenant, role))
                            else:
                                q.execute("""delete from role_permission rp using permission p, role_grant g
                                    where p.id=rp.permission_id and p.code=%s and g.tenant_id=rp.tenant_id
                                      and g.role_id=rp.role_id and g.member_id=%s
                                    returning rp.tenant_id,rp.role_id,rp.permission_id,rp.scope_rule_id""", (code, member))
                            removed = q.fetchall()
                            assert len(removed) == 1 if owned else bool(removed)
                        else:
                            q.execute("""update tenant_member set status='DISABLED'
                                where tenant_id=%s and id=%s and status='ACTIVE'""", (tenant, member))
                            assert q.rowcount == 1
                        if action == "issue" and kind == "permission":
                            pending = executor.submit(post, sessions[actor], base + "/" + action, body)
                            deadline = time.monotonic() + 5
                            while time.monotonic() < deadline:
                                q.execute("select pg_stat_clear_snapshot()")
                                q.execute("""select count(*) from pg_stat_activity
                                    where datname=current_database() and pid<>pg_backend_pid()
                                    and (%s::text is null or application_name=%s)
                                    and pg_backend_pid()=any(pg_blocking_pids(pid))""",
                                          (app_name, app_name))
                                if q.fetchone()[0] > 0:
                                    break
                                time.sleep(0.025)
                            else:
                                raise AssertionError("Issue command never waited on the security-writer guard")
                    committed = True
                    if pending:
                        response = pending.result(timeout=10)
                        checks.append("issue/revocation-commits-before-blocked-command")
                    else:
                        response = post(sessions[actor], base + "/" + action, body)
                assert response.status_code in (401, 403), (response.status_code, response.text)
                assert snapshot(db, loan, owned) == before
                checks.append(action + "/" + kind)
            finally:
                if committed:
                    with transaction(db, owned) as q:
                        guard(q, tenant)
                        if kind == "permission":
                            for row in removed:
                                q.execute("""insert into role_permission(tenant_id,role_id,permission_id,scope_rule_id)
                                    values(%s,%s,%s,%s)""", row)
                                assert q.rowcount == 1
                        else:
                            q.execute("""update tenant_member set status='ACTIVE'
                                where tenant_id=%s and id=%s and status='DISABLED'""", (tenant, member))
                            assert q.rowcount == 1
                    with transaction(db, owned) as q:
                        if kind == "permission":
                            for t, role, permission, scope in removed:
                                q.execute("""select count(*) from role_permission where tenant_id=%s
                                    and role_id=%s and permission_id=%s and scope_rule_id is not distinct from %s""",
                                          (t, role, permission, scope))
                                assert q.fetchone() == (1,)
                        else:
                            q.execute("select status from tenant_member where tenant_id=%s and id=%s", (tenant, member))
                            assert q.fetchone() == ("ACTIVE",)
                    sessions[actor].close()
                    sessions[actor] = open_actor(actor)

        issue = {"definitionVersion": "1", "expectedVersion": 1}
        denied("keeper", "issue", issue, "permission")
        denied("keeper", "issue", issue, "member")
        current = snapshot(db, loan, owned)["loan"]
        competing = result(post(sessions["reader"], LOANS, {
            "definitionVersion": "2", "organizationId": current["organization_id"],
            "custodyScopeId": current["custody_scope_id"], "purpose": "AU08 可用性竞争",
            "usageLocation": "合成试验区", "toolIds": tools[1:]}))["data"]
        competing_base = LOANS + "/" + competing["id"]
        result(post(sessions["keeper"], competing_base + "/issue", {"definitionVersion": "2", "expectedVersion": 1}))
        before = snapshot(db, loan, owned)
        unavailable = result(post(sessions["keeper"], base + "/issue", issue), 409)
        assert unavailable["code"] == "TOOL_UNAVAILABLE"
        assert snapshot(db, loan, owned) == before
        checks.append("issue/current-tool-unavailable/no-partial-commit")
        result(post(sessions["reader"], competing_base + "/return-requests", {
            "definitionVersion": "2", "expectedVersion": 2, "toolIds": tools[1:]}))
        result(post(sessions["keeper"], competing_base + "/receive", {
            "definitionVersion": "2", "expectedVersion": 3,
            "items": [{"toolId": tools[1], "condition": "NORMAL"}]}))
        issued = result(post(sessions["keeper"], base + "/issue", issue))["data"]
        assert issued["version"] == 2 and issued["definitionVersion"] == "1"
        returned = {"definitionVersion": "1", "expectedVersion": 2, "toolIds": tools}
        denied("reader", "return-requests", returned, "permission")
        denied("reader", "return-requests", returned, "member")
        result(post(sessions["reader"], base + "/return-requests", returned))
        receive = {"definitionVersion": "1", "expectedVersion": 3, "items": [
            {"toolId": tools[0], "condition": "NORMAL"},
            {"toolId": tools[1], "condition": "DAMAGED", "reason": "已借工装损坏，授权人员接收"}]}
        denied("keeper", "receive", receive, "permission")
        denied("keeper", "receive", receive, "member")
        settled = result(post(sessions["keeper"], base + "/receive", receive))["data"]
        assert settled["state"] == "SETTLED" and settled["version"] == 4
        with transaction(db, owned) as q:
            q.execute("select count(*) from tool_loan_item where loan_id=%s and issued_at is not null and settled_at is null", (loan,))
            assert q.fetchone()[0] == 0
            q.execute("select outcome,count(*) from tool_return_request_item where loan_id=%s group by outcome", (loan,))
            assert dict(q.fetchall()) == {"NORMAL": 1, "DAMAGED": 1}
            q.execute("select count(*) from tool_loan_revision where loan_id=%s", (loan,))
            assert q.fetchone()[0] == 4
        checks.append("damaged-issued-tool/return-and-receive/settled")
    (STATE.parent / "summary.json").write_text(json.dumps({"loan": loan, "checks": checks}, indent=2))
    return "2"


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["prepare", "verify"])
    phase = parser.parse_args().phase
    configure_local()
    if phase == "prepare":
        print("V1 loan prepared", prepare())
    else:
        verify()
        print("AU08 passed", len(json.loads((STATE.parent / "summary.json").read_text())["checks"]), "checks")
