# P0 engineering baseline

Current Java21 runtime/platform and probe-cleanup evidence (2026-11-02) is recorded in [the Java21 verification note](verification/java21-environment-and-p0-cleanup-2026-11-02.md). The checkpoint descriptions below are historical; real PostgreSQL acceptance remains open.

Status: first engineering slice delivered for review; P0 remains open. This document records delivered checks and the next integration gate; it does not mark P0 complete.

## Scope in this slice

- Maven reactor and wrapper for the Java management backend.
- Spring Boot, Spring Modulith, Flowable, jOOQ, Flyway, PostgreSQL JDBC, and Spring Session JDBC dependencies.
- Nine Spring Modulith boundaries, with an automated architecture verification test.
- A CI workflow using JDK 21 and the wrapper. CI intentionally runs no database-backed profile.
- An opt-in `lan-postgres-compatibility` integration profile against a dedicated disposable LAN PostgreSQL database. It contains no embedded or local database fallback.

The modules are `identity`, `tenancy`, `directory`, `authorization`, `applicationregistry`, `workflow`, `support`, `auditops`, and `dualprevention`. The business sample package is only a boundary placeholder; it is not a delivered workflow or business feature.

## Version and compatibility evidence

| Component | Selected version | Evidence in this slice | Still unverified |
|---|---:|---|---|
| Java source target | 21 | Maven compiler targets release 21 | A local JDK 21 runtime was not available; local compilation used JBR 25.0.3 |
| Spring Boot | 4.1.2 | Clean package/verify and runtime YAML/module scan after BOM alignment | Full application context start against PostgreSQL |
| Spring Modulith | 2.1.1 | Classpath resolution; all nine modules and declared dependency metadata verify | Production module contents and adapters |
| Flowable | 8.0.0 | Classpath resolution and main/test compilation | Flowable schema start, timer transaction behavior, callback behavior, and database recovery against PostgreSQL |
| jOOQ | 3.21.9 | Runtime/codegen artifacts resolve and the classpath check passes | PG18 metadata/code generation and SQL execution against the dedicated DB |
| Flyway | 12.4.0 | Core and PostgreSQL module resolve | Real migration execution on PG18 |
| Spring Session JDBC | 4.1.1 | Classpath check passes | Session table initialization and round trip on PG18 |

Flowable's released dependency line is based on Spring Boot 4.0.2 and brings Spring Framework 7.0.3/Security 7.0.2. The first runtime module test exposed a real mismatch: Boot 4.1.2's YAML loader called a Spring Framework method absent from the selected 7.0.3 `spring-beans` artifact. ADR 023 aligns Framework and Security to the versions managed by Boot 4.1.2 before the Modulith and Flowable BOM imports. After that change, the clean local build and module test pass. This is a dependency graph correction, not a vendor support declaration for Flowable on Boot 4.1.2. It does not prove Flowable schema creation, transaction participation, timer execution, or recovery; those checks require the dedicated LAN database and have not been run.

## Checks and environment

The default `clean verify` runs four tests: classpath compatibility, module boundary/dependency metadata, an explicit forbidden cross-module dependency probe, and the named Flowable delegate's three-write failure path. The latest local run passed all four with 0 failures, errors, or skips. Main and test Java sources compiled with `--release 21`, and the Spring Boot JAR packaging completed. The opt-in integration sources also compiled with the `lan-postgres-compatibility` profile. The local runtime was the observed JBR 25.0.3, not JDK 21. GitHub Actions `Backend CI` does not run LAN database tests; its status is revision-specific and should be checked against the current PR head or merge preview.

The `lan-postgres-compatibility` profile adds Failsafe tests that require all of the following environment values: `LAN_PG_JDBC_URL`, `LAN_PG_USERNAME`, `LAN_PG_PASSWORD`, `LAN_PG_TEST_DATABASE_NAME`, and `LAN_PG_DEDICATED_TEST_DB_ACK`. The URL parser rejects a small set of literal loopback host spellings and requires a named database; it does not verify LAN reachability, DNS resolution, routing, firewall rules, or database isolation, and is not a network security boundary. The operator must provide a dedicated disposable test database. Credentials must be separate from the URL, and the acknowledgement must equal `I_UNDERSTAND_THIS_IS_A_DISPOSABLE_DB:<database-name>`. The gate runs before the Spring test context can initialize schemas.

The compatibility profile uses a one-connection pool and a test-only Flyway migration. It checks PG18, jOOQ access, Flowable's use of the Boot `DataSource` and transaction manager, Spring Session JDBC round trip, transaction rollback across engine/projection/transition/outbox, timer-job failure rollback, and transaction-local RLS isolation across tenants. It requires a non-superuser/non-`BYPASSRLS` database role. It does not create any production schema.

This Mac has only the previously observed JBR 25 on `PATH`; the build compiles Java 21 bytecode via `--release 21`. No JDK or database was installed. PostgreSQL and Redis remain LAN dependencies. The LAN profile has not been executed because no connection parameters or dedicated test database were supplied, so Flowable timer and callback acceptance remains blocked. There is no callback application boundary in this skeleton yet; duplicate/late callback semantics, reconciliation/freeze/repair, production RLS policy coverage, and real tenant connection lifecycle still need implementation and live integration validation.

The CI job does not receive LAN credentials and does not attempt any DB connection. GitHub Actions terminal status is recorded with the pull request review evidence, not inferred from a successful local build.

## Dev-based transaction verification update — 2026-11-02

The current dev-based patch fixes the one-connection failure-listener false positive, strengthens retry metadata checks, and adds existing-task and background-executor probes. Its fresh default/profile compilation evidence and exclusive-database execution requirements are recorded in [the dev verification note](verification/p0-transaction-dev-2026-11-02.md). Earlier test counts and live-results statements above describe their recorded checkpoint, not the current branch. The stricter dev role/target preflight and disabled startup DDL are preserved.
