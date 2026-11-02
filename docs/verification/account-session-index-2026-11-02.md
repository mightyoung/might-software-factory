# Account-scoped JDBC Session index — 2026-11-02

This batch follows pending PR #24 and the [login attempt protection receipt](login-attempt-throttle-2026-11-02.md). It repairs the existing identity module's session-index foundation. It does not add a session enumeration or bulk revocation endpoint.

## Defect and resulting behavior

Before the fix, distinct account principals produced the same authentication name, `VerifiedSessionPrincipal`: the class had a redacted `toString()` but no named-principal interface. A direct JDK21 probe using the pinned authentication token reproduced that collision. Spring Security uses this fallback when the principal provides no supported name interface. [Pinned Security 7.1.2 source](https://github.com/spring-projects/spring-security/blob/e17858065427ef265d649e2e281a9cba7e5ff319/core/src/main/java/org/springframework/security/authentication/AbstractAuthenticationToken.java#L69-L95)

`VerifiedSessionPrincipal` now implements JDK `Principal`; `getName()` is the account UUID. Sessions for different tenants/members of one account share that account index, while other accounts use a different index. Login keys, actor/member/tenant IDs and the redacted class name are not index aliases. The redacted `toString()`, serial UID and all seven stored fields remain unchanged; no new identity can be selected by clients.

A corrupt deserialized principal without an account returns null rather than a shared fallback name. The pinned resolver omits a null index, and the existing session context resolver still rejects that principal. Legacy principals missing the credential version retain zero and remain unauthenticated even though their account identifier can be indexed. Naming a principal never authorizes it. [Pinned null-index handling](https://github.com/spring-projects/spring-session/blob/7b66dd0b42b131cb1191b30cec1a689de4e06a3b/spring-session-core/src/main/java/org/springframework/session/SingleIndexResolver.java#L47-L51)

## Compatibility and lookup limits

Ordinary HTTP session access updates last-access metadata. When that changed session is saved, JDBC Session recalculates its principal index; this can repair an active old row containing the fixed name. This batch performs no eager migration or full-table update. Unvisited old rows can remain under the old index until accessed, expired or reauthenticated, so the new index cannot yet be treated as an exhaustive inventory of all pre-rollout sessions. [Pinned request access/save](https://github.com/spring-projects/spring-session/blob/7b66dd0b42b131cb1191b30cec1a689de4e06a3b/spring-session-core/src/main/java/org/springframework/session/web/http/SessionRepositoryFilter.java#L219-L235), [pinned JDBC index recalculation](https://github.com/spring-projects/spring-session/blob/7b66dd0b42b131cb1191b30cec1a689de4e06a3b/spring-session-jdbc/src/main/java/org/springframework/session/jdbc/JdbcIndexedSessionRepository.java#L912-L925)

The default JDBC `findByPrincipalName` query does not filter expired rows. Its result is a lookup, not proof of current authentication, membership or credential validity. Future session-management consumers must independently enforce ownership, expiration and current security state. The normal HTTP session lookup still rejects and deletes expired sessions. An explicit principal-name session attribute takes precedence in the framework; no endpoint in this batch accepts such an attribute from clients. [Pinned index query](https://github.com/spring-projects/spring-session/blob/7b66dd0b42b131cb1191b30cec1a689de4e06a3b/spring-session-jdbc/src/main/java/org/springframework/session/jdbc/JdbcIndexedSessionRepository.java#L510-L527), [resolver precedence](https://github.com/spring-projects/spring-session/blob/7b66dd0b42b131cb1191b30cec1a689de4e06a3b/spring-session-core/src/main/java/org/springframework/session/PrincipalNameIndexResolver.java#L52-L61)

No database schema, role, production grants, dependency or public wire contract changes are required.

## Verification

**PASS for the selected account-index behavior.** Actual runtime: Temurin21.0.13, Java release 21 and PostgreSQL 18.6. The existing exclusive disposable identity target, explicit fixture-write opt-in and temporary runtime grants were reused; provisioning remained external to application startup.

| Verification | Result |
| --- | --- |
| Default clean build | 313 tests, zero failures/errors, 18 explicit macOS secure-filesystem capability skips; package succeeded |
| LAN-profile offline suite | 364 tests, zero failures/errors, the same 18 skips; skips are not passes |
| HTTPS/PostgreSQL identity suite | 15 passed, zero failures/errors/skips |
| Existing foundation/session probes | 15 passed, zero failures/errors/skips |
| Failsafe total and package | 30 passed, zero failures/errors/skips; BUILD SUCCESS |
| Post-run inspection | Original ACLs restored on all 19 temporarily granted tables; separate read-only inspection found all 30 selected resource tables empty |
| Independent review | No blocking issue |

Linux JDK21 CI verifies both offline suites with secure-filesystem capability required; its result is revision-specific and recorded on the PR. It does not connect to the LAN database or replace that acceptance evidence.

Four new offline tests exercise stable account grouping, real resolver/security-context serialization, corrupt missing-account rejection, and unchanged serialized field shape/UID. Existing legacy serialization tests additionally check the correct account index while retaining the zero-version denial.

The HTTPS/PostgreSQL suite retains thirteen identity scenarios and adds two:

- Two accounts, with two tenant sessions for the first account, persist the expected `principal_name` values and return only owned IDs through the real indexed repository. Individual logout removes the relevant index entry while the other account remains usable.
- A precise owned-session mutation models the previous fixed index; ordinary HTTP access restores its account index without changing another account. A separate owned expiry mutation proves an indexed expired row remains discoverable but cannot authenticate and is deleted on HTTP access.

Repository reloads are preceded by raw SQL assertions of the exact known session IDs, preventing deserialization of unrelated sessions. Cleanup uses observed session IDs, never a bulk principal-name delete. The second account's sessions and fixture are closed first, preserving the primary fixture's shared permission row until its dependents are gone.

```sh
./mvnw -o -B -ntp clean verify
./mvnw -o -B -ntp -Plan-postgres-compatibility \
  -Dit.test=IdentityLoginPostgresIT,CredentialVersionSessionPostgresIT,LanPostgresCompatibilityIT,SessionPostgresTransactionIT,FlowableRestartRecoveryPostgresIT,ModulithPostgresTransactionIT \
  verify
```

Account provisioning, invitation/reset, audited credential writers, sensitive-action reauthentication, shared throttling and public account-session management remain open. This is an index correctness slice, not production acceptance of those consumers.
