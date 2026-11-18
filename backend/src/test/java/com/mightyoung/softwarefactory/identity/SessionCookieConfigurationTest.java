package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.context.servlet.AnnotationConfigServletWebApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** Real Boot/session context and filter fixtures; no server, JDBC, V20 or HUMAN authority activation. */
class SessionCookieConfigurationTest {
    private final WebApplicationContextRunner runner = embeddedRunner()
            .withUserConfiguration(SessionCookieConfiguration.class, MapFixture.class);

    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test void embeddedPropertiesCustomizeOnceAndActualFilterSharesTheObservedSerializer() {
        runner.withUserConfiguration(OrderedCustomizers.class).withPropertyValues(
                "server.servlet.session.cookie.name=AUTH_PROBE",
                "server.servlet.session.cookie.domain=example.test",
                "server.servlet.session.cookie.path=/auth",
                "server.servlet.session.cookie.http-only=false",
                "server.servlet.session.cookie.secure=true",
                "server.servlet.session.cookie.max-age=120s",
                "server.servlet.session.cookie.same-site=none",
                "server.servlet.session.cookie.partitioned=true").run(context -> {
            assertThat(context).hasNotFailed();
            // No pre-attached servlet context: actual Boot selects its embedded property factory path.
            assertThat(context.getServletContext()).isNull();
            KnownLogoutRequests bundle = context.getBean(KnownLogoutRequests.class);
            CookieSerializer serializer = context.getBean(CookieSerializer.class);
            assertThat(serializer).isSameAs(bundle.cookieSerializer());
            assertThat(context.getBean(HttpSessionIdResolver.class)).isSameAs(bundle.sessionIdResolver());
            assertThat(context).doesNotHaveBean("cookieSerializer");
            Recorder recorder = context.getBean(Recorder.class);
            assertThat(recorder.orders).containsExactly(10, 20);
            assertThat(recorder.targets).hasSize(2).allSatisfy(target -> assertThat(target).isSameAs(serializer));
            assertThat(((DefaultCookieSerializer) serializer).getRememberMeRequestAttribute())
                    .isEqualTo(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR);

            Created created = create(context, request(context));
            assertThat(created.header()).startsWith("AUTH_PROBE=").contains("Domain=example.test", "Path=/auth",
                    "Max-Age=120", "; Secure", "SameSite=None", "Partitioned").doesNotContain("HttpOnly");
            Cookie emitted = MockCookie.parse(created.header());
            assertThat(emitted.getValue()).isNotEqualTo(created.id());
            AtomicInteger copies = new AtomicInteger();
            for (int attempt = 0; attempt < 2; attempt++) {
                var incoming = request(context);
                incoming.setCookies(new CountingCookie(emitted.getName(), emitted.getValue(), copies));
                int beforeFilter = copies.get();
                filter(context).doFilter(incoming, new MockHttpServletResponse(), (raw, response) -> {
                    HttpServletRequest wrapped = (HttpServletRequest) raw;
                    assertThat(wrapped.getSession(false).getId()).isEqualTo(created.id());
                    assertThat(wrapped.getRequestedSessionId()).isEqualTo(created.id());
                    // Upstream DefaultCookieSerializer does not clone cookies. These clones prove
                    // the actual filter traversed this bundle observer's snapshot/delegate path.
                    assertThat(copies.get()).isEqualTo(beforeFilter + 2);
                    try (var capture = bundle.capture(wrapped)) {
                        assertThat(capture.bearerId()).isEqualTo(created.id());
                        assertThat(copies.get()).isGreaterThan(beforeFilter + 2);
                    }
                });
            }
            assertThat(recorder.orders).containsExactly(10, 20);
            assertThat(context.getBeansOfType(SessionAuthorityService.class)).isEmpty();
            assertThat(context.getBeansOfType(CurrentHumanSessionAuthority.class)).isEmpty();
            assertThat(context.getBeansOfType(SessionAuthorityControlReader.class)).isEmpty();
            assertThatThrownBy(() -> ((DefaultCookieSerializer) serializer).setCookieName("changed"))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test void omittedSameSiteAndExplicitFalseOverrideSerializerDefaultsAndSecureRequest() {
        runner.withPropertyValues("server.servlet.session.cookie.same-site=omitted",
                "server.servlet.session.cookie.http-only=false", "server.servlet.session.cookie.secure=false",
                "server.servlet.session.cookie.partitioned=false").run(context -> {
            assertThat(context).hasNotFailed();
            var incoming = request(context); incoming.setSecure(true);
            assertThat(create(context, incoming).header()).doesNotContain("SameSite=", "HttpOnly", "; Secure", "Partitioned");
        });
    }

    @Test void nullPropertiesLeaveNamePathHttpOnlySameSiteAndRequestDerivedSecurityDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var plain = request(context); plain.setContextPath("/ctx"); plain.setRequestURI("/ctx/action");
            assertThat(create(context, plain).header()).startsWith("SESSION=")
                    .contains("Path=/ctx", "HttpOnly", "SameSite=Lax")
                    .doesNotContain("Domain=", "Max-Age=", "; Secure", "Partitioned");
            var secure = request(context); secure.setSecure(true);
            assertThat(create(context, secure).header()).contains("; Secure");
        });
    }

    @Test void maxAgePreservesBootSecondsAndIntNarrowingIncludingZeroAndOverflow() {
        for (String duration : List.of("120500ms", "0s", "2147483648s")) {
            runner.withPropertyValues("server.servlet.session.cookie.max-age=" + duration).run(context -> {
                assertThat(context).hasNotFailed();
                String header = create(context, request(context)).header();
                if (duration.equals("120500ms")) assertThat(header).contains("Max-Age=120;");
                else if (duration.equals("0s")) assertThat(header).contains("Max-Age=0;");
                else assertThat(header).doesNotContain("Max-Age=");
            });
        }
    }

    @Test void actualExistingPolicyOverridesMappedPropertiesWithoutInventingCustomizerOrder() {
        runner.withUserConfiguration(ActualSecurityPolicy.class).withPropertyValues(
                "server.servlet.session.cookie.name=PROPERTY_NAME", "server.servlet.session.cookie.domain=example.test",
                "server.servlet.session.cookie.path=/property", "server.servlet.session.cookie.http-only=false",
                "server.servlet.session.cookie.secure=false", "server.servlet.session.cookie.same-site=none",
                "server.servlet.session.cookie.max-age=60s", "server.servlet.session.cookie.partitioned=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(create(context, request(context)).header()).startsWith("SESSION=")
                    .contains("Path=/;", "HttpOnly", "; Secure", "SameSite=Lax", "Domain=example.test", "Max-Age=60", "Partitioned")
                    .doesNotContain("PROPERTY_NAME", "Path=/property", "SameSite=None");
        });
    }

    @Test void malformedInputAndLaterCleanRequestDoNotReuseAContextObservation() {
        runner.withPropertyValues("server.servlet.session.cookie.name=NONDEFAULT").run(context -> {
            assertThat(context).hasNotFailed();
            var bundle = context.getBean(KnownLogoutRequests.class);
            var malformed = request(context); malformed.setCookies(new Cookie("NONDEFAULT", "!malformed!"));
            filter(context).doFilter(malformed, new MockHttpServletResponse(), (raw, response) -> {
                var wrapped = (HttpServletRequest) raw;
                assertThat(wrapped.getRequestedSessionId()).isNull();
                assertThatThrownBy(() -> bundle.capture(wrapped)).isInstanceOfSatisfying(SessionSensitiveException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(SessionSensitiveException.Reason.UNAVAILABLE));
            });
            Created created = create(context, request(context));
            var valid = request(context); valid.setCookies(MockCookie.parse(created.header()));
            filter(context).doFilter(valid, new MockHttpServletResponse(), (raw, response) -> {
                try (var capture = bundle.capture((HttpServletRequest) raw)) { assertThat(capture.bearerId()).isEqualTo(created.id()); }
            });
        });
    }

    @Test void actualContextPreCaptureRotationCannotTreatNewIdAsKnownOldLineage() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var bundle = context.getBean(KnownLogoutRequests.class); Created created = create(context, request(context));
            var incoming = request(context); incoming.setCookies(MockCookie.parse(created.header()));
            filter(context).doFilter(incoming, new MockHttpServletResponse(), (raw, response) -> {
                var wrapped = (HttpServletRequest) raw;
                assertThat(wrapped.getSession(false).getId()).isEqualTo(created.id()); wrapped.changeSessionId();
                assertThat(wrapped.getRequestedSessionId()).isEqualTo(created.id());
                assertThat(wrapped.getSession(false).getId()).isNotEqualTo(created.id());
                assertThatThrownBy(() -> bundle.capture(wrapped)).isInstanceOfSatisfying(SessionSensitiveException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(SessionSensitiveException.Reason.UNAVAILABLE));
            });
        });
    }

    @Test void deletedFrameworkRowRetainsConfiguredCookieEvidenceWithoutInventingSqlOwnership() {
        runner.withPropertyValues("server.servlet.session.cookie.name=RETAINED").run(context -> {
            assertThat(context).hasNotFailed(); Created created = create(context, request(context));
            context.getBean(MapSessionRepository.class).deleteById(created.id());
            var incoming = request(context); incoming.setCookies(MockCookie.parse(created.header()));
            filter(context).doFilter(incoming, new MockHttpServletResponse(), (raw, response) -> {
                var wrapped = (HttpServletRequest) raw; assertThat(wrapped.getSession(false)).isNull();
                try (var capture = context.getBean(KnownLogoutRequests.class).capture(wrapped)) {
                    assertThat(capture.bearerId()).isEqualTo(created.id()); assertThat(capture.claimedAccount()).isNull();
                }
            });
        });
    }

    @Test void actualContextRejectsFirstDeadSecondLiveCookieAmbiguity() {
        runner.run(context -> {
            assertThat(context).hasNotFailed(); var bundle = context.getBean(KnownLogoutRequests.class);
            Created live = create(context, request(context));
            var response = new MockHttpServletResponse(); bundle.sessionIdResolver().setSessionId(request(context), response, "dead-id");
            var incoming = request(context); incoming.setCookies(MockCookie.parse(response.getHeader("Set-Cookie")), MockCookie.parse(live.header()));
            filter(context).doFilter(incoming, new MockHttpServletResponse(), (raw, servletResponse) -> {
                var wrapped = (HttpServletRequest) raw;
                assertThat(wrapped.getRequestedSessionId()).isEqualTo("dead-id");
                assertThat(wrapped.getSession(false).getId()).isEqualTo(live.id());
                assertThatThrownBy(() -> bundle.capture(wrapped)).isInstanceOf(SessionSensitiveException.class);
            });
        });
    }

    @Test void primaryForeignSerializerFailsExplicitUniquenessInsteadOfSilentlyWinningInjection() {
        runner.withUserConfiguration(PrimaryForeignSerializer.class).run(context -> {
            assertThat(context).hasFailed(); assertTrustedUniquenessFailure(context.getStartupFailure());
        });
    }

    @Test void primaryForeignResolverFailsExplicitUniquenessInsteadOfSilentlyWinningInjection() {
        runner.withUserConfiguration(PrimaryForeignResolver.class).run(context -> {
            assertThat(context).hasFailed(); assertTrustedUniquenessFailure(context.getStartupFailure());
        });
    }

    @Test void nonPrimaryForeignSerializerOrResolverAlsoCannotStart() {
        runner.withUserConfiguration(ForeignSerializer.class).run(context -> assertThat(context).hasFailed());
        runner.withUserConfiguration(ForeignResolver.class).run(context -> assertThat(context).hasFailed());
    }

    @Test void actualBootNegativeControlBuildsAnUnusedSecondSerializerWhenOnlyResolverIsExported() {
        embeddedRunner().withUserConfiguration(MapFixture.class, ResolverOnlyNegativeControl.class, OrderedCustomizers.class)
                .withPropertyValues("server.servlet.session.cookie.name=BOOT_ONLY").run(context -> {
            assertThat(context).hasNotFailed();
            KnownLogoutRequests bundle = context.getBean(KnownLogoutRequests.class);
            CookieSerializer unused = context.getBean(CookieSerializer.class);
            assertThat(unused).isNotSameAs(bundle.cookieSerializer());
            Recorder recorder = context.getBean(Recorder.class);
            assertThat(recorder.orders).hasSize(4);
            assertThat(recorder.orders.stream().filter(order -> order == 10).count()).isEqualTo(2);
            assertThat(recorder.orders.stream().filter(order -> order == 20).count()).isEqualTo(2);
            assertThat(recorder.targets).contains((DefaultCookieSerializer) bundle.cookieSerializer(), (DefaultCookieSerializer) unused);
            assertThat(create(context, request(context)).header()).startsWith("BUNDLE_ONLY=");
            var response = new MockHttpServletResponse(); unused.writeCookieValue(new CookieSerializer.CookieValue(request(context), response, "sample-id"));
            assertThat(response.getHeader("Set-Cookie")).startsWith("BOOT_ONLY=");
        });
    }

    @Test void warMapsActualServletCookieConfigBeforeCustomizersAndIgnoresEmbeddedProperties() {
        warRunner().withUserConfiguration(OrderedCustomizers.class).withInitializer(context -> {
            var cookie = context.getServletContext().getSessionCookieConfig();
            cookie.setName("WAR_SESSION"); cookie.setDomain("war.example.test"); cookie.setPath("/war");
            cookie.setHttpOnly(true); cookie.setSecure(true); cookie.setMaxAge(90);
            cookie.setAttribute("SameSite", "Strict"); cookie.setAttribute("Partitioned", "true");
        }).withPropertyValues("server.servlet.session.cookie.name=IGNORED_PROPERTY", "server.servlet.session.cookie.path=/ignored",
                "server.servlet.session.cookie.http-only=false", "server.servlet.session.cookie.secure=false",
                "server.servlet.session.cookie.same-site=omitted", "server.servlet.session.cookie.max-age=999s").run(context -> {
            assertThat(context).hasNotFailed(); assertThat(context.getServletContext()).isNotNull();
            var bundle = context.getBean(KnownLogoutRequests.class); var serializer = context.getBean(CookieSerializer.class);
            assertThat(serializer).isSameAs(bundle.cookieSerializer());
            assertThat(context.getBean(HttpSessionIdResolver.class)).isSameAs(bundle.sessionIdResolver());
            assertThat(context).doesNotHaveBean("cookieSerializer");
            var recorder = context.getBean(Recorder.class); assertThat(recorder.orders).containsExactly(10, 20);
            assertThat(recorder.targets).hasSize(2).allSatisfy(target -> assertThat(target).isSameAs(serializer));
            Created created = create(context, request(context));
            assertThat(created.header()).startsWith("WAR_SESSION=").contains("Domain=war.example.test", "Path=/war",
                    "HttpOnly", "; Secure", "Max-Age=90", "SameSite=Strict", "Partitioned").doesNotContain("IGNORED_PROPERTY", "/ignored", "Max-Age=999");
            Cookie emitted = MockCookie.parse(created.header()); var copies = new AtomicInteger();
            var incoming = request(context); incoming.setCookies(new CountingCookie(emitted.getName(), emitted.getValue(), copies));
            filter(context).doFilter(incoming, new MockHttpServletResponse(), (raw, response) -> {
                var wrapped = (HttpServletRequest) raw;
                assertThat(wrapped.getSession(false).getId()).isEqualTo(created.id());
                assertThat(copies.get()).isEqualTo(2);
                try (var capture = bundle.capture(wrapped)) { assertThat(capture.bearerId()).isEqualTo(created.id()); }
                assertThat(copies.get()).isGreaterThan(2);
            });
            assertThat(recorder.orders).containsExactly(10, 20);
        });
    }

    @Test void warNullAttributesAndPrimitiveFalsePreserveItsDistinctUpstreamDefaults() {
        warRunner().withPropertyValues("server.servlet.session.cookie.same-site=lax",
                "server.servlet.session.cookie.http-only=true", "server.servlet.session.cookie.secure=true",
                "server.servlet.session.cookie.partitioned=true", "server.servlet.session.cookie.max-age=999s").run(context -> {
            assertThat(context).hasNotFailed(); var incoming = request(context); incoming.setSecure(true);
            assertThat(create(context, incoming).header()).startsWith("SESSION=").contains("Path=/")
                    .doesNotContain("Domain=", "Max-Age=", "HttpOnly", "; Secure", "SameSite=", "Partitioned");
        });
    }

    @Test void warMaxAgeUsesItsActualIntValueAndExplicitFalseAttribute() {
        for (int seconds : List.of(-1, 0, 120)) {
            warRunner().withInitializer(context -> {
                var cookie = context.getServletContext().getSessionCookieConfig();
                cookie.setMaxAge(seconds); cookie.setHttpOnly(false); cookie.setSecure(false);
                cookie.setAttribute("Partitioned", "false");
            }).run(context -> {
                assertThat(context).hasNotFailed(); var incoming = request(context); incoming.setSecure(true);
                String header = create(context, incoming).header();
                if (seconds < 0) assertThat(header).doesNotContain("Max-Age=");
                else assertThat(header).contains("Max-Age=" + seconds + ";");
                assertThat(header).doesNotContain("HttpOnly", "; Secure", "Partitioned", "SameSite=");
            });
        }
    }

    @Test void warAlsoAppliesActualExistingPolicyAfterItsServletConfigWithoutArtificialOrder() {
        warRunner().withUserConfiguration(ActualSecurityPolicy.class).withInitializer(context -> {
            var cookie = context.getServletContext().getSessionCookieConfig();
            cookie.setName("WAR_BEFORE_POLICY"); cookie.setPath("/war-before-policy");
            cookie.setHttpOnly(false); cookie.setSecure(false); cookie.setAttribute("SameSite", "None");
        }).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(create(context, request(context)).header()).startsWith("SESSION=")
                    .contains("Path=/;", "HttpOnly", "; Secure", "SameSite=Lax")
                    .doesNotContain("WAR_BEFORE_POLICY", "/war-before-policy", "SameSite=None");
        });
    }

    @Test void warRealFilterStillRejectsMalformedAndRotatedEvidenceThenAcceptsAnIndependentRequest() {
        warRunner().run(context -> {
            assertThat(context).hasNotFailed(); var bundle = context.getBean(KnownLogoutRequests.class);
            var malformed = request(context); malformed.setCookies(new Cookie("SESSION", "!malformed!"));
            filter(context).doFilter(malformed, new MockHttpServletResponse(), (raw, response) ->
                    assertThatThrownBy(() -> bundle.capture((HttpServletRequest) raw)).isInstanceOf(SessionSensitiveException.class));
            Created created = create(context, request(context));
            var incoming = request(context); incoming.setCookies(MockCookie.parse(created.header()));
            filter(context).doFilter(incoming, new MockHttpServletResponse(), (raw, response) -> {
                var wrapped = (HttpServletRequest) raw; wrapped.getSession(false); wrapped.changeSessionId();
                assertThatThrownBy(() -> bundle.capture(wrapped)).isInstanceOf(SessionSensitiveException.class);
            });
            Created independent = create(context, request(context));
            var next = request(context); next.setCookies(MockCookie.parse(independent.header()));
            filter(context).doFilter(next, new MockHttpServletResponse(), (raw, response) -> {
                try (var capture = bundle.capture((HttpServletRequest) raw)) { assertThat(capture.bearerId()).isEqualTo(independent.id()); }
            });
        });
    }

    private static WebApplicationContextRunner warRunner() {
        // The pre-attached MockServletContext deliberately selects Boot's real WAR branch.
        return new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(SessionAutoConfiguration.class))
                .withUserConfiguration(SessionCookieConfiguration.class, WarMapFixture.class);
    }

    private static WebApplicationContextRunner embeddedRunner() {
        // The default runner attaches MockServletContext before condition evaluation and Boot
        // treats that as WAR. Supply its public context factory without a pre-attached context.
        return new WebApplicationContextRunner(AnnotationConfigServletWebApplicationContext::new)
                .withConfiguration(AutoConfigurations.of(SessionAutoConfiguration.class));
    }
    private static MockHttpServletRequest request(AssertableWebApplicationContext context) {
        return new MockHttpServletRequest(context.getServletContext() != null
                ? context.getServletContext() : context.getBean(MockServletContext.class));
    }
    private static SessionRepositoryFilter<?> filter(AssertableWebApplicationContext context) {
        return context.getBean(SessionRepositoryFilter.class);
    }
    private static Created create(AssertableWebApplicationContext context, MockHttpServletRequest request) throws Exception {
        var id = new AtomicReference<String>(); var response = new MockHttpServletResponse();
        filter(context).doFilter(request, response, (raw, servletResponse) -> id.set(((HttpServletRequest) raw).getSession().getId()));
        return new Created(id.get(), response.getHeader("Set-Cookie"));
    }
    private static Throwable deepest(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause(); return failure;
    }
    private static void assertTrustedUniquenessFailure(Throwable failure) {
        assertThat(deepest(failure)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one trusted session cookie bundle");
    }
    private record Created(String id, String header) { }

    private static final class CountingCookie extends Cookie {
        private static final long serialVersionUID = 1L;
        private final AtomicInteger copies;
        CountingCookie(String name, String value, AtomicInteger copies) { super(name, value); this.copies = copies; }
        @Override public Object clone() { copies.incrementAndGet(); return super.clone(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSpringHttpSession
    static class MapFixture {
        @Bean MapSessionRepository sessionRepository() { return new MapSessionRepository(new ConcurrentHashMap<String, Session>()); }
        @Bean MockServletContext requestServletContext() { return new MockServletContext(); }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableSpringHttpSession
    static class WarMapFixture {
        @Bean MapSessionRepository sessionRepository() { return new MapSessionRepository(new ConcurrentHashMap<String, Session>()); }
    }
    static class Recorder {
        final List<Integer> orders = new ArrayList<>();
        final List<DefaultCookieSerializer> targets = new ArrayList<>();
        void record(int order, DefaultCookieSerializer target) { orders.add(order); targets.add(target); }
    }
    @Configuration(proxyBeanMethods = false)
    static class OrderedCustomizers {
        @Bean Recorder recorder() { return new Recorder(); }
        @Bean @Order(10) DefaultCookieSerializerCustomizer firstCustomizer(Recorder recorder) {
            return serializer -> { recorder.record(10, serializer); serializer.setUseBase64Encoding(true); serializer.setJvmRoute("node-a"); };
        }
        @Bean @Order(20) DefaultCookieSerializerCustomizer secondCustomizer(Recorder recorder) {
            return serializer -> recorder.record(20, serializer);
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class ActualSecurityPolicy {
        @Bean DefaultCookieSerializerCustomizer sessionCookiePolicy() {
            // Invoke the owning production factory, not a copied policy or invented @Order.
            return new SecurityConfiguration().sessionCookiePolicy();
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class PrimaryForeignSerializer {
        @Bean @Primary CookieSerializer foreignSerializer() { return new DefaultCookieSerializer(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class PrimaryForeignResolver {
        @Bean @Primary HttpSessionIdResolver foreignResolver() { return HeaderHttpSessionIdResolver.xAuthToken(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class ForeignSerializer {
        @Bean CookieSerializer foreignSerializer() { return new DefaultCookieSerializer(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class ForeignResolver {
        @Bean HttpSessionIdResolver foreignResolver() { return HeaderHttpSessionIdResolver.xAuthToken(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class ResolverOnlyNegativeControl {
        @Bean KnownLogoutRequests knownLogoutRequests(ObjectProvider<DefaultCookieSerializerCustomizer> customizers) {
            return new KnownLogoutRequests(serializer -> {
                serializer.setCookieName("BUNDLE_ONLY"); customizers.orderedStream().forEach(customizer -> customizer.customize(serializer));
            });
        }
        @Bean CookieHttpSessionIdResolver sessionIdResolver(KnownLogoutRequests bundle) { return bundle.sessionIdResolver(); }
    }
}
