# Retained-evidence remediation acceptance — 2026-11-02

Five actual HTTPS/PostgreSQL remediation cases passed against production command, authorization, persistence, Flowable and event-publication components. A fresh Linux build also passed 467 offline tests with zero failures/errors/skips. This slice consumes explicitly fixture-provisioned synthetic files and metadata; it does **not** implement or accept production upload, malware scanning or the complete G4 gate.

## Actual cases

| Case | Observed result |
| --- | --- |
| Two remediation rounds | Dispatch v4 → submit v5 → reject v6/new round → redispatch to another member v7 → submit new evidence v8 → accept v9/CLOSED. Real tasks/assignees, notices, rounds, sealed manifests, signed fixed-version evidence and ended process history agree. Each command's serial replay preserves the result and participant counts. |
| Refusals | A submitter with legitimate verify permission still cannot verify their own work; stale version and the current assignee's same-finding evidence from the old round are rejected without committed increments. |
| Submission late failure | Actual event registration occurs before the injected exception; submission/snapshot/seal/revision, finding/task, receipt, audit and publication state return to their baseline. Same-key retry succeeds and replay adds nothing. |
| Rejection late failure | Actual verification/next-round/notice and engine changes are observed inside the transaction, then roll back together; same-key retry succeeds once. |
| Acceptance late failure | Actual process completion is observed before the injected exception. Runtime task and unfinished history return after rollback; retry closes the process once. |

The fixtures use real production login/CSRF. No injected authenticated principal, memory workflow, fake receipt or metadata-only URL substitutes for real file verification. Failure injection delegates to the actual publication registry before throwing; it is confined to test configuration.

## Production correction discovered by real execution

The first five-case run passed submission rollback but failed four cases on verification. A narrow diagnostic resolver retained the production response while recording only exception classes, SQLState and stack location. It identified the pinned-evidence check. A real database assertion then showed a stored timestamp's nanosecond remainder of 0 against the signed receipt's remainder of 512. PostgreSQL persists these timestamps at microsecond precision; the original issuer signed nanosecond precision.

A fixed-clock regression reproduced the defect with remainder 789. The issuer now truncates the timestamp to microseconds **before signing a new receipt**. Signature validation and snapshot/receipt equality remain strict; a 1ns snapshot difference still fails both policy and content opening. Signed re-reading, reopening and receipt reuse preserve the canonical time. No timestamp tolerance, read-time rounding, historical rewrite, policy bypass, SQL privilege expansion or workflow shortcut was introduced.

Initial failed logs, owned business histories and associated evidence are retained. Previously issued noncanonical receipts remain unchanged; their persisted timestamp mismatch continues to fail closed. This batch does not reconcile earlier synthetic snapshots created by the precision defect or establish a production data-upgrade path.

## Environment and resource boundary

The host macOS capability check found POSIX and unix:nlink but no SecureDirectoryStream. Actual acceptance instead used a local Linux arm64 container, pinned official Maven 3.9.11/Java21 image, Java **21.0.9+10** as reported by that image. This is a Java21 test environment, not evidence of the host's 21.0.13 patch baseline or a production runtime upgrade.

The container ran as a named non-root test UID with a read-only root filesystem, no Linux capabilities, no published ports, and bounded CPU/memory. A clean work copy excluded original environment files and Git credentials. Only dependency artifacts were mounted read-only; the test received dedicated runtime/migration inputs and no operator credentials. Network-free preflight first verified a real immutable file, signed receipt and pinned full-content read. Database fixture writes began only after explicit target/storage acknowledgements and effective privilege checks, including evidence sealing and metadata locking.

Private directories, retained signing key, immutable versions, receipt registry and per-run manifests remain together with their database ancestors. Storage/probe failures are hard failures, not skipped acceptance. Session cleanup is restricted to owned sessions; background global cleanup is disabled for this suite. Temporary containers exited and were removed. The batch added no database migration or runtime grant.

The final fresh Linux verify forced secure-storage capability checks: 467 offline tests and 5 integration cases passed, all with zero failures/errors/skips. File-adapter regressions executed on Linux rather than relying on the host's capability skips. Post-run role/migration/identity-event privilege audit passed.

## Remaining scope

Production bounded upload/quarantine/scanner/finalization, runtime metadata-writing permissions, browser assignment/evidence forms, actual cross-tenant/member content denials, evidence-edit/seal concurrency, late old-round commands and full-chain browser acceptance remain separate G4 work. This result does not sign off all G4, P1, deployment, hardware, capacity, recovery or the overall plan.
