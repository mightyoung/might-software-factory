package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VerifiedSessionPrincipalSerializationTest {

    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void verifiedPrincipalRoundTripsThroughSpringSessionDefaultSerializer() {
        VerifiedSessionPrincipal principal = principal();
        SerializingConverter serializer = new SerializingConverter();
        DeserializingConverter deserializer = new DeserializingConverter();

        byte[] serialized = serializer.convert(principal);
        VerifiedSessionPrincipal restored = (VerifiedSessionPrincipal) deserializer.convert(serialized);

        assertThat(restored.actorId()).isEqualTo(ACTOR_ID);
        assertThat(restored.tenantId()).isEqualTo(TENANT_ID);
        assertThat(restored.memberId()).isEqualTo(MEMBER_ID);
        assertThat(restored.toString()).isEqualTo("VerifiedSessionPrincipal");
    }

    @Test
    void savedSecurityContextHasASerializableSessionAttributeGraph() {
        SecurityContext context = new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal(), null, List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"))));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();

        repository.saveContext(context, request, response);

        Object sessionAttribute = request.getSession(false).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(sessionAttribute).isInstanceOf(SecurityContext.class);

        byte[] serialized = new SerializingConverter().convert(sessionAttribute);
        SecurityContext restored = (SecurityContext) new DeserializingConverter().convert(serialized);
        VerifiedSessionPrincipal restoredPrincipal = (VerifiedSessionPrincipal)
                restored.getAuthentication().getPrincipal();

        assertThat(restoredPrincipal.actorId()).isEqualTo(ACTOR_ID);
        assertThat(restoredPrincipal.tenantId()).isEqualTo(TENANT_ID);
        assertThat(restoredPrincipal.memberId()).isEqualTo(MEMBER_ID);
    }

    private static VerifiedSessionPrincipal principal() {
        return VerifiedSessionPrincipalTestFixture.create(ACTOR_ID, TENANT_ID, MEMBER_ID);
    }
}
