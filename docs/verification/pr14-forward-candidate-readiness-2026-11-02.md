# PR14 forward-candidate readiness — 2026-11-02

Status: the forward candidate passed local PostgreSQL/Flowable close acceptance and final offline verification. Pre-migration V1 continuity also passed; hosted CI must pass on the pushed head before merge. Deployment remains gated. Execution continued into 2026-11-03 (Asia/Shanghai). This report does not approve a deployment, apply V13 to NAS, or complete the overall development plan.

## Source and migration boundary

The candidate integrates merged `dev` `1133f83d8399a9e41a9c2927e41329953f6487a9` (including the identity-session mainline and urgency correction) with original PR14 draft `39503493ff72356462b753b5e2a4134599281a82`. The known-source application installation chain is V2–V8, V11 and V12; the not-established close/relation migration is appended as **V13**. Existing migration sources remain unchanged. The [original PR14 source tree](https://github.com/mightyoung/might-software-factory/tree/39503493ff72356462b753b5e2a4134599281a82) retains the unapplied V10 draft and historical contract/design evidence. Its old test counts are not current candidate results.

Historical targets recording V9 without its original Java source remain **BLOCKED**. A known-source exclusive target cannot prove those installations upgradeable. No invented V9, rewritten history, `outOfOrder`, `repair` or `baseline` is part of this candidate. No NAS V13 application or V2 BPMN deployment is claimed here.

## Verified frontend scope

The temporary integration snapshot resolved FindingDetails conflicts by retaining the current command panel and the NOT_ESTABLISHED terminal-state presentation. The real command panel now explains that a not-established record is terminated and new evidence requires a related record; it exposes no actions for that state. The existing demo rendering has the same terminal explanation. URGENT backend reads map back to frontend IMMEDIATE, consistently with submission.

- A new command-panel regression first failed because the terminal explanation was absent: one failed, 29 passed in the targeted component/ledger run.
- The final complete Vitest run passed **272 tests in 25 files**, with no skipped tests. It includes the localhost contract mock service; execution required localhost listening outside the filesystem sandbox.
- `npm run build` passed TypeScript `tsc --noEmit` and Vite production build. No frontend conflict markers remain.
- The tests cover terminal state rendering/actions and existing HTTP adapter roundtrips. They are not Java API, real PostgreSQL, Flowable runtime or real browser acceptance.

The UI has **no new close-command entry**. The OpenAPI/generated contract includes `POST /api/v1/findings/{id}/close-not-established` with `expectedVersion`, `stage` (PERSON/SAFETY) and required `reason`; the frontend command union/HTTP adapter and action panel do not yet submit it. Related creation is not a new UI flow in this candidate. Backend close support must not be described as a complete browser close journey.

## Backend and database evidence

The final Linux arm64 Java21 clean verify passed **494 offline tests**, with zero failures, errors or skips and mandatory SecureDirectoryStream checks. This supersedes the preliminary 493-test pass before the approval-fingerprint correction. All integration sources compiled. The first isolated snapshot lacked the parent POM and then required contract/document files; those runner errors were retained and corrected before the final pass.

Four new exclusive local PostgreSQL18.6 databases use the pinned official image `postgres@sha256:5c4783a8cc3f0074912164f94dfa1be4e043438f0f707cf968d7af9a0d31a2c8`. The internal Docker network publishes no database ports. The Java runner uses pinned Maven/Temurin21.0.9+10. No NAS, human account, production data or existing role was changed.

- A known-source V12 target migrated normally from nine application migrations to V13, with exactly one pending migration, checksum validation and no history overrides. A separate clean target installed all ten migrations. Six fixed vendor resources provisioned Flowable/Session. Historical V9/V10 are absent, not fabricated.
- Runtime remains a nonowner without superuser, role membership, BYPASSRLS, schema CREATE or temporary-object privileges. FORCE RLS and ownership were audited. Binding remains SELECT/INSERT only: an actual runtime `SELECT ... FOR UPDATE` fails with PostgreSQL42501, while both close stages pass through the existing advisory lock protocol.
- **6/6 FindingNotEstablishedPostgresIT** cases passed actual HTTPS, PostgreSQL and Flowable: PERSON/SAFETY close preserves grade, pins V2 and ends the real instance; same-key replay and changed-payload conflict; both late-publication failures roll back business/receipt/confirmation/transition/audit/publication/engine participants and retry once; same-tenant related creation, cross-tenant hiding and terminal/link triggers; existing V1 approves normally and refuses close with no business effects.
- Existing **14 command**, **5 remediation** and **1 independent JVM restart** cases passed against V13 and the exact V1/V2 definitions. Remediation consumes actual retained immutable files, signed receipts and full content checks on Linux. These are 26 distinct cases across controlled batches, not one whole-suite execution. The first combined regression had 15 passes and five remediation startup errors because its database-specific fixture acknowledgment was absent; after correcting only the local test configuration, all five remediation cases passed.
- Ordinary APPROVE retains `String.strip()` and `confirmation-payload-v2`. Independent review found and rejected the draft's incompatible Unicode normalization. The regression failed before correction, then **43/43 focused tests** passed, including original PERSON/SAFETY NBSP-body replay, changed-payload conflict and immutable hashes/results/history. New close has its own Unicode normalization and namespace.
- The 310 Java/resource/contract/build files in the executed snapshot were hash-compared with the shared candidate. Test reports were preserved before the final clean. Independent source/contract review approved the corrected candidate.

## Remaining gates

A separate exclusive target passed the genuine pre-migration V1 continuity proof: seed an actual V1 definition/instance/task/binding under V12, apply exactly V13, compare all recorded pre-existing finding columns and complete recorded confirmation/receipt/transition/audit/publication/binding/engine rows, then start the current application over HTTPS. The original task survives; PERSON and SAFETY close each return409 with the full recorded participant snapshot unchanged. Both ordinary approvals succeed and continue the original instance/definition to SAFETY_DISPATCH_PENDING/version4. This is additional to the 26 batched integration cases, not a fixture created after migration.

The first independent runner omitted javac `-parameters` and returned400 during controller binding; the normal Maven build and six new IT cases were unaffected. After correcting the runner, an exact snapshot caught a legitimate asynchronous completion update from the preceding successful approval. The failed database/logs were preserved. A fourth fresh local target reran seed→V13→HTTPS proof successfully after adding a bounded ten-second wait for owned successful publications to complete before capturing the next baseline. Refused commands still use immediate exact comparisons, with no fields removed and no request retry. Independent review approved that stabilization. The standalone proof source is pinned as SHA256 `3f1907de0decfef2b24c6b6c8e25f69b8ebe9d17c1d16d873bac207553658ddc`.

Hosted CI must pass on the final pushed head before merge. Target-specific deployment still requires source-complete migration history and reviewed permissions, deliberate V2 deployment followed by V13/application rollout. No NAS migration or production deployment is authorized by this test evidence. The [PG18 checklist](../pg18-finding-evidence-integration-checklist.md#pr-14-forward-candidate-v13-and-v2-bpmn) records the release boundaries.

V1 instances must not be silently migrated. Return-for-information/supplement cycles, historical V9 recovery, trusted evidence ingestion and the rest of the overall approved plan retain their existing boundaries. This report and the PR14-specific contract updates do not change that overall plan.
