package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentSessionServiceTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsCurrentMemberDisplayFieldsOnlyAfterRepositoryRevalidation() {
        asMember();
        CurrentSessionDetails expected = new CurrentSessionDetails(
                ACTOR, ACCOUNT, TENANT, "north-plant", "North plant", MEMBER, "Synthetic Operator");
        CurrentSessionService service = new CurrentSessionService(
                new SessionContextResolver(), context -> {
                    assertThat(context.tenantAuthEpoch()).isEqualTo(17L);
                    assertThat(context.memberAuthEpoch()).isEqualTo(23L);
                    return Optional.of(expected);
                });

        assertThat(service.current()).isEqualTo(expected);
    }

    @Test
    void revokedMembershipAndMismatchedRepositoryIdentityFailClosed() {
        asMember();
        CurrentSessionService revoked = new CurrentSessionService(new SessionContextResolver(), context -> Optional.empty());
        assertThatThrownBy(revoked::current).isInstanceOf(AccessDeniedException.class);

        CurrentSessionService mismatched = new CurrentSessionService(new SessionContextResolver(), context ->
                Optional.of(new CurrentSessionDetails(ACTOR, ACCOUNT, TENANT, "north-plant", "North plant",
                        UUID.fromString("20000000-0000-0000-0000-000000000099"), "Other member")));
        assertThatThrownBy(mismatched::current).isInstanceOf(AccessDeniedException.class);
    }

    private static void asMember() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                VerifiedSessionPrincipalTestFixture.create(ACTOR, ACCOUNT, TENANT, MEMBER, 17L, 23L),
                null, "ROLE_AUTHENTICATED"));
    }
}
