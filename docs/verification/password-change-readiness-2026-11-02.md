# Password change implementation readiness — 2026-11-02

This B3 slice adds authenticated self-service password change and credential-generation checks on protected requests. It builds on the clean-target business acceptance in the preceding batch. The new migration, additive runtime privileges and eight prepared password-change PostgreSQL cases have **not** been executed. Offline checks and source review do not establish real database acceptance.

## Behavior and transaction boundary

`POST /api/v1/account/password` requires the existing authenticated session and CSRF protection. It accepts only `currentPassword` and `newPassword`, rejects duplicate/unknown fields, oversized bodies and non-UTF-8 input, and returns 204 requiring a fresh login on success. The current password remains compatible with the existing 72-byte BCrypt limit. A new password needs at least 15 Unicode code points and at most 72 UTF-8 bytes; input is not trimmed or normalized. This is a bounded application policy, not a comprehensive standards-compliance claim.

Trusted identity resolution and bounded admission precede parsing and expensive password work. Pre-admission 429 responses do not parse passwords, compute hashes or insert security events. Admitted failures are recorded after the main transaction exits. BCrypt verification and hashing occur outside the global authorization lock; a request-local proof expires after 60 seconds and binds the exact identity, old hash and credential version.

Inside one top-level managed transaction, the authorization bridge acquires the global writer lock, sets tenant context, rechecks current identity and the prepared proof, updates account generation and credential hash using compare-and-set, and inserts the successful audit and security event. A failed success-audit operation rolls back both credential writes and success records. A separate transaction attempts the failure event. If commit raises after the callback completed, the outcome is conservatively UNKNOWN; a database outage may also prevent failure recording. No successful persistence is fabricated.

Every protected request checks the persisted account state and credential generation. A known stale or inactive account receives 401 and loses authentication; an unavailable identity store receives 503 without destroying an otherwise valid session. Principal/tenant/business authorization remains subject to its existing guard-time checks. Old sessions are never upgraded in place. Successful password change clears current authentication/cookies, with best-effort session cleanup after commit.

Identity owns the ports; authorization and audit modules implement them. The dependency boundary remains unchanged. Store writes require the exact managed bridge context, connection and active guard; ambient transactions and savepoint invalidation are covered. Audit differences contain only numeric credential generations. Passwords, hashes, request bodies and session values are excluded from audit payloads and diagnostic representations. Server-generated trace IDs are consistent across response bodies, headers and events.

## Review corrections

Review found that byte-level UTF-8 validation followed by Jackson byte parsing still accepted BOM-less UTF-16/32 through automatic encoding detection. Eight regression cases reproduced the issue in both the new password parser and existing login parser. Both now parse only the strictly decoded, owned character buffer, erased on success and failure. Genuine UTF-8 multilingual/emoji input remains covered. The OpenAPI 409 response now declares the actual error body and generated frontend types agree. Independent source re-review found no remaining blocking issues.

## Database gate and remaining evidence

V12 defines tenant-isolated, FORCE RLS, append-only `security_event` storage for authenticated password-change events. It includes explicit reason/result constraints and no automatic retention deletion or public query endpoint. It remains unapplied. The resource review proposes only three column UPDATE permissions across account/credential and INSERT on the new event table for the existing restricted runtime; event SELECT and other identity writes remain prohibited. No schema ownership, bypass privilege or new role is proposed.

The opt-in real PostgreSQL fixture checks the dedicated target acknowledgement, migration shape, RLS, append-only protections and exact effective privileges before inserting owned synthetic data. Its eight cases cover successful change, all old tenant sessions, wrong current password, actual audit-insert failure/rollback, two prepared requests contending on the global lock, strict request rejection, real privilege denials/rate limiting, and proof expiry. Fixtures preserve immutable histories and their ancestors. They do not reset human credentials or clean shared data.

The existing business-target grant profile is still identity-read-only. B3 must not silently relax that profile or apply its migration to make tests pass. Further database authorization and actual G3 execution are required before password change can be called accepted. Historical V9 recovery, production upgrade/deployment, browser password UI, trusted evidence ingestion and the rest of the overall plan remain separate work.

## Current local verification

A fresh Java21 clean Maven verify with integration sources compiled passed 462 offline tests with zero failures/errors and 18 explicit macOS secure-directory capability skips. Module dependency verification and full startup with actual listener/JDK proxies pass. The first restricted run could not bind the local test server; rerunning with local service access passed. Frontend generated contracts, typecheck and production build pass. These results do not replace the pending eight G3 real PostgreSQL cases.

Existing production-path PostgreSQL regressions were rerun at the B3 implementation: IdentityLoginPostgresIT 15/15 and FindingCommandPostgresIT 14/14 passed, zero failures/errors/skips. These use the previously approved identity-read-only grant profile; V12 and the password endpoint are deliberately excluded. New requests after account lock/disable or credential rotation now expect 401, while principal/tenant denials and writer-first in-flight command guard checks retain 403. No new migration or privilege was needed for these 29 cases.
