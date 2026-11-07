# Tenant audit PostgreSQL acceptance

## Read-only privilege preflight

`TenantAuditPrivilegePostgresIT` is an opt-in prerequisite for the tenant audit query acceptance suite. It does not migrate schemas, seed users, assign capabilities, change grants, or activate the HTTP endpoint. Run it only against a separately reviewed test target and role. Environment acknowledgements are execution guards, not permission to create resources.

Use the existing LAN PostgreSQL compatibility target inputs and trusted target allowlist. The target parser requires the existing migration/application input shape, but this preflight opens only the configured application connection; it never connects with migration credentials. Do not substitute an unrelated NAS or production target when the reviewed configuration is unavailable. Do not put passwords in command arguments or committed files.

Select the class explicitly with the `lan-postgres-compatibility` Maven profile and `-Dit.test=TenantAuditPrivilegePostgresIT`, and set `LAN_PG_AUDIT_PRIVILEGE_PREFLIGHT=true` in the reviewed execution environment. The class is disabled without that exact flag so ordinary business-role acceptance remains separate. With the flag enabled, missing or inconsistent target, role, acknowledgement or credential inputs fail. Acceptance requires actual Failsafe execution of one test with zero failures, errors and skips, plus the reviewed target binding. A successful compilation, skipped test or green offline CI is not database acceptance.

The preflight uses one PostgreSQL 18 application connection in a read-only repeatable-read transaction and rolls it back before closing. It reads system catalogs and effective privilege functions, not audit-event contents. Necessary checks are:

- The connected database/current user/session user match the configured target. The role is not the database/schema owner, elevated, inheriting or a member of another role, and has the existing application CONNECT/public USAGE/no public CREATE baseline. Rejecting all role memberships is deliberately conservative; legitimate inherited role configurations are rejected rather than silently accepted or changed.
- `public.audit_event` is an ordinary non-owned table with RLS and FORCE RLS enabled.
- Effective column SELECT exists for exactly `tenant_id`, `id`, `actor_id`, `delegator_id`, `action`, `resource_type`, `resource_id`, `result`, `trace_id`, `policy_version` and `occurred_at`. Every required column exists once. All other current or future columns, including `reason` and `diff_redacted`, lack effective SELECT. Table-wide SELECT is rejected.
- Audit table write/reference/trigger/maintenance rights and column write/reference rights are absent. Effective SELECT on `security_event` and `authentication_event`, at table or column level, is absent. Missing tables, ambiguous metadata, null or malformed results fail closed.

The effective privilege functions account for PUBLIC and inherited grants; adding a narrow column grant cannot remove a broader grant. In particular, the existing business acceptance profile permits audit table SELECT and INSERT, so it cannot pass this read-only query profile. This test never revokes that profile or creates a replacement role/pool. A separately approved role/connection design remains necessary if the target has broader privileges.

## Remaining acceptance gates

Passing this catalog preflight proves only the stated necessary conditions at the observed snapshot. It does not prove correct RLS policy expressions, actual cross-tenant isolation, current HUMAN/capability authority, the complete identity-reader privilege set, absence of function-based privilege escalation, all-database least privilege, V23 trigger behavior, query plans or runtime activation.

Before enabling the query, retain separate real PostgreSQL/HTTPS evidence for V23 migration/catalog/constraint and parent-row concurrency behavior; production identity and AUDIT_TENANT authorization; two-tenant and platform-negative cases; exact keyset ordering/filtering; revocation commit orders and next-page reauthorization; statement cancellation and connection recovery; representative query plans; persistent Session/CSRF and final response headers. No simulated JDBC, H2 or controlled-identity container result closes these gates.

Current source delivery does not execute this preflight or close any real-database gate. Record the exact source SHA, target binding, runtime role evidence and Failsafe XML when a reviewed execution becomes available.
