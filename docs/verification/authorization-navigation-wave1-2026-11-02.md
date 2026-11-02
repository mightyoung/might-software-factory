# Authorization and navigation implementation — 2026-11-02

This is the first execution batch of the reviewed overall development plan. It is a candidate for `dev`, with no merge or deployment claim.

## Result and boundaries

Finding commands now acquire transaction advisory authorization locks before their transaction callback, including command receipt replay. The guard verifies the actual managed connection is READ COMMITTED and read-write, uses global/tenant scopes shared with future security writers, preserves a stricter lock timeout, and rejects scope upgrades. Spring savepoint rollback invalidates the scope and prevents the outer transaction committing, even if no second guard call occurs. Direct JDBC/SQL savepoints bypass Spring callbacks and are outside the supported writer protocol. Database contention and transient connection failures use the existing retryable dependency-unavailable response; transaction configuration mistakes do not.

The backend exposes session bootstrap and a per-application menu, using registered application metadata, persisted tenant entitlement, and current organization-scoped permissions. Missing or disabled entitlements deny access. The same entitlement is checked independently by finding reads, reportable organization choices, evidence reads and workflow commands. V11 adds the FORCE-RLS entitlement table without enabling applications or granting runtime access. V9/V10 identifiers remain reserved for historical candidates.

The frontend reuses the earlier HTTP adapter candidate, consumes authoritative backend bootstrap/menu, and maps only recognized permissions to presentation controls. HTTP failures do not switch to synthetic data. Explicit Vitest source inclusion preserves dependency exclusions and prevents discovery of tests inside the isolated type-generator dependencies.

## Fresh evidence

| Check | Result |
| --- | --- |
| Java 21 full backend LAN-profile build with integration execution disabled | 398 tests, zero failures/errors; 18 explicitly skipped secure-directory tests on this macOS filesystem |
| Real PostgreSQL authorization guard probe | 8 passed, zero skips; real blocking observed using pg_blocking_pids and pg_locks |
| Frontend unit/integration suite | 24 files, 239 passed |
| Frontend production build and typecheck | Passed |
| OpenAPI generated types | All four contract outputs current |
| Chrome browser against contract mock service | 9 passed; mock evidence only |

The eight database tests cover both directions of global/tenant lock contention, a separate READ COMMITTED statement after waiting, transaction rollback lock release, preservation and failure of a strict lock timeout, rejection of joined RR/read-only transactions, and invalidation after a Spring nested rollback. They use the previously authorized FORCE-RLS probe table and its existing non-owner runtime role. They create no database resources and grant no privileges. They do **not** prove production identity revocation, production finding/engine/audit/outbox atomicity, real frontend/backend integration, or complete business acceptance.

## Migration reconciliation

A fresh read-only inspection distinguished three targets rather than combining their evidence: (A) the approved P0 foundation plus selected identity migrations; (B) a historical business installation recording V2–V8 and a JDBC V9 adoption; (C) an older installation using a different identity migration lineage. Target A has no complete business schema. Target B records a null V9 checksum, but the original Java migration bytes are unavailable. A historical source digest alone cannot restore the migration or prove upgrade compatibility. Target C is not compatible merely because some table names coincide. No existing target history was repaired, baselined, rewritten or upgraded in this batch.

A separate private resource proposal fixes known-source V2–V8/V11 and pinned vendor SQL for a new exclusive target, separate non-elevated migration/runtime identities, precise privileges and retained immutable audit records. It awaits concrete resource approval. This clean installation would not prove V9 recovery or an upgrade of an existing target. Runtime remains without DDL, ownership or BYPASSRLS; asynchronous engine execution remains disabled.

## Remaining gates

- G0 complete-source historical upgrade reconciliation remains blocked by the unavailable V9 bytes; the independent known-source target requires resource approval and installation checks.
- G1.0 remains open: actual identity, grant/scope phantom and candidate-assignee revocation commit-order tests are required, with every security writer participating in the guard protocol.
- G1 remains open: real production command success, replay and late-failure rollback across finding, engine, receipt, transition, audit and actual Modulith registry.
- G2-N and G2a/G2b remain open: production-backed navigation acceptance, real browser authentication/read integration, then real reporting after G1.
- B3/B4 and later phase epics retain their dependencies and acceptance exits; this batch does not complete the overall plan.

The prior standalone identity acceptance must be reprovisioned on an approved target containing V11, with explicit runtime SELECT on the new table. Its fixture now seeds owned entitlements and its security mutations use the guard; old test results cannot be carried forward as acceptance for this tree.
