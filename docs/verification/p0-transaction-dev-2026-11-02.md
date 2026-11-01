# P0 transaction verification on dev — 2026-11-02

Base: `845f191` (`origin/dev`). This change is limited to compatibility tests, their fixtures, local credential-file ignore rules, and this record. Production security configuration, business migrations, frontend contracts, and the runtime initialization policy are retained from dev.

## Fixed false-positive path

The compatibility test previously reduced the main pool to one connection. On a Flowable job failure, the failure listener starts a REQUIRES_NEW transaction while the original transaction still holds its connection. With a single connection this recording transaction waits for the pool timeout. Flowable can mask that secondary exception while returning the deliberate projection failure, so the old test could pass without proving failure recording succeeded.

The main pool now retains two connections. The manual retry probe checks the persisted retry timer, decremented retry count, exception stack, absence of an executable/dead-letter job, and then the successful second execution. The separate RLS application pool remains at one connection and retains the same-backend-PID context-reuse checks.

The test suite also adds:

- A jOOQ outbox write in the joint engine/projection/transition rollback path.
- Completion of an already committed task, rollback to its prior task/projection/version, and successful retry.
- A real background executor timer failure/retry probe. A bounded test-only barrier prevents the retry from writing before the rollback assertions finish; cleanup releases it before stopping the executor and restores the configuration/pool.
- A no-database regression for the exclusive background-executor target acknowledgement.

## Retained database protections

The dev exact URL allowlist, approved tunnel constraints, and pre-Spring read-only inspection of both database roles remain unchanged. Both roles must be non-elevated, non-owner, NOINHERIT, and without role memberships; only the migration identity may create in public. None of Flyway, SQL init, Modulith, Session, or Flowable schema initialization is enabled by the LAN test profile. Approved test tables and grants must be provisioned separately.

A background executor performs global job acquisition. Its new probe therefore requires an operator-confirmed **exclusive disposable database**, plus zero pre-existing timers and executable jobs. It must not run on a shared business database. In addition to the existing target/role gate values, supply:

```text
LAN_PG_EXCLUSIVE_ASYNC_TEST_DB_ACK=I_CONFIRM_EXCLUSIVE_ASYNC_TEST_DB:<exact-test-database-name>
```

The acknowledgement records the operator's assurance; it does not automatically prove exclusive access or prevent another client from inserting a job concurrently. Keep other clients stopped for the probe. The guard runs before deployment or executor startup and fails closed if the acknowledgement is absent or mismatched.

## Current-branch evidence

| Check | Result |
| --- | --- |
| Default `clean verify` | PASS: 212 tests, zero failures/errors/skips, JAR packaged |
| `-Plan-postgres-compatibility -DskipITs verify` | PASS: 225 non-database tests, zero failures/errors/skips; all integration sources compile |
| LAN PostgreSQL execution on this dev-based branch | NOT RUN: current stricter roles/provisioning/exclusive target conditions have not been established for this branch |
| Remote JDK21 CI | Consult the PR head checks; local results are not remote CI evidence |

Local environment: JBR 25.0.3, Java compilation target 21. The startup regression's H2 database is test-only and does not replace PostgreSQL acceptance. The profile run explicitly used `SERVER_ADDRESS=127.0.0.1` for its temporary startup-test HTTP listener. An earlier unprivileged local attempt could not bind that listener; rerunning with local execution permission succeeded without source changes.

Historical PG18.6 results from the earlier local baseline do not establish the dev-based branch's live acceptance. This record intentionally reports only fresh checks on the current implementation. Full LAN acceptance remains an explicit review gate, so the PR is initially draft.
