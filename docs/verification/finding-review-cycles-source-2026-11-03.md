# Finding review cycles and reporter supplements: source verification

Date: 2026-11-03. Scope: the G006 source batch on `codex/finding-review-cycles-20261103`, based on `5fd77662918141e29ee916f2f89da3bcdbaf3943`. The accepted R2 implementation-plan identity is SHA-256 `51dfe7eb7b33a96ba16d2d10fb2acaa91e29e23cea4734fdeb91a4aebe1f6bd6`.

This checkpoint records implementation, source review and local verification. G006 and the wider P0–P2 plan remain incomplete. No database migration, new runtime grant, process deployment or actual-target activation ran for this batch.

## Implemented behavior

| Slice | Source behavior |
| --- | --- |
| 1. Domain and persistence | Adds `LEGACY`/`CYCLE_V1` review protocols and a review cycle independent of remediation round. V17 defines append-only cycles, decisions, reporter supplements and command-participant proof, composite provenance constraints, FORCE RLS, immutable legacy confirmation/transition history, and result-cycle receipt rules. Existing completed legacy NULL-cycle receipts remain readable through the explicit legacy namespace allowlist; newly completed receipts require a cycle. |
| 2. Workflow boundary | Adds the separate V3 BPMN and tenant-specific exact-definition ID/resource-hash pin. Task authority binds the original guarded transaction, physical connection, task identity and synchronization lifetime; completion rechecks the binding. Public workflow ports provide exact-ID initiation and nonmutating bound-definition reads. Application configuration explicitly disables startup process-definition scanning. |
| 3. Review commands | PERSON or SAFETY can return a finding for information with a reason. The original reporter appends a statement, opens cycle+1 and restarts PERSON review before SAFETY review. Return and supplement preserve remediation round and grade; only SAFETY APPROVE accepts GENERAL/MAJOR. Current permission, eligible distinct reviewers, nonreporter rules, same-cycle PERSON proof and actual task authority precede new effects. Replay rechecks current authority and immutable original participation; changed payloads conflict. |
| 4. HTTP, history and contracts | Adds bounded strict review-decision/supplement parsers, session/CSRF-protected routes, explicit safe errors, and scoped typed review history with authenticated keyset cursors. Legacy response shapes and fingerprint namespaces remain intact. History reports stored identity and unavailable display provenance without inventing historical names or grades. Finding ledger schema version 5 exposes cycle/protocol without changing default fields. |
| 5. Desktop consumer | Selects legacy or V3 routes from server protocol and authorized action hints. Adds return, reporter supplement and history views. Same-record transient failures preserve the exact pending payload/key; scope changes and 401/403 clear protected state, and late reads cannot restore a denied selection. The mock V3 fixture is explicit test tooling. |
| 6. New initiation routing | With initiation disabled, new findings retain the V2 route. When enabled, fresh create/unbound DISCOVERED initialization resolves the tenant's exact V3 pin before command claims/effects and fails unavailable if it cannot validate the pin. Successful old create/initialization receipts remain replayable after current authorization without needing a new pin. Existing bound cases keep their original definitions. |

New initiation defaults to `false`, with an empty tenant-pin map. This source switch neither deploys V3 nor migrates existing V1/V2 instances. Even with initiation disabled, the expanded repositories require the V17 schema and appropriate runtime column permissions before this application version can run against a target.

The source-only migration is `backend/src/main/resources/db/migration/V17__finding_review_cycles_and_supplements.sql`, SHA-256 `b99491ea23f2f7df6ab47df84ea0ae462d4dd83784f73190115a8549fe5c2731`. V17 is **UNAPPLIED** and its proposed runtime ACL changes are **UNGRANTED**. This report does not authorize applying them.

## Executed verification

Backend full-verification evidence: `/tmp/msf-review-cycles-integrated-linux-20261103/full-proof/verification-summary.json`. The run used the existing pinned Linux ARM64 Maven image, Java 21, offline dependencies and mandatory SecureDirectoryStream capability. Image identity: `maven@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237`, local image ID `sha256:3ead0ff36a4b796440e451013a4ce803dbd02f07b6c5b634cc2ad67927dfcc10`. Exact runner arguments and logs remain in the local evidence directories.

| Check | Recorded result and limit |
| --- | --- |
| Default backend verification | 697 tests across 106 suites; 0 failures/errors/skips; exit 0. Discovery audit covers the default test sources. |
| PostgreSQL compatibility profile verification | 752 tests across 111 suites; 0 failures/errors/skips; exit 0. These are the same 697 tests plus 55 guard tests, not 1,449 unique tests. |
| Secure storage | Adapter 18 and platform 2 tests passed with mandatory secure-storage capability and no skips in both runs. |
| Integration compilation in full run | All 25 `*IT` classes compiled. Failsafe execution was skipped; zero Failsafe XML reports and no actual PostgreSQL integration execution. |
| Frontend unit tests | 300 tests across 28 files passed. |
| Frontend typecheck/build | Node 22 typecheck and production build passed. |
| Generated API types | Cached generator check passed for all 7 generated contract files. |
| Mock browser journey | 10 Chrome tests passed against the local mock server, including repeated returns/supplements and same-key retry after an uncertain result. This does not prove actual Java/PostgreSQL/Flowable or real-account browser behavior. |

Frontend logs are `/private/tmp/g006-frontend-fullunit-r2.log`, `g006-frontend-typecheck-r2.log`, `g006-frontend-build-r2.log`, `g006-frontend-api-check-r2.log` and `g006-frontend-mockchrome-r2-attempt1.log`.

The first integrated compilation exposed four ambiguous generic assertions in the new integration test; explicit type witnesses corrected them before the successful full run. Earlier failed compilation/test diagnostics are retained. They do not establish runtime database results.

## Later integration-test preparedness repairs

After the full verification above, independent source review identified and repaired three issues in the two new integration-test files:

- Require each needed finding review-column SELECT/INSERT/UPDATE privilege and receipt result-cycle SELECT/UPDATE privilege, joined with AND, before fixture writes or deployment. The four-table SELECT AND INSERT gates remain.
- Expand rollback snapshots to 12 Flowable tables scoped through owned process/execution/task identities, including variables, activity, identity links, detail and referenced bytearrays. The mappings were checked against the pinned Flowable 8 DDL. Diagnostics expose row counts only. Absence of unreferenced orphan bytearrays remains unproved.
- Reuse five real authenticated sessions, validate them through the production session endpoint and reauthenticate after 401. Production login throttles remain unchanged.

Independent preparedness re-review returned **CLEAR** for these repairs. That verdict concerns source preparedness, not executed acceptance.

A separate Linux profile compile-only gate then passed, exit 0: 223 main and 153 test sources compiled, with all 25 IT class files present. It executed **zero tests**, produced no Surefire/Failsafe XML and performed no database work. Evidence: `/tmp/msf-review-cycles-it-repair-linux-20261103/compile-verification-summary.json`, `compile-result.json` and `compile-20261103T100406626478Z.log`.

## Source binding and review

| Artifact | Identity and scope |
| --- | --- |
| Full backend run inputs | 412 entries in `full-proof/source-manifest.json`; manifest SHA-256 `de03ac30e1e9dc97cfdac0d9aca7ac31a5a57144988af56dd767b6770fcbecc0`. This is the snapshot before the two later IT repairs. |
| Repaired-source compile inputs | 412 entries in `/tmp/msf-review-cycles-it-repair-linux-20261103/source-manifest.json`; SHA-256 `e5c97a8313688d3f60005ddc2a9f42952f1e90144fa30d63fb55f03abd9d1c94`. The only input differences from the full-run snapshot are the two IT files below. All current inputs matched this manifest during report preparation. |
| `FindingReviewCyclePostgresIT.java` | Final SHA-256 `037d708a9b6a57384b9a658527fb94797f87010063e25eb3abc500e4d8db6b56`. |
| `FindingReviewCyclePostgresFixture.java` | Final SHA-256 `0573d53af6aa862e7f4a620f92ffe9a61b9b2cf94fbc2d74c8b1e3b9b8613cc6`. |
| Full-run packaged JAR | SHA-256 `33d07a81ea23f4ffdc4cc774a1b7dfc4c189e9881c8799022296f21f98d687f7`, 51,814,581 bytes. The subsequent changes affect integration-test sources only. |
| Frontend inputs | 91-file `/private/tmp/g006-frontend-source-r2.manifest`; path-NUL/bytes-NUL aggregate SHA-256 `c831b7c9bf2c1b0a3a849fe1bf0e82ba47b1cc6706b6f90faa7b16f6d75ad56e`. |

The 697/752 results remain bound to their original snapshot. The later gate proves repaired IT compilation only; it is not a rerun of those tests or actual integration execution. This documentation-only report is outside the application manifests. Local raw logs and manifests are not committed by this report.

Native independent lanes reported source approval/CLEAR for command/workflow authority, HTTP/contracts, history/schema version 5, desktop scope/error handling and final initiation routing. These are session review verdicts, not hosted CI results or signed runtime acceptance.

The bounded `ai-slop-cleaner` writer pass recommended no source changes, and the conductor independently accepted that recommendation. Plan/report SHA-256: `529132c01d3ac5c559239a546c0b6674c2c44b24274b7502f9ba375ac028c485`. Reviewed compatibility constructors, legacy receipt/proof rules, disabled initiation, exact retry state and unavailable history display have concrete behavior requirements. The pass found no justified deletion or simplification; it does not close the aggregate final quality gate. Existing module architecture/dependency tests passed. No standalone security scan ran; no frontend lint script is available.

## Remaining acceptance

- Apply only separately authorized schema/grants to the approved exclusive test target, then execute the real repository/HTTPS/Flowable integration tests. V15/V16/V17 source presence and compiled IT classes are not applied-target evidence.
- Prove current authorization, RLS, structural triggers, concurrent replay, security commit-order barriers and complete atomic rollback against actual PostgreSQL/Flowable. The source snapshot expansion is preparation, not rollback proof.
- Create and retain genuine PRE17 V1/V2 instances using the preserved old application/resource snapshot, then prove upgrade continuity and unchanged old receipt/fingerprint behavior. Writing legacy-looking rows with the new application cannot substitute for that gate. Missing original V9 remains a separate historical-target limitation.
- Prove zero startup process-deployment delta, restart continuity, repeated review cycles and the complete remediation path on the actual target.
- Run the real HTTPS/Secure-cookie/CSRF desktop journey with distinct actual test accounts, including authorization changes and unknown-outcome retry. Mock Chrome results do not close this gate.
- Complete actual producer/scanner/evidence integration and the remaining cross-story P0–P2 acceptance. No CLEAN/READY claim follows from review-cycle source or mocked services.

These gates remain **NOT_RUN/open**. This batch supplies reviewable source and scoped verification; it establishes neither G006 completion nor production readiness.

## Follow-up: reject PostgreSQL-incompatible NUL text

After the original source checkpoint at `eacd741535ef8ccd52e825e49c79c0b2a9654075`, a read-only PostgreSQL 18 probe and a Java 21 constructor probe exposed a new V3 input gap: review reasons and supplement statements accepted U+0000, which PostgreSQL text cannot store. The shared V3 text validator now rejects that character before command-service execution. Legacy text validation, fingerprints, parser structure and SQL are unchanged. Independent source review cleared the minimal correction.

A fresh isolated Linux Java 21 focused gate passed **93 tests across 5 classes**, with zero failures/errors/skips: review-cycle contracts 6, parser 8, controller 7, legacy workflow contracts 13 and command service 59. Three new regressions executed at the value-contract, JSON-parser and HTTP boundaries; the HTTP regression verifies rejection before either command service. Evidence: `/tmp/msf-review-cycles-nul-linux-20261103/focused-verification-summary.json`. Its 412-file input manifest SHA-256 is `8594783d42605c1b646ed0931a2555efa196dae8d9f84b153f8778797e770b88`, unchanged before and after execution.

| Corrected source | SHA-256 |
| --- | --- |
| `FindingReviewCycleContracts.java` | `313a2a93b2c9ce2631835302cce1c9e3fa271ec33e3f3c304098d5517f120a99` |
| `FindingReviewCycleContractTest.java` | `cce94746e4b534fcfae7e494050a01175f4cb78f620dcc59c232ca5ec82cbcc4` |
| `FindingReviewCycleRequestParserTest.java` | `0075c7fd45c0d58dbc9937509301ed391a1ae6bca7fe2f1201071def6f75f81f` |
| `FindingReviewCycleControllerHttpTest.java` | `b7e3b5c3ef6d94ee07a4d0cc421af84a42c0eedebba241a2f65ac9890ed760dd` |

The original checkpoint's hosted [Backend CI](https://github.com/mightyoung/might-software-factory/actions/runs/41616873278) and [Frontend CI](https://github.com/mightyoung/might-software-factory/actions/runs/41616873327) succeeded at `eacd741`; those results predate this correction. The earlier 697/752 full-run and repaired-IT compile evidence remain bound to their respective older snapshots. This focused 93-test gate verifies the four-file correction and selected legacy regressions; it is not a fresh full-suite or actual database/IT run. No migration, grant, process deployment or acceptance fixture write occurred for this correction. All remaining acceptance gates above stay open.
