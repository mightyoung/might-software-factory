"""AU-08 live HTTPS and PostgreSQL acceptance using an owned synthetic database."""
import argparse
from concurrent.futures import ThreadPoolExecutor
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

DATABASE = "msf_au08_test"
ORIGIN = os.environ["AU08_ORIGIN"]
if (os.environ.get("PGDATABASE") != DATABASE
        or os.environ.get("PGHOST") != "127.0.0.1"
        or os.environ.get("AU08_ACK") != "isolated:" + DATABASE
        or not re.fullmatch(r"https://127\.0\.0\.1:\d+", ORIGIN)):
    raise RuntimeError("An explicit owned loopback AU-08 database is required")
STATE = Path(os.environ["AU08_EVIDENCE"]) / "scenario.json"
LOANS = "/api/v1/tooling/loan-applications"


def post(session, path, body):
    return session.post(ORIGIN + path, json=body, timeout=30, headers={
        "X-XSRF-TOKEN": session.cookies["XSRF-TOKEN"], "Idempotency-Key": str(uuid4())})


def result(response, status=200):
    assert response.status_code == status, (response.status_code, response.text)
    return response.json()


def login(actor):
    session = requests.Session()
    session.trust_env = False
    session.verify = os.environ["AU08_CA"]
    session.get(ORIGIN + "/api/v1/auth/csrf", timeout=10).raise_for_status()
    post(session, "/api/v1/auth/login", {"tenantCode": "d1seed",
        "loginKey": "d1seed-" + actor, "password": os.environ["D2_PASSWORD_" + actor.upper()]}).raise_for_status()
    session.get(ORIGIN + "/api/v1/auth/csrf", timeout=10).raise_for_status()
    return session


def snapshot(db, loan):
    with db.cursor() as q:
        q.execute("""select jsonb_build_object('loan',to_jsonb(l),
          'revision',(select jsonb_agg(r order by version) from tool_loan_revision r where loan_id=l.id),
          'receipt',(select jsonb_agg(r order by result_version) from tool_loan_command_receipt r where loan_id=l.id),
          'items',(select jsonb_agg(r order by version,line_no) from tool_loan_item r where loan_id=l.id),
          'returns',(select jsonb_agg(r order by request_id,tool_id) from tool_return_request_item r where loan_id=l.id),
          'tasks',(select jsonb_agg(t order by t.id_) from workflow_binding b join act_ru_task t on t.proc_inst_id_=b.process_instance_id where b.business_id=l.id),
          'successAudit',(select count(*) from audit_event a where a.resource_id=l.id and a.result='SUCCESS'))
          from tool_loan l where l.id=%s""", (loan,))
        value = q.fetchone()[0]
    db.commit()
    return value


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
        print("V1 loan prepared", loan["id"])


def verify():
    scenario = json.loads(STATE.read_text())
    loan, tools = scenario["id"], scenario["tools"]
    base = LOANS + "/" + loan
    sessions = {actor: login(actor) for actor in ["reader", "keeper"]}
    checks = []
    with psycopg2.connect("") as db:
        with db.cursor() as q:
            q.execute("""select t.id::text,m.id::text,a.login_key from tenant t
                join tenant_member m on m.tenant_id=t.id join account a on a.id=m.account_id
                where t.code='d1seed' and a.login_key in ('d1seed-reader','d1seed-keeper')""")
            actors = {r[2].removeprefix("d1seed-"): r[:2] for r in q.fetchall()}
        db.commit()
        assert result(sessions["reader"].get(ORIGIN + LOANS + "/definition", timeout=10))["data"]["definitionVersion"] == "2"

        def denied(actor, action, body, kind):
            tenant, member = actors[actor]
            before = snapshot(db, loan)
            with db.cursor() as q:
                guard(q, tenant)
                if kind == "permission":
                    q.execute("""delete from role_permission rp using permission p, role_grant g
                        where p.id=rp.permission_id and p.code=%s and g.tenant_id=rp.tenant_id
                        and g.role_id=rp.role_id and g.member_id=%s returning rp.tenant_id,rp.role_id,rp.permission_id,rp.scope_rule_id""",
                        ("tool_loan." + ("create" if action == "return-requests" else action), member))
                    removed = q.fetchall()
                    assert removed
                else:
                    q.execute("update tenant_member set status='DISABLED' where tenant_id=%s and id=%s", (tenant, member))
                    assert q.rowcount == 1
            try:
                if action == "issue" and kind == "permission":
                    # Hold the real tenant security-writer guard until a command waits on it.
                    with ThreadPoolExecutor(max_workers=1) as executor:
                        pending = executor.submit(post, sessions[actor], base + "/" + action, body)
                        deadline = time.monotonic() + 5
                        with db.cursor() as q:
                            while time.monotonic() < deadline:
                                q.execute("""select count(*) from pg_stat_activity
                                    where datname=current_database() and pid<>pg_backend_pid()
                                    and pg_backend_pid()=any(pg_blocking_pids(pid))""")
                                if q.fetchone()[0] > 0:
                                    break
                                time.sleep(0.025)
                            else:
                                raise AssertionError("Issue command never waited on the security-writer guard")
                        db.commit()
                        response = pending.result(timeout=10)
                        checks.append("issue/revocation-commits-before-blocked-command")
                else:
                    db.commit()
                    response = post(sessions[actor], base + "/" + action, body)
                assert response.status_code in (401, 403), (response.status_code, response.text)
                assert snapshot(db, loan) == before
                checks.append(action + "/" + kind)
            finally:
                db.rollback()
                with db.cursor() as q:
                    guard(q, tenant)
                    if kind == "permission":
                        q.executemany("insert into role_permission(tenant_id,role_id,permission_id,scope_rule_id) values(%s,%s,%s,%s) on conflict do nothing", removed)
                    else:
                        q.execute("update tenant_member set status='ACTIVE' where tenant_id=%s and id=%s", (tenant, member))
                db.commit()
                sessions[actor].close()
                sessions[actor] = login(actor)

        issue = {"definitionVersion": "1", "expectedVersion": 1}
        denied("keeper", "issue", issue, "permission")
        denied("keeper", "issue", issue, "member")
        current = snapshot(db, loan)["loan"]
        competing = result(post(sessions["reader"], LOANS, {
            "definitionVersion": "2", "organizationId": current["organization_id"],
            "custodyScopeId": current["custody_scope_id"], "purpose": "AU08 可用性竞争",
            "usageLocation": "合成试验区", "toolIds": tools[1:]}))["data"]
        competing_base = LOANS + "/" + competing["id"]
        result(post(sessions["keeper"], competing_base + "/issue", {"definitionVersion": "2", "expectedVersion": 1}))
        before = snapshot(db, loan)
        unavailable = result(post(sessions["keeper"], base + "/issue", issue), 409)
        assert unavailable["code"] == "TOOL_UNAVAILABLE"
        assert snapshot(db, loan) == before
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
        with db.cursor() as q:
            q.execute("select count(*) from tool_loan_item where loan_id=%s and issued_at is not null and settled_at is null", (loan,))
            assert q.fetchone()[0] == 0
            q.execute("select outcome,count(*) from tool_return_request_item where loan_id=%s group by outcome", (loan,))
            assert dict(q.fetchall()) == {"NORMAL": 1, "DAMAGED": 1}
            q.execute("select count(*) from tool_loan_revision where loan_id=%s", (loan,))
            assert q.fetchone()[0] == 4
        checks.append("damaged-issued-tool/return-and-receive/settled")
    for session in sessions.values():
        session.close()
    (STATE.parent / "summary.json").write_text(json.dumps({"loan": loan, "checks": checks}, indent=2))
    print("AU08 passed", len(checks), "checks", ", ".join(checks))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["prepare", "verify"])
    phase = parser.parse_args().phase
    prepare() if phase == "prepare" else verify()
