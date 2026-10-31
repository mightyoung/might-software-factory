package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.FindingReadAuthorizationSnapshot;
import com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.SecurityConfiguration;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReportableOrganizationController.class)
@Import({SecurityConfiguration.class, SessionContextResolver.class, ReportableOrganizationService.class,
        FindingApiExceptionHandler.class, ReportableOrganizationControllerHttpTest.TestDependencies.class})
class ReportableOrganizationControllerHttpTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID AUTHORIZED_ORG = UUID.fromString("31000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuthorizationState authorizationState;

    @BeforeEach
    void resetAuthorization() {
        authorizationState.snapshot.set(snapshot("ACTIVE", "HUMAN", 1L, 1L));
    }

    @Test
    void endpointReturnsOnlyOrganizationsFromTheCurrentMembersCreateScope() throws Exception {
        mvc.perform(get("/api/v1/organizations/reportable").with(authenticatedMember()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(AUTHORIZED_ORG.toString()))
                .andExpect(jsonPath("$.data[0].code").value("NORTH"))
                .andExpect(jsonPath("$.data[0].name").value("North plant"));
    }

    @Test
    void unauthenticatedRequestCannotReadReportableOrganizations() throws Exception {
        mvc.perform(get("/api/v1/organizations/reportable"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void staleAuthenticatedMemberReceivesForbidden() throws Exception {
        authorizationState.snapshot.set(snapshot("ACTIVE", "HUMAN", 0L, 1L));
        mvc.perform(get("/api/v1/organizations/reportable").with(authenticatedMember()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_DENIED"));
    }

    private static RequestPostProcessor authenticatedMember() {
        return request -> {
            var principal = VerifiedSessionPrincipalTestFixture.create(ACTOR, ACCOUNT, TENANT, MEMBER, 1L, 1L);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new TestingAuthenticationToken(principal, null, "ROLE_AUTHENTICATED"));
            request.setAttribute("SPRING_SECURITY_CONTEXT", context);
            request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            return request;
        };
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean
        AuthorizationState authorizationState() {
            return new AuthorizationState();
        }

        @Bean
        AuthenticationProvider localPasswordAuthenticationProvider() {
            return new AuthenticationProvider() {
                @Override
                public org.springframework.security.core.Authentication authenticate(
                        org.springframework.security.core.Authentication authentication) {
                    return null;
                }
                @Override
                public boolean supports(Class<?> authentication) { return false; }
            };
        }

        @Bean
        FindingWorkflowAuthorizationRepository findingWorkflowAuthorizationRepository(AuthorizationState state) {
            return new FindingWorkflowAuthorizationRepository() {
                @Override
                public Optional<FindingReadAuthorizationSnapshot> loadActionSnapshot(
                        com.mightyoung.softwarefactory.identity.CurrentTenantContext context,
                        String permissionCode, String action) {
                    return Optional.of(state.snapshot.get());
                }
                @Override
                public boolean isAssignableMemberAuthorizedForAction(
                        UUID tenantId, UUID memberId, UUID organizationId, String permissionCode) { return false; }
            };
        }

        @Bean
        ReportableOrganizationReadRepository reportableOrganizationReadRepository() {
            return (tenantId, organizationIds) -> organizationIds.contains(AUTHORIZED_ORG)
                    ? List.of(new ReportableOrganization(AUTHORIZED_ORG, "NORTH", "North plant"))
                    : List.of();
        }
    }

    static final class AuthorizationState {
        private final AtomicReference<FindingReadAuthorizationSnapshot> snapshot = new AtomicReference<>(
                snapshot("ACTIVE", "HUMAN", 1L, 1L));
    }

    private static FindingReadAuthorizationSnapshot snapshot(
            String principalStatus, String principalKind, long tenantEpoch, long memberEpoch) {
        return new FindingReadAuthorizationSnapshot(
                ACTOR, ACCOUNT, principalKind, principalStatus, "ACTIVE", "ACTIVE", "ACTIVE",
                tenantEpoch, memberEpoch, Set.of(AUTHORIZED_ORG));
    }
}
