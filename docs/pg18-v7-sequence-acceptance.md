# PG18 V7 finding-number sequence: acceptance evidence

Status: the designated NAS runner applied V7 and the exact column-level runtime grant to `might_software_factory_pg18_test` on PostgreSQL 18.6. It then ran the allocator acceptance from independent test commit `ff276290cc497650007e3a6106c2c5327f59539f`: **1 test passed, 0 failures, 0 errors, 0 skipped**. The execution receipt is `task-8/pg18-handoff/v7-sequence-execution-receipt.md`; this local integration task did not connect to the database or rerun the test. The receipt records zero committed test fixtures and closure of the temporary tunnel.

## Test layers and limits

`backend/src/integration-test/java/com/mightyoung/softwarefactory/FindingNumberSequencePostgresIT.java` is the real PostgreSQL 18 repository/RLS test. It runs the ported read-only `DatabaseTarget.preflightFromEnvironment` gate before fixture writes, requires the V7 history row and exact disposable database, and then uses the configured non-owner app role for allocation. It does not run Flyway, create schema objects, issue grants, bypass RLS, or write Flowable tables. It creates two random synthetic tenant rows itself, so it no longer depends on existing tenants. The migration identity must already own `public.tenant`; the test verifies this read-only before inserting each fixture row in its own transaction with matching `app.tenant_id`. It also requires the previously reviewed app-role `SELECT` access to `public.finding` and the V3 `tenant` rows.

The fixture uses only `tenant(id, code, name, status)` with `auth_epoch` left at its default. It needs no account, member, organization, permission, or workflow seed. It tracks both tenant IDs/codes and each `(tenant_id, business_day)` sequence key. Cleanup runs in one transaction per tenant, sets that tenant's local context, deletes only the test's date keys, confirms zero visible sequence rows before deleting the parent, deletes the parent by its exact ID and code, and verifies zero children/parent under the same tenant context after commit. It never uses an unscoped count to infer cleanup success.

The PG test exercises two-tenant/day allocation, 12 simultaneous allocations for one tenant/day, overflow from 9998 through 10000, cross-tenant visibility/write rejection, missing-context rejection, transaction-local context cleanup, and rollback of a failed allocator transaction. Before fixture writes, it validates the exact V7 policy and effective column privileges. It also validates that `public.tenant` has exactly the V3 `tenant_isolation` policy (`ALL`, `PUBLIC`, tenant-ID `USING` and `WITH CHECK`) and the V4 `tenant_login_lookup` policy (`SELECT`, `PUBLIC`, tenant-code `USING`, no `WITH CHECK`); additional or broadened policies fail the preflight. The successful run verified and removed its random tenant fixtures and sequence keys, leaving zero rows from that run.

The existing no-database `FindingWorkflowCommandServiceTest` covers same-key single creation and result replay, replay after Shanghai midnight without allocating on the new date, tenant/date isolation, and `9999` to `10000` business-code formatting. Those service assertions use a memory repository and workflow fixture. They do not establish PostgreSQL command-receipt persistence, production Flowable integration, or rollback of Flowable/runtime tables. The PG test is intentionally a repository-level allocator acceptance, not an end-to-end workflow test. Real create/idempotency/Flowable transaction acceptance remains pending the Flowable schema and grants.

The target gate requires the externally managed exact JDBC URL allowlist and a successful read-only role preflight. A loopback URL is accepted only for the approved PG18 test database when its port matches `PG18_TUNNEL_LOCAL_PORT` from the designated wrapper and its full URL is present in `LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST`. The wrapper's tunnel setup and allowlist are external trusted inputs; the Java gate does not treat an arbitrary localhost URL as proof of a tunnel. The no-database gate test rejects missing or mismatched ports, a different database, and a URL absent from the allowlist. The compatibility profile keeps Flyway, Flowable schema update, Spring Session initialization, SQL init, and Modulith schema initialization disabled. The final external target-gate run used a temporary loopback tunnel and closed it after the test.

To compile the integration source without connecting to PostgreSQL:

```sh
./mvnw -pl backend -P lan-postgres-compatibility -DskipTests test-compile
```

The target-gate and tenant-policy contract regression tests are pure input tests and do not open a database connection:

```sh
./mvnw -pl backend -P lan-postgres-compatibility -Dtest=LanPostgresDatabaseTargetTest,FindingTenantPolicyDefinitionTest test
```

The service-layer cases can be rerun without a database:

```sh
./mvnw -pl backend -Dtest=FindingWorkflowCommandServiceTest test
```

For a separately authorized rerun, the isolated Failsafe target is:

```sh
./mvnw -pl backend -P lan-postgres-compatibility -Dit.test=FindingNumberSequencePostgresIT verify
```

The database run also needs `LAN_PG_JDBC_URL`, `LAN_PG_TEST_JDBC_URL` (exactly equal), `LAN_PG_TEST_DATABASE_NAME`, `LAN_PG_DEDICATED_TEST_DB_ACK`, the separately managed `LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST`, and migration/app-role credentials supplied by the designated wrapper. For an approved local tunnel, the wrapper supplies `PG18_TUNNEL_LOCAL_PORT` and the exact local URL allowlist entry outside Maven. Do not reuse credentials or create a tunnel from this local development task.

Local verification on the follow-up branch: offline `test-compile` compiled the integration sources successfully; the profile's Surefire `test` phase passed 174 tests (0 failures/errors/skips), including the two pure target/policy classes. That phase did not execute Failsafe `*IT` classes and made no database connection.

## Runtime privilege delta

The migration role creates `public.finding_number_sequence`; the application runtime role must not own this table, have `SUPERUSER` or `BYPASSRLS`, or receive migration DDL/history access. The Java upsert in `JooqFindingWorkflowRepository.nextFindingCode` uses the `(tenant_id, business_day)` conflict target, reads `last_sequence`, writes the three inserted columns, updates only `last_sequence`, and returns `last_sequence`.

The receipt records that the **only V7 table grants** below were applied to the confirmed runtime role. Treat this as an audit record; do not rerun the grants blindly:

```sql
GRANT SELECT (tenant_id, business_day, last_sequence)
    ON TABLE public.finding_number_sequence TO might_software_factory_app;
GRANT INSERT (tenant_id, business_day, last_sequence)
    ON TABLE public.finding_number_sequence TO might_software_factory_app;
GRANT UPDATE (last_sequence)
    ON TABLE public.finding_number_sequence TO might_software_factory_app;
```

These are column grants for the SQL the application executes. Do not grant `DELETE`, `TRUNCATE`, `REFERENCES`, ownership, schema/table DDL, or broad role membership. Do not add Flowable, Session, P0, or unrelated table privileges as part of V7. Check effective privileges through inherited roles as well as direct ACLs. PostgreSQL requires `SELECT` on columns read by `ON CONFLICT` (including conflict-target columns and expressions) and `RETURNING`; see the [PostgreSQL 18 INSERT privilege rules](https://www.postgresql.org/docs/18/sql-insert.html).

The migration already enables and forces RLS and scopes rows through `app.tenant_id`. Confirm the runtime is not an owner/bypass role, the policy is present and forced, and each request sets `app.tenant_id` transaction-locally before sequence access. RLS does not replace SQL grants, and it does not constrain whole-table `TRUNCATE`; keep that privilege absent. See the [PostgreSQL 18 row-security rules](https://www.postgresql.org/docs/18/ddl-rowsecurity.html).

## Read-only preflight for a future repeat

1. Confirm the actual server is the existing PostgreSQL 18.6 instance and verify `current_database()` equals the DBA-confirmed disposable test database. The recorded target is `might_software_factory_pg18_test`. Abort on any mismatch; never fall back to another database.
2. Identify the exact migration and runtime roles. Verify the runtime role is not table owner, superuser, or RLS bypass, and that direct or inherited grants do not include `DELETE`, `TRUNCATE`, DDL, or unreviewed access to other databases.
3. Inspect whether V7 is already recorded in `flyway_schema_history` and whether the target sequence table exists. Stop on partial or unexpected state; do not edit migration history or retry blindly.
4. Review effective column grants for exactly the V7 privilege delta above. Do not execute grants from this document until the role and test database are independently verified and the operation is specifically approved.

## Acceptance results and remaining checks

The independent PG18.6 run from commit `ff276290cc497650007e3a6106c2c5327f59539f` completed these checks:

1. V7 was already applied to the exact target; the test verified the migration history, table owner and primary key, forced RLS policy, effective app-role column grants, and absence of unapproved privileges.
2. The allocator was exercised for two tenants, 12 concurrent allocations, `9999` to `10000`, mismatched and missing tenant contexts, context cleanup, and transaction rollback. The test removed only its own rows and asserted zero residue.
3. The existing service unit tests cover same-key replay across Shanghai midnight and per-tenant/date business-code behavior with a memory repository. Those are not real PostgreSQL command-receipt or end-to-end workflow results.

The report records Surefire XML SHA256 `6ad1b6d516aea25d6b6345033171336dd819d7fc998c5eb4655ef009f065bd9b` and text report SHA256 `41b75da6910cecbc7d8728f4e91841de903674aeca971785e9e49b1ab28439bb`. It identifies the executed IT source SHA256 as `80a4676193b7aad7d485fb76835442e3ce4e2f66c301baa494b649bcd541def6`. The complete secret-free result is in the execution receipt named above.

Still outstanding: no real HTTP login/current-user/create/list/detail flow; no real command receipt idempotency or same-key replay across midnight; no complete Flowable transaction, event publication, audit, and rollback acceptance. The PG test is a repository-level allocator acceptance, not an end-to-end finding workflow test. The local branch has not rerun the database test.

The PG18.6 result is separate from Java unit tests and does establish the listed V7 RLS, grants, allocator concurrency, rollback, and cleanup checks for its exact test run. It does not establish real API command idempotency or complete Flowable/auth integration. This document alone does not authorize another runtime database action; any rerun requires separate approval and the dedicated NAS execution thread.
