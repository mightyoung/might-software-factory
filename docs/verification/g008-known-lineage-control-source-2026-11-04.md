# G008 known-lineage and control source verification — 2026-11-04

Status: this batch passes its reviewed source, focused-test and full-source gates. **It does not complete G008 or establish deployed session enforcement.** Formal Boot/filter wiring, all protected HUMAN consumers, target V20 readiness and real G008 database/HTTPS acceptance remain open.

## Source scope

The batch has 28 implementation/test files: 20 production Java files and eight unit-test classes, based on integrated commit `a9cb065c97f22581567050333ed515c9e6edbd76`. This report is the twenty-ninth delivery file. The source manifest is `50ffb9349e7fa492ef4d8e489020658ed2b12d4e5b6452dd0b354fe796fa7794`.

- Known-lineage logout separates request evidence from fresh HUMAN authority. The guarded logout path resolves canonical or retained HMAC lineage and account ownership, applies a durable fence and success audit within its owning security transaction, and performs framework/local cleanup only after a confirmed normal outcome. LOCAL_ONLY requires an explicit authoritative absence lookup and a normally returned owning transaction. Lookup, key, audit or commit uncertainty remains UNKNOWN, preserves local proof and does not retry the effect.
- Persisted control has explicit OFF and ENFORCING results. A standalone read owns its read-only repeatable-read boundary without manufacturing a human principal. Caller-bound checks positively read the persisted control in the caller's actual transaction. A missing table/row, invalid control, missing retained keys or storage error cannot become an OFF fallback. OFF is not business permission; ENFORCING requires the retained key coverage and current session-source checks.
- The identity-owned `HumanSessionAuthorityTransaction` SPI borrows an existing caller boundary without opening a nested transaction or acquiring another guard. Its authorization-owned implementation checks actual guard ownership, scope, data source, connection holder, physical connection, thread/lifetime, transaction mode and savepoint state, and supplies the caller's common observed instant. `JooqCurrentHumanSessionAuthority` consumes that verified boundary and reasserts it around the SQL check. These public seams are implemented and tested, but not yet connected to every production HUMAN consumer.
- Existing session registration, listing, revocation and sensitive-audit core is extended through explicit construction. No automatic bean, login/logout endpoint or property-based enforcement bypass is added by this batch.

## Pre-capture rotation repair

Independent review found a HIGH issue in the earlier R1 capture: actual session NEW could take precedence over still-requested OLD before capture, missing retained OLD evidence and permitting an incorrect local-only outcome. The repair requires a freshly selected singleton cookie ID to equal the framework requested ID and any existing actual session ID. OLD selected/requested versus rotated NEW rejects before boundary/store access or cleanup.

`KnownLogoutRequests` owns one actual `CookieHttpSessionIdResolver` and one private final observing `DefaultCookieSerializer` subclass. Trusted construction explicitly establishes the actual cookie name, applies its configuration callback, then seals all twelve public mutating setters, including cookie name, Base64 mode and JVM route. The same resolver instance is exposed for trusted filter assembly; this source factory is intentionally not automatically wired.

The observer deep-copies the servlet cookie snapshot, counts only the configured matching name and delegates decoding to `DefaultCookieSerializer`. It does not parse Cookie headers or decode Base64 independently. Each capture/recheck uses a private request/thread-bound attempt and requires exactly one observation with the complete unchanged ordered resolver result. Missing, duplicate, exceptional, replaced or mismatched observations fail UNKNOWN; attempt state is removed in finally. Raw matching-cookie count must equal the selected-list size and be at most one. A malformed encoded cookie that upstream silently discards is therefore UNKNOWN, not absence. Native Servlet cookie/URL flags are not Spring Session provenance.

Capture rechecks request/session/authentication/principal/thread/lifetime facts and resolver evidence. Eligible decoded cookies remain available for authoritative retained-lineage lookup after the framework row disappears; neither cookie possession nor observation alone proves SQL ownership or a committed fence.

## Verification

The unchanged reviewed source was captured as 495 build inputs from the current checkout. All commands used the existing pinned Linux Java21.0.9/Maven3.9.11 image with offline/no-network, nonroot, read-only dependency cache, dropped capabilities, no-new-privileges and explicit Mockito agent controls. No live database credentials or target were mounted.

| Check | Tests | Suites | Failures / errors / skipped | Actual required secure-storage tests | Actual database IT |
|---|---:|---:|---|---:|---:|
| Focused session/core/module selector | 86 | 13 | 0 / 0 / 0 | Not selected | 0 |
| Default `clean verify` | 880 | 130 | 0 / 0 / 0 | 20 | 0 |
| Compatibility profile, `-DskipITs clean verify` | 935 | 135 | 0 / 0 / 0 | 20 | 0 |

The new `KnownLogoutRequestsTest` contains **10 actual `SessionRepositoryFilter`-driven fixture methods and four pure configuration/nonce/resolver contract methods**, plus one added direct capture authentication-mutation regression in the existing capture suite. This corrects the original source handoff's imprecise label of fourteen filter-driven methods; the old handoff is retained unchanged. The rotation fixture proves UNKNOWN with zero boundary/store/cleanup calls. Other regressions cover custom cookie name/Base64/JVM route, retained no-live-row evidence, malformed input, duplicate cookies, native flag independence, sealed setters, changed or exceptional resolver observations, finally cleanup and successive requests. These are framework/unit fixtures, not deployed Boot or HTTP acceptance.

Both full phases actually ran the mandatory secure-storage cases: eighteen local private-storage adapter cases, the named real-storage configuration case and the real-reader budget-release case. The requirement was not inferred merely from a flag. All were successful and unskipped.

Fresh default compilation processed 277 main and139 test Java inputs and produced 784 classes plus22 runtime resources. Profile compilation processed277 main and180 test/integration Java inputs and produced944 classes plus25 runtime resources. The additional41 integration Java sources were compiled only; no Failsafe IT ran.

Every emitted class, including nested/anonymous classes, is major65 and bound by SourceFile/package/source hash to the frozen input. Every nonclass runtime resource matches its frozen source. All suite XML records use current test/main class roots and Java21. The common194-entry runtime classpath contains191 individually hashed current cached dependency JARs, two class roots and the existing trailing empty entry. The806 default runtime artifacts are byte-identical to the earlier focused capture. Profile main classes and shared resources are byte-identical to default. No separate runtime ProtectionDomain probe was added; evidence relies on clean compilation, exact artifact/source hashes and XML current-classpath binding.

## Evidence identities and preservation

| Artifact | SHA256 |
|---|---|
| Reviewed owned28 source manifest | `50ffb9349e7fa492ef4d8e489020658ed2b12d4e5b6452dd0b354fe796fa7794` |
| Fresh495-input whole-source manifest | `fc3f41c06598f201deb14fa589e5bb4b1d42aa9046a5148f5f74ebed58622ffc` |
| Focused verified summary | `5da8e14ee5615a95cc600a0556506767e1ae081bd5c38eaac5a63e4b4e55c669` |
| Full default verified summary | `1aeee109468bd9524a8377ffc9489782a24d1b8fe6eebd57526f72b706fd0d92` |
| Full profile verified summary | `53357d15af515fb1ba7c94b7c12c94beec2c6bc64df3e2a56370bde57e0cff8b` |
| Full verification packet | `5da282e833099fe280795ad42cdee4dd63d3ec20811a9b7b680021b616f7a89d` |
| Full2824-file evidence manifest | `32ae6b354965b0c78f61de7edea62cb330007174538693481e2a302333f78413` |
| Profile969-runtime-artifact manifest | `e332866321765a2f1bd9a05dd2651adc287dc467435c10d4748c027aa772c28c` |
| Profile compiled runtime archive | `0b96d5bd546445c0c0e035cd3ae36f0af5e879a2fdfdfabc71176af8ae7a92c1` |
| Unchanged source V20 | `dafe3dff365e0fd50761af8082a6d6462e7354fdae02d4bea31bb8d7689e7c54` |

Local retained evidence is under `/tmp/msf-g008-trusted-cookie-fix-20261104`, `/tmp/msf-g008-trusted-cookie-targeted-linux-20261104/attempt1` and `/tmp/msf-g008-trusted-cookie-full-linux-20261104/attempt1`. These are audit captures, not installed runtime configuration. Original logs, commands, XML, source captures and compiled outputs remain retained.

The original R1 source manifest `0b3312208665f1067468b500109b3ecc3918a29ece408d04bd638886d0a2a309` and its older865/920 source-test results remain historical evidence; they do not close the subsequently discovered rotation issue. The withdrawn strict container-flag R2 proposal remains preserved and was not compiled or tested. No original failed diagnostic or earlier handoff was overwritten to make the current result appear successful. The trusted-cookie repair changed seven R1 files and added two; the other nineteen implementation/test files remain byte-identical to R1.

## Remaining gates

Formal Boot wiring must preserve the exact deployed cookie-property mapping and all actual ordered customizers once before sealing, bind the same serializer/resolver to the actual filter and capture factory, and prove filter/logout/CSRF ordering in a real Boot context. Explicitly supplying a serializer does not prove that Boot's factory customizations were applied.

Login registration, exact logout coordination, session listing/revocation and every protected HUMAN read/write consumer still need their reviewed production integration and real-service evidence. The source SPI is not proof those consumers invoke it. Target V20 history/checksums, exact least-privilege grants, retained-key readiness, OFF/ENFORCING activation policy and real PostgreSQL fencing/races remain separate gates. This batch did not apply V20, create or change roles/grants/keys, activate enforcement, connect a real G008 database, deploy HTTP endpoints or claim complete P0–P2 acceptance.
