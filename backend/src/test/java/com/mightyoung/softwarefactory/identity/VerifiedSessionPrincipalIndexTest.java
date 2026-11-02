package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.PrincipalNameIndexResolver;

import java.io.ObjectStreamClass;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedSessionPrincipalIndexTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accountScopeGroupsDifferentTenantsAndMembersWithoutGroupingOtherAccounts() {
        UUID account = UUID.randomUUID();
        var tenantA = principal(account);
        var tenantB = principal(account);
        var otherAccount = principal(UUID.randomUUID());

        assertThat(authentication(tenantA).getName()).isEqualTo(account.toString());
        assertThat(authentication(tenantB).getName()).isEqualTo(account.toString());
        assertThat(authentication(otherAccount).getName()).isEqualTo(otherAccount.accountId().toString())
                .isNotEqualTo(account.toString());
        assertThat(tenantA.toString()).isEqualTo("VerifiedSessionPrincipal");
        assertThat(authentication(tenantA).toString()).doesNotContain(account.toString(),
                tenantA.actorId().toString(), tenantA.tenantId().toString(), tenantA.memberId().toString());
    }

    @Test
    void springSessionResolverUsesTheAccountNameAfterSecurityContextSerialization() {
        var principal = principal(UUID.randomUUID());
        var context = new SecurityContextImpl(authentication(principal));
        var restored = new DeserializingConverter().convert(new SerializingConverter().convert(context));
        var session = new MapSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", restored);

        assertThat(new PrincipalNameIndexResolver<MapSession>().resolveIndexesFor(session))
                .containsOnlyKeys(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME)
                .containsEntry(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                        principal.accountId().toString());
    }

    @Test
    void deserializedMissingAccountHasNoIndexAndCannotResolveAnAuthenticatedTenant() throws Exception {
        var malformed = principal(UUID.randomUUID());
        var account = VerifiedSessionPrincipal.class.getDeclaredField("accountId");
        account.setAccessible(true);
        account.set(malformed, null); // Model constructor-bypassing corrupt persisted state.
        var restored = (VerifiedSessionPrincipal) new DeserializingConverter().convert(
                new SerializingConverter().convert(malformed));
        var authentication = authentication(restored);
        var session = new MapSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));

        assertThat(restored.getName()).isNull();
        assertThat(authentication.getName()).isNull();
        assertThat(new PrincipalNameIndexResolver<MapSession>().resolveIndexesFor(session)).isEmpty();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        assertThatThrownBy(() -> new SessionContextResolver().currentTenant())
                .isInstanceOf(UnauthenticatedSessionException.class);
    }

    @Test
    void serializationIdentityAndStoredFieldShapeStayUnchanged() {
        var descriptor = ObjectStreamClass.lookup(VerifiedSessionPrincipal.class);
        assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        assertThat(descriptor.getFields()).extracting(field -> field.getName()).containsExactly(
                "credentialVersion", "memberAuthEpoch", "tenantAuthEpoch",
                "accountId", "actorId", "memberId", "tenantId");
    }

    private static VerifiedSessionPrincipal principal(UUID accountId) {
        return new VerifiedSessionPrincipal(UUID.randomUUID(), accountId, UUID.randomUUID(), UUID.randomUUID(),
                1L, 1L, 7L);
    }

    private static Authentication authentication(VerifiedSessionPrincipal principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }
}
