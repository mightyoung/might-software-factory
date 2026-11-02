# P0 PostgreSQL foundation receipt — 2026-11-02

Status: **PASS for the 13 selected database-backed foundation probes**. This follows the earlier [integration-readiness candidate](p0-integration-readiness-2026-11-02.md); its NOT RUN status describes the checkpoint before explicit provisioning approval. This receipt does not declare all P0 or production acceptance complete.

## Execution boundary

The user approved a fresh, exclusive disposable LAN database and a dedicated migration identity. Test Flyway V1 and the pinned Flowable 8.0.0, Spring Session JDBC 4.1.1, and Spring Modulith 2.1.1 vendor schemas were explicitly provisioned before application startup. Business migrations V2–V8 were not executed. The existing strict target/identity gates passed; no elevated identity was supplied to the test process. Connection values, credentials, and deployment inventory are intentionally excluded from this record.

Runtime: actual Temurin21.0.13, Java release 21, PostgreSQL 18. Flowable/JDBC schema initialization stayed disabled. The one global async acquisition probe ran only on the newly approved exclusive target. The restricted RLS probe identity retained its single-table test privilege surface.

## Final verification

```sh
./mvnw -o -B -ntp -Plan-postgres-compatibility \
  -Dit.test=LanPostgresCompatibilityIT,SessionPostgresTransactionIT,FlowableRestartRecoveryPostgresIT,ModulithPostgresTransactionIT \
  verify
```

Configuration was injected locally; no secrets appeared in command arguments or committed files.

| Suite | Result |
| --- | --- |
| `LanPostgresCompatibilityIT` | 8 passed: PostgreSQL/jOOQ/Flyway/Session compatibility, shared engine transaction manager, joint rollback, existing-task rollback/retry, persisted timer failure metadata, actual background retry, RLS isolation and pooled context reuse |
| `SessionPostgresTransactionIT` | 3 passed: shared DataSource/manager with `REQUIRES_NEW`, saved session update and invalidation survive outer business rollback while its outbox rolls back, no global expiry scheduler |
| `FlowableRestartRecoveryPostgresIT` | 1 passed: three independent engine instances recover committed task/timer/retry state, manually finish owned jobs and reject old-job replay without duplicate outbox |
| `ModulithPostgresTransactionIT` | 1 passed: actual `event_publication` row is present inside the engine/jOOQ/JDBC transaction, rolls back with it on a late failure, and the successful control completes through the existing production listener |
| Failsafe total | **13 tests, zero failures/errors/skips** |
| Non-database tests in the same run | 263 tests, zero failures/errors, 18 explicit macOS secure-directory capability skips; these skips are not passes |
| Packaging | BUILD SUCCESS, JAR packaged |

The final read-only post-run inspection found all 13 checked resource tables empty: test projections/transitions/outbox/RLS rows, sessions/attributes, deployments/executions/tasks/job queues, and event publications. It also verified the intended test-table ownership and restricted probe ACLs. No cleanup used a global data-delete operation.

## Corrections found through real execution and review

- A migration identity subject to FORCE RLS cannot prove cleanup using an unscoped `count(*)`. Cleanup assertions now use the restricted probe identity, both test tenant contexts, and every owned key.
- The basic Session round trip now always removes its independently committed session in `finally`.
- The first 12-test run exposed a startup auto-deployment outside the intended probe scope. The LAN-only profile now disables `flowable.check-process-definitions`, with pure configuration and runtime empty-resource assertions. The initial startup deployment was removed only after checking its exact identity, checked-in BPMN bytes, and absence of running/historical instances; the final rerun left no deployment behind. Production configuration is unchanged.
- The added Modulith test checks the real persistent publication registry, rather than using a custom outbox as a substitute for that boundary.

## Remaining acceptance scope

Not established here: business migration upgrade/rollback, complete production finding/audit command transactions or runtime grants, hard process termination or database crash recovery, backup restoration, external delivery/idempotency, capacity, dependency license/SBOM gates, or production deployment. Engine recreation is graceful and explicit job retry; it is not an abrupt-loss recovery claim. The production audit table is not replaced by the synthetic transition marker used in these infrastructure probes.

Linux JDK21 CI separately executes the full non-database suites with secure-directory capability required. Its result must be read at the submitted PR head; CI does not connect to this LAN database.
