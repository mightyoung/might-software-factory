# Studio publication HTTP staging

This document describes a dormant transport. Neither adapter registers a route, bean, migration, role grant, or deployment. The browser-only Player continues to open offline files without this API.

| Proposed route | Request | Result |
| --- | --- | --- |
| `POST /api/v1/admin/studio/screens/{screenKey}/publish` | Existing authenticated write/CSRF chain. Exactly one `X-Expected-Published-Version` decimal header (0 for the first publication), UTF-8 `application/json` without compression, no query parameters, and at most 256 KiB. | 201 for a newly committed version, 200 for unchanged; JSON contains only `outcome`, decimal string `version`, and `sha256`. Invalid input, role denial, version conflict, or commit failure does not produce a receipt. |
| `GET /api/v1/studio/screens/{screenKey}/published` | Existing authenticated session, optional one positive decimal `version`; no other parameters. | 200 for an exact verified immutable projection, 404 for an authorized miss, 403 for an ungranted screen. A pinned miss never falls back to current. Only a successful read emits a strong ETag. `If-None-Match` never bypasses a fresh authorized read. |

The tenant comes only from the captured human session. Both methods run through their corresponding owner on the guarded connection and return no representation until commit. All responses produced by the adapters are private/no-store. The adapters have offline coverage. A test-only MockMvc bridge also runs the proposed write path through the actual management security configuration, login provider/filter, CSRF cookie/header handling, credential-version check and absolute session lifetime. The bridge uses in-memory identity fixtures, a servlet test session, and a stubbed publish owner. Anonymous, expired and outdated sessions and missing or mismatched CSRF headers are rejected before dispatch. A valid session with matching CSRF reaches the owner with the session tenant, regardless of a caller-provided tenant header. This does not exercise persisted sessions, database-backed authorization or transaction correctness, or any registered application route.

A separate offline case injects a response-write failure after the transaction manager's commit using JDBC test doubles. A retry at the original version returns a conflict, with exactly one append, audit and head update across both requests. This is not a browser recovery or database integration test.

The test-only bridge also runs the Player GET through the management session chain and the actual read owner, separate screen-grant query and immutable projection. With synthetic guard/authority and JDBC doubles, it verifies no storage on anonymous or outdated sessions; a fresh grant check despite a matching ETag; no bytes or ETag after grant removal or failed commit; and no current-version lookup when a requested version is missing, even when current is available. It does not verify real grant mutations, PostgreSQL locking or route activation.

## Before enabling the routes

1. Bind the exact routes to the existing verified session and CSRF filter chain. Do not infer tenant or actor from headers, accept a token attribute as proof of CSRF, or publish from the offline Player.
2. Register only the approved same-tenant scene-admin publish capability and the separate per-screen read grant. Use the existing shared tenant command guard; every applicable grant, revocation and screen retirement writer must use the matching exclusive guard.
3. Prove the actual bound routes reject unverified or expired sessions and writes without CSRF before calling application code. Test same-tenant roles, revoked read, exact pin miss, version conflict and committed receipts against the assembled runtime.
4. Test a committed publication with a lost response. Retrying with the original expected version must not append a new version or audit record. A version conflict alone does not prove who advanced the head. Keep the draft and show an unknown outcome; do not resubmit automatically with a refreshed version or assume the publisher can read the Player screen.
5. Verify two-session revoke/read ordering and commit/rollback using an explicitly authorized isolated database target; do not use application or production data. Separately obtain the configuration or deployment approval required for any activation.
