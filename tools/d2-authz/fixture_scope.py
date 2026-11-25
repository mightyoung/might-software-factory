"""Unwired fixture transaction contract; it does not open connections.

A future project-only bootstrap must independently create and observe the
marker-derived tenant in the owned database before this contract can be used.
Existing local acceptance keeps its original entry point and data.
"""

from contextlib import contextmanager
from uuid import UUID, uuid5

from hosted_contract import DATABASE, canonical_uuid
from hosted_observation import FIXTURE_ROLE, OwnedContainer

TENANT_CODE = "d1seed"
TENANT_NAME = "D1 isolated tooling seed v1"
# psycopg2/libpq transaction status PQTRANS_IDLE. No driver import in offline guards.
TRANSACTION_IDLE = 0


def synthetic_tenant_id(instance):
    """No caller-supplied tenant identifier: only the observed instance determines it."""
    if not canonical_uuid(instance):
        raise ValueError("Invalid disposable AU-08 instance")
    return str(uuid5(UUID(instance), "msf/d2-authz/d1seed"))


@contextmanager
def fixture_transaction(connection, owned):
    """Give only an idle dedicated fixture connection one verified local scope.

    The caller must supply an OwnedContainer obtained from the actual runner
    inspection and use the separately observed fixture role. The body must not
    commit, rollback, or change scope itself.
    """
    if not isinstance(owned, OwnedContainer):
        raise ValueError("Missing owned AU-08 database observation")
    tenant_id = synthetic_tenant_id(owned.instance)
    if connection.autocommit is not False or connection.info.transaction_status != TRANSACTION_IDLE:
        raise ValueError("AU-08 fixture requires a fresh transaction")
    with connection, connection.cursor() as cursor:
        cursor.execute("""
            select current_database(), current_user, session_user,
                   current_setting('msf.ci_instance', true),
                   nullif(current_setting('app.tenant_id', true), '')
            from pg_catalog.pg_roles r
            where r.rolname = current_user and r.rolcanlogin and not (
                r.rolsuper or r.rolbypassrls or r.rolinherit or r.rolreplication
                or r.rolcreatedb or r.rolcreaterole
                or exists(select 1 from pg_catalog.pg_auth_members m where m.member = r.oid)
            )
            and current_setting('server_version_num')::int >= 180000
            and current_setting('server_version_num')::int < 190000
            and not pg_catalog.has_database_privilege(current_database(), 'CREATE,TEMP')
            and not pg_catalog.has_schema_privilege('public', 'CREATE')
            and not exists(select 1 from pg_catalog.pg_database d
                           where d.datname = current_database() and d.datdba = r.oid)
            """)
        if cursor.fetchone() != (DATABASE, FIXTURE_ROLE, FIXTURE_ROLE, owned.instance, None):
            raise ValueError("AU-08 fixture connection has the wrong instance, PostgreSQL version, role or inherited context")
        cursor.execute("select pg_catalog.set_config('app.tenant_id', %s, true)", (tenant_id,))
        if cursor.fetchone() != (tenant_id,):
            raise ValueError("Cannot bind AU-08 transaction to the owned tenant")
        cursor.execute("select id::text,code,name,status from public.tenant where id = %s::uuid", (tenant_id,))
        if cursor.fetchone() != (tenant_id, TENANT_CODE, TENANT_NAME, "ACTIVE"):
            raise ValueError("Cannot verify the owned synthetic AU-08 tenant")
        yield cursor
