"""Explicit effective ACL for the two non-owner roles in disposable AU-08 only."""

from contextlib import closing

from hosted_bootstrap import MIGRATOR_ROLE, connect
from hosted_observation import FIXTURE_ROLE, RUNTIME_ROLE, observe_database, observe_fixture

TABLE_OPS = ("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "MAINTAIN")
COLUMN_OPS = ("SELECT", "INSERT", "UPDATE", "REFERENCES")

# Existing D0 identity reads needed by the login/authorization envelope (no identity writes).
RUNTIME = {name: {"SELECT": None} for name in """
    account principal credential tenant tenant_application tenant_member org_unit member_org
    position member_position user_group group_member permission role scope_rule role_permission role_grant
    scope_org tool_custody_scope tool_custodian act_ge_property act_re_deployment act_re_procdef
    act_ge_bytearray act_procdef_info
""".split()}
for name in ("security_event", "authentication_event"):
    RUNTIME[name] = {"INSERT": None}
for name in ("workflow_binding", "audit_event", "ledger_record_revision", "ledger_command_receipt",
             "tool_loan_revision", "tool_loan_command_receipt", "tool_return_request"):
    RUNTIME[name] = {"SELECT": None, "INSERT": None}
for name, columns in {
    "tool_loan": "version state purpose usage_location updated_at last_command_id",
    "tool_loan_item": "settled_at",
    "tool_return_request_item": "received_at outcome reason received_by_member_id command_id",
}.items():
    RUNTIME[name] = {"SELECT": None, "INSERT": None, "UPDATE": columns.split()}
RUNTIME["tool"] = {"SELECT": None,
    "INSERT": "tenant_id id version created_at updated_at created_by_actor_id updated_by_actor_id code name model description custody_scope_id organization_id item_state".split(),
    # V31 invoker also advances the version and audit columns on a damaged receipt.
    "UPDATE": "item_state version updated_at updated_by_actor_id".split()}
# V32 settlement also reads occurred_at through the invoker trigger, even on a normal receipt.
RUNTIME["tool_loan_loss"] = {"SELECT": "tenant_id loan_id tool_id command_id reason occurred_at".split()}
RUNTIME["session_authority_activation"] = {"SELECT": """
    singleton state version activated_at activated_by_database_role activation_event_id activation_evidence_sha256
""".split()}
RUNTIME["account_session_authority"] = {"SELECT": """
    primary_id account_id binding_kind session_key_id session_digest bound_credential_version authenticated_at
    absolute_expires_at state version created_at created_event_id revoked_at revocation_event_id
""".split()}
# Same explicit framework list as the existing D0 lane; no CREATE/ownership granted.
for name in """
    act_ru_entitylink act_hi_entitylink act_ru_identitylink act_hi_identitylink act_ru_job act_ru_timer_job
    act_ru_suspended_job act_ru_deadletter_job act_ru_history_job act_ru_external_job flw_ru_batch flw_ru_batch_part
    act_ru_task act_hi_taskinst act_hi_tsk_log act_ru_variable act_hi_varinst act_ru_event_subscr act_hi_procinst
    act_hi_actinst act_hi_detail act_hi_comment act_hi_attachment act_ru_execution act_evt_log act_ru_actinst
    spring_session spring_session_attributes event_publication
""".split():
    RUNTIME[name] = {verb: None for verb in ("SELECT", "INSERT", "UPDATE", "DELETE")}

FIXTURE = {name: {"SELECT": None} for name in """
    tool_loan tool_loan_revision tool_loan_command_receipt tool_loan_item tool_return_request_item act_ru_task
""".split()}
FIXTURE.update({
    "tenant": {"SELECT": "id code name status".split()},
    "account": {"SELECT": "id login_key".split()},
    "tenant_member": {"SELECT": "tenant_id id account_id status".split(), "UPDATE": ["status"]},
    # V23 invoker triggers require an UPDATE column for FOR KEY SHARE in restore.
    # Match the existing D0 non-authority parent columns; do not grant role, code or scope-body writes.
    "permission": {"SELECT": "id code resource_type action".split(), "UPDATE": ["label"]},
    "scope_rule": {"SELECT": "tenant_id id kind".split(), "UPDATE": ["version_no"]},
    "role_grant": {"SELECT": "tenant_id role_id member_id".split()},
    "role_permission": {"SELECT": "tenant_id role_id permission_id scope_rule_id".split(),
                        "INSERT": "tenant_id role_id permission_id scope_rule_id".split(), "DELETE": None},
    "workflow_binding": {"SELECT": "tenant_id business_id process_instance_id".split()},
    "audit_event": {"SELECT": "id tenant_id resource_id result action".split()},
    "act_re_procdef": {"SELECT": "tenant_id_ id_ key_ version_ suspension_state_ resource_name_ deployment_id_".split()},
    "act_ge_bytearray": {"SELECT": "deployment_id_ name_ bytes_".split()},
})
RECEIVE = "public.mark_received_tool_damaged(uuid,uuid,timestamptz,uuid)"


def effective_table(manifest, table, operation):
    return operation in manifest.get(table, {}) and manifest[table][operation] is None


def effective_column(manifest, table, column, operation):
    return effective_table(manifest, table, operation) or column in (manifest.get(table, {}).get(operation) or ())


def apply_grants(owned, passwords):
    from psycopg2 import sql

    observe_database(owned, passwords[RUNTIME_ROLE])
    observe_fixture(owned, passwords[FIXTURE_ROLE])
    with closing(connect(owned, MIGRATOR_ROLE, passwords[MIGRATOR_ROLE])) as db, db, db.cursor() as q:
        q.execute("""select current_database(), current_user, session_user, current_setting('msf.ci_instance', true),
                            current_setting('server_version_num')::int,
                            rolsuper or rolbypassrls or rolinherit or rolcreatedb or rolcreaterole or rolreplication
                       from pg_roles where rolname=current_user""")
        row = q.fetchone()
        if (not row or row[:4] != ("msf_d2_issue_test", MIGRATOR_ROLE, MIGRATOR_ROLE, owned.instance)
                or not 180000 <= row[4] < 190000 or row[5] is not False):
            raise ValueError("Unexpected AU-08 grant owner")
        for role, manifest in ((RUNTIME_ROLE, RUNTIME), (FIXTURE_ROLE, FIXTURE)):
            for table, spec in manifest.items():
                for operation, columns in spec.items():
                    privilege = sql.SQL(operation)
                    if columns is not None:
                        privilege += sql.SQL(" ({})").format(sql.SQL(",").join(map(sql.Identifier, columns)))
                    q.execute(sql.SQL("grant {} on table {} to {}").format(
                        privilege, sql.Identifier("public", table), sql.Identifier(role)))
        q.execute(sql.SQL("grant execute on function " + RECEIVE + " to {}").format(sql.Identifier(RUNTIME_ROLE)))
        for role, manifest in ((RUNTIME_ROLE, RUNTIME), (FIXTURE_ROLE, FIXTURE)):
            verify_acl(q, role, manifest)
        q.execute("select has_function_privilege(%s,%s,'EXECUTE'),has_function_privilege(%s,%s,'EXECUTE')",
                  (RUNTIME_ROLE, RECEIVE, FIXTURE_ROLE, RECEIVE))
        if q.fetchone() != (True, False):
            raise ValueError("Unexpected AU-08 damaged-tool helper ACL")


def verify_acl(q, role, manifest):
    q.execute("""select c.relname, pg_get_userbyid(c.relowner), c.relrowsecurity and c.relforcerowsecurity,
                        op, has_table_privilege(%s,c.oid,op), has_table_privilege(%s,c.oid,op||' WITH GRANT OPTION')
                   from pg_class c cross join unnest(%s::text[]) op
                  where c.relnamespace='public'::regnamespace and c.relkind in ('r','p','v','m','f')""",
              (role, role, list(TABLE_OPS)))
    seen = set()
    for table, owner, forced, operation, actual, may_grant in q.fetchall():
        seen.add(table)
        if (owner != MIGRATOR_ROLE or may_grant or actual != effective_table(manifest, table, operation)
                or (table in {"tool", "tool_loan", "tool_loan_revision", "tool_return_request_item",
                              "role_permission", "tenant_member"} and not forced)):
            raise ValueError(f"Unexpected AU-08 table ACL or owner: {table}/{operation}")
    if not manifest.keys() <= seen:
        raise ValueError("AU-08 table manifest differs from the migrated schema")
    q.execute("""select c.relname,a.attname,op,has_column_privilege(%s,c.oid,a.attnum,op),
                        has_column_privilege(%s,c.oid,a.attnum,op||' WITH GRANT OPTION')
                   from pg_class c join pg_attribute a on a.attrelid=c.oid
                   cross join unnest(%s::text[]) op
                  where c.relnamespace='public'::regnamespace and c.relkind in ('r','p','v','m','f')
                    and a.attnum>0 and not a.attisdropped""", (role, role, list(COLUMN_OPS)))
    for table, column, operation, actual, may_grant in q.fetchall():
        if may_grant or actual != effective_column(manifest, table, column, operation):
            raise ValueError(f"Unexpected AU-08 column ACL: {table}/{column}/{operation}")
