# P0 integration candidate — 2026-11-02

Follow-up: after explicit provisioning approval, the [13 selected PostgreSQL foundation probes passed](p0-postgres-acceptance-2026-11-02.md). The NOT RUN statements below preserve this earlier pre-provisioning checkpoint.

Dev base: `845f191`. This candidate integrates the previously separate PR #17 transaction/retry checks, #18 target guard, and #19 Java21 platform/owned-cleanup changes before adding the checks below. They have not been merged into dev. Conflict resolution retains the two-connection failure-recording pool, strict target/identity gates, Java21 Linux capability enforcement, jOOQ rollback writes, retry metadata, and owned cleanup. There is one shared probe-cleanup helper.

## Parallel development slices

1. `LanPostgresRolePreflightTest`: 32 pure checks. Each identity has a valid case and 15 independent single-field rejections, covering database/identity mismatch, database/schema ownership, elevated flags, membership, and missing/excess schema privileges.
2. `SessionPostgresTransactionIT`: three real-PostgreSQL probes for shared DataSource/transaction manager, independent Session updates, and independent invalidation. The default Spring Session JDBC transaction propagation is `REQUIRES_NEW`: Session persistence and invalidation must survive a business rollback while the business outbox rolls back. These are separate transaction boundaries, not a promise of Session/business atomicity. Explicit saves occur inside the outer transaction, and backend PID plus outbox visibility verify the outer transaction resumes afterward.
3. `FlowableRestartRecoveryPostgresIT`: three sequential, independently created engines reload a committed task, timer, and failed-job retry from the same database. A new delegate instance completes the retry and old job replay is rejected without a second event. This is graceful engine recreation with explicit job execution; it does not prove process-kill recovery or automatic acquisition. Only owned engines/pool/deployment/events are closed or removed. DDL, global acquisition/reset, history cleanup, and extra engine configurators remain disabled.

All new PostgreSQL probes call the existing strict read-only target/role preflight before creating their application context or pool. They require separately provisioned schemas; no startup initializer is enabled.

## Side-effect correction found during cross-review

Spring Session JDBC normally schedules a database-wide expired-session sweep every minute. That default exceeded the test-owned cleanup boundary even when each test's `finally` used its own ID. The LAN-only profile now sets `spring.session.jdbc.cleanup-cron: "-"`. The pure YAML test checks the value, and the new Session configuration probe checks `cleanupCron == "-"` and no `taskScheduler`. Production configuration is unchanged.

## Environment acceptance boundary

The configured LAN endpoint was inspected with read-only catalog queries. Connection details, database identities, and deployment inventory are intentionally omitted from this repository record. No account, password, grant, schema, or data was changed by the inspection.

The integrated PostgreSQL suite remains **NOT RUN** because the strict identity/provisioning prerequisites have not all been established. The background executor probe additionally requires operator-confirmed exclusive database use. Read-only inspection is not application startup, migration, transaction, or recovery acceptance.

## Validation boundaries

Local checks use installed Temurin21.0.13, release 21, and the existing dependency cache. Default verification exercises the existing test-only H2 startup; the LAN-profile command uses `-DskipITs` and only compiles database-backed tests. Neither is PG18 acceptance. macOS lacks secure-directory capability, so 18 real-file positive tests are explicitly skipped locally; Linux CI must enforce that capability and execute them.

| Check | Result |
| --- | --- |
| Default `clean verify` | BUILD SUCCESS: 215 tests, 197 executed successfully, 18 explicit macOS platform skips, zero failures/errors; JAR packaged |
| LAN-profile `-DskipITs verify` | BUILD SUCCESS: 263 non-database tests, 245 executed successfully, the same 18 platform skips, zero failures/errors; all new IT sources compiled |
| Targeted guard/delegate checks | 44 passed, zero failures/errors/skips |
| Guard recheck after expiry-sweep fix | 42 passed, zero failures/errors/skips; all IT sources recompiled |
| Read-only environment inspection | Connectivity/catalog inspection only; strict integration prerequisites remain incomplete |
| PostgreSQL transaction/Session/recovery probes | NOT RUN |
| `git diff --check` | PASS |

The default/profile results precede the final small expiry-sweep correction; its affected configuration regression and integration-source compilation passed in the subsequent targeted recheck. Remote Linux CI runs both full verification commands on the submitted final head. Remote results are revision-specific and must be read at that PR head.
