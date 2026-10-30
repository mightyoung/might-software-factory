package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentEntityReadAccessPolicyTest {

    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ORG_ID = UUID.fromString("31000000-0000-0000-0000-000000000001");

    private final CurrentTenantContext session =
            new CurrentTenantContext(ACTOR_ID, ACCOUNT_ID, TENANT_ID, MEMBER_ID, 17L, 23L);

    @Test
    void grantsFindingReadOnlyForActiveIdentityWithCurrentEpochsAndAnExplicitOrganizationScope() {
        PersistentEntityReadAuthorizationRepository repository = (context, permissionCode) ->
                Optional.of(snapshot("ACTIVE", "ACTIVE", "ACTIVE", 17L, 23L, Set.of(ORG_ID)));
        EntityReadAccessPolicy policy = new PersistentEntityReadAccessPolicy(repository);

        Optional<EntityReadGrant> grant = policy.resolveEntityReadGrant(session, "finding");

        assertThat(grant).contains(new EntityReadGrant(TENANT_ID, Set.of(ORG_ID)));
    }

    @Test
    void rejectsAnInactiveAccountTenantOrMembership() {
        for (FindingReadAuthorizationSnapshot identity : Set.of(
                snapshot("LOCKED", "ACTIVE", "ACTIVE", 17L, 23L, Set.of(ORG_ID)),
                snapshot("ACTIVE", "SUSPENDED", "ACTIVE", 17L, 23L, Set.of(ORG_ID)),
                snapshot("ACTIVE", "ACTIVE", "DISABLED", 17L, 23L, Set.of(ORG_ID)))) {
            PersistentEntityReadAuthorizationRepository repository = (context, permissionCode) ->
                    Optional.of(identity);

            assertThat(new PersistentEntityReadAccessPolicy(repository)
                    .resolveEntityReadGrant(session, "finding")).isEmpty();
        }
    }

    @Test
    void rejectsSessionsWhoseActorIsNotAnActiveHumanPrincipalBoundToTheSessionAccount() {
        for (FindingReadAuthorizationSnapshot identity : Set.of(
                principalSnapshot(ACTOR_ID, ACCOUNT_ID, "HUMAN", "DISABLED", "ACTIVE", "ACTIVE", "ACTIVE",
                        17L, 23L, Set.of(ORG_ID)),
                principalSnapshot(ACTOR_ID, ACCOUNT_ID, "SERVICE", "ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE",
                        17L, 23L, Set.of(ORG_ID)),
                principalSnapshot(UUID.fromString("10000000-0000-0000-0000-000000000099"), ACCOUNT_ID,
                        "HUMAN", "ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE", 17L, 23L, Set.of(ORG_ID)),
                principalSnapshot(ACTOR_ID, UUID.fromString("11000000-0000-0000-0000-000000000099"),
                        "HUMAN", "ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE", 17L, 23L, Set.of(ORG_ID)))) {
            PersistentEntityReadAuthorizationRepository repository = (context, permissionCode) ->
                    Optional.of(identity);

            assertThat(new PersistentEntityReadAccessPolicy(repository)
                    .resolveEntityReadGrant(session, "finding")).isEmpty();
        }
    }

    @Test
    void rejectsSessionsWhoseTenantOrMemberAuthorizationEpochIsStale() {
        for (FindingReadAuthorizationSnapshot identity : Set.of(
                snapshot("ACTIVE", "ACTIVE", "ACTIVE", 18L, 23L, Set.of(ORG_ID)),
                snapshot("ACTIVE", "ACTIVE", "ACTIVE", 17L, 24L, Set.of(ORG_ID)))) {
            PersistentEntityReadAuthorizationRepository repository = (context, permissionCode) ->
                    Optional.of(identity);

            assertThat(new PersistentEntityReadAccessPolicy(repository)
                    .resolveEntityReadGrant(session, "finding")).isEmpty();
        }
    }

    @Test
    void rejectsMissingPermissionScopeAndUnsupportedEntityKeys() {
        PersistentEntityReadAuthorizationRepository emptyScope = (context, permissionCode) ->
                Optional.of(snapshot("ACTIVE", "ACTIVE", "ACTIVE", 17L, 23L, Set.of()));
        PersistentEntityReadAuthorizationRepository missing = (context, permissionCode) -> Optional.empty();

        assertThat(new PersistentEntityReadAccessPolicy(emptyScope)
                .resolveEntityReadGrant(session, "finding")).isEmpty();
        assertThat(new PersistentEntityReadAccessPolicy(missing)
                .resolveEntityReadGrant(session, "finding")).isEmpty();
        assertThat(new PersistentEntityReadAccessPolicy(emptyScope)
                .resolveEntityReadGrant(session, "unknown")).isEmpty();
    }

    @Test
    void requestsTheStableFindingReadPermissionCode() {
        String[] observedCode = new String[1];
        PersistentEntityReadAuthorizationRepository repository = (context, permissionCode) -> {
            observedCode[0] = permissionCode;
            return Optional.of(snapshot("ACTIVE", "ACTIVE", "ACTIVE", 17L, 23L, Set.of(ORG_ID)));
        };

        new PersistentEntityReadAccessPolicy(repository).resolveEntityReadGrant(session, "finding");

        assertThat(observedCode[0]).isEqualTo("finding.read");
    }

    private static FindingReadAuthorizationSnapshot snapshot(
            String accountStatus, String tenantStatus, String memberStatus,
            long tenantAuthEpoch, long memberAuthEpoch, Set<UUID> organizations) {
        return principalSnapshot(ACTOR_ID, ACCOUNT_ID, "HUMAN", "ACTIVE", accountStatus, tenantStatus,
                memberStatus, tenantAuthEpoch, memberAuthEpoch, organizations);
    }

    private static FindingReadAuthorizationSnapshot principalSnapshot(
            UUID principalId, UUID principalAccountId, String principalKind, String principalStatus,
            String accountStatus, String tenantStatus, String memberStatus,
            long tenantAuthEpoch, long memberAuthEpoch, Set<UUID> organizations) {
        return new FindingReadAuthorizationSnapshot(
                principalId, principalAccountId, principalKind, principalStatus,
                accountStatus, tenantStatus, memberStatus, tenantAuthEpoch, memberAuthEpoch, organizations);
    }
}
