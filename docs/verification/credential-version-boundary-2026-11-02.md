# Credential-version session boundary — 2026-11-02

Base: P0 candidate `73e8f06` (PR #20), not yet merged into dev at implementation time. This batch builds on that candidate and does not alter any schema, grant, credential or database connection configuration. The existing account `credential_version` column is used.

## Resulting behavior

- The login query reads the account credential generation with its status/hash. The provider transfers that positive generation to the server-side principal and tenant context; clients cannot supply it.
- Current-session reads bind the captured version in SQL. Finding read/action authorization reads account status/version together and rejects missing/mismatched state before resolving membership/roles. Existing active-account, principal, membership and authorization-epoch checks remain required. Candidate-assignee checks intentionally do not require a candidate's login session.
- The serial UID stays stable. A real synthetic fixture generated from the previous six-field principal deserializes with version 0 and is rejected; production has no constructor or fallback that defaults the missing version to 1. New principal versions survive serialization and real JDBC Session reload.
- Login JSON enforces the existing `additionalProperties: false` contract and required string types, rejects duplicate keys (including escaped equivalent names), extra root values, invalid roots/types and wildcard/non-JSON media types. The 4096-byte budget and existing field limits remain. Owned byte/password buffers are cleared on failure and partial I/O; successful password storage is transferred to the one-use token for its existing credential erasure.
- Real-encoder checks exposed BCrypt prefix truncation: a hash of a 72-byte password accepted appended ASCII/UTF-8 suffixes. Authentication now rejects empty, malformed-UTF-8 and over-72-byte input before matching, using a 73-byte owned buffer that is always cleared. Rejected inputs use a fixed bounded non-sensitive dummy probe and the same generic bad-credentials result, never a truncated match.

## Compatibility and transaction boundaries

Existing unversioned sessions must log in again on rollout; they are not silently upgraded. A stored version rejected against current account state uses the existing access-denied behavior; a missing/unversioned principal is unauthenticated. Public response DTOs are unchanged.

The JSON password field's 1–1024-character format limit is unchanged; it does not promise that BCrypt can authenticate that entire range. The authentication boundary accepts only valid UTF-8 inputs of 1–72 bytes. Previously truncated long passwords are intentionally no longer accepted and may need credential reset before use. Future credential writers must enforce this byte boundary as well.

The version check is part of existing authorization transactions, not an account lock or global request-cancellation mechanism. Standalone reads are configured read-only REPEATABLE_READ with REQUIRED propagation. Inside an existing workflow READ_COMMITTED transaction they join that transaction without upgrading isolation. Changes after the applicable read snapshot do not guarantee cancellation of that in-flight request or already-open content stream.

Password change/reset endpoints are not implemented here. Future writers must change the hash and monotonically advance `account.credential_version` in one transaction; changing a hash alone does not activate revocation. Do not reset the version to 1 or reuse older values.

## Verification and limits

Local verification uses actual Temurin21.0.13 and Java release 21. After the BCrypt correction, the final LAN-profile run executed all 333 non-database tests: 315 passed, zero failures/errors, 18 explicit secure-filesystem capability skips on macOS. These skips are not passes. Packaging succeeded. The suite includes 50 strict-JSON cases, 12 read/write credential-version cases and 11 provider cases including the real-encoder byte-boundary regressions.

The approved PostgreSQL foundation target was reused with its existing schemas/privileges:

```sh
./mvnw -o -B -ntp -Plan-postgres-compatibility \
  -Dit.test=CredentialVersionSessionPostgresIT,LanPostgresCompatibilityIT,SessionPostgresTransactionIT,FlowableRestartRecoveryPostgresIT,ModulithPostgresTransactionIT \
  verify
```

**15 real-PostgreSQL tests passed, zero failures/errors/skips**: the previous 13 foundation checks plus two Session serialization probes. The new probes verify a non-default credential version survives an actual JDBC-backed security-context round trip, and a legacy zero-version principal is still rejected after that round trip. They close their dedicated Spring context after the class and clean only their own sessions.

Identity/account SQL shape, login issuance and version changes, HTTP denial/re-login, and read/write authorization revocation use synthetic repositories/SQL providers in this batch. They are **not** real-PostgreSQL identity-table, password-reset or business-command acceptance. No business V2–V8 migration, account seeding or new grants were performed. The post-run inspection checks the existing foundation target's resources; connection details and identity inventory are omitted from the repository record.

Linux JDK21 CI runs the default and profile suites with secure-file capability required, without LAN database credentials. Its final result is revision-specific and must be read from this batch's PR head. All broader acceptance gaps from the P0 receipt remain unless explicitly covered above.
