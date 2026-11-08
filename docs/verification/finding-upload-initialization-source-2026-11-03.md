# Finding upload intent and evidence-set initialization: source verification

Date: 2026-11-03. Scope: the reviewed G021 upload-intent core, G022 metadata HTTP surface, and G024 empty evidence-set initialization batch, based on `1f05b976dd70ef5952c2254e73dbab181711b883` on `codex/upload-intent-core-20261103`.

This is a local source-verification checkpoint. It does not close the stories or establish database, producer, browser, or production acceptance.

## Source behavior

- The workflow task read validates the persisted revision-1/submission-1 binding and exact tenant, definition, process, task, and assignment context. It uses the caller's managed connection and checks that Flowable uses the same physical connection. Commands require the existing authorization command guard in a READ COMMITTED write transaction; status reads require a read-only REPEATABLE READ snapshot. Guard validation does not acquire or upgrade locks.
- Upload reservation derives current owner identity from the session, checks current action/assignment and evidence-set ownership, validates optimistic versions, and persists an immutable declaration with an audit event. A tenant quota lock serializes quota accounting and actor-scoped idempotency. Authorized replay checks the current context before returning the original intent; expiry is derived from the transaction snapshot.
- Upload readiness is fail-closed by default. Capability probing occurs outside the guarded transaction, with capability currency checked before commit. The supplied default readiness implementation cannot enable an actual producer. An intent is metadata only: it does not create bytes, scanner claims, evidence items, file metadata, or workflow transitions.
- G022 adds POST reservation and GET owner status under `/api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-uploads`. Parsing is bounded and token-based, rejects extra/duplicate fields and coercion, enforces JSON UTF-8 and declaration limits, and uses the existing session/CSRF chain. Responses are `no-store`; first reservation returns 201 and replay returns 200. Domain errors have explicit safe HTTP mappings.
- G024 adds POST `/api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-sets`. It derives ownership from the session and checks the current remediation assignment, finding version, idempotency, and open-set quotas. The set, immutable receipt, and audit event share one guarded transaction. The initial set is empty, UPLOADING, revision 0, unverified, and unsealed. Initialization does not require a producer or advance finding/evidence revisions or workflow state.
- V15 and V16 add source definitions for tenant-isolated immutable upload intents and initialization receipts, including structural provenance triggers and FORCE RLS. These files are source-only in this checkpoint; their presence is not evidence of applied schema or runtime grants.
- Startup repository coverage now expects 26 repositories and explicitly includes the three new repository beans while retaining the JDK proxy assertion for every repository.

## Independent review boundary

The following are native review-lane verdicts communicated during this session, not persistent CI results or a signed acceptance gate:

| Lane | Reviewed scope and verdict |
| --- | --- |
| `workflow_task_read_review` | Original workflow/guard slice: APPROVE. Historical source inventory: `/tmp/workflow-task-read-20261103-sha256.txt`. |
| `upload_core_architecture_review` | R4 pinned-context correction: focused CLEAR. Historical inventory: `/tmp/g021-upload-core-owned-sha256-r4.txt`. Later precise domain-error changes were separately approved by `upload_http_review`; the old inventory is not claimed to match every final file. |
| `upload_http_review` | G022 HTTP slice: APPROVE after the 33-test targeted run. G024 final HTTP slice: APPROVE after correcting the missing task-conflict 409 code in the YAML contract. |
| `evidence_set_initialization_review` | G024 domain slice, ten owned files: CLEAR. Inventory: `/tmp/g024-evidence-set-initialization-owned-sha256-r1.txt`. |

The final V16 SHA-256 is `7d405b429b556e7757a26dfa7f7cfe826c334165462e5048a1658a6edb5fade8`. The current Linux manifest below, rather than historical review inventories, binds the final application source.

## Executed verification

The final run used the already available pinned Linux ARM64 Maven image `maven@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237`, local image ID `sha256:3ead0ff36a4b796440e451013a4ce803dbd02f07b6c5b634cc2ad67927dfcc10`. Containers ran without network access or image pulls, with a read-only root filesystem and the existing Maven cache mounted read-only. The runner records Java/Maven versions in its logs.

Both commands used offline Maven, the existing cache, the cached Mockito agent, and mandatory secure-storage capability (`software-factory.test.require-secure-storage=true`, also passed to the test JVM). The executed Maven goal sets were:

```text
mvn -o -B -ntp [recorded local cache/JVM arguments] clean verify
mvn -o -B -ntp [recorded local cache/JVM arguments] -Plan-postgres-compatibility -DskipITs verify
```

The exact commands and container arguments remain in the local runner scripts and command JSON files under the evidence directory below.

| Check | Actual result |
| --- | --- |
| Default clean verify | 609 tests across 94 suites; 0 failures, 0 errors, 0 skips; exit 0. Discovery audit has no missing default test source. |
| Compatibility profile verify | 664 tests across 99 suites; 0 failures, 0 errors, 0 skips; exit 0. This is the same 609 tests plus 55 pure guard tests, not 1,273 unique tests. |
| Secure storage | `LocalPrivateFileStorageAdapterTest`: 18 passed; `LocalPrivateFileStoragePlatformTest`: 2 passed, with SecureDirectoryStream mandatory, in both runs. |
| Integration source compilation | All 24 `*IT` classes compiled, including the new 13-test PostgreSQL upload-intent class. Failsafe execution was skipped; no actual PostgreSQL integration test executed. |
| G022 targeted group | 33 tests passed, 0 failures/errors/skips, in `/tmp/g022-http-tests-r3.log`. |
| G024 targeted group | 45 tests passed, 0 failures/errors/skips, in `/tmp/g024-http-tests-r2.log`. |
| Final affected G024 HTTP group | 7 tests passed, 0 failures/errors/skips, in `/tmp/g024-http-task-conflict-r1.log`. |
| OpenAPI parsing/type generation | Both YAML contracts parsed and generated types with cached `openapi-typescript` 7.13.0. G022 log: `/tmp/g022-upload-openapi-parse.log`; final G024 log: `/tmp/g024-initialization-task-conflict-openapi.log` (earlier G024 log: `/tmp/g024-initialization-openapi-parse.log`). |

These targeted groups overlap the final Linux suites; they are not additional unique-test totals. HTTP tests use controlled test collaborators and do not prove a real repository-backed HTTP deployment.

The first Linux attempt is retained in the local evidence directory. It exposed the outdated startup repository-count assertion after adding the three repositories. The final assertion preserves all repository proxy checks and the corrected second attempt passed; the first attempt is not an unresolved current product failure.

## Source and artifact binding

Local evidence root: `/tmp/msf-upload-initialization-linux-20261103/attempt2/`.

- `verification-summary.json`: final results, compilation inventory, artifact sizes/hashes, and unchanged-source flags.
- `default-discovery-audit.json`, `default-surefire-reports/`, and `profile-surefire-reports/`: test discovery and per-suite XML evidence.
- `default-command.json`, `profile-command.json`, `default.sh`, `profile.sh`, and corresponding timestamped logs: exact executed commands and output.
- `source-manifest.json`: 380 source/build inputs; file SHA-256 `9670ca2f35158014a0c51339c6af6ad07f28c54097addd68b8d349f854e750c5`. Before staging, all 380 current inputs matched their recorded hashes and sizes, and all 56 changed/new application and contract files were included. This report is a documentation-only addition outside that application manifest.
- Packaged `backend-0.1.0-SNAPSHOT.jar`: 51,719,543 bytes; SHA-256 `617f02c6547fd0b91d88041d12e891a2680d42886cf4c8689e17ee007f49533b`.

The raw evidence is local and is not included in this commit. The checkpoint contains the 56 application/contract files and this report only. Runtime lock/state files, environment files, private provisioning artifacts, and the approved G006 R2 plan are outside the commit. A credential-pattern scan of the known batch found no matching private-key, AWS access-key, GitHub-token, or OpenAI project-key patterns; this is a bounded scan, not a general security certification.

## Remaining acceptance gates

Actual V15 new-table grant approval is pending. No V15/V16 DDL, runtime privilege change, or real database IT execution is established here. The approved read-only local target preflight concerns the existing V14 target and remains separate from this checkpoint; the existing container is preserved.

Real PostgreSQL/RLS/trigger/concurrency behavior, repository-backed HTTP, Flowable READ COMMITTED/REPEATABLE READ behavior against the actual engine/database, scanner and upload producer integration, browser workflows, and production acceptance remain unverified. The compiled 13-test PostgreSQL upload-intent suite must not be presented as executed acceptance. This report authorizes no deployment or story-completion inference.
