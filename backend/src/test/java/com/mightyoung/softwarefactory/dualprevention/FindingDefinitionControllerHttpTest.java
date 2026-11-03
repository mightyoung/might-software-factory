package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.reference.ReferenceQueryContract;
import com.mightyoung.softwarefactory.identity.*;
import jakarta.servlet.http.Cookie;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real web/security handlers with an explicit synthetic repository; not PostgreSQL acceptance. */
@WebMvcTest(FindingDefinitionController.class)
@Import({SecurityConfiguration.class, SessionContextResolver.class, FindingDefinitionProvider.class,
        FindingDefinitionService.class, FindingOrganizationReferenceRequestParser.class, FindingApiExceptionHandler.class,
        FindingDefinitionControllerHttpTest.Dependencies.class})
class FindingDefinitionControllerHttpTest {
    static final String DEFINITION = "/api/v1/entities/finding/definition";
    static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID ACCOUNT = UUID.fromString("11000000-0000-0000-0000-000000000001");
    static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID MEMBER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID ORGANIZATION = UUID.fromString("31000000-0000-0000-0000-000000000001");
    @Autowired MockMvc mvc;
    @Autowired TestReads reads;
    @BeforeEach void reset() { reads.read = true; reads.create = true; reads.version = 1; reads.calls = 0; reads.candidateCalls = 0; }

    @Test void finiteProjectionPublishesOnlyTheCurrentlyAuthorizedContract() throws Exception {
        mvc.perform(get(DEFINITION).param("use", "READ").with(member()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.use").value("READ"))
                .andExpect(jsonPath("$.data.fields.state.query.type").value("ENUM"))
                .andExpect(jsonPath("$.data.actions").isEmpty()).andExpect(jsonPath("$.data.references").isEmpty())
                .andExpect(jsonPath("$.data.layout.create").isEmpty()).andExpect(jsonPath("$.meta.schemaVersion").value("1"));
        mvc.perform(get(DEFINITION).param("use", "CREATE").with(member()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.use").value("CREATE"))
                .andExpect(jsonPath("$.data.fields.state").doesNotExist())
                .andExpect(jsonPath("$.data.fields.grade.query.enumValues[0]").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.actions['finding.create'].inputFields.length()").value(11))
                .andExpect(jsonPath("$.data.layout.create.length()").value(7));
    }
    @Test void weakSemanticValidatorUsesCurrentProjectionAndIgnoresRandomEnvelopeMetadata() throws Exception {
        var first = mvc.perform(get(DEFINITION).param("use", "READ").with(member())).andExpect(status().isOk()).andReturn().getResponse();
        var second = mvc.perform(get(DEFINITION).param("use", "READ").with(member())).andExpect(status().isOk()).andReturn().getResponse();
        String etag = first.getHeader("ETag");
        assertThat(etag).matches("W/\"[0-9a-f]{64}\""); assertThat(second.getHeader("ETag")).isEqualTo(etag);
        assertThat(first.getContentAsString()).isNotEqualTo(second.getContentAsString());
        mvc.perform(get(DEFINITION).param("use", "READ").with(member()).header("If-None-Match", "\"unrelated\", " + etag.substring(2)))
                .andExpect(status().isNotModified()).andExpect(content().string(""))
                .andExpect(header().string("ETag", etag)).andExpect(header().string("Vary", "Cookie"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("private"), org.hamcrest.Matchers.containsString("no-cache"))));
        String create = mvc.perform(get(DEFINITION).param("use", "CREATE").with(member())).andReturn().getResponse().getHeader("ETag");
        assertThat(create).isNotEqualTo(etag);
        reads.version++;
        mvc.perform(get(DEFINITION).param("use", "READ").with(member()).header("If-None-Match", etag))
                .andExpect(status().isOk()).andExpect(header().string("ETag", org.hamcrest.Matchers.not(etag)));
    }
    @Test void authorizedWildcardReturnsBodylessPrivate304AfterCurrentAuthorityResolution() throws Exception {
        String etag = mvc.perform(get(DEFINITION).param("use", "READ").with(member()))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        for (String method : List.of("GET", "HEAD")) {
            int before = reads.calls;
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), DEFINITION)
                    .param("use", "READ").with(member()).header("If-None-Match", "*"))
                    .andExpect(status().isNotModified()).andExpect(content().string(""))
                    .andExpect(header().string("ETag", etag)).andExpect(header().string("Vary", "Cookie"))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                            org.hamcrest.Matchers.containsString("private"), org.hamcrest.Matchers.containsString("no-cache"))));
            assertThat(reads.calls).isEqualTo(before + 1);
        }
    }
    @Test void nonmatchingMalformedMixedAndDuplicateValidatorsDoNotBecomeWildcardMatches() throws Exception {
        for (Object[] values : List.of(new Object[]{"\"unrelated\""}, new Object[]{"*, \"unrelated\""},
                new Object[]{"*", "\"unrelated\""}, new Object[]{"*", "*"}, new Object[]{"W/*"})) {
            int before = reads.calls;
            mvc.perform(get(DEFINITION).param("use", "READ").with(member()).header("If-None-Match", values))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.use").value("READ"));
            assertThat(reads.calls).isEqualTo(before + 1);
        }
    }
    @Test void revocationIsCheckedBeforeWildcardOrWarmValidatorAndProjectionDoesNotGrantTheOtherAction() throws Exception {
        var etag = mvc.perform(get(DEFINITION).param("use", "READ").with(member())).andReturn().getResponse().getHeader("ETag");
        reads.read = false;
        mvc.perform(get(DEFINITION).param("use", "READ").with(member()).header("If-None-Match", "*"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTION_DENIED"));
        mvc.perform(get(DEFINITION).param("use", "READ").with(member()).header("If-None-Match", etag)).andExpect(status().isForbidden());
        mvc.perform(get(DEFINITION).param("use", "CREATE").with(member())).andExpect(status().isOk());
        reads.read = true; reads.create = false;
        mvc.perform(get(DEFINITION).param("use", "READ").with(member())).andExpect(status().isOk());
        mvc.perform(get(DEFINITION).param("use", "CREATE").with(member())).andExpect(status().isForbidden());
    }
    @Test void projectionSelectorRejectsMissingDuplicateUnknownCaseAndUndeclaredParameters() throws Exception {
        for (String query : List.of("", "?use=READ&use=CREATE", "?use=read", "?use=finding.create", "?use=READ&permission=finding.read"))
            mvc.perform(get(DEFINITION + query).with(member())).andExpect(status().isBadRequest());
        assertThat(reads.calls).isZero();
        mvc.perform(get(DEFINITION).param("use", "READ")).andExpect(status().isUnauthorized());
    }
    @Test void referenceRouteRequiresSessionCsrfAndStrictBoundedBodyAndReturnsNoFalseCursor() throws Exception {
        mvc.perform(post(FindingDefinitionProvider.REFERENCE_PATH).with(member()).contentType("application/json").content("{\"query\":\"\"}"))
                .andExpect(status().isForbidden());
        assertThat(reads.candidateCalls).isZero();
        mvc.perform(post(FindingDefinitionProvider.REFERENCE_PATH).with(member()).with(csrf()).contentType("application/json").content("{\"query\":\"北\",\"limit\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(ORGANIZATION.toString()))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist()).andExpect(jsonPath("$.meta.schemaVersion").value("1"));
        assertThat(reads.lastRequest.query()).isEqualTo("北"); assertThat(reads.lastRequest.limit()).isEqualTo(2);
        mvc.perform(post(FindingDefinitionProvider.REFERENCE_PATH).with(member()).with(csrf()).contentType("application/json").content("{\"query\":\"\",\"limit\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(FindingDefinitionProvider.REFERENCE_PATH).with(member()).with(csrf()).contentType("application/json").content(" ".repeat(2049)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post(FindingDefinitionProvider.REFERENCE_PATH).with(member()).with(csrf()).contentType("text/plain").content("{\"query\":\"\"}"))
                .andExpect(status().isUnsupportedMediaType());
    }
    private static RequestPostProcessor member() {
        return request -> {
            SecurityContext security = SecurityContextHolder.createEmptyContext();
            security.setAuthentication(new TestingAuthenticationToken(VerifiedSessionPrincipalTestFixture.create(ACTOR, ACCOUNT, TENANT, MEMBER, 1, 1), null, "ROLE_AUTHENTICATED"));
            request.setAttribute("SPRING_SECURITY_CONTEXT", security);
            request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
            VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(request.getSession(false)); return request;
        };
    }
    private static RequestPostProcessor csrf() {
        return request -> { request.setCookies(new Cookie("XSRF-TOKEN", "test-csrf-token")); request.addHeader("X-XSRF-TOKEN", "test-csrf-token"); return request; };
    }
    @TestConfiguration(proxyBeanMethods = false) static class Dependencies {
        @Bean AuthenticationEvents events() { return event -> { }; }
        @Bean AccountStatusReadRepository accounts() { return account -> Optional.of(new AccountSecurityState("ACTIVE", 1)); }
        @Bean TestReads testReads() { return new TestReads(); }
        @Bean AuthenticationProvider localPasswordAuthenticationProvider() {
            return new AuthenticationProvider() {
                public org.springframework.security.core.Authentication authenticate(org.springframework.security.core.Authentication value) { return null; }
                public boolean supports(Class<?> type) { return false; }
            };
        }
    }
    static final class TestReads implements FindingDefinitionReadRepository {
        boolean read = true, create = true; long version = 1; int calls, candidateCalls; ReferenceQueryContract.Request lastRequest;
        public <T> T inSnapshot(CurrentTenantContext context, Function<Snapshot, T> work) {
            calls++;
            return work.apply(new Snapshot() {
                public Authority authorize(Use use) {
                    if ((use == Use.READ && !read) || (use == Use.CREATE && !create)) throw new AccessDeniedException("Denied synthetic authority");
                    return new Authority(use, context, Set.of(ORGANIZATION), version);
                }
                public ReferenceQueryContract.Page organizations(Authority authority, ReferenceQueryContract.Request request) {
                    candidateCalls++; lastRequest = request;
                    return new ReferenceQueryContract.Page(List.of(new ReferenceQueryContract.Item(ORGANIZATION, "NORTH", "北厂")), null);
                }
                public void requireActive() { }
            });
        }
    }
}
