package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CapturedHumanRequestTest {
    static final Instant AUTHENTICATED=Instant.parse("2026-11-04T00:00:00.123456789Z");
    static MockHttpServletRequest authenticatedRequest(){
        var principal=new VerifiedSessionPrincipal(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2,3,4);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",List.of()));
        var request=new MockHttpServletRequest();request.getSession().setAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE,AUTHENTICATED);return request;
    }
    static CapturedHumanRequest capture(MockHttpServletRequest request){return new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(12)).capture(request);}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void captureDoesNotRefreshAuthenticationAndNeverPrintsBearer(){
        var request=authenticatedRequest();String bearer=request.getSession().getId();var captured=capture(request);
        assertThat(captured.authenticatedAt()).isEqualTo(AUTHENTICATED);
        assertThat(captured.absoluteExpiresAt()).isEqualTo(AUTHENTICATED.plus(Duration.ofHours(12)));
        assertThat(captured.toString()).doesNotContain(bearer);
        captured.close();assertThatThrownBy(captured::requireCurrent).isInstanceOf(SessionSensitiveException.class);
    }
    @Test void sessionIdRotationCannotReuseCapturedAuthority(){
        var request=authenticatedRequest();var captured=capture(request);request.changeSessionId();
        assertThatThrownBy(captured::requireCurrent).isInstanceOf(SessionSensitiveException.class);
    }
    @Test void replacedAuthenticationAndChangedLifetimeMetadataDeny(){
        var request=authenticatedRequest();var captured=capture(request);
        request.getSession().setAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE,AUTHENTICATED.plusSeconds(1));
        assertThatThrownBy(captured::requireCurrent).isInstanceOf(SessionSensitiveException.class);
        request.getSession().setAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE,AUTHENTICATED);
        SecurityContextHolder.clearContext();assertThatThrownBy(captured::requireCurrent).isInstanceOf(SessionSensitiveException.class);
    }
    @Test void crossThreadCaptureCannotBeReused()throws Exception{
        var captured=capture(authenticatedRequest());var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread=new Thread(()->{try{captured.requireCurrent();}catch(Throwable error){failure.set(error);}});thread.start();thread.join();
        assertThat(failure.get()).isInstanceOf(SessionSensitiveException.class);
    }
}
