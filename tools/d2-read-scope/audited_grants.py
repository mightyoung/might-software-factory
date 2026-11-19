"""Audited permission changes for the isolated synthetic handover acceptance."""


def prepare_fixture(connection, tenant):
    """Restore the synthetic K1 permission after prior acceptance runs."""
    with connection:
        with connection.cursor() as query:
            query.execute("select current_database()")
            assert query.fetchone()[0] == "msf_d2_read_scope_test"
            query.execute("""insert into role_permission(tenant_id,role_id,permission_id,scope_rule_id)
                select r.tenant_id,r.id,p.id,rp.scope_rule_id
                from role r join role_permission rp on rp.tenant_id=r.tenant_id and rp.role_id=r.id
                join permission existing on existing.id=rp.permission_id and existing.code='tool.read'
                cross join permission p where r.tenant_id=%s and r.code='D1-keeper' and p.code='tool_loan.receive'
                on conflict do nothing""", (tenant,))


def audit_baseline(connection, tenant):
    with connection:
        with connection.cursor() as query:
            query.execute("select id::text from permission_change_event where tenant_id=%s", (tenant,))
            return {row[0] for row in query.fetchall()}


def verify_change(connection, tenant, role_code, direction, baseline):
    """Verify one real administrator event with precisely the receive permission delta."""
    assert direction in ("grant", "revoke")
    with connection:
        with connection.cursor() as query:
            query.execute("""select e.id::text,r.code,a.login_key,e.added_permission_codes,
                e.removed_permission_codes,e.trace_id::text,e.occurred_at::text
                from permission_change_event e join role r on r.tenant_id=e.tenant_id and r.id=e.role_id
                join tenant_member m on m.tenant_id=e.tenant_id and m.id=e.actor_member_id
                join account a on a.id=m.account_id where e.tenant_id=%s""", (tenant,))
            events = [row for row in query.fetchall() if row[0] not in baseline]
            assert len(events) == 1, "Expected exactly one new permission-change event"
            event_id, code, actor, added, removed, trace, occurred = events[0]
            assert code == role_code and actor == "d1seed-admin"
            expected_added = ["tool_loan.receive"] if direction == "grant" else []
            expected_removed = ["tool_loan.receive"] if direction == "revoke" else []
            assert added == expected_added and removed == expected_removed
            assert trace and occurred
            return {"eventId": event_id, "role": code, "actor": actor, "direction": direction,
                    "permission": "tool_loan.receive", "traceId": trace, "occurredAt": occurred}
