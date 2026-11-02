# Absolute session lifetime — 2026-11-02

This batch builds on the pending PR #22 [identity login acceptance](identity-login-postgres-2026-11-02.md). It implements the existing design's configurable maximum authentication age, independently of Spring Session's sliding idle timeout.

## Behavior

- `software-factory.security.session.absolute-timeout` defaults to `12h`. The existing idle timeout remains `30m`. Invalid, non-positive, sub-millisecond and millisecond-overflow configuration is rejected at startup.
- Only a successful POST login records the server-side authentication `Instant` in the session. An older anonymous session does not shorten the login's lifetime. Reading the session never updates this marker; successful reauthentication establishes a new start time.
- After loading the security context and before CSRF/authorization/application handlers, verified authentication is rejected when its marker is missing, has the wrong type, lies in the future, or has reached the absolute deadline. The session is invalidated and authentication cleared. Legacy persisted sessions without this marker must log in again; they are not upgraded on read.
- Public CSRF bootstrap and fresh login remain reachable after expiration. The check governs request entry; it does not cancel work already running or streams already opened. It relies on the server clock; clock rollback past the authentication time fails closed. Principal serialization and database schema are unchanged.
- The previous login filter matched only the path, which left non-POST requests eligible for authentication. It now explicitly matches POST, preserving the CSRF-protected login contract. GET with valid login JSON cannot issue authenticated state.

The method-specific request matcher uses the supported [Spring Security request matcher API](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/web/servlet/util/matcher/PathPatternRequestMatcher.Builder.html), also checked against the pinned 7.1.2 library. This change adds no dependencies or production grants.

## Verification scope

The new unit tests cover the login origin, non-sliding deadline and exact boundary, reauthentication, malformed/legacy metadata, invalid configuration and filter ordering effects. Existing synthetic HTTP fixtures carry explicit authentication metadata; their timestamps use the same clock as the security policy.

The existing real HTTPS/PostgreSQL identity suite adds four scenarios: a deadline/re-login lifecycle and three persisted metadata failures (missing, wrong type, future). A test clock advances authentication age while JDBC Session retains its real idle clock. This proves real HTTP/JDBC reload and invalidation at controlled times, not a twelve-hour soak test. Attribute mutations first verify the observed session belongs to the current random fixture account.

**PASS for the selected session-lifetime behavior.** Actual runtime: Temurin21.0.13, Java release 21, PostgreSQL 18.6. Reproduction uses the existing exclusive disposable test target, explicit identity-fixture opt-in, pre-provisioned schema and temporary runtime grants described in the prior receipt; this batch adds no schema, role or privilege requirements.

| Verification | Result |
| --- | --- |
| Default clean build | 293 tests, zero failures/errors, 18 explicit macOS secure-filesystem capability skips; packaged successfully |
| LAN-profile non-database suite | 344 tests, zero failures/errors, the same 18 capability skips; skips are not passes |
| Identity HTTPS/JDBC Session suite | 12 tests passed, zero failures/errors/skips, including the four added lifetime/metadata scenarios and GET login regression |
| Existing foundation/session probes | 15 tests passed, zero failures/errors/skips |
| Failsafe total and package | 27 tests passed, zero failures/errors/skips; BUILD SUCCESS |
| Post-run inspection | Original ACLs restored on all 19 temporarily granted tables; separate read-only inspection found all 30 selected resource tables empty |
| Independent review | No blocking issue; in-flight limitation retained |
| Frontend contract consumers | Regenerated session API types; contract comparison, typecheck, 176 tests and production build passed locally |

The fixed-clock workflow HTTP fixture initially stamped synthetic sessions using wall time, so the new future-time gate correctly rejected them. The fixture now shares its existing fixed instant between workflow and session metadata; a subsequent sequential clean run passed. Linux JDK21 CI runs both offline suites with secure-filesystem capability required; its revision-specific result is recorded on the PR and does not replace LAN database evidence.

```sh
./mvnw -o -B -ntp clean verify
./mvnw -o -B -ntp -Plan-postgres-compatibility \
  -Dit.test=IdentityLoginPostgresIT,CredentialVersionSessionPostgresIT,LanPostgresCompatibilityIT,SessionPostgresTransactionIT,FlowableRestartRecoveryPostgresIT,ModulithPostgresTransactionIT \
  verify
```

Account initialization, invitation/password-reset endpoints, failure throttling, sensitive-action reauthentication and full business-command acceptance remain open. This batch does not declare the login deployment production-ready.
