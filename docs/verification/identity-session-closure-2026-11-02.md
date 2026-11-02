# Identity login and session lifecycle acceptance — 2026-11-02

This batch extends the existing identity-table login/password acceptance with real persisted session rotation, independent application JVMs and actual dependency failures. It fixes a production response-boundary defect. All data belongs to synthetic fixtures in the previously approved exclusive target; no new migration, role or runtime grant was added.

## Actual PostgreSQL coverage

Thirty distinct actual HTTPS/PostgreSQL cases passed in controlled batches, plus two lower-level JDBC serialization cases. They were not all run by one unqualified command.

| Suite | Passed | Scope |
| --- | ---: | --- |
| IdentityLoginPostgresIT | 16 | Existing login/CSRF, two-tenant identity/directory reads, account index, expiration/absolute age, cooldown, generation/status/permission revocation and precise runtime privileges; new actual JDBC session fixation defense and Basic-entry refusal |
| IdentityLoginFailurePostgresIT | 3 | Actual credential-query timeout/recovery, late Session-write rollback/recovery, early response commit followed by real framework save retry without publishing an authentication cookie |
| IdentitySessionLifecyclePostgresIT | 3 | Same original sessions across graceful JVM restart; cross-node logout/replay/restart; cross-node password change with an unvisited persisted old-generation session and an unaffected other-account session |
| PasswordChangePostgresIT | 8 | Regression of actual password changes, old sessions, audit rollback, preparation race/expiry, request rejection, rate admission and privileges |
| CredentialVersionSessionPostgresIT | 2 | JDBC principal serialization/generation compatibility only; test-constructed principals, **not** additional identity-table HTTP login proof |

The 16-case identity run passed first. After explicit child JVM resource limits were added, the three lifecycle cases, three failures, eight password cases and two serialization cases passed again in a 16-case batch. Earlier lifecycle runs are retained but not double-counted. The final fresh Linux clean verify passed 472 offline tests with zero failures/errors/skips and secure-storage checks required. All integration cases above had zero failures/errors/skips. A post-run read-only migration/role/identity-event privilege audit passed.

## Persisted sessions and independent processes

The restart case retains the original two browser cookie jars and starts a new application PID at the same port. It does not log in again after restart. Actual persisted session IDs, verified actor/account/tenant/member, epochs, credential generation and original authentication instant agree before and after restart; protected current/bootstrap requests succeed.

The logout case deep-clones actual cookies into an independent jar for the other node. That node reads the same session and logs out. The original jar retains its cookie and is rejected, including after the original node restarts. A separate unvisited copy avoids proving logout merely by client-side cookie deletion.

The password case stops the node holding a second-tenant session before the other node changes the same account's password. Owner read-only inspection proves the unvisited old Session row still exists with generation1. A new PID rejects that original cookie, while the new password establishes generation2 and the old password fails. Another synthetic account retains its own original session identity and authentication age. This proves generation validation rather than deleting every indexed session.

A separate actual reauthentication case changes the selected tenant on an existing login, verifies real JDBC ID/browser-cookie rotation and removal of the old ID, replays the old independent cookie unsuccessfully, and checks the new identity/index. Correct Basic credentials still cannot bypass the JSON login entry.

Children receive a clean environment containing only runtime/TLS settings; no migration/operator credentials or fixture acknowledgements. DDL, Flyway, auto-deployment, jobs and global Session cleanup are disabled. Each child has a 512MiB maximum heap, 192MiB metaspace, 64MiB direct-memory budget, two active processors, bounded HTTP threads/connections and a two-connection pool. Owned children must stop gracefully; forced cleanup on an error is not counted as successful graceful acceptance. Private logs are retained in an explicitly ignored owner-only location. Only owned sessions/processes are cleaned; histories and FK ancestors remain.

## Real failures and production correction

A request-scoped JDBC instrument delegates to the actual production datasource. An independent owner connection briefly locks the account relation; actual PostgreSQL lock observation and SQLState57014 prove the credential query timed out inside a real read-only authentication transaction. The response is a generic503, no authenticated session is established, and the same browser can log in after the lock is released. No privilege was revoked or schema altered to manufacture this failure.

The late-write instrument executes real Session SQL and observes the parent row, SecurityContext and authentication-time attributes on the same non-autocommit connection before throwing a controlled exception. Initially the error escaped the framework filter and returned500. The new outer login-only persistence boundary maps an uncommitted database/transaction failure to the existing JSON503 contract, clears request authentication and removes authentication-cookie headers before expiring them. It preserves unrelated/security headers, clears stale response length/body and uses the same POST/path matcher as login. Its order precedes the configured Spring Session filter. It does not access the original container session or retry persistence. Already committed responses retain their original exception rather than pretending their status can change.

In the ordinary finally-save failure case, both real Session tables roll back by exact owned primary key; the failed ID cannot authenticate and a manual login recovers. The fixture distinguishes a genuinely received cookie from a constructed negative replay of a known rolled-back ID. No cookie/session value or serialized attribute is printed by the new assertions.

Spring Session4.1.1 has a separate boundary: an early flush can fail its first save and then save again in finally. A third actual case triggers precisely this sequence through the real success handler/response wrapper. It explicitly proves that the second save **committed** one owned Session and its attributes. The response is still503, exposes only expiring authentication cookies, and the browser remains unauthenticated; a new login succeeds. The unpublished owned row is removed only by fixture cleanup. Authentication reads and Session persistence are separate transactions. Neither all503 responses nor transport/commit uncertainty imply database rollback.

Five new offline boundary tests cover stale Content-Length/authentication-cookie removal, retained security/preferences headers, context-path matching, unknown commits without retry claims, committed responses, route/method isolation and filter ordering. Independent review found no remaining blocker.

## Remaining identity scope

This is bounded login/session acceptance, not production readiness or all P1 completion. Login-success/failure durable security events are not yet wired; the V12 authenticated password-change event model cannot be counted as login audit. Account onboarding, invitation/reset and actual delivery, OIDC/MFA, public session management, shared multi-instance throttling, representative performance/proxy deployment and abrupt host/process loss remain separate contracts and gates. The graceful restart results do not establish force-kill or disaster recovery. Existing in-flight authorization/snapshot boundaries remain unchanged.
