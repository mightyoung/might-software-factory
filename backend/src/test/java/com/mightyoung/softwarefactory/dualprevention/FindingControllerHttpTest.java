package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator.ValidatedQuery;
import com.mightyoung.softwarefactory.authorization.EntityReadAccessPolicy;
import com.mightyoung.softwarefactory.authorization.EntityReadGrant;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingRecord;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingRepository;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SecurityConfiguration;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;

@WebMvcTest(FindingController.class)
@Import({
        SecurityConfiguration.class,
        SessionContextResolver.class,
        FindingReadAuthorizer.class,
        FindingReadService.class,
        FindingEntitySchema.class,
        FindingApiExceptionHandler.class,
        com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator.class,
        FindingControllerHttpTest.TestDependencies.class
})
class FindingControllerHttpTest {

    private static final String SCHEMA_PATH = "/api/v1/entities/finding/schema";
    private static final String QUERY_PATH = "/api/v1/entities/finding/query";
    private static final String DETAIL_PATH = "/api/v1/entities/finding/";
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID ORG_A = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID FINDING_B = UUID.fromString("40000000-0000-0000-0000-000000000002");

    @Autowired private MockMvc mvc;
    @Autowired private TestFindingRepository repository;
    @Autowired private TestReadAccessPolicy accessPolicy;

    @BeforeEach
    void resetExplicitTestFixtures() {
        repository.reset();
        accessPolicy.grant = Optional.of(new EntityReadGrant(TENANT_A, Set.of(ORG_A)));
        accessPolicy.calls = 0;
    }

    @Test
    void unauthenticatedAndUnverifiedPrincipalsFailClosedWith401() throws Exception {
        mvc.perform(get(SCHEMA_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(cookie().exists("XSRF-TOKEN"));

        mvc.perform(get(SCHEMA_PATH).with(authenticationFor(new TestingAuthenticationToken(
                        "ordinary-user", null, new SimpleGrantedAuthority("ROLE_AUTHENTICATED")))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        assertThat(accessPolicy.calls).isZero();
    }

    @Test
    void authenticatedMemberWithoutReadGrantIsForbidden() throws Exception {
        accessPolicy.grant = Optional.empty();

        mvc.perform(get(SCHEMA_PATH).with(authenticationFor(TENANT_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_DENIED"));
        assertThat(repository.pageCalls + repository.detailCalls).isZero();
    }

    @Test
    void sessionTenantMismatchInAuthorizationScopeIsRejectedBeforeQuerying() throws Exception {
        accessPolicy.grant = Optional.of(new EntityReadGrant(TENANT_B, Set.of(ORG_A)));

        mvc.perform(post(QUERY_PATH)
                        .with(authenticationFor(TENANT_A))
                        .with(validCsrf())
                        .contentType("application/json")
                        .content("{\"page\":{\"number\":1},\"size\":20}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_DENIED"));
        assertThat(repository.pageCalls).isZero();
    }

    @Test
    void csrfRejectsMissingAndMismatchedTokensBeforeTheReadService() throws Exception {
        mvc.perform(post(QUERY_PATH)
                        .with(authenticationFor(TENANT_A))
                        .contentType("application/json")
                        .content("{\"page\":{\"number\":1},\"size\":20}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_DENIED"));

        mvc.perform(post(QUERY_PATH)
                        .with(authenticationFor(TENANT_A))
                        .with(invalidCsrf())
                        .contentType("application/json")
                        .content("{\"page\":{\"number\":1},\"size\":20}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_DENIED"));
        assertThat(repository.pageCalls).isZero();
    }

    @Test
    void validListUsesSessionTenantAndServerScopeAndReturnsExactPagination() throws Exception {
        FindingRecord synthetic = syntheticFinding();
        repository.pageResult = new FindingRepository.PageResult(List.of(synthetic), 11L);

        mvc.perform(post(QUERY_PATH)
                        .with(authenticationFor(TENANT_A))
                        .with(validCsrf())
                        .header("X-Tenant-Id", TENANT_B.toString())
                        .contentType("application/json")
                        .content("{\"page\":{\"number\":1},\"size\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(synthetic.id().toString()))
                .andExpect(jsonPath("$.data.items[0].organizationId").value(ORG_A.toString()))
                .andExpect(jsonPath("$.data.items[0].organizationName").value("安全一部"))
                .andExpect(jsonPath("$.data.items[0].description").value(synthetic.description()))
                .andExpect(jsonPath("$.data.items[0].sourceCategory").value("FIELD_OBSERVATION"))
                .andExpect(jsonPath("$.data.items[0].urgency").value("NORMAL"))
                .andExpect(jsonPath("$.data.items[0].location").value("North loading dock"))
                .andExpect(jsonPath("$.data.items[0].interimMeasure").value("Temporary barrier installed"))
                .andExpect(jsonPath("$.data.items[0].backfill").value(false))
                .andExpect(jsonPath("$.data.items[0].relatedFindingId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].sourceEventId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].riskId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].createdAt").exists())
                .andExpect(jsonPath("$.data.page.total").value(11))
                .andExpect(jsonPath("$.data.page.pageCount").value(1))
                .andExpect(jsonPath("$.meta.schemaVersion").value("4"));

        assertThat(repository.lastScope.tenantId()).isEqualTo(TENANT_A);
        assertThat(repository.lastScope.organizationIds()).containsExactly(ORG_A);
        assertThat(repository.lastQuery.sort()).extracting("field")
                .containsExactly("createdAt", "id");
        assertThat(repository.lastQuery.sort()).extracting("direction")
                .containsExactly(Direction.DESC, Direction.ASC);
        assertThat(repository.lastQuery.fields()).contains("id", "code", "organizationId", "organizationName",
                "description", "sourceEventId", "riskId", "discoveredAt", "sourceCategory", "urgency",
                "location", "interimMeasure", "backfill", "relatedFindingId", "createdAt");
    }

    @Test
    void detailReturnsCreationFieldsAndReadableOrganizationName() throws Exception {
        FindingRecord synthetic = syntheticFinding();
        repository.visible = Optional.of(synthetic);

        mvc.perform(get(DETAIL_PATH + synthetic.id()).with(authenticationFor(TENANT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationId").value(ORG_A.toString()))
                .andExpect(jsonPath("$.data.organizationName").value("安全一部"))
                .andExpect(jsonPath("$.data.code").value(synthetic.code()))
                .andExpect(jsonPath("$.data.description").value(synthetic.description()))
                .andExpect(jsonPath("$.data.discoveredAt").exists())
                .andExpect(jsonPath("$.data.sourceEventId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.riskId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.grade").value(synthetic.grade()))
                .andExpect(jsonPath("$.data.relatedFindingId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.meta.schemaVersion").value("4"));
    }

    @Test
    void requestBodyCannotSelectTenantAndInternalFieldsOrUnsupportedPaginationAreRejected() throws Exception {
        for (String body : List.of(
                "{\"page\":{\"number\":1},\"size\":20,\"tenantId\":\"" + TENANT_B + "\"}",
                "{\"filterAST\":{\"operator\":\"AND\",\"conditions\":[{\"field\":\"organization_id\",\"operator\":\"EQ\",\"value\":\""
                        + ORG_A + "\"}]},\"page\":{\"number\":1},\"size\":20}",
                "{\"filterAST\":{\"operator\":\"AND\",\"conditions\":[{\"field\":\"roundNo\",\"operator\":\"EQ\",\"value\":1.5}]},\"page\":{\"number\":1},\"size\":20}",
                "{\"page\":{\"cursor\":\"next\"},\"size\":20}",
                "{\"page\":{\"number\":1},\"size\":10}")) {
            mvc.perform(post(QUERY_PATH)
                            .with(authenticationFor(TENANT_A))
                            .with(validCsrf())
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        }
        assertThat(repository.pageCalls).isZero();
    }

    @Test
    void nonexistentAndOutOfScopeDetailsShareTheSameNotVisibleResponse() throws Exception {
        UUID nonexistent = UUID.fromString("40000000-0000-0000-0000-000000000099");

        String outsideScope = mvc.perform(get(DETAIL_PATH + FINDING_B)
                        .with(authenticationFor(TENANT_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_VISIBLE"))
                .andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(get(DETAIL_PATH + nonexistent)
                        .with(authenticationFor(TENANT_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_VISIBLE"))
                .andReturn().getResponse().getContentAsString();

        assertThat(outsideScope).contains("The finding is not visible");
        assertThat(missing).contains("The finding is not visible");
        assertThat(repository.detailCalls).isEqualTo(2);
        assertThat(repository.lastScope.tenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void schemaReadReturnsOnlyTheRegisteredFindingFields() throws Exception {
        mvc.perform(get(SCHEMA_PATH).with(authenticationFor(TENANT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.key").value("finding"))
                .andExpect(jsonPath("$.data.schemaVersion").value("4"))
                .andExpect(jsonPath("$.data.fields.organization_id").doesNotExist())
                .andExpect(jsonPath("$.data.fields.organizationId.type").value("UUID"))
                .andExpect(jsonPath("$.data.fields.relatedFindingId.type").value("UUID"))
                .andExpect(jsonPath("$.data.fields.organizationName.type").value("TEXT"))
                .andExpect(jsonPath("$.data.defaultFields").value(org.hamcrest.Matchers.hasItems(
                        "organizationId", "organizationName", "description", "sourceEventId", "riskId", "createdAt")))
                .andExpect(jsonPath("$.data.fields.description.sortable").value(false));
    }

    private static FindingRecord syntheticFinding() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-30T10:00:00Z");
        return new FindingRecord(
                UUID.fromString("40000000-0000-0000-0000-000000000001"),
                "FND-TEST-01", "Synthetic test finding", now, null, null,
                "GENERAL", "PERSON_CONFIRM_PENDING", 1, 1L, now, now, ORG_A, "安全一部",
                "FIELD_OBSERVATION", "NORMAL", "North loading dock", "Temporary barrier installed", false);
    }

    private static RequestPostProcessor authenticationFor(UUID tenantId) {
        var principal = VerifiedSessionPrincipalTestFixture.create(ACTOR_ID, tenantId, MEMBER_ID);
        return authenticationFor(new TestingAuthenticationToken(principal, null,
                new SimpleGrantedAuthority("ROLE_AUTHENTICATED")));
    }

    private static RequestPostProcessor authenticationFor(Authentication authentication) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            request.setAttribute("SPRING_SECURITY_CONTEXT", context);
            request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(request.getSession(false));
            return request;
        };
    }

    private static RequestPostProcessor validCsrf() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", "test-csrf-token"));
            request.addHeader("X-XSRF-TOKEN", "test-csrf-token");
            return request;
        };
    }

    private static RequestPostProcessor invalidCsrf() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", "expected-csrf-token"));
            request.addHeader("X-XSRF-TOKEN", "different-csrf-token");
            return request;
        };
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDependencies {
        @org.springframework.context.annotation.Bean
        com.mightyoung.softwarefactory.identity.AuthenticationEvents authenticationEvents() {
            return event -> { }; // Web-slice observation stub; real append acceptance runs against PostgreSQL.
        }

        // This HTTP slice supplies identity state explicitly; persisted revocation is tested separately.
        @Bean
        com.mightyoung.softwarefactory.identity.AccountStatusReadRepository accountSecurityStates() {
            return account -> java.util.Optional.of(
                    new com.mightyoung.softwarefactory.identity.AccountSecurityState("ACTIVE", 1));
        }

        @Bean TestFindingRepository testFindingRepository() { return new TestFindingRepository(); }
        @Bean TestReadAccessPolicy testReadAccessPolicy() { return new TestReadAccessPolicy(); }
        @Bean AuthenticationProvider localPasswordAuthenticationProvider() {
            return new AuthenticationProvider() {
                @Override
                public Authentication authenticate(Authentication authentication) {
                    return UsernamePasswordAuthenticationToken.authenticated(
                            VerifiedSessionPrincipalTestFixture.create(ACTOR_ID,
                                    UUID.fromString("11000000-0000-0000-0000-000000000001"),
                                    TENANT_A, MEMBER_ID, 0L, 0L), null, List.of());
                }

                @Override
                public boolean supports(Class<?> authentication) {
                    return authentication.getName().equals(
                            "com.mightyoung.softwarefactory.identity.LocalPasswordAuthenticationToken");
                }
            };
        }
    }

    static final class TestFindingRepository implements FindingRepository {
        private int pageCalls;
        private int detailCalls;
        private FindingReadScope lastScope;
        private ValidatedQuery lastQuery;
        private PageResult pageResult = new PageResult(List.of(), 0);
        private Optional<FindingRecord> visible = Optional.empty();

        void reset() {
            pageCalls = 0;
            detailCalls = 0;
            lastScope = null;
            lastQuery = null;
            pageResult = new PageResult(List.of(), 0);
            visible = Optional.empty();
        }

        @Override
        public PageResult findPage(FindingReadScope scope, ValidatedQuery query) {
            pageCalls++;
            lastScope = scope;
            lastQuery = query;
            return pageResult;
        }

        @Override
        public Optional<FindingRecord> findVisible(FindingReadScope scope, UUID findingId) {
            detailCalls++;
            lastScope = scope;
            return visible;
        }
    }

    static final class TestReadAccessPolicy implements EntityReadAccessPolicy {
        private int calls;
        private Optional<EntityReadGrant> grant = Optional.empty();

        @Override
        public Optional<EntityReadGrant> resolveEntityReadGrant(CurrentTenantContext context, String entityKey) {
            calls++;
            return grant;
        }
    }
}
