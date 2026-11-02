# Production-path business acceptance — 2026-11-02

This batch executes the approved exclusive-target proposal and continues the authorization/navigation implementation. All business identities and records are synthetic. No existing-target migration history, existing account password, production deployment or PR merge was changed.

## Installation and privilege boundary

A new exclusive PostgreSQL18 target was installed from eight fixed application SQL migrations (V2–V8 and V11), six pinned Flowable/Spring Session schema resources, and the publication schema already provided by V5. The new history was created on an empty schema with no baseline, repair, out-of-order or invented V9 migration. A read-only audit verified 84 tables, migration-owned tables, separate non-elevated roles, FORCE RLS, identity read-only runtime access, append-only audit privileges and no runtime schema CREATE/TEMP/ownership/BYPASSRLS. No runtime privilege was expanded to make command tests pass. This is clean-install evidence, not a historical V9 recovery, an existing-target upgrade, host capacity, backup or production acceptance.

## Production corrections discovered by real tests

1. Plain jOOQ SQL bound OffsetDateTime parameters as varchar. PostgreSQL rejected discovered_at with SQLState 42804. Nine external timestamp parameters now explicitly cast to timestamptz, including transition/audit, confirmation, notice, submission and immutable evidence timestamps. The SQL regression failed before the correction and passes with it; actual create and progression now succeed.
2. Binding SELECT FOR UPDATE required an UPDATE privilege absent from the reviewed append-only runtime policy. Both workflow start and completion now take a transaction advisory binding lock before accessing the engine, then read the binding without UPDATE privileges. The helper reuses the same managed RW/READ_COMMITTED authorization guard, checks tenant GUC, rejects multiple bindings in one transaction, preserves stricter timeout limits and marks lock failures rollback-only. Lock domains and order are documented in ADR023.
3. HTTP login discarded Retry-After when mapping a 429 response. The session adapter now retains a bounded delay and the login form displays a countdown, blocks repeated submission and permits a manual retry at expiry. Missing/invalid headers use a short local pause. No automatic login retry or synthetic fallback was added.

4. Two form submissions in one React batch both observed the old submitting state. The existing AbortController now provides an immediate in-flight guard, released in finally. The regression failed with two adapter calls and now passes with one; a separate real-browser scene also sends one create request. Backend receipt idempotency remains authoritative.

## Actual PostgreSQL evidence

The following 64 distinct cases passed in controlled batches. A full initial run also executed 399 offline tests with zero failures/errors and 18 explicit macOS secure-directory capability skips. After targeted corrections, only affected suites were rerun. This is not a claim that one unqualified command ran every integration class: older P0 probe suites use a different approved target and are deliberately excluded from this business installation.

| Suite | Passed | Evidence |
| --- | ---: | --- |
| IdentityLoginPostgresIT | 15 | Production HTTPS authentication, session lifecycle, revocation, scoped choices; actual identity UPDATE and DDL refusal with SQLState42501 |
| CredentialVersionSessionPostgresIT | 2 | JDBC Session credential generation round-trip |
| NavigationPostgresIT | 3 | Real login, two-tenant entitlement differences, missing/disabled configuration, current scope/menu and minimal payload |
| AuthorizationConcurrencyPostgresIT | 22 | Eleven actor/candidate/tenant/member/grant/scope/organization changes in both commit orders, real persisted session, production policy, observed database blocking |
| WorkflowBindingLockPostgresIT | 7 | Direct binding-lock contention, independent bindings, commit/rollback release, strict timeout, unmanaged/mismatched context and scope refusal |
| FindingCommandPostgresIT | 14 | Production create/legacy initialize/double confirmation/dispatch, serial and parallel replay, midnight numbering, stale versions, access/configuration refusals, four rollback points and actual-command revocation ordering |
| FindingRestartPostgresIT | 1 | Two independently launched application JVMs plus separately opened real engines, exact persisted finding/receipt/binding/task/audit/publication state and no duplicate deployment |

Finding tests use real production login, command service, jOOQ repository, Flowable, append-only audit and Modulith JDBC registry. Test-only faults delegate to actual persistence before failing; no memory engine, fake outbox or injected authenticated principal substitutes for the production path. Failure checkpoints cover engine start, audit insertion, receipt completion and real publication registration, with zero committed participant/counter increments and a successful same-key retry.

Actual command concurrency tests observe pg_blocking_pids and advisory pg_locks, then prove both orders: credential revocation commits first and the command commits nothing; or the command commits first, retains its result, and the old session cannot write after revocation. Separate same-version confirmations advance at most once. The broader 22 policy cases supplement these command cases; they are not described as 22 separate full business command runs.

The restart observer initially used a one-connection pool, which cannot support Flowable ID allocation's nested transaction. The test now uses two bounded connections. Child processes inherit no operator/migration credentials, disable schema creation, automatic deployment, background jobs and global Session cleanup, and are stopped by the test. This proves an owned graceful application restart, not host loss, force-kill, asynchronous job recovery or disaster recovery.

## Browser and frontend evidence

Frontend contract generation, typecheck, 266 tests and production build pass. A separate HTTP-mode build was served through a test-local HTTPS reverse proxy to the real backend and database. The system Chrome browser used a narrowly pinned localhost certificate; the proxy verified backend TLS. No mock, request interception, HAR, trace, cookie export or credential logging was used.

Actual Chrome core scenario passed: CSRF/login, authoritative bootstrap/menu, schema/query, owned detail display, real different-tenant detail404, reload with scoped data, Secure/HttpOnly session cookie, and logout/protected reload401. The separate real-rate-limit scenario also passed: five incorrect passwords, actual429 with Retry-After, disabled countdown with no automatic requests, manual successful login after cooldown, missing-CSRF logout403 that preserves the session, then normal logout204.

Actual dependency-failure Chrome scenario passed: temporarily revoke only the approved entitlement SELECT, observe real backend503 and unavailable UI, restore the exact privilege and recover the original session. Disable only the owned tenant entitlement, observe bootstrap identity with no apps and menu/query403 without a fabricated login/logout, then re-enable and recover. The driver verifies target, role, fixture provenance and its saved baseline, and restores privileges/state in finally. The SELECT baseline was restored; entitlement version advanced monotonically.

Actual reporting Chrome scenarios passed after G1: duplicate same-draft requests agree on one command/finding, the list/detail match version1, and refresh creates nothing. The initial harness incorrectly expected a detail panel to remain open after a query; its failure and already-committed IDs were preserved, then a strictly read-only continuation verified the remaining steps. No fresh create was used to replace that result. A new independent scene after the synchronous guard fix exercised two same-batch form submits with exactly one actual create request and completed the full scenario. Separate owner read-only checks for both scenes verified one tenant increment, completed receipt, binding, root process, task, transition, audit and actual publication. These are synthetic live-service scenarios, not industry/site acceptance.

## Retention and remaining scope

Business histories, immutable records, engine instances and FK ancestors remain in the owned test target for reinspection. Only explicitly owned session records and local child processes are cleaned by fixtures. No audit trigger was disabled, schema was truncated, default privilege widened or role elevated. Overall host verification remains separate.

The historical V9 Java bytes remain unavailable; no clean-install result repairs that source gap. B3 audit/password-change work, B4 trusted evidence ingestion and remediation/verification, and later event/device/video/capacity/release phases retain their own contracts, resource authorizations and acceptance requirements. This batch is not completion of the entire overall development plan.
