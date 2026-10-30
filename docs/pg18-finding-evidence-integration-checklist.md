# PG18 finding and file-evidence integration checklist

This is a handoff for a later isolated PostgreSQL 18 integration run. It does not authorize connecting to a database, applying migrations, granting privileges, creating accounts, or seeding a shared environment. Migrations V2–V6 in this branch have not been applied.

## Database identities and schemas

- Use a migration identity for versioned SQL and a separate runtime identity for the application. The runtime role must not own application tables and must not have `SUPERUSER` or `BYPASSRLS`; do not grant it DDL or migration-history writes.
- The application uses `public`, Flowable 8.0.0 process tables, Spring Session JDBC tables, and the Spring Modulith `public.event_publication` registry. Runtime startup is configured not to create or update any of these schemas. Provision their schemas with their versioned/vendor DDL before starting the application.
- Tenant-scoped application tables use `FORCE ROW LEVEL SECURITY` and the transaction-local `app.tenant_id`. Verify the runtime role is subject to RLS and each tenant command sets this value inside its transaction.

## Current application SQL privilege surface

Grant only the listed operations to the runtime identity. `SELECT ... FOR UPDATE` and `SELECT ... FOR SHARE` require `SELECT` plus `UPDATE` on at least one column of every locked table in PostgreSQL 18. The locked tables below therefore need an explicit lock privilege decision; do not assume `SELECT` alone is enough. Prefer a reviewed column-level grant or an approved lock-function design over broad table-level `UPDATE` where that can meet the application’s lock requirement.

| Tables | Runtime operations used by current code |
| --- | --- |
| `finding` | `SELECT`, `INSERT`, `UPDATE`; `FOR UPDATE` lock |
| `workflow_binding` | `SELECT`, `INSERT`; `FOR UPDATE` lock (therefore lock privilege) |
| `finding_command_receipt` | `SELECT`, `INSERT`, `UPDATE`; duplicate-command `FOR UPDATE` lock |
| `finding_transition` | `INSERT` |
| `finding_confirmation` | `INSERT` |
| `remediation_round` | `INSERT`, `UPDATE` |
| `remediation_notice` | `SELECT`, `INSERT`, `UPDATE` |
| `evidence_set` | `SELECT`; `FOR UPDATE` lock (therefore lock privilege) |
| `evidence_item` | `SELECT`; `FOR UPDATE` lock (therefore lock privilege) |
| `remediation_submission` | `SELECT`, `INSERT` |
| `verification` | `INSERT` |
| `finding_stage_assignment` | `SELECT` |
| `audit_event` | `INSERT` only; its update/delete trigger is append-only |
| `file_object` | tenant-scoped `SELECT`; `FOR SHARE` lock (therefore lock privilege) |
| `file_link` | tenant-scoped `SELECT`; `FOR SHARE` lock (therefore lock privilege) |
| `event_publication` | Spring Modulith JDBC registry needs `SELECT`, `INSERT`, `UPDATE`, `DELETE` for publication, completion, resubmission, and cleanup |

Identity and authorization reads used by login and per-command authorization need `SELECT` on: `account`, `credential` (login only), `principal`, `tenant`, `tenant_member`, `org_unit`, `member_org`, `position`, `member_position`, `user_group`, `group_member`, `role`, `role_grant`, `role_permission`, `permission`, `scope_rule`, and `scope_org`. The application does not provision these rows through the finding workflow. RLS/login lookup policies and query-specific column access still need to be checked against the selected grants.

The bundled Flowable 8.0.0 PostgreSQL core create scripts define these process/common/history tables: `ACT_GE_PROPERTY`, `ACT_GE_BYTEARRAY`, `ACT_RU_ENTITYLINK`, `ACT_HI_ENTITYLINK`, `ACT_RU_IDENTITYLINK`, `ACT_HI_IDENTITYLINK`, `ACT_RU_JOB`, `ACT_RU_TIMER_JOB`, `ACT_RU_SUSPENDED_JOB`, `ACT_RU_DEADLETTER_JOB`, `ACT_RU_HISTORY_JOB`, `ACT_RU_EXTERNAL_JOB`, `FLW_RU_BATCH`, `FLW_RU_BATCH_PART`, `ACT_RU_TASK`, `ACT_HI_TASKINST`, `ACT_HI_TSK_LOG`, `ACT_RU_VARIABLE`, `ACT_HI_VARINST`, `ACT_RU_EVENT_SUBSCR`, `ACT_RE_DEPLOYMENT`, `ACT_RE_MODEL`, `ACT_RU_EXECUTION`, `ACT_RE_PROCDEF`, `ACT_EVT_LOG`, `ACT_PROCDEF_INFO`, `ACT_RU_ACTINST`, `ACT_HI_PROCINST`, `ACT_HI_ACTINST`, `ACT_HI_DETAIL`, `ACT_HI_COMMENT`, and `ACT_HI_ATTACHMENT`. Provision them separately because `flowable.database-schema-update=false`. The exact Flowable DML subset depends on active engine services/history settings and still needs a SQL trace from the disposable PG18 run; do not grant broad CRUD to these tables based on this inventory alone. Also inspect whether IDM or event-registry services are enabled before provisioning their separate `ACT_ID_*` or `FLW_EVENT_*` tables. Spring Session JDBC schema creation is disabled; precreate `SPRING_SESSION` and `SPRING_SESSION_ATTRIBUTES` and allow the session store's required DML. These vendor tables are not tenant-RLS protected by V2–V6; keep access limited to the backend role and validate the tenant-bound process queries during integration.

## Minimal isolated seed data

For one disposable test tenant only, prepare:

1. One `ACTIVE` tenant, one active human account/principal/member, and a password credential hash only if the HTTP login path is included. Keep auth epochs consistent. Use synthetic identities and a disposable password; do not reuse production credentials.
2. An active organization, current `member_org` membership for every test actor, and only the position/group memberships required by the chosen role-grant subjects.
3. The V5 finding permissions (`finding.create`, `finding.confirm.person`, `finding.confirm.safety`, `finding.dispatch`, `finding.remediate`, `finding.verify`, `finding.workflow.initialize`), plus active roles, role-permission scope rules, and time-valid grants covering only the test organization and test members.
4. Exactly one current `finding_stage_assignment` per organization for each stage `PERSON_CONFIRM`, `SAFETY_CONFIRM`, `SAFETY_DISPATCH`, and `SAFETY_VERIFY`. Each assignee must be active and have the matching action permission/scope. The same member may fill more than one stage only if the business test explicitly intends that; the command rules still prevent a remediation submitter from accepting their own work.
5. Deploy the checked-in `findingRemediationV1` BPMN definition through the Flowable migration/deployment procedure. `workflow_binding` is created by the application and must not be hand-seeded.
6. The file/evidence writer and upload service are not implemented in this slice. For a positive file-evidence test only, after the commands create the finding and open a remediation round, use an isolated fixture to insert the matching `evidence_set`, `evidence_item`, `file_object`, and `file_link` rows with the exact tenant/finding/round/member/item bindings. Add a test-only trusted verifier that produces a receipt bound to tenant, file, SHA-256, and storage version. The current production adapter intentionally returns no receipt, so a positive submission cannot pass until a trusted verifier is implemented and wired. No raw file bytes are required for the workflow test.

Do not seed findings, command receipts, transition history, confirmations, remediation rounds/notices/submissions, verifications, audit events, or event publications. Exercise those through the commands so transaction behavior is tested. Seed evidence rows only as isolated test fixtures because their writer service is not implemented here.

## PG18 acceptance checks still outstanding

- Apply V2–V6 only to a disposable PostgreSQL 18 test database with the migration identity; inspect legacy `evidence_item.file_id` rows before validating the new V6 foreign key.
- Exercise login/session, all workflow commands, Flowable process creation/completion, audit insertion, Modulith publication and completion in one real transaction boundary. Force a late failure and prove the finding, evidence locks, Flowable state, audit row, and publication all roll back together.
- Verify same-tenant reads, cross-tenant invisibility, `FORCE ROW LEVEL SECURITY` under the non-owner runtime role, and the exact minimum grants including lock privileges.
- Test a synthetic trusted receipt success and mismatch, revoked link, wrong tenant/member/finding/round, QUARANTINED/DELETING file, URL item, missing verifier, and rollback after verification failure.
- Confirm cleanup/retention and delivery behavior for `event_publication`, and independently validate production login bootstrap, reset, rate-limit, and credential-version revocation gates.

The positive content-verification path, real file-storage acceptance, Flowable/JOOQ/Modulith rollback, runtime grants, deployment seeds, and production identity bootstrap are not verified by the local synthetic tests.

## Reference

PostgreSQL 18 documents that all row-locking clauses (`FOR UPDATE`, `FOR NO KEY UPDATE`, `FOR SHARE`, `FOR KEY SHARE`) require `UPDATE` privilege on at least one column of each selected table: [PostgreSQL 18 `SELECT`](https://www.postgresql.org/docs/18/sql-select.html).
