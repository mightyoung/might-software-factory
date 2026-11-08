# Evidence-set initialization: acceptance source preparation

Date: 2026-11-03. Scope: G024 integration-test source added after `62a1d596c2f6ac445cde4c1c67dd6308a2de891c` (PR #37). This batch changes no application implementation, migration, runtime grant or configuration. G024 and the wider P0–P2 acceptance remain incomplete.

## Prepared behavior checks

`FindingEvidenceSetInitializationPostgresIT` defines **15 planned invocations**: eight ordinary tests, four current-authority variants and three fault variants. They have **not executed**. The source exercises the production initializer through actual HTTPS/session/CSRF interfaces and requires an explicitly acknowledged owned target. It prepares a real V2 process/binding and assigned remediation task, while business metadata is seeded directly; this does not prove the whole create-to-dispatch business journey.

| Prepared check | Intended assertion; runtime result remains NOT_RUN |
| --- | --- |
| First initialization | HTTP 201 creates only an empty owned UPLOADING set at revision 0, immutable initialization receipt and audit, without requiring an available upload producer or creating file/link/item metadata. The assigned task remains current. |
| Stable replay and namespace | Exact retry returns HTTP 200 with the original facts after finding-version/evidence-revision changes. Changed payload, stale fresh commands and cross-finding reuse conflict; another owner has a separate namespace. |
| Current authority before replay | Grant expiry, credential-version change, disabled account and changed assignment deny replay using the existing real session. |
| Quotas | Nonempty UPLOADING/QUARANTINED sets count; FAILED, closed-round and old-round metadata are excluded. A real concurrent waiter is identified by backend PID and `pg_locks`/`pg_blocking_pids`, then checked against the winner's quota effect. |
| Concurrent same key | Two real HTTPS contenders must yield one 201 and one 200, identical facts and exactly one set, receipt and audit effect. Worker exceptions propagate through bounded futures. |
| Isolation and provenance | Missing/foreign tenant contexts cannot read or forge receipts. Restricted runtime mutation is denied; intrinsic append-only/provenance checks are probed through rollback-only owner transactions with immediate constraints. |
| Request authority | Authentication, CSRF, foreign finding and missing actual task assignment deny the command without creating a set. |
| Three rollback boundaries | Test listeners inject failure only after the actual set, receipt or audit INSERT completes, with a reached-boundary assertion. Observed participants must roll back; same-key retry must commit once and then replay. |

The fixture checks target identity, source-era migration, nonowner FORCE RLS and effective least privileges before fixture writes or explicit V2 deployment. Receipt UPDATE is denied at both table and column privilege levels; this includes the two-line preparedness repair independently re-reviewed CLEAR. The suite plans at most 18 successful real logins against the unchanged production 60-per-60-second budget, reusing a browser's session within each owned case.

Rollback snapshots cover tenant-owned business, evidence, file metadata, receipt, audit and persistent-publication rows, plus 12 engine tables scoped through owned process/execution/task IDs. Referenced variable bytearrays are included; absence of unreferenced orphan bytearrays is unproved. Diagnostic rendering exposes row counts only. Quota fixtures contain UNVERIFIED/QUARANTINED metadata; they fabricate neither CLEAN/READY evidence nor a historical seal.

## Executed compile-only verification

Evidence: `/tmp/msf-g024-dual-compile-linux-20261103/attempt2/dual-compile-summary.json`. Both isolated Linux Java 21 profile compilations exited 0, with input bindings unchanged before/after. Each executed **zero tests**, produced zero Surefire/Failsafe XML reports and performed no database work, application startup or acceptance acknowledgement.

| Compile input | Source identity | Result and purpose |
| --- | --- | --- |
| A: current source plus the two new IT files | 414-file manifest SHA-256 `b3f9525ffcf9ad27b514ff2a3d9a790d1609d38a149a342799aac2e2e448cd64` | All 26 IT classes compiled. All 414 current input hashes matched during report preparation. This application source requires V17 and must not run against a V16-only target. |
| B: fresh copy of retained PRE17 source plus only the same two IT files | 382-file manifest SHA-256 `d05c789d6a4170ab587c71c1982cdaa43091cfb32823b37cef7e3b35fa908742` | All 25 IT classes compiled. This overlay prepares a V16-phase run with the preserved older application interfaces; compilation does not prove actual upgrade continuity. |

Case manifests, commands and logs are retained under the corresponding `A-current` and `B-pre17-overlay` subdirectories. The original retained 380-file snapshot and its manifest, SHA-256 `9670ca2f35158014a0c51339c6af6ad07f28c54097addd68b8d349f854e750c5`, remain unchanged. Its separately reviewed association with Git `5fd7766` is **379 byte-identical files plus `mvnw.cmd` with retained CRLF versus Git LF**, which normalizes exactly; it is not a claim that all 380 files are byte-identical to Git. The fresh overlay does not edit the retained original.

| New source | SHA-256 |
| --- | --- |
| `backend/src/integration-test/java/com/mightyoung/softwarefactory/FindingEvidenceSetInitializationPostgresIT.java` | `2204b927c16cd162e0738323bc56f4016d14f716beb265b93763d0beb8041456` |
| `backend/src/integration-test/java/com/mightyoung/softwarefactory/FindingEvidenceSetInitializationPostgresFixture.java` | `dd7b80e17a4f6c12b34cc95fdac3be8ddfc02b5c2aaf29b58e2524f0753dff8f` |

Independent preparedness review returned CLEAR after the receipt-column privilege repair. This is a source review verdict. No fresh unit/full-suite, actual IT, scanner/storage or browser acceptance result is asserted by this batch; earlier PR #37 verification retains its own source scope.

## Open gates

- The consolidated V15–V17 schema/grant request remains pending, with no approval reply or applied change. Source presence, compilation and fixture acknowledgements confer no migration, grant or deployment authority.
- All 15 planned invocations require actual execution against the separately authorized target. Real HTTPS/CSRF/session outcomes, RLS, constraints, lock contention and atomic rollback remain unproved.
- Positive exclusion of genuinely sealed READY sets from quota calculations belongs to actual producer/finalization/sealing acceptance; seeded READY/sealed rows cannot substitute for it.
- Whole-business create/confirm/dispatch, genuine old-application continuity, restart and complete cross-story upload/evidence acceptance remain open. The direct metadata/V2 task precondition proves none of those journeys.
- Unreferenced orphan-bytearray absence is outside the scoped snapshot proof. No actual rollback claim follows from compilation.

This batch supplies reviewable acceptance code and two source-era compilation results. It does not close G024 or any real-service acceptance gate.
