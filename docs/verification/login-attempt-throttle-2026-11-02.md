# Login attempt limits and source/account cooling — 2026-11-02

Follow-up: [account-scoped Session index](account-session-index-2026-11-02.md) repairs the next identity foundation gap. The results below describe the earlier throttling checkpoint.

This batch follows pending PR #23 and the [absolute session lifetime receipt](session-absolute-lifetime-2026-11-02.md). It adds bounded protection to the existing POST login path before password verification, without modifying account status, credentials, database schema or production privileges.

## Policy and boundaries

These are configurable engineering defaults, not measured deployment capacity:

| Setting under `software-factory.security.login-throttle` | Default |
| --- | --- |
| `global-attempts` / `source-attempts` | 600 / 60 |
| `attempt-window` | 60s, fixed windows |
| `failure-threshold` / `failure-window` | 5 bad-credential results / 5min per source and normalized login key |
| `cooldown` | 30s |
| `max-concurrent` | 8 password authentications |
| `max-sources` / `max-pairs` | 4096 / 8192 retained entries |

All admitted login attempts consume source/global budget, including successful login, malformed JSON and dependency failures. CSRF is checked earlier, and the login filter still accepts only POST. The source/account failure counter counts only `BadCredentialsException`, including the provider's generic invalid account/context outcomes. Successful authentication does not reset accumulated failures within that window. Cooldown expiry starts a fresh failure window; rejected requests do not extend the cooldown.

The pair key uses the provider's strip → NFKC → ROOT lowercase normalization and deliberately omits tenant selection. A request reserves both pair failure capacity and global concurrency before invoking the provider. Reservations close on success, expected failure, dependency failure and unexpected exceptions; parsed credentials are erased even when reservation is denied. Late results from an older window cannot write failures into a new generation. State is protected by short synchronized sections; database and BCrypt work run outside that lock.

The limiter retains only HMAC identifiers using a random per-process key, not raw source addresses or login keys. Memory is bounded; new keys fail closed when capacity is occupied, and active entries are not evicted to admit attackers. Pair entries are reclaimed only after expiration and when no reservation remains; source attempt windows can be reclaimed after expiration. Intervals use a separate monotonic nanosecond source, independent of the session-lifetime wall clock. Invalid capacities or intervals fail startup.

The response is generic `429 RATE_LIMITED`, `retryable=true`, with a positive integer `Retry-After` in seconds. It does not reveal which limit was reached. The interval is a bounded retry hint; another budget may still prevent the next attempt. CSRF bootstrap, existing authenticated reads and logout have no login attempt budget.

`server.forward-headers-strategy` defaults explicitly to `none`; the limiter uses the servlet socket peer and does not read `Forwarded`, `X-Forwarded-For` or `X-Real-IP`. Behind a reverse proxy, clients can therefore share its source budget. Trusted-proxy address resolution is not delivered by this slice.

This protection is local to one process and resets on restart. It is not cross-instance rate limiting, persistent account lockout, cross-source account aggregation or a complete defense against distributed guessing. Those remain open along with shared/edge limits, MFA and deployment capacity checks. [OWASP's authentication guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#login-throttling) distinguishes account-level guessing protection and lockout denial-of-service risks; this slice intentionally limits the cooling scope and makes no broader acceptance claim.

## Verification

**PASS for the selected source/account cooling slice.** Actual runtime: Temurin21.0.13, Java release 21 and PostgreSQL 18.6. The existing exclusive disposable identity target and its write opt-in were reused; this batch introduced no schema, role or grant requirement.

| Verification | Result |
| --- | --- |
| Default clean build | 309 tests, zero failures/errors, 18 explicit macOS secure-filesystem capability skips; package succeeded |
| LAN-profile offline suite | 360 tests, zero failures/errors, the same 18 skips; skips are not passes |
| HTTPS/PostgreSQL identity suite | 13 tests passed, zero failures/errors/skips |
| Existing foundation/session probes | 15 passed, zero failures/errors/skips |
| Failsafe total and package | 28 passed, zero failures/errors/skips; BUILD SUCCESS |
| Frontend consumers | Contract comparison, typecheck, 176 tests and production build passed |
| Post-run inspection | Original ACLs restored on all 19 temporarily granted tables; a separate read-only inspection found all 30 selected resource tables empty |
| Independent review | No blocking issue |

The 16 added offline tests include concurrent admission, late-result generations, saturated capacity, signed monotonic wrap, cooldown boundaries, and release/credential-erasure paths. Review identified that globally rejected unknown sources could occupy source slots; admission now rejects before allocating those slots, with a regression proving next-window capacity remains available.

The HTTPS suite retains its prior twelve identity scenarios and adds a real bad-password → cooldown → fresh-login lifecycle. It verifies 30/20/1-second retry hints do not slide, equivalent Unicode login/tenant changes and spoofed forwarding headers cannot bypass the pair limit, the account state/version and existing session remain unchanged, and fresh login persists a usable JDBC session at the exact cooldown boundary. A separate monotonic test clock controls rate-limit time without changing JDBC Session's idle clock or the application's absolute-lifetime clock. This is controlled-time HTTP/database acceptance, not a load or elapsed-time endurance test.

```sh
./mvnw -o -B -ntp clean verify
./mvnw -o -B -ntp -Plan-postgres-compatibility \
  -Dit.test=IdentityLoginPostgresIT,CredentialVersionSessionPostgresIT,LanPostgresCompatibilityIT,SessionPostgresTransactionIT,FlowableRestartRecoveryPostgresIT,ModulithPostgresTransactionIT \
  verify
```

The OpenAPI login contract includes 429 and its required positive `Retry-After` header; generated frontend types are synchronized. CI verifies offline Java and frontend consumers without LAN credentials. Real database evidence is recorded separately.
