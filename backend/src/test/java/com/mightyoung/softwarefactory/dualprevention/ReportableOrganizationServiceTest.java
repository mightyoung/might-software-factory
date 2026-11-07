package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.FindingReadAuthorizationSnapshot;
import com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportableOrganizationServiceTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID REPORTABLE = UUID.fromString("31000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void asksForTheCurrentCreateActionScopeAndReturnsOnlyThoseActiveOrganizations() {
        asMember();
        AtomicReference<Set<UUID>> queriedIds = new AtomicReference<>();
        ReportableOrganization row = new ReportableOrganization(REPORTABLE, "NORTH", "North plant");
        ReportableOrganizationService service = new ReportableOrganizationService(
                new SessionContextResolver(),
                authorization(Set.of(REPORTABLE)),
                (tenant, ids) -> {
                    assertThat(tenant).isEqualTo(TENANT);
                    queriedIds.set(ids);
                    return List.of(row);
                }, (tenant, app) -> Optional.of(new com.mightyoung.softwarefactory.tenancy.TenantApplicationState(true,1)));

        assertThat(service.reportable()).containsExactly(row);
        assertThat(queriedIds.get()).containsExactly(REPORTABLE);
    }

    @Test
    void aValidMemberWithoutCreateScopeGetsNoTenantWideOrganizationDirectory() {
        asMember();
        AtomicReference<Set<UUID>> queriedIds = new AtomicReference<>();
        ReportableOrganizationService service = new ReportableOrganizationService(
                new SessionContextResolver(), authorization(Set.of()),
                (tenant, ids) -> {
                    queriedIds.set(ids);
                    return List.of();
                }, (tenant, app) -> Optional.of(new com.mightyoung.softwarefactory.tenancy.TenantApplicationState(true,1)));

        assertThat(service.reportable()).isEmpty();
        assertThat(queriedIds.get()).isEmpty();
    }

    @Test
    void staleAuthorizationEpochFailsBeforeOrganizationLookup() {
        asMember();
        AtomicReference<Boolean> queried = new AtomicReference<>(false);
        FindingWorkflowAuthorizationRepository stale = new FindingWorkflowAuthorizationRepository() {
            @Override
            public Optional<FindingReadAuthorizationSnapshot> loadActionSnapshot(
                    com.mightyoung.softwarefactory.identity.CurrentTenantContext context,
                    String permissionCode, String action) {
                return Optional.of(snapshot(Set.of(REPORTABLE), 17L, 22L));
            }
            @Override
            public java.util.List<com.mightyoung.softwarefactory.authorization.AssignableFindingMember> findAssignableMembers(
                    UUID tenantId, UUID organizationId, String permissionCode, String prefix, UUID afterMemberId, int limit) { return java.util.List.of(); }
            @Override
            public boolean isAssignableMemberAuthorizedForAction(
                    UUID tenantId, UUID memberId, UUID organizationId, String permissionCode) { return false; }
        };
        ReportableOrganizationService service = new ReportableOrganizationService(
                new SessionContextResolver(), stale,
                (tenant, ids) -> {
                    queried.set(true);
                    return List.of();
                }, (tenant, app) -> Optional.of(new com.mightyoung.softwarefactory.tenancy.TenantApplicationState(true,1)));

        assertThatThrownBy(service::reportable).isInstanceOf(AccessDeniedException.class);
        assertThat(queried.get()).isFalse();
    }

    @Test
    void missingOrDisabledApplicationDeniesBeforeOrganizationLookupAndRevocationIsFresh() {
        asMember();
        var enabled = new AtomicReference<Optional<com.mightyoung.softwarefactory.tenancy.TenantApplicationState>>(
                Optional.of(new com.mightyoung.softwarefactory.tenancy.TenantApplicationState(true,1)));
        var queried = new AtomicReference<Boolean>(false);
        var service = new ReportableOrganizationService(new SessionContextResolver(), authorization(Set.of(REPORTABLE)),
                (tenant, ids) -> { queried.set(true); return List.of(); }, (tenant, app) -> {
                    assertThat(tenant).isEqualTo(TENANT); assertThat(app).isEqualTo("safety"); return enabled.get();
                });
        service.reportable();
        queried.set(false);
        enabled.set(Optional.of(new com.mightyoung.softwarefactory.tenancy.TenantApplicationState(false,2)));
        assertThatThrownBy(service::reportable).isInstanceOf(AccessDeniedException.class);
        enabled.set(Optional.empty());
        assertThatThrownBy(service::reportable).isInstanceOf(AccessDeniedException.class);
        assertThat(queried.get()).isFalse();
    }

    private static FindingWorkflowAuthorizationRepository authorization(Set<UUID> organizationIds) {
        return new FindingWorkflowAuthorizationRepository() {
            @Override
            public Optional<FindingReadAuthorizationSnapshot> loadActionSnapshot(
                    com.mightyoung.softwarefactory.identity.CurrentTenantContext context,
                    String permissionCode, String action) {
                assertThat(permissionCode).isEqualTo("finding.create");
                assertThat(action).isEqualTo("create");
                return Optional.of(snapshot(organizationIds, 17L, 23L));
            }
            @Override
            public java.util.List<com.mightyoung.softwarefactory.authorization.AssignableFindingMember> findAssignableMembers(
                    UUID tenantId, UUID organizationId, String permissionCode, String prefix, UUID afterMemberId, int limit) { return java.util.List.of(); }
            @Override
            public boolean isAssignableMemberAuthorizedForAction(
                    UUID tenantId, UUID memberId, UUID organizationId, String permissionCode) { return false; }
        };
    }

    private static FindingReadAuthorizationSnapshot snapshot(Set<UUID> organizationIds, long tenantEpoch, long memberEpoch) {
        return new FindingReadAuthorizationSnapshot(ACTOR, ACCOUNT, "HUMAN", "ACTIVE", "ACTIVE",
                "ACTIVE", "ACTIVE", tenantEpoch, memberEpoch, organizationIds);
    }

    private static void asMember() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                VerifiedSessionPrincipalTestFixture.create(ACTOR, ACCOUNT, TENANT, MEMBER, 17L, 23L),
                null, "ROLE_AUTHENTICATED"));
    }
}
