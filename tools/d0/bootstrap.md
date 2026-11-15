# D0 isolated framework and seed bootstrap

Run `bootstrap` only after applying the reviewed application migrations through V26. The separate `migrate` and `workflow` entries below handle reviewed migration and synthetic-tenant process deployment. None creates databases, activates sensitive-session authority or sends notifications. The operator must separately provision a fresh isolated database and its migration identity. Existing development/NAS databases are deliberately rejected.

## Provisioning order

1. Create an isolated PostgreSQL 18 database named `msf_d0_<purpose>_test`. Keep database and public-schema ownership with the provisioning administrator. Create separate login migrator/runtime roles, both NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT, without memberships.
2. Revoke PUBLIC database privileges and public-schema privileges in this new database. Grant CONNECT to the two roles; grant public USAGE/CREATE only to the migrator, and USAGE only to runtime. Runtime must have no effective database CREATE/TEMP or schema CREATE.
3. Compile `bootstrap.java`, `migrate.java`, and `workflow.java` together with the pinned backend classpath. Run from the repository root. Set the target variables below plus `D0_MIGRATOR_USERNAME/PASSWORD` and `D0_OPERATOR_USERNAME/PASSWORD`. Run class `migrate`: through V22 as migrator, V23 as operator, the checked-in V23 ownership repair, then remaining migrations and Flyway validation as migrator. The operator must be able to bypass FORCE RLS for the explicit V23 step. It is never substituted for the application's identity. Preserve the private log as migration evidence.
4. Run `bootstrap framework` with operator credentials set to the migrator so the pinned framework creates its tables under the migration owner. Run it twice to verify schema validation without auto-upgrade.
5. Apply the two runtime grant scripts in the order below. They assert effective ACLs; unexpected PUBLIC/inherited rights fail the transaction.
6. Run `bootstrap seed` with the provisioning identity and five private synthetic passwords. Run it twice to verify identical seed state. Do this before browser scenarios intentionally modify fixture data.
7. For browser workflow acceptance only, run class `workflow` with migrator credentials. It deploys checked-in v1/v2 process resources for the owned seeded tenant. A repeated run compares exact deployed resources/bytes and does not deploy a new version. Other deployments under that synthetic tenant fail closed. This does not enable review-cycle v3 or activate sensitive-session authority.

`migrate` and `workflow` retain the same explicit database-name/URL/ACK guard as bootstrap. None repairs Flyway history, drops data, disables RLS/triggers or chooses an existing development database. Capture the source commit and uncommitted source manifest alongside results; these operator entries are not a claim of production acceptance.

## Build and target

Use Java 21 and the backend dependency classpath from the same checkout. For example, from the repository root:

```sh
./mvnw -pl backend dependency:build-classpath -Dmdep.outputFile=/private/tmp/d0-backend-classpath
```

Pass the resulting classpath to the Java source launcher without printing its contents. `tools/d0/bootstrap.java` uses the already pinned PostgreSQL driver, Flowable and Spring Security bcrypt dependencies; it adds no package dependency. Keep raw process output in a private file, since framework libraries may log database details. The entry's own failure message contains only exception type and SQLState.

All values below are environment inputs, never command-line passwords or repository files:

| Input | Meaning |
|---|---|
| `D0_JDBC_URL` | Plain `jdbc:postgresql://HOST:PORT/DATABASE`, with no credentials/query parameters |
| `D0_DATABASE` | Must match `msf_d0_[a-z0-9_]+_test` |
| `D0_BOOTSTRAP_ACK` | Exactly `isolated-d0-only:` followed by that database name |
| `D0_OPERATOR_USERNAME`, `D0_OPERATOR_PASSWORD` | Operator/migration connection; runtime credentials are never reused |
| `D0_TENANT_CODE` | Optional; default `d0seed`, restricted to `d0[a-z0-9-]{0,30}` |
| `D0_SEED_PASSWORD_REPORTER` | Private password for reporter |
| `D0_SEED_PASSWORD_PERSON` | Private password for first confirmation actor |
| `D0_SEED_PASSWORD_SAFETY` | Private password for safety confirmation/dispatch/verification actor |
| `D0_SEED_PASSWORD_REMEDIATOR` | Private password for remediation actor |
| `D0_SEED_PASSWORD_ADMIN` | Private password for permission administrator |

Allowed hosts are loopback (`127.0.0.1`/`localhost`) and the previously selected LAN test host `192.168.1.2`. Both the URL and server-reported database must match the named target; PostgreSQL must be version 18. No alternate database/profile is selected implicitly. Missing V26 history rejects the operation before bootstrap writes.

Invoke `java --class-path <classpath> tools/d0/bootstrap.java framework`, then the same entry with `seed`. Populate passwords through the parent process environment or an owner-only local credential source. Each seed password must be valid UTF-8 with 16–72 bytes; stored values use `{bcrypt}` with cost 12, matching the application encoder. No generated or supplied password is printed.

## Framework behavior

On an empty engine namespace, the pinned Flowable 8 configuration creates its schema with IDM, event registry and async executor disabled. If any engine table already exists, schema update is disabled and the engine must validate the existing version; this avoids silently upgrading a partial or unrelated engine schema. The engine is immediately closed. It does not deploy application process definitions.

Spring Session uses the pinned dependency's `org/springframework/session/jdbc/schema-postgresql.sql`, in a transaction. Both Session tables must either exist or be absent; partial installation fails. Existing table contents are retained. This helper validates engine version and Session table presence; complete effective schema, ownership and runtime privilege verification remains a separate D0 gate. Creation may complete Flowable before a later Session failure; rerun validates that completed part and resumes Session creation.

## Seed behavior

The seed creates one explicitly marked synthetic tenant, one organization, `safety` and `platform` entitlements, five separate accounts/members/roles and stage assignments. IDs are deterministic within database + tenant code. Login keys are `<tenantCode>-reporter`, `-person`, `-safety`, `-remediator`, `-admin`.

- Reporter: finding read/create/workflow initialization.
- Person: finding read/person confirmation.
- Safety: finding read/safety confirmation/dispatch/verification.
- Remediator: finding read/remediation.
- Admin: permission catalog read/manage, without finding actions.

All grants use explicit organization scope. The first confirmer and safety confirmer are distinct from the reporter. The helper only references canonical permission rows supplied by migrations; it never creates arbitrary permission codes. It does not seed business findings, upload files or claim safe evidence.

Seed writes use one READ COMMITTED transaction, the existing global security advisory writer lock and the transaction-local tenant. No trigger or RLS policy is disabled. Missing prerequisite permission, identity collision or a later insert failure rolls back all seed rows. A conflicting existing tenant is rejected. A repeat run verifies identities, password matches, exact role permission sets, effective seed grants/scopes, entitlements and stage assignments; it neither resets passwords nor restores removed authority. Intentionally changed fixture state must be handled by its owner, not repaired by rerunning bootstrap.

## Verification and boundaries

Compile `bootstrap.java` and `bootstrap_test.java` together into a private temporary directory with `javac -proc:none -cp <classpath> -d <private-directory> ...`, then execute `bootstrap_test` with that directory plus the dependency classpath. The 18 offline checks verify target refusals, deterministic namespaces and role separation. They do not open a database connection.

V26 must additionally be verified against real PostgreSQL in three states: missing `finding.read` creates the canonical tuple and metadata; existing compatible tuple retains its ID and all grants; incompatible resource/action fails without rewriting it. Runtime grants and framework table ownership must be audited separately after bootstrap. A complete D0 result also requires rerun evidence and login/role/permission/browser checks against the actual backend. Offline compilation and guard success do not establish those gates.

## Legacy runtime privilege baseline

After framework and application provisioning, apply `backend/src/main/resources/db/operator/d0-legacy-runtime-grants.sql`, then `d0-runtime-grants.sql`. Both use psql variables `runtime_role`, `target_database`, and `d0_ack=d0-runtime-direct-grants-v1`. The legacy entry accepts only isolated `msf_d0_*_test` targets. It requires a separate login runtime with no elevated flags, role memberships, object ownership, database CREATE/TEMP or public-schema CREATE. Remove effective PUBLIC CREATE/TEMP at isolated database provisioning; the script does not change shared/PUBLIC ACLs.

The legacy file owns V2–V18.3 table ACLs plus selected framework tables. It resets direct table and column rights on that explicit manifest, then checks effective table and column rights before committing. Unexpected inherited/PUBLIC rights fail the transaction. No default ACL or routine ACL is changed. Existing V2–V18.3 helper routines retain their migration-provided invoker/PUBLIC execution baseline; V21/V22 reviewed helper deltas belong to the newer runtime script.

Its source mapping is:

- `IdentityLoginPostgresIT.RuntimePrivilegeProfile`: identity SELECT; only account `credential_version` and credential `secret_hash`/`changed_at` UPDATE for self-service password change. Security/authentication event rows are INSERT-only.
- `JooqFindingWorkflowRepository`: finding aggregate, counter, command-result completion, round/notice state and submission sealing use explicit UPDATE columns. History and snapshots are SELECT/INSERT without UPDATE/DELETE. `workflow_binding` uses an advisory lock, so immutable binding rows need no fake UPDATE grant.
- `JooqFindingEvidenceUploadRepository`, `JooqFindingEvidenceSetInitializationRepository`, `JooqFindingEvidenceIngestionRepository`: reservation/initialization INSERT; technical upload progression has a fixed UPDATE list. Evidence-set revision/seal UPDATE columns are required by V6 invoker triggers.
- `JooqFileIngestionLedger`: file, allocation, execution, proof, receipt and publication facts are append-only SELECT/INSERT; their migration triggers still validate provenance and state. No file-history UPDATE/DELETE is granted.
- Flowable 8 resource manifests `flowable.postgres.create.common.sql` (20), `.engine.sql` (7), `.history.sql` (5): exactly 32 tables with engine CRUD, alongside Session's two tables and the persistent event registry. No unnecessary IDM/event-registry schema is added to satisfy the older 45-table test assumption.

The required order matters: the legacy script first establishes read-only directory/authorization base grants, and the newer script adds the controlled V19+ identity lifecycle, asset/risk, audit and permission-management deltas. Applying the legacy reset after the newer script would remove those overlapping deltas; always rerun both in documented order. Column ACL assertions here describe the baseline at this step; final combined-role acceptance must account for the later explicitly reviewed delta.

## Real browser check

`node tools/d0/browser.mjs` uses the frontend's installed Playwright package and local Chrome. Supply `D0_BROWSER_ORIGIN=https://127.0.0.1:<port>`, `D0_BROWSER_OUTPUT` (private evidence directory), the database/ACK, tenant code and five seed password variables above. The frontend must explicitly use `VITE_API_MODE=http` and proxy to the real runtime-role backend. Use an isolated generated TLS certificate; the browser tolerates that certificate only for this loopback acceptance endpoint.

The check logs in, reloads the session, creates a finding from its published definition, confirms with separate actors, dispatches remediation, checks missing-evidence refusal and edits/restores a reporter permission through the real UI. It preserves synthetic findings for inspection. It does not simulate trusted file ingestion, supply pre-approved evidence or claim that a privileged scanner was deployed. Database integration suites and native scanner protocol checks remain separate gates.
