# Java 21 environment and P0 probe cleanup — 2026-11-02

Base: `845f191` (`origin/dev`). The final user decision retains Java 21. The temporary, uncommitted Java17/jOOQ3.19 migration was withdrawn; the Maven release and CI remain 21, with the original Boot/Flowable/jOOQ dependency versions.

## Installed runtime

Eclipse Temurin `21.0.13+9-LTS`, macOS aarch64, is installed at `~/Library/Java/JavaVirtualMachines/jdk-21.0.13+9/Contents/Home`. The official archive was checked against the SHA-256 supplied by the Adoptium API: `bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a`.

The existing `JAVA_HOME` entry in the user's login profile now selects this runtime; both `java -version` and `./mvnw -version` in a fresh login shell report 21.0.13. A process started before that edit may retain its old environment, so verification explicitly selects the installed Java21 home. Existing JDKs are retained.

Installation source: [Adoptium archive instructions](https://adoptium.net/installation/archives), [Temurin21 release](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9).

## Local storage platform boundary

A direct `Files.newDirectoryStream` probe under Temurin21 on this Mac returns `UnixDirectoryStream`, not `SecureDirectoryStream`. The production private-file adapter requires secure directory handles to protect its file access; its refusal to initialize is retained. No ordinary-path fallback, relaxed permissions, or trusted receipt bypass is introduced.

The test suite must distinguish unsupported local storage from successful storage verification. Only tests requiring actual secure directory operations may be skipped on an unsupported filesystem. Fail-closed construction and explicit local-storage activation are still checked. Linux CI sets `software-factory.test.require-secure-storage=true`; missing capability fails the job instead of skipping those tests. Local partial execution and remote full execution are separate evidence.

## P0 cleanup fix

The joint rollback and manual timer failure/retry probes previously retained deployments and could leave a retry timer behind when an assertion failed. Both now select their definition by the deployment created by that test and clean up in `finally`. Only that deployment and its process-scoped projection, transition, and outbox records are removed. Business cleanup is attempted even if engine cleanup fails. No global cleanup or relaxed target/role gate is added.

These test-source changes are independent of the extra retry assertions in draft PR #17. PostgreSQL-backed execution remains NOT RUN on this branch: the current restricted identities, approved schema provisioning, and exclusive executor target are not established. Compilation or local startup against test-only H2 does not complete P0 database acceptance.

## Fresh evidence

All local runs below use actual Temurin21.0.13 and Java release 21, with `SERVER_ADDRESS=127.0.0.1` for the existing temporary startup-test HTTP listener:

| Check | Result |
| --- | --- |
| `./mvnw -o -B -ntp clean verify` | BUILD SUCCESS: 214 tests, 196 executed successfully, 18 explicitly skipped for missing secure-directory capability, zero failures/errors; JAR packaged |
| `./mvnw -o -B -ntp -Plan-postgres-compatibility -DskipITs verify` | BUILD SUCCESS: 226 non-database tests, 208 executed successfully, the same 18 platform skips, zero failures/errors; all integration sources compiled |
| Actual Java21 application startup | PASS in existing startup regression, using test-only H2; no PostgreSQL acceptance implied |
| Platform refusal contracts | 2 executed and passed on this unsupported Mac; construction and explicit activation both fail closed |
| Required-capability negative control | Setting the CI flag on one real-storage positive test caused the expected build failure: 1 failure, zero skips |
| `git diff --check` | PASS |
| Live PostgreSQL P0 cleanup/transaction checks | NOT RUN |

Remote Linux JDK21 CI must run both verification commands with required secure-storage capability. Consult the submitted PR head for that separate result; the local skipped tests are not counted as passes.
