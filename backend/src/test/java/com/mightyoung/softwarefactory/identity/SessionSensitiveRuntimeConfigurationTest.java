package com.mightyoung.softwarefactory.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

class SessionSensitiveRuntimeConfigurationTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void absentMaterialIsUnavailableOnlyWhenRequired() {
        var beans=new DefaultListableBeanFactory();
        var provider=new SecurityConfiguration().sessionSensitiveKeyProvider(beans.getBeanProvider(SessionSensitiveKeys.class));
        assertThat(provider).isNotNull();
        assertThatThrownBy(provider::requireRetainedKeys).isInstanceOf(SessionSensitiveException.class);
    }

    @Test void explicitSingleMaterialIsReturnedWithoutGeneratingOrReplacingKeys() {
        var beans=new DefaultListableBeanFactory();
        var keys=new SessionSensitiveKeys("fixture",Map.of("fixture",new byte[32]));
        beans.registerSingleton("retained",keys);
        var provider=new SecurityConfiguration().sessionSensitiveKeyProvider(beans.getBeanProvider(SessionSensitiveKeys.class));
        assertThat(provider.requireRetainedKeys()).isSameAs(keys);
    }

    @Test void ambiguousMaterialNeverChoosesAnArbitraryKeySet() {
        var beans=new DefaultListableBeanFactory();
        beans.registerSingleton("one",new SessionSensitiveKeys("one",Map.of("one",new byte[32])));
        beans.registerSingleton("two",new SessionSensitiveKeys("two",Map.of("two",new byte[32])));
        var provider=new SecurityConfiguration().sessionSensitiveKeyProvider(beans.getBeanProvider(SessionSensitiveKeys.class));
        assertThatThrownBy(provider::requireRetainedKeys).isInstanceOf(SessionSensitiveException.class);
    }

    @Test void requestCaptureUsesTheConfiguredAbsoluteTimeoutWithoutAccessingKeys() {
        var principal=VerifiedSessionPrincipalTestFixture.create(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",List.of()));
        var request=new MockHttpServletRequest();
        var at=Instant.parse("2026-11-04T00:00:00.123456Z");
        VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(request.getSession(),at);
        var requests=new SecurityConfiguration().sessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(3));
        var captured=requests.capture(request);
        try { assertThat(captured.absoluteExpiresAt()).isEqualTo(at.plus(Duration.ofHours(3))); }
        finally { captured.close(); }
    }
}
