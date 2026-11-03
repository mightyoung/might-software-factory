package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

class KnownLineageLogoutCaptureTest {
    final KnownLogoutRequests requests = new KnownLogoutRequests(serializer -> serializer.setUseBase64Encoding(false));
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void missingLifetimeMetadataAndUnauthenticatedFlagDoNotInventFreshness(){
        var request=CapturedHumanRequestTest.authenticatedRequest();request.getSession(false).removeAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE);
        SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);
        try(var captured=requests.capture(request)){
            captured.requireUnchanged();assertThat(captured.claimedAccount()).isNotNull();
            assertThat(captured.toString()).doesNotContain(request.getSession(false).getId());
        }
    }
    @Test void expiredAuthenticationTimestampIsNotFreshAuthorityOrAMissingLineage(){
        var request=CapturedHumanRequestTest.authenticatedRequest();request.getSession(false).setAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE,java.time.Instant.EPOCH);
        try(var captured=requests.capture(request)){captured.requireUnchanged();assertThat(captured.claimedAccount()).isNotNull();}
    }
    @Test void removedFrameworkRowCanStillSupplyCookieBearerEvidenceForHmacLookup(){
        var request=cookieRequest("server-parsed-cookie-id");
        try(var captured=requests.capture(request)){
            assertThat(request.getSession(false)).isNull();assertThat(captured.bearerId()).isEqualTo("server-parsed-cookie-id");
            assertThat(captured.claimedAccount()).isNull();
        }
    }
    @Test void noSessionAndNoCookieIsFactsOnlyNotOwnership(){
        try(var captured=requests.capture(new MockHttpServletRequest())){assertThat(captured.bearerId()).isNull();}
    }
    @Test void requestedIdWithoutSelectedCookieAndMalformedSelectedIdAreUnknown(){
        var request=new MockHttpServletRequest();request.setRequestedSessionId("id");request.setRequestedSessionIdFromURL(true);
        assertThatThrownBy(()->requests.capture(request)).isInstanceOf(SessionSensitiveException.class);
        request.setRequestedSessionIdFromURL(false);request.setCookies(new Cookie("SESSION","bad value"));request.setRequestedSessionId("bad value");
        assertThatThrownBy(()->requests.capture(request)).isInstanceOf(SessionSensitiveException.class);
    }
    @Test void sessionRotationAuthenticationChangeAndClosedCaptureDeny(){
        var request=CapturedHumanRequestTest.authenticatedRequest();var captured=requests.capture(request);
        request.changeSessionId();assertThatThrownBy(captured::requireUnchanged).isInstanceOf(SessionSensitiveException.class);
        captured.close();assertThatThrownBy(captured::requireUnchanged).isInstanceOf(SessionSensitiveException.class);
    }
    @Test void authenticationMutationDeniesEvenWithStableSessionAndCookie(){
        var captured=requests.capture(CapturedHumanRequestTest.authenticatedRequest());
        SecurityContextHolder.clearContext();assertThatThrownBy(captured::requireUnchanged).isInstanceOf(SessionSensitiveException.class);
    }
    @Test void crossThreadAndCookieMutationDeny()throws Exception{
        var request=cookieRequest("id");var captured=requests.capture(request);var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread=new Thread(()->{try{captured.requireUnchanged();}catch(Throwable failure){error.set(failure);}});thread.start();thread.join();
        assertThat(error.get()).isInstanceOf(SessionSensitiveException.class);
        request.setCookies(new Cookie("SESSION","changed"));assertThatThrownBy(captured::requireUnchanged).isInstanceOf(SessionSensitiveException.class);
    }
    private MockHttpServletRequest cookieRequest(String id){
        var request=new MockHttpServletRequest();request.setCookies(new Cookie("SESSION",id));request.setRequestedSessionId(id);return request;
    }
}
