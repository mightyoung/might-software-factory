# Studio publication HTTP staging

This document describes a dormant transport. Neither adapter registers a route, bean, migration, role grant, or deployment. The browser-only Player continues to open offline files without this API.

| Proposed route | Request | Result |
| --- | --- | --- |
| `POST /api/v1/admin/studio/screens/{screenKey}/publish` | Existing authenticated write/CSRF chain. Exactly one `X-Expected-Published-Version` decimal header (0 for the first publication), UTF-8 `application/json` without compression, no query parameters, and at most 256 KiB. | 201 for a newly committed version, 200 for unchanged; JSON contains only `outcome`, decimal string `version`, and `sha256`. Invalid input, role denial, version conflict, or commit failure does not produce a receipt. |
| `GET /api/v1/studio/screens/{screenKey}/published` | Existing authenticated session, optional one positive decimal `version`; no other parameters. | 200 for an exact verified immutable projection, 404 for an authorized miss, 403 for an ungranted screen. A pinned miss never falls back to current. Only a successful read emits a strong ETag. `If-None-Match` never bypasses a fresh authorized read. |

The tenant comes only from the captured human session. Both methods run through their corresponding owner on the guarded connection and return no representation until commit. All responses produced by the adapters are private/no-store. These tests exercise the adapters and an in-memory Servlet CSRF filter; they are not deployment, PostgreSQL concurrency or production routing evidence.

## Before enabling the routes

1. Bind the exact routes to the existing verified session and CSRF filter chain. Do not infer tenant or actor from headers, accept a token attribute as proof of CSRF, or publish from the offline Player.
2. Register only the approved same-tenant scene-admin publish capability and the separate per-screen read grant. Use the existing shared tenant command guard; every applicable grant, revocation and screen retirement writer must use the matching exclusive guard.
3. Prove the actual bound routes reject unverified or expired sessions and writes without CSRF before calling application code. Test same-tenant roles, revoked read, exact pin miss, version conflict and committed receipts against the assembled runtime.
4. Verify two-session revoke/read ordering and commit/rollback using an explicitly authorized isolated database target; do not use application or production data. Separately obtain the configuration or deployment approval required for any activation.
