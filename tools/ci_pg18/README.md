# Temporary PostgreSQL 18 acceptance lanes

Each job creates a dedicated PostgreSQL container on the hosted runner and exercises the original application migrations plus its selected capture, registry or source path. It does not connect to NAS, activate production listeners or recovery schedulers, or establish historical V9 upgrade acceptance.

`risk_capture.py` remains the default capture entry. `risk_registry.py` and `risk_source.py` are thin entries into the same container/process/cleanup engine, using separate immutable code-owned specifications. No environment value, command-line target or arbitrary lane object can choose a database, role, profile or artifact path.

| Fixed boundary | Capture | Owned registry |
| --- | --- | --- |
| Database | `msf_ci_risk_capture` | `msf_ci_risk_registry` |
| Runtime role | `msf_ci_capture_runtime` | `msf_ci_registry_runtime` |
| ACK | `ephemeral-risk-capture-only` | `ephemeral-risk-registry-only` |
| Maven profile | `ci-pg18-risk-capture` | `ci-pg18-risk-registry` |
| State/container prefix | `msf-ci-pg18-` | `msf-ci-pg18-risk-registry-` |
| Container kind label | `risk-capture` | `risk-registry` |
| Artifact directory | `artifacts/ci-pg18-risk-capture` | `artifacts/ci-pg18-risk-registry` |

The administrator remains `msf_ci_bootstrap` in every isolated container. The registry lane does not expand capture grants. Its fixture applies the same 21 original migrations, verifies the nine publication columns, and gives its separate nonprivileged runtime only CONNECT, schema USAGE, nine-column SELECT, five-column INSERT and `completion_date` UPDATE. It seeds no tenant, account or source business graph and activates no capture/listener/recovery scheduler. Its actual native graph uses the existing Jackson serializer with a repository-local safe wrapper; it is a separate acceptance gate from source transaction borrowing and full restart recovery.

Each dedicated Maven profile selects one integration class and its strict CI target tests. Both Maven and the harness reject missing tests. Capture and registry require their nine lane-specific named integration scenarios and nonzero target tests. Source requires its three exact named integration scenarios and exactly 52 target tests. Every lane requires zero failures/errors/skips. Extra JUnit XML in either Failsafe or Surefire is rejected, including reports copied from the other lane. A successful Maven exit alone does not pass acceptance.

The harness:
- rejects externally supplied CI database and LAN/tunnel settings;
- pulls the hardcoded official postgres:18-bookworm tag, resolves its immutable digest, and runs only that digest for this job on linux/amd64;
- requires Docker Engine 28 or later, a single 127.0.0.1 random-port mapping, a new server-instance marker and matching job ownership labels;
- gives Java a fixed database name and plain JDBC URL, with separate generated credentials in runner-private files;
- records original migration checksums and only the approved database/schema/privilege evidence;
- removes its exact owned container and anonymous volume, verifies absence and deletes credentials and private process output.

The digest is resolved and recorded for each run; this is not a claim that a mutable tag has a permanent digest. The job never executes the tag after resolution. A missing ownership record or unknown anonymous volume state produces a failed or unverified cleanup result.

Before any database credentials or container exist, the workflow runs the dedicated profile through the test lifecycle to expose compilation and offline target failures.

Raw Maven/Flyway output is captured in a private temporary file because failed migrations may echo SQL. It is never printed or uploaded. Only the selected lane's `result.json` is uploaded. Success evidence has the fixed database and `causeSchemaVerified` (capture), `publicationSchemaVerified` (registry), or `sourceSchemaVerified` (source), plus the original migration list and effective-rights proof. Capture keeps its original four-key failure evidence. Registry and source failure evidence additionally require their exact database, so a renamed capture failure cannot pass. Unknown phases, fields or SQLState shapes are rejected. Container logs, complete inspect output, raw JUnit XML, environment files and dumps are excluded.

The workflow runs the pure guard suite first:

    python3 -B -m unittest discover -s tools/ci_pg18 -p 'test_*.py' -v

Container creation is available only in the expected GitHub Actions repository context:

    python3 -B tools/ci_pg18/risk_capture.py run

The independent registry workflow uses:

    python3 -B tools/ci_pg18/risk_registry.py run

and its `if: always()` cleanup uses `risk_registry.py cleanup`. The nine exact registry cases are:

1. `originalMigrationsAndRegistryPrivilegesAreExact`
2. `actualJacksonAndSevenNativeSqlPaths`
3. `pgBaselineAttributesAreRestored`
4. `ackCommitThenThrowKeepsDurableCompletion`
5. `registrationCommitThenThrowKeepsOneIncompletePublication`
6. `committedResetFailureStopsClose`
7. `committedCloseFailureIsNotRetried`
8. `statementFailureRollsBackCompletion`
9. `disposalSerializationFailureIsSafeAndReadOnly`

Maven runs in an exclusive session. Forked JVMs must carry this run’s process marker before the harness can signal the exact group; timeouts terminate and wait before database cleanup. Unproven process ownership prevents destructive cleanup and keeps the result failed. Maven failure diagnostics contain only counts and the nine reviewed case states.

Cleanup validates a present lane-specific intent before any process signal. A missing intent with a process/start receipt is unknown and retained. Only a completely empty state can prove interruption before container intent; leftover resource or credential records without intent are refused and retained. Registry and source process receipts additionally carry their fixed kind; capture's existing receipt shape remains unchanged. Container removal still requires the exact lane name, ownership labels (repository/run/attempt/instance/kind), container ID and recorded anonymous volume. A copied capture state never authorizes registry process signaling or deletion. Registry PASS artifacts also require their own validated database evidence and exact nine-case summary; changing only `acceptanceKind` cannot create a passing registry receipt.

The script uses try/finally, and the workflow repeats the idempotent cleanup entry with if: always(). A terminated or lost runner cannot be described as verified cleanup unless the recorded result actually proves it.


## Actual source lane

The source workflow uses `risk_source.py run` and `risk_source.py cleanup`. Its fixed identities are database `msf_ci_risk_source`, runtime `msf_ci_source_runtime`, ACK `ephemeral-risk-source-only`, Maven profile `ci-pg18-risk-source`, kind `risk-source`, state/container prefix `msf-ci-pg18-risk-source-`, and artifact directory `artifacts/ci-pg18-risk-source`. Capture and registry targets, roles, grants and acceptance results remain separate.

Before creating credentials or a container, source acceptance requires `GITHUB_SHA` to match the actual checkout HEAD, records its commit and Git tree, and rejects tracked changes and every nonignored untracked file. The check runs before the harness creates its own result artifact; ordinary ignored build outputs remain allowed. The source PASS receipt requires this revision binding, its own schema/migration evidence, its own exact three-case summary and exactly 52 target guards. Neither a copied capture nor registry result/process/intent/resource record can authorize a source pass or cleanup.

The source fixture applies the original 21 migrations and the pinned Spring Session framework schema, then verifies actual PG18/database/role/instance markers. It seeds owned identity, authorization, directory and ENFORCING session prerequisites with matching retained-key HMAC. A valid work-unit/hazard ancestor graph is fixture setup and does not establish Asset/hazard producer acceptance. Runtime uses the complete actual source SQL catalog and real current identity/authorization, guard, directory and audit implementations. Risk roots, revisions, links, snapshots, receipts, risk audit and native publication are produced by `RiskScenarioService`, never directly seeded.

The fixed column-rights proposal grants no runtime identity/admin writes. It checks effective relation/column rights across the entire public schema, role ownership/memberships/elevation, database CREATE/TEMP, schema CREATE and sequences. `hazard_source UPDATE(version)` is necessary for the original SECURITY INVOKER `FOR UPDATE` row-lock function. PUBLIC function EXECUTE defaults are retained and are a separate limitation, not an exact function-allowlist proof. Source schema evidence covers the six forced-RLS risk tables, original 13 deferred FKs and three enabled deferred closure triggers, validated/enforced constraints, the original nine publication columns and ENFORCING activation state.

Source-only controlled diagnostics record fixed case phases, issuer entered/returned counts, bounded runtime connection/INSERT counts, the first driver failure SQLState and one-way SQL SHA256, plus the last successful SQL operation. The recorder stops observing when an issuer call returns or fails, so registry disposal cannot replace its diagnostic cut. SQL text, parameter/result values, messages, stack traces and credentials are never admitted to the diagnostic shape. The harness strictly validates this metadata before adding it to the same result.json; unknown fields/cases/operations, malformed hashes/states and unbounded counts fail acceptance. A source PASS requires all three entered cases and passing diagnostic/test outcomes. Failure remains failure even when its completed fixture/schema evidence is preserved. Offline workflow preparation runs the 52 target guards and six diagnostic privacy tests; the subsequent clean acceptance run still selects exactly three IT cases and 52 target guards.

Required source cases:
1. `actualCreateCommitsSourceAndNativePublicationTogether`
2. `sameKeyReplayAddsNoEffectsAndChangedPayloadConflicts`
3. `latePublisherFailureRollsBackAllSourceEffects`

The tests independently observe eight durable counts and runtime connection controls. A successful command commits all eight effects once; replay/conflict add no effects. Injected late failure must occur after a successful native publication INSERT and then leave no durable changes, with one source borrow, no commit and one rollback. The real risk listener is registered, but its asynchronous tasks are deferred. These cases do not claim successful capture/ACK, revoked-authority replay, restart/recovery, races, host loss, HTTP password login or complete producer/listener/registry acceptance.

Official runtime references:
- [PostgreSQL image data layout](https://hub.docker.com/_/postgres?tab=description)
- [Docker port publishing](https://docs.docker.com/engine/network/port-publishing/)
- [Docker immutable digest pulls](https://docs.docker.com/reference/cli/docker/image/pull/#pull-an-image-by-digest-immutable-identifier)
- [Docker anonymous-volume removal](https://docs.docker.com/reference/cli/docker/container/rm/#remove-a-container-and-its-volumes--v---volumes)
- [GitHub masking](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#masking-a-value-in-a-log)
