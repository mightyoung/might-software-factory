# Identity login PostgreSQL acceptance — 2026-11-02

Follow-up: [absolute session lifetime](session-absolute-lifetime-2026-11-02.md) adds the configurable authentication-age gate and restricts login to POST. The 8-scenario results below describe this earlier checkpoint.

This batch follows the [credential-version boundary receipt](credential-version-boundary-2026-11-02.md), based on the pending PR #21 candidate. It adds a real identity-table acceptance application and fixtures; production authentication and API behavior are unchanged.

## Scope and preparation

The existing exclusive disposable PostgreSQL 18 foundation target was reused. Only the checked-in V3 identity/directory/authorization and V4 password-login migrations were explicitly selected before application startup. They were recorded in a separate acceptance migration journal, with baseline 0 identifying the pre-existing infrastructure. V2 and V5–V8 were not applied or represented as applied. This is selected fresh-schema compatibility, not a complete production migration upgrade test.

The runtime receives identity-table reads and JDBC Session operations only. It does not own those tables, cannot create schema objects or mutate identity rows, and cannot read workflow-engine or publication data. Fixture seeding, password/version changes and revocation use a separate constrained migration connection. No elevated identity is supplied to Maven. Existing table ACLs are saved before temporary grants, restored in a finally path and compared after the run. Connection values, account inventory, credentials and local provisioning artifacts are excluded from this receipt.

Both runtime startup and fixture creation require the existing exact target/allowlist/disposable-database and separate-role gates, plus `LAN_PG_IDENTITY_ACCEPTANCE_ACK=I_CONFIRM_IDENTITY_FIXTURE_WRITES:<database>`. These checks run before fixture writes. Startup does not migrate schemas; Flowable engines, process auto-deployment, Modulith publication infrastructure and global Session expiry scheduling are disabled in the identity acceptance application.

## Real HTTP coverage

`IdentityLoginPostgresIT` starts a loopback HTTPS server with the production security configuration, password provider, identity/tenant/authorization repositories and current-session/reportable-organization controllers. There are no mocked repositories, injected authenticated principals or fabricated sessions. A private temporary certificate is trusted by the test client, with hostname verification enabled. The observer filter records actual servlet session IDs for precise inspection and cleanup.

| Scenario | Required evidence |
| --- | --- |
| CSRF, login, two tenants and logout | Missing/wrong CSRF is denied; bad credentials and an unjoined tenant are denied; real BCrypt login issues secure cookies and JDBC security contexts; each tenant sees its own current membership and reporting organization; logout deletes its session while the other tenant stays usable. |
| Password generation | Hash and credential version change atomically in the fixture transaction; old sessions and password are rejected; the new password issues a rotated session containing the new version. This does not test a password-change endpoint. |
| Authorization revocation | Grant expiry removes reporting scope; membership revocation denies session reads and login; restoring membership with a new epoch does not revive the old session; fresh login works and the other tenant stays isolated. |
| Four identity state changes | Account lock, account disable and principal disable reject both tenants; suspending one tenant rejects only that tenant. Old-session reads are denied and new login returns a generic error without exposing status or password details. |
| Runtime privileges and FORCE RLS | Actual account UPDATE is denied by PostgreSQL; 13 tenant tables enforce RLS; raw SQL sees no tenant without context, and only tenant A inside its transaction. The same physical pooled connection has clean tenant/login settings afterwards. |

Random fixture keys scope all mutations and cleanup. Session cleanup uses only observed owned session IDs. Tenant cleanup assertions retain the relevant RLS context; global fixture rows are checked separately. Shared pre-existing permission rows are preserved. This suite deliberately uses one runtime connection to exercise context reuse, not to measure concurrency or capacity.

## Verification

**PASS for all 8 selected real identity-login scenarios**, alongside all 15 existing database-backed foundation/session probes: **23 tests, zero failures/errors/skips**. Runtime was actual Temurin21.0.13, release 21, PostgreSQL 18.6. Reproduction requires explicit external schema/privilege preparation on an exclusive disposable target; the test suite does not grant privileges or provision a database automatically.

```sh
./mvnw -o -B -ntp -Plan-postgres-compatibility \
  -Dit.test=IdentityLoginPostgresIT,CredentialVersionSessionPostgresIT,LanPostgresCompatibilityIT,SessionPostgresTransactionIT,FlowableRestartRecoveryPostgresIT,ModulithPostgresTransactionIT \
  verify
```

The same run executed 336 non-database tests with zero failures/errors and 18 explicit macOS secure-filesystem capability skips. A separate default `clean verify` executed 285 tests with the same 18 skips. These skips are not passes. Both builds packaged successfully. The new three pure tests cover the identity-write opt-in, retained role separation and isolated runtime profile.

The post-run checks confirmed the original ACLs on all 19 temporarily granted tables were restored. A separate read-only inspection checked all 30 selected identity/foundation/session/engine/publication resource tables were empty, including rows otherwise hidden from the migration owner by FORCE RLS. It used no data-delete operation and was not supplied to the acceptance application. Independent static review found no blocking issue.

Review also identified a certificate-tool failure cleanup issue. Startup now terminates and waits for the owned child process on timeout/interruption, preserves the interrupt and original exception, and removes its temporary directory. A direct JDK21 interrupted-startup probe confirmed no live child or new TLS directory remained; the 8 identity scenarios were rerun after the correction.

Linux JDK21 CI runs both non-database suites with secure-filesystem capability required. Its result is revision-specific; CI does not connect to the LAN database or replace the real-service evidence above.

## Remaining scope

This is acceptance of the existing login → persisted session → authorized reads → revocation/re-login → logout path. It does not implement account registration/provisioning, password-change/reset endpoints, rate limiting, full business commands/audit transactions, production grants, complete migration upgrades, external delivery, abrupt-loss recovery or production deployment. The broader limits in the [P0 foundation receipt](p0-postgres-acceptance-2026-11-02.md) still apply.
