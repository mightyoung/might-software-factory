# Isolated G008 PG18 acceptance — source only

Status: implementation prepared; not yet compiled, connected, migrated or executed. This directory owns only the new fixture/resources. Root owns the inactive Maven profile, shared REAUTH target and thin runner. No existing NAS role/account, production configuration, migration or RLS/constraint is modified.

Target: msf_ci_g008_reauth / msf_ci_reauth_runtime / ephemeral-g008-reauth-only; bootstrap identity msf_ci_bootstrap. Only the provenance-validated REAUTH target is accepted. Fresh public schema and absent runtime role are required before original21 migrations (2,3,4,5,6,7,8,11–24); original Flyway validate/checksums, framework Spring Session pinned schema. Historical V9 is not reconstructed or bypassed: fresh-install evidence cannot establish an old V9 upgrade.

The graph uses actual ManagedSessionSensitiveSecurityTransaction, PostgresAuthorizationWriteGuard, JdbcTransactionManager, real identity session/register/revoke stores, real audit adapter and public ReauthIssuerService.create, actual bcrypt12 hashes and retained canonical SessionSensitiveKeys. The servlet request/verified principal are synthetic identity-package helpers over persisted framework rows; actual SessionAuthorityService.register writes the ACTIVE authority and REGISTER_SESSION audit. No proof, issuance/revoke receipt or revocation fence is seeded. This does not prove HTTP login, SessionRepositoryFilter, cookie/CSRF or browser behavior.

Permissions: the exact runtime-grants.sql column matrix is mandatory. It contains SELECT/INSERT/UPDATE only over12 relations, plus CONNECT/public USAGE. No table-wide/sequence/REFERENCES/MAINTAIN or event/receipt DELETE/UPDATE rights, no identity credential/account writer rights, no contact/recovery/security_event/authentication_event/audit_event grants. Consumer SELECT * requires explicitly enumerated full authority/proof columns; that is disclosed rather than silently claiming narrower access. Spring Session principal_name UPDATE exercises actual register index canonicalization. Whole-public-schema effective column rights must match this matrix, with no grant options/elevation/membership/ownership.

Function closure: require_sensitive_global_guard(), require_sensitive_enforcement(), require_sensitive_source(character varying,uuid,uuid,bigint) must be bootstrap-owned ordinary INVOKER functions with effective EXECUTE. V20 retains original PUBLIC EXECUTE=true. This lane adds no redundant direct function grant. Every public function must have zero direct ACL entry to the runtime role and runtime effective EXECUTE equal its original PUBLIC baseline. This is a precise PUBLIC-baseline check, not all-database function hardening or PUBLIC revocation. Java's private password preparation is not a PostgreSQL bcrypt-proof; runtime database credentials remain trusted application-writer credentials, never HUMAN client credentials.

Runtime connection probes do not replace SQL: they delegate actual PreparedStatement execution, recognize execute/executeUpdate/executeLargeUpdate, count only successful row effects and scope commit counters to connections that actually inserted a proof. Actual late audit write faults, original deferred constraint failure, commit-then-throw SQL/Runtime/Error and real two-connection advisory lock waits are tested. Independent observer is privileged read-only-by-convention and refuses ambient managed resource contexts; it never writes issuer effects. Runtime rejects unexpected additional acquisition while a managed lease is active. Fault tests capture INFO/DEBUG plus stdout/stderr and scan passwords, actual randomly generated token/private stored digests/hash and driver canaries. The SecureRandom subclass delegates real randomness and retains only private in-memory test canaries; no mock boundary/SQL engine or durable proof token is used.

The guard suite preserves52 existing provenance cases independently and adds3 cross-lane parameter cases: expected55, zero skips. The initial IT suite has the following10 fixed methods (not executed):
- originalSchemaPrivilegesAndActualGuardAreEnforced
- actualIssuerFeedsActualActiveTargetRevoke
- absentAuthorityTargetGetsDirectRevocationFence
- issuanceAndConsumptionReceiptsReplayWithoutRedeliveringToken
- explicitReissueAcrossRetainedKeysReplacesOnlyExactLiveProof
- currentCredentialAndSourceChangesMeetBothGuardCommitOrders
- lateActualAuditFailureRollsBackIssuanceAndTerminalization
- deferredCommitConstraintFailureRollsBackTheWholeGraph
- commitThenThrowKeepsOneDurableIssueAndNeverDeliversItsToken
- crossAccountSourceTargetAndTenantContextCannotBorrowTheProof

Evidence contract: ci-pg18-reauth-safe-evidence.json, reauthSchemaVerified, fixed migration metadata/PG18/database/image digest; ci-pg18-reauth-safe-failure.json uses only the agreed fixed phases/SQLState. BeforeAll evidence alone is not successful test acceptance. Raw account/session/proof IDs, passwords, keys, hashes/digests/tokens and original diagnostics are not public evidence fields.

Actual expiry: NOT_IMPLEMENTED_NOT_RUN. Production TTL remains300 seconds; a real PostgreSQL expiry case must wait actual time with a reviewed timeout, not rewrite immutable proof timestamps, alter V20 or substitute component clocks.

Cross-JVM restart: NOT_IMPLEMENTED_NOT_RUN. Required follow-up is JVM1 legitimate issuance then process exit/lost memory token; JVM2 with retained server keys and same persisted synthetic source gets historical metadata only; explicit fresh password/new key revokes that old live proof and issues/consumes a new token. No plaintext token file/args/stdout. Distinct PID/start proof is required; closing a same-JVM context does not satisfy this gate. Full G008 and production activation remain open even after the initial10 pass.
