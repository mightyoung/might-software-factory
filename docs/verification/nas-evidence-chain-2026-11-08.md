# NAS evidence and remediation integration — 2026-11-08

## Acceptance status

The candidate has **not** been deployed to NAS. End-to-end browser acceptance of
upload → scan → final publication → remediation submission → rejection → another
round → acceptance is **pending**. Existing NAS development data and application
deployment were preserved. SSH rejected the previously supplied credentials;
password retries stopped and updated connection information was requested.

## Candidate behavior

- Finding details show persisted reporter/current assignee labels and the latest
  200 chronological transitions, with explicit history truncation.
- Verifiers can inspect the latest 20 submitted statements, submitter labels,
  submission times and downloads bound to the sealed evidence manifest. Each
  returned submission has at most 100 exact references; missing/oversized
  manifests fail closed rather than showing an incomplete evidence set.
- An authenticated assignee can initialize an evidence set, reserve a PNG/JPEG
  upload, send raw bytes once, query/cancel it, and submit a remediation statement
  only after the server reports `FINALIZED`. The first UI supports one image per
  evidence set.
- Identity-scoped recovery stores request metadata, never file bytes or statements.
  Unknown reservation outcomes replay the exact request/key; unknown byte-receive
  outcomes are queried without automatically resending bytes. An explicit stale
  version rejection can be refreshed safely before a committed intent exists.
- Final publication commits file metadata, a file link, an evidence item, the
  evidence-set revision, the finalized intent and **both** immutable/receipt charge
  transfers in one transaction. Pending uploads prevent sealing a submission.
- Signed `SCANNED_CONTENT_V2` publication remains distinct from legacy `CONTENT`.
  Historical verification uses the pinned receipt and exact version, not a new
  scan or a mutable latest file. The reader validates the existing physical tenant
  transaction and verifies bytes before releasing them to the response.
- Scheduled failures log only correlation identifiers and allowlisted stage/error
  categories; raw exceptions, scanner output, paths and secrets are omitted.

## Evidence obtained

| Check | Result | Boundary |
| --- | --- | --- |
| Frontend full suite, HTTP build, types, bundle and 7 API contracts | 386 tests passed; final presentation adjustment passed its 9 component tests | No NAS candidate browser acceptance |
| Final complete ARM64 Linux backend suite and executable JAR packaging | 970 passed, zero skipped | Network disabled; no database or HTTP acceptance implied |
| ARM64 Linux V2 reader/config/actual content-service transaction tests | 19 passed, zero skipped | Controlled JDBC connection plus real filesystem bytes |
| Native scanner strict ARM64 compiler/protocol/negative tests | Passed | Does not accept a NAS scanner generation |
| Java scanner memory policy boundary tests | 8 passed | Exact 2 GiB cap; 2 GiB+1, zero and integer overflow rejected |
| Dedicated PostgreSQL 18.6 database, normal migration and validation | 18 migrations through V18.3 passed | Separate synthetic test database; development database remains V14 |
| Real PostgreSQL publication/rollback/isolation/submitted-material fixtures | 11 passed, zero skipped | Synthetic signed checkpoints; not scanner or HTTP acceptance |
| Reporter/assignee/history read with restricted development role | Passed, including wrong-org/wrong-tenant denial | Direct repository call; candidate HTTP deployment still pending |

Real database fixtures exercise all logical publication rows, both retained-byte
transfers, rollback after a late exception, deferred incomplete-publication denial,
cancelled/stale/cross-tenant/expired contexts, the pending-upload seal gate, and two
intents competing for the same evidence revision. They also read the persisted V2
receipt through both real PostgreSQL read-only and writable repeatable-read
transactions. They do not claim actual scan, immutable-file durability, browser,
Flowable or HTTP acceptance.

The final fixtures also prove that old untransferred immutable/receipt allocations
remain charged during disposal claims, release only after confirmed absence, and
cannot dispose of the currently transferred evidence. A real submission repository
seals a V2 snapshot; the detail repository returns the exact statement, submitter,
item/file/hash/protocol and denies wrong-organization/wrong-tenant reads.

Database testing exposed and fixed three previously unexecuted paths: PL/pgSQL
CASE-expression syntax in unpublished V18; PostgreSQL temporal parameters encoded
as strings by plain-SQL bindings; and a scan-claim guard that required but omitted
`attempt_no` from its permitted fields. V18 was corrected before its first
successful application. Later fixes use new forward migrations; no repair,
baseline or rewrite of successful migration history was used.

## Scanner resource measurements

Official ClamAV 1.4.6 ARM64 resources were downloaded and CVD signatures checked.
FreshClam updated the daily definition to 28176; main63 and bytecode339 were
retained. With no network during scanning, a clean file returned exit0 and an
EICAR string supplied through stdin returned exit1. Peak memory for the latter
was 1,008,136,192 bytes. The same resource set at 512 MiB was OOM-killed (exit137).
The Java/native policy cap was therefore raised to exactly 2 GiB while preserving
other limits. No host EICAR file was created.

These ordinary container measurements **do not** establish the broker's target
kernel controls or readiness. No accepted scanner generation or trusted upload
was fabricated from them.

## Remaining deployment checks

1. Restore NAS SSH access and inspect current state before modifying services.
2. Install and validate the native broker using a non-root application identity;
   verify namespaces, exact cgroup placement, swap denial, process reaping,
   cross-UID controls, resource pinning and denial probes. Do not run the web
   container privileged or as root to bypass these checks.
3. Apply validated forward migrations to the development database with its
   migrator role, and grant only observed runtime operations. Do not alter other
   development/test databases or grant application/audit/history deletion.
4. Build/deploy the final ARM64 image and frontend, then perform real HTTPS Chrome
   tests with a real uploaded image, actual scanner rejection, statement/evidence
   inspection, submission, rejection/new round and acceptance. Validate persisted
   transitions, immutable downloads, cancellation, revocation, restart and
   cross-tenant denial.

No compile, mock, synthetic checkpoint or isolated-database result above is a
substitute for the remaining NAS browser acceptance.
