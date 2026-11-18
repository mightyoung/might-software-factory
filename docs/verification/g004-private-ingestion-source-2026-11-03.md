# G004 private ingestion and scanning: source verification

Source checkpoint: 2026-11-03. Verification continued on 2026-11-04 Asia/Shanghai. The offline source gates described below completed; actual database and privileged scanner-resource acceptance remain open.

This source batch is on `codex/private-evidence-ingestion-20261103`, based on parent PR [#37](https://github.com/mightyoung/might-software-factory/pull/37), exact parent head `62a1d596c2f6ac445cde4c1c67dd6308a2de891c`. The coordinator checked that PR as open/draft/unmerged against `dev` at `02a770a91339c43bfc09d5b10efc3a05e680c177`. This branch contains neither V19 nor V20 and does not include G007 PRs #38/#39/#40. It is not merged-stack or P0–P2 completion evidence.

## Implemented boundary

Private server-named quarantine streaming, durable identity/hash observations, exact baseline rehash, immutable copy and V2 receipt issuance stay outside application transactions. Allocations and charges remain ledger-owned. Writer, reader, recovery and disposal use the same per-allocation FileChannel lock; data FDs close before releasing that lock. Permanent WRITE_STOP prevents late writers while permitting valid COMPLETE-source read reuse. Recovery and disposal retain charges on UNKNOWN and require actual writer/process quiescence and fresh guarded binding. No worker READY/publication or G025 routing is activated by private file classes.

The repo-owned bounded Linux broker has fixed closed commands, full 32-byte challenge and descriptor binding, one/two physical slots with stable native locks, durable preclone START_INTENT and permanent STOP, atomic clone3 into the exact cgroup/namespaces, distinct scanner UID, bounded pipes and deadlines, and truthful missing-CHILD KERNEL_ABSENT recovery. Profile/resource inventories are content-addressed and pinned; missing resources deny. Retained recovery resolves the exact old profile without requiring fresh CLEAN/signing. Source implementation and synthetic/unprivileged tests do not accept a target profile.

The two HIGH source-review findings were repaired: final-credential PR_SET/GET_PDEATHSIG(SIGKILL) precedes ARMED, and only the actual creating supervisor sends GO after installation confirmation; child setup has its own trusted timeout and finite EOF/deadline handling. Untrusted prctl/credential/capset changes and clone namespace escapes are denied; NOSUID plus NO_NEW_PRIVS prevents privileged exec clearing. Setup SIGALRM is cancelled with pending/deadline checking before exec. New launch/probe admission, preclone and GO require real capget Effective CAP_KILL; no cap is granted, and retained recovery does not inherit this new-launch requirement. Actual cross-UID/PID1 signal permission and LSM remain target gates.

Every execution requires fixed memory.swap.max=0 write/readback and exact support. Cgroup policy V2 binds this observed control; old policy facts mismatch to UNKNOWN rather than being reissued. Java/C effective containment policy V2 and generation encoding bind swap, ARMED/GO and expected capability. No public DTO, SQL or wire-shape extension was needed for these fixes.

## Source identity and independent review

Owned freeze3: 20 inputs, manifest SHA256 `3dd8cf05d6b09a66c035d7ae587fc01f236ad3e82819356d89319ea9c1a5a01e`, `/tmp/msf-g004-native-highs-source-20261103/freeze3/source-manifest.json`.

The coordinator accepted a finite independent source review as CLEAR with no remaining HIGH/MEDIUM at annotation key `g004-native-freeze3-independent-source-clear-20261103` in its `.omx/ultragoal/ledger.jsonl`. There is no standalone review file; this is a coordination record, not privileged resource acceptance.

Full current capture: 609 inputs, SHA256 `e65f6c9a5a8a3176605bc8e58a3ef745f244f2e21e89fb5d858726dac9f70d9e`, `/tmp/msf-g004-reader-joint-linux-20261103/freeze3-java/green/source-manifest.json`. Business freeze4 and schema source hashes were checked on capture. No retained target or prototype class was reused.

## Executed verification

| Stage | Actual result |
| --- | --- |
| ARM native strict build and unprivileged protocol | Exit 0; GCC 13.5.0, aarch64-linux-gnu, build glibc 2.36; no hardening/diagnostic flags relaxed. |
| Same exact native binaries in Java runtime | Exit 0; Ubuntu 24.04.3, glibc 2.39. Ordinary pipe/child, PR_SET/GET/filter denial, setup timer, capmask, strict swap parser, portable records/frame/argv, AF_ALG empty/abc/million-a/provider-unavailable tests pass. No changed UID, namespace or cgroup test ran. |
| G004 targeted | Clean compilation of 248 main and 127 unit-test Java sources; 196 tests/25 suites; zero failures/errors/skips; exit 0. |
| Complete default clean verify | 838 tests/118 suites; zero failures/errors/skips; exit 0. |
| Compatibility profile `-DskipITs clean verify` | Fresh rerun: 893 tests/123 suites; zero failures/errors/skips; exit 0. Compiles 248 main and 165 test Java sources: 127 unit-test plus 38 integration-source files, including all 25 `*IT` classes. Failsafe execution is skipped: zero actual integration-test executions and zero Failsafe test XML reports. |
| Required Linux secure storage | Adapter 18 + platform 2 actual XML tests, zero skips, separately in targeted, complete default and compatibility-profile runs; mandatory property enabled. |
| Class/source binding | Source hashes unchanged. Each completed Java stage preserves real class outputs, classfile manifest and XML; actual CodeSource audits verify 15 private/shared/business classes in targeted/default and those 15 plus all 25 integration classes in the profile, against fresh target/classes or target/test-classes without initialization. |

The profile includes the same 838 default tests plus 55 offline guards; the counts are overlapping runs, not 1,731 unique tests. An earlier profile attempt was interrupted for scheduling (exit 137, 432 partial tests/61 suites); its records remain at `/tmp/msf-g004-reader-joint-linux-20261103/freeze3-java/green/profile-interruption-note.json` and were not reused for the successful fresh rerun.

Completed source verification summary: `/tmp/msf-g004-private-source-final-20261103/verification-summary.json`, SHA256 `3cb72abc002ddacc84063272935ed108fb47e6155034bfc91ad2e1d1f75d06e2`. The completed default and targeted evidence and class-output copies remain under `/tmp/msf-g004-reader-joint-linux-20261103/freeze3-java/green`; the fresh profile evidence is under `/tmp/msf-g004-reader-joint-linux-20261103/freeze3-profile-retry`. Native attempt5 evidence remains separate from earlier attempts. Logs, source manifests, compiler/runtime command lines, classfile hashes and actual XML are retained in those directories. A later verification-report-only file was added after the 609-input capture; every captured input was rechecked unchanged.

The same real reader regression fixture and child classpath fail the old implementation with exact `UNSAFE_UNLOCKED` and pass the repaired implementation with `BUSY`. The test also covers no false absence while the read FD is held, legal stopped-source read reuse, and actual deletion only after close. Original un-attributed red and the intermediate accessor compile failure remain preserved. Evidence: `/tmp/msf-g004-reader-joint-linux-20261103/verification-summary.json`; current source gates supersede its earlier green scope.

All Java commands ran offline in the existing pinned Linux ARM64 image `sha256:3ead0ff36a4b796440e451013a4ce803dbd02f07b6c5b634cc2ad67927dfcc10`, Java 21.0.9/Maven 3.9.11, read-only container root, nonroot numeric user, cap-drop ALL, no-new-privileges, no network and read-only Maven cache. Native compiler image is the approved existing GCC ARM manifest `b749c1b9f17bff9a41ffe29b4109ecfc60a516efdf4c0e1eb4408e141f92a6d7`, local ID `74d0867017dedc53f9a0b7b37001f08cf6b72c680ba8facf009b9031d804eea9`.

## Native provenance and license facts

Native six-input manifest `a3ef380d26094219d1711c7bd42c5ef5ca54fce8c3a4698515ce3646ecc130f2`; C SHA256 `20a3515a42ae8d50516295a0019b8f118e5a9dc847084b59daff3780ccd9e2fd`; broker binary SHA256 `b626cf50e0391352d9cef4e5511191b5af6908dec9439a30c43a260f89c1680f`. ELF evidence records aarch64 PIE, RELRO/NOW/no-executable-stack build flags, interpreter `/lib/ld-linux-aarch64.so.1`, and dynamic dependencies libc.so.6/ld-linux-aarch64.so.1. No handwritten crypto, new Maven dependency or linked crypto library was added; integrity hashing uses fixed kernel AF_ALG SHA256 without fallback.

`/tmp/msf-native-broker-build-20261103/attempt5` retains exact source/binary hashes, compiler and binutils output, kernel/provider facts, link map, symbols, ELF metadata, build/runtime logs and Debian component/copyright files: glibc/libc-dev `2.36-9+deb12u14`, linux-libc-dev `6.1.187-1`, binutils `2.40-2`. These are factual build-tool/runtime/header inventories, not a complete redistribution/license approval. The actual ClamAV package, definitions, staged shared libraries, target provider and their provenance/signatures remain unaccepted resources.

## Database and runtime gates still open

V18 `backend/src/main/resources/db/migration/V18__private_ingestion_scanning.sql`, SHA256 `5ab6acfffa921317d865add795b385be3e9c77f045deeced8721363a56fa7a45`, is UNAPPLIED. Migration/ledger/codec/worker tests in this report are source/unit/static boundary evidence. No G004 JDBC/PostgreSQL/Flowable runtime acceptance ran. Runtime grants remain unchanged; the minimal-permission provisioning proposal has not received authorization for execution. This source report does not authorize any migration, provisioning or grant change.

No broker was installed, capability/UID/cgroup/mount/namespace/sysctl changed, scanner/target profile accepted or privileged probe executed. Remaining actual-resource gates include two-slot physical sharing, exact package/definitions/library inventory, clean/EICAR controls, real denial probes, supervisor death around installation/ARMED/GO and blocked scanner work, cross-UID/PID1 signal delivery/LSM, real descendant reaping, memory/swap/controller accounting, clone-before-CHILD recovery, orphan reaper, transport read timeout and immutable storage durability. No unit record, compiled binary or source review replaces those gates. No Git operation, push, merge or deployment was performed by this source owner.

## Generated contract check added before source publication

On 2026-11-04 Asia/Shanghai, the existing Node 22.22.0 executable ran `node frontend/tools/api-types/generate.mjs --check` in an immutable 17-input capture. It exited 0 and reported that all seven contracts and generated frontend type files matched. The existing cached generator dependencies matched lock SHA256 `14f813b42af5d765d78e6710d7951de27d2da48703eb90038a70fefcb7ea1ef1`; no fetch or install occurred. Capture aggregate SHA256 is `a49d3add73e4083801084e8204ffd04067ee4461847435a64892cf3482d159f6`. Both the capture and source-worktree inputs remained unchanged, including the G004 upload contract and generated type.

Exact preflight, command/output and result evidence remain under `/tmp/msf-g004-generated-contract-check-20261104/{preflight.json,generator-check.log,result.json}`. This check establishes generated-type consistency only; it is not frontend build, browser, database or configured-scanner acceptance.

This section is a documentation-only addition after the Java/native verification. The original 610-input final manifest and original report SHA256 `64b5bec70474611ceefee970292f41c3c5694519d60cc4f7d70ce2d5504386f0` remain retained unchanged. The new report hash is recorded separately by the Git publisher; it is not substituted into the historical verified manifest. All product inputs remain unchanged, so no Java/native verification was rerun for this report addition.
