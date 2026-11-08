# G008 shared Cookie wiring source verification — 2026-11-04

Status: the three-file shared Cookie configuration passes independent source review, focused framework tests and both complete source gates. **This is bounded Boot/Spring Session configuration evidence, not complete G008 or deployed HTTP/JDBC/V20 enforcement acceptance.**

## Change and immediate consumer

The batch starts from published core commit `3ba95a15c4ce6a62d9666558fec8cfbaa2fbcf41` and changes three implementation/test files. This report is the fourth delivery file.

- `KnownLogoutRequests.cookieSerializer()` exposes the same existing sealed serializer through the `CookieSerializer` interface. The resolver getter returns the same attached concrete `CookieHttpSessionIdResolver`. Only the getter/import/construction Javadoc changes; existing observer, decoding, nonce, capture, ownership, SQL and cleanup protocols remain unchanged.
- `SessionCookieConfiguration` constructs one trusted bundle and exports its three stable singleton references: bundle, serializer and concrete resolver. Actual Spring Session configuration consumes the resolver; the interface-visible serializer bean makes Boot's otherwise-unused default serializer factory back off. Both configured objects come from the same construction path and serializer configuration is sealed after all property mappings/customizers.
- A finite startup check requires exactly one bundle, one `HttpSessionIdResolver` and one `CookieSerializer` and verifies their identities against that bundle. An additional `@Primary` serializer/resolver cannot silently win injection and leave a different effective configuration. This supplements source construction and actual-filter behavior; bean enumeration alone is not proof of filter/delegate identity or protection against arbitrary later post-processing.

There is no separate default resolver, private-field reflection, manual Cookie-header/Base64 parser, native transport-flag authority or new dependency. The existing `SecurityConfiguration.sessionCookiePolicy()` is unchanged: SESSION name, / path, Secure, HttpOnly and SameSite Lax. Its exact source hash remains `a302cb6088e2de553807881cc9dbda88dc11f5d87c36ee9f6f3afeb93286e045`.

## Both upstream deployment semantics preserved

The configuration uses the same public complementary `@ConditionalOnNotWarDeployment` / `@ConditionalOnWarDeployment` conditions as pinned Boot4.1.2. There is no manual runtime deployment guess or unsupported-WAR rejection.

| Source | Mapping before ordered customizers |
|---|---|
| Embedded `ServerProperties` | name, domain, path, nullable HttpOnly, nullable Secure, Duration seconds narrowed through `asInt`, SameSite enum, nullable Partitioned |
| WAR actual `ServletContext.SessionCookieConfig` | nullable name/domain/path, primitive HttpOnly/Secure, integer maxAge, SameSite Servlet attribute with `.always()`, Partitioned Servlet attribute via Boolean conversion |

Embedded null source values are skipped while explicit false is retained. A nonnull SameSite OMITTED enum still calls `setSameSite(null)`; it is not filtered after transformation. MaxAge preserves Boot's seconds/int-narrowing semantics. WAR preserves its distinct primitive false/default maxAge behavior and clears the default SameSite when the actual attribute is null. WAR Servlet attributes are not replaced by embedded server-cookie properties.

Each branch then applies every actual `DefaultCookieSerializerCustomizer` using `ObjectProvider.orderedStream()` once, including Boot's remember-me customizer, before the bundle seals all twelve public mutable setters. The actual application policy retains its existing precedence after mapping; no order annotation is invented for it.

## Verification and scope

All commands ran against an unchanged fresh497-input source capture and the independently reviewed three-file manifest, using the existing pinned Linux Java21.0.9/Maven3.9.11 image, offline/no-network, nonroot501, read-only dependencies, dropped capabilities, no-new-privileges and explicit Mockito agent.

| Executed phase | Tests | Suites | Failures / errors / skipped | Actual mandatory secure-storage cases | Actual database IT |
|---|---:|---:|---|---:|---:|
| Three-class focused `clean test` | 40 | 3 | 0 / 0 / 0 | Not selected | 0 |
| Default `clean verify` | 898 | 131 | 0 / 0 / 0 | 20 | 0 |
| Compatibility profile, `-DskipITs clean verify` | 953 | 136 | 0 / 0 / 0 | 20 | 0 |

The new `SessionCookieConfigurationTest` executes18 methods using actual Boot `SessionAutoConfiguration`, actual `@EnableSpringHttpSession`/`SessionRepositoryFilter` and a `MapSessionRepository`. All18 reviewed method names match successful, unskipped XML in each complete source phase. Existing trusted-cookie14 and direct-capture8 cases also pass.

The new tests establish both deployment mappings/defaults/false/null/OMITTED/maxAge behavior, ordered once-only customizers on the exported serializer, actual production-policy precedence, foreign primary/nonprimary startup failure, and Boot's actual unused-extra-serializer negative control. Nondefault name/Base64/JVM route and Cookie-clone observation prove the actual filter traverses the bundle observer's snapshot path before trusted capture; stable reference assertions supplement that behavior. Malformed, rotated, deleted-row and multiple-cookie evidence and later independent requests preserve fail-closed capture semantics. The actual production policy factory is invoked by a narrow fixture; the entire security-filter chain is not started.

Both full phases actually execute20 mandatory storage capability cases:18 private-storage adapter cases plus the named real-storage configuration and real-reader budget-release cases. This is checked from successful unskipped XML, not inferred from the requirement flag.

Default compilation uses278 main and140 test Java inputs, producing800 major65 classes plus22 source-bound resources (822 artifacts). Profile uses278 main and181 test/integration Java inputs, producing960 major65 classes plus25 resources (985 artifacts). All41 additional integration sources compile only; actual Failsafe IT is0.

Every emitted class, including nested/anonymous classes, is matched through SourceFile/package/source hash to the frozen input. Every runtime resource matches its source bytes. All XML suites record current test/main roots, Java21 and a common194-entry runtime classpath containing191 individually hashed current cached JARs, two roots and the existing trailing empty entry. JAR hashes and exact classpath match across both complete phases. Default's822 runtime artifacts are byte-identical to the focused capture; profile main classes and shared resources match default. No separate runtime ProtectionDomain probe was added.

No source correction or automatic retry was required. Original commands, logs, XML, source captures, class/resource manifests and compiled archives are preserved.

## Corrected framework assumptions

The earlier retained `BootCookieWiringReferenceTest.java.example` was never compiled or executed. Its default `WebApplicationContextRunner` pre-attaches `MockServletContext`. Exact Boot4.1.2 condition bytecode and the new passing context tests show that this selects WAR, so that sketch did not establish its assumed embedded eight-property behavior.

The new embedded fixture uses the runner's public context-factory constructor without a pre-attached ServletContext during condition evaluation; a separate MockServletContext bean serves only Spring Session and requests. WAR tests deliberately use the default runner. An intermediate description of WAR as customizers-only was also incorrect: actual upstream WAR ServletContext fields/attributes are mapped before customizers, and this implementation preserves them. The original facts/sketch packet and withdrawn unfrozen WAR-rejection scratch remain unchanged; neither is represented as prior successful runtime evidence.

Upstream alignment was checked against exact Boot4.1.2 commit `634e6fd6bc2e475d745c0bdceb8e775864f7f2ec`, Spring Session4.1.1 commit `7b66dd0b42b131cb1191b30cec1a689de4e06a3b`, retained source and cached public API/condition bytecode. See the [Boot factory and mappings](https://github.com/spring-projects/spring-boot/blob/634e6fd6bc2e475d745c0bdceb8e775864f7f2ec/module/spring-boot-session/src/main/java/org/springframework/boot/session/autoconfigure/SessionAutoConfiguration.java) and [Spring Session configuration](https://github.com/spring-projects/spring-session/blob/7b66dd0b42b131cb1191b30cec1a689de4e06a3b/spring-session-core/src/main/java/org/springframework/session/config/annotation/web/http/SpringHttpSessionConfiguration.java).

## Evidence identities

| Artifact | SHA256 |
|---|---|
| Independently reviewed source3 manifest | `1c00006438d5c9cf84b8f3162d86257bca13f77eca06d8b40015a9880096d091` |
| Fresh497-input source manifest | `3e54e157f1eb3102f7a80d8889fcfcb7682ea7301d91044009114c0b326f5239` |
| Focused verified summary | `23dcb044315ffe30bbcb2520f7c8ffb915ef499586162239011ec1f9e65ec7b8` |
| Default verified summary | `e7e09500378c6fa5f6ee386827e74b7b793745c0f4e160911220fd9e562e3cce` |
| Profile verified summary | `37cbf47beda512929a740b563278d4a52d43cca0b926769cf371329f179421cf` |
| Full verification packet | `42bdfa753be9443be7735befc361de45f59313c09fb8975403ba48fa6cd22b85` |
| Full2863-file evidence manifest | `5ca470b1a5ba48f186c78097243ef50e91bdc6e911d049c943e7ba00549fc367` |
| Profile985-artifact runtime manifest | `aee7dbc6a23f82baaf1dca837df9fb96b650fc6cff4e6aa91a46e82addfa602e` |
| Profile compiled archive | `b70f505c63b97736fd937c730f0ead4cdda87673298b9c52dc2e9472dca74335` |
| Unchanged source V20 | `dafe3dff365e0fd50761af8082a6d6462e7354fdae02d4bea31bb8d7689e7c54` |

Retained local packets are `/tmp/msf-g008-boot-cookie-wiring-source-20261104`, `/tmp/msf-g008-boot-cookie-wiring-targeted-linux-20261104/attempt1` and `/tmp/msf-g008-boot-cookie-wiring-full-linux-20261104/attempt1`. They are audit evidence, not installed configuration or secrets.

## Remaining acceptance

This batch wires Cookie construction and its immediate framework consumer. It registers no session-authority service/control/key SQL bean and changes no login/logout handler or protected HUMAN authorization call edge. Full `SecurityConfiguration` integration, embedded servlet filter registration/order, actual server/browser/HTTP/JDBC behavior, V20 target readiness/activation and all-HUMAN consumption remain open. Cookie/Map-repository evidence does not prove SQL ownership, a durable fence or a successful transaction.

No new key, schema, role/grant, database operation, V20 application, sensitive core activation or deployment belongs to this batch. Complete G008 and overall P0–P2 acceptance remain unproven until their remaining required gates are satisfied.
