"""Run against the dedicated read-scope database using private environment credentials."""
import json
import os
import subprocess
import uuid
from pathlib import Path
import psycopg2

DATABASE = "msf_d2_read_scope_test"
if os.environ.get("PGDATABASE") != DATABASE or os.environ.get("D2_SCOPE_ACK") != f"isolated-read-scope:{DATABASE}":
    raise RuntimeError("Dedicated read-scope database and acknowledgement required")
root = Path(__file__).resolve().parents[2]
evidence = Path(os.environ["D2_SCOPE_EVIDENCE"])
if not evidence.is_relative_to(Path("/private/tmp")):
    raise RuntimeError("Private evidence directory required")
evidence.mkdir(parents=True, exist_ok=True)
connection = psycopg2.connect("")
with connection:
    with connection.cursor() as query:
        query.execute((Path(__file__).with_name("seed.sql")).read_text().replace("\\set ON_ERROR_STOP on\n", "").replace("begin;", "", 1).rsplit("commit;", 1)[0])
        query.execute("""select t.id,s.id,s.organization_id,tool.id from tenant t
            join tool_custody_scope s on s.tenant_id=t.id and s.code='D1-SCOPE-A'
            join tool on tool.tenant_id=t.id and tool.custody_scope_id=s.id
            where t.code='d1seed' and tool.code like 'D2-READ-SCOPE-%' order by tool.created_at desc limit 1""")
        tenant, scope, organization, tool = map(str, query.fetchone())
        query.execute("select id from org_unit where tenant_id=%s and code='D1-ORG-B'", (tenant,))
        other_organization = str(query.fetchone()[0])
        query.execute("select m.id from tenant_member m join account a on a.id=m.account_id where m.tenant_id=%s and a.login_key='d1seed-reader'", (tenant,))
        member = str(query.fetchone()[0])
        # A foreign tenant's pending record is an explicit negative fixture, never returned by its ID.
        query.execute("""select t.id,m.id,s.id,s.organization_id from tenant t join tenant_member m on m.tenant_id=t.id
            join tool_custody_scope s on s.tenant_id=t.id where t.id<>%s limit 1""", (tenant,))
        foreign_tenant, foreign_member, foreign_scope, foreign_organization = map(str, query.fetchone())
        foreign_loan = str(uuid.uuid4())
        query.execute("""insert into tool_loan(tenant_id,id,version,state,applicant_member_id,organization_id,custody_scope_id,
            definition_version,purpose,created_at,updated_at,last_command_id)
            values(%s,%s,1,'PENDING_ISSUE',%s,%s,%s,'1','跨租户负例',now(),now(),%s)""",
            (foreign_tenant, foreign_loan, foreign_member, foreign_organization, foreign_scope, str(uuid.uuid4())))
with connection:
    with connection.cursor() as query:
        query.execute("update member_org set valid_to=now() where tenant_id=%s and member_id=%s and org_id<>%s and valid_to is null", (tenant, member, organization))
        query.execute("""insert into member_org(tenant_id,member_id,org_id,kind,valid_from)
            select %s,%s,%s,'PRIMARY',now() where not exists(select 1 from member_org
                where tenant_id=%s and member_id=%s and org_id=%s and valid_to is null)""", (tenant, member, organization, tenant, member, organization))
        query.execute("""insert into tool_custodian(tenant_id,custody_scope_id,member_id,valid_from)
            select %s,%s,m.id,now() from tenant_member m join account a on a.id=m.account_id
            where m.tenant_id=%s and a.login_key='d1seed-keeper' and not exists(select 1 from tool_custodian k
                where k.tenant_id=m.tenant_id and k.custody_scope_id=%s and k.member_id=m.id and k.valid_to is null)""", (tenant, scope, tenant, scope))
env = dict(os.environ, D2_SCOPE_ID=scope, D2_ORG_A=organization, D2_TOOL_ID=tool, D2_FOREIGN_LOAN=foreign_loan)
def browser(phase):
    output = subprocess.check_output(["node", "tools/d2-read-scope/browser.mjs"], cwd=root,
        env=dict(env, D2_SCOPE_PHASE=phase), text=True)
    (evidence / f"{phase}.log").write_text(output)
    print(output.strip())
browser("create")
loan_id = json.loads((evidence / "loan.json").read_text())["id"]
def business_facts():
    with connection:
        with connection.cursor() as query:
            query.execute("""select row_to_json(l)::text,
                (select count(*) from tool_loan_revision r where r.tenant_id=l.tenant_id and r.loan_id=l.id),
                (select count(*) from tool_loan_command_receipt r where r.tenant_id=l.tenant_id and r.loan_id=l.id),
                (select count(*) from tool_loan_item i where i.tenant_id=l.tenant_id and i.loan_id=l.id and i.issued_at is not null and i.settled_at is null)
                from tool_loan l where l.tenant_id=%s and l.id=%s""", (tenant, loan_id))
            return query.fetchone()
before_read = business_facts()
with connection:
    with connection.cursor() as query:
        query.execute("update member_org set valid_to=now() where tenant_id=%s and member_id=%s and org_id=%s and valid_to is null", (tenant, member, organization))
        query.execute("insert into member_org(tenant_id,member_id,org_id,kind,valid_from) values(%s,%s,%s,'PRIMARY',now())", (tenant, member, other_organization))
browser("transferred")
with connection:
    with connection.cursor() as query:
        query.execute("""update tool_custodian set valid_to=now() where tenant_id=%s and custody_scope_id=%s
            and member_id=(select m.id from tenant_member m join account a on a.id=m.account_id
                where m.tenant_id=%s and a.login_key='d1seed-keeper') and valid_to is null""", (tenant, scope, tenant))
browser("revoked")
with connection:
    with connection.cursor() as query:
        query.execute("delete from role_permission where tenant_id=%s and permission_id=(select id from permission where code='tool_loan_department.read')", (tenant,))
        query.execute("select count(*) from role_permission where tenant_id=%s and permission_id=(select id from permission where code='tool_loan_department.read')", (tenant,))
        assert query.fetchone()[0] == 0
browser("permission-revoked")
assert business_facts() == before_read, "Read and denied commands changed the loan facts"
connection.close()
(evidence / "summary.json").write_text(json.dumps({"database": DATABASE, "originalDepartmentPreserved": True,
    "ordinaryBorrowerDeniedScopedRead": True, "explicitReadGrantRequired": True, "currentCustodyRequired": True,
    "crossTenantHidden": True, "readDoesNotGrantCommands": True, "browserReadOnlyDetail": True, "businessFactsUnchanged": True}, indent=2))
