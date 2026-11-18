package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Spring Session filter/serializer behavior, not SQL or Boot activation evidence. */
class KnownLogoutRequestsTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void preCaptureRotationRejectsBeforeBoundaryStoreOrCleanup()throws Exception{
        var requests=new KnownLogoutRequests(serializer->{});
        var repository=repository();var saved=repository.createSession();repository.save(saved);
        var raw=cookieRequest(requests,saved.getId());
        var boundaries=mock(SessionSensitiveSecurityTransaction.class);var store=mock(SessionAuthorityStore.class);
        var audit=mock(SessionSensitiveAudit.class);
        var service=new SessionAuthorityService(mock(SessionSensitiveRequests.class),boundaries,store,audit,requests);
        filter(requests,repository,raw,wrapped->{
            assertThat(wrapped.getRequestedSessionId()).isEqualTo(saved.getId());
            assertThat(wrapped.getSession(false)).isNotNull();wrapped.changeSessionId();
            assertThat(wrapped.getSession(false).getId()).isNotEqualTo(saved.getId());
            var result=service.logout(wrapped,UUID.randomUUID(),()->fail("uncertain lineage must preserve local proof"));
            assertThat(result.revocationState()).isEqualTo(SessionSensitiveContracts.RevocationState.UNKNOWN);
            assertThat(result.localCleanupComplete()).isFalse();verifyNoInteractions(boundaries,store);
            verify(audit).failure(any());
        });
    }

    @Test void customNameBase64AndJvmRouteUseSameRealFilterResolver()throws Exception{
        var requests=new KnownLogoutRequests(serializer->{serializer.setCookieName("CUSTOM_SESSION");serializer.setJvmRoute("node-a");});
        var repository=repository();var saved=repository.createSession();repository.save(saved);
        var raw=cookieRequest(requests,saved.getId());
        assertThat(raw.getCookies()[0].getName()).isEqualTo("CUSTOM_SESSION");
        assertThat(raw.getCookies()[0].getValue()).isNotEqualTo(saved.getId());
        filter(requests,repository,raw,wrapped->{
            try(var capture=requests.capture(wrapped)){
                assertThat(capture.bearerId()).isEqualTo(saved.getId());capture.requireUnchanged();
            }
        });
    }

    @Test void noFrameworkRowStillRetainsEligibleDecodedCookieForHmacLookup()throws Exception{
        var requests=new KnownLogoutRequests(serializer->{});String retained="retained-only-id";
        filter(requests,repository(),cookieRequest(requests,retained),wrapped->{
            assertThat(wrapped.getSession(false)).isNull();assertThat(wrapped.getRequestedSessionId()).isEqualTo(retained);
            try(var capture=requests.capture(wrapped)){assertThat(capture.bearerId()).isEqualTo(retained);}
        });
    }

    @Test void malformedEncodedCookieCannotBecomeCommittedAbsence()throws Exception{
        var requests=new KnownLogoutRequests(serializer->{});var raw=new MockHttpServletRequest();
        raw.setCookies(new Cookie("SESSION","!invalid-base64!"));
        filter(requests,repository(),raw,wrapped->{
            assertThat(wrapped.getRequestedSessionId()).isNull();assertThat(wrapped.getSession(false)).isNull();
            assertThatThrownBy(()->requests.capture(wrapped)).isInstanceOf(SessionSensitiveException.class);
        });
    }

    @Test void duplicateCookiesCannotSelectEitherFirstOrLaterValidSession()throws Exception{
        var requests=new KnownLogoutRequests(serializer->serializer.setUseBase64Encoding(false));
        var repository=repository();var saved=repository.createSession();repository.save(saved);
        var raw=new MockHttpServletRequest();raw.setCookies(new Cookie("SESSION","absent-id"),new Cookie("SESSION",saved.getId()));
        filter(requests,repository,raw,wrapped->{
            assertThat(wrapped.getRequestedSessionId()).isEqualTo("absent-id");
            assertThat(wrapped.getSession(false).getId()).isEqualTo(saved.getId());
            assertThatThrownBy(()->requests.capture(wrapped)).isInstanceOf(SessionSensitiveException.class);
        });
    }

    @Test void duplicateIdenticalCookiesAreStillAmbiguous()throws Exception{
        var requests=new KnownLogoutRequests(serializer->serializer.setUseBase64Encoding(false));
        var raw=new MockHttpServletRequest();raw.setCookies(new Cookie("SESSION","same-id"),new Cookie("SESSION","same-id"));
        filter(requests,repository(),raw,wrapped->assertThatThrownBy(()->requests.capture(wrapped)).isInstanceOf(SessionSensitiveException.class));
    }

    @Test void nativeContainerTransportFlagsDoNotOverrideActualSpringCookie()throws Exception{
        var requests=new KnownLogoutRequests(serializer->{});var raw=cookieRequest(requests,"retained-id");
        raw.setRequestedSessionIdFromCookie(false);raw.setRequestedSessionIdFromURL(true);
        filter(requests,repository(),raw,wrapped->{
            assertThat(wrapped.isRequestedSessionIdFromCookie()).isFalse();assertThat(wrapped.isRequestedSessionIdFromURL()).isTrue();
            try(var capture=requests.capture(wrapped)){assertThat(capture.bearerId()).isEqualTo("retained-id");}
        });
    }

    @Test void nativeJsessionidAndCookieFlagDoNotInventSpringCookie()throws Exception{
        var requests=new KnownLogoutRequests(serializer->{});var raw=new MockHttpServletRequest();
        raw.setCookies(new Cookie("JSESSIONID","native-id"));raw.setRequestedSessionId("native-id");raw.setRequestedSessionIdFromCookie(true);
        filter(requests,repository(),raw,wrapped->{
            assertThat(wrapped.getRequestedSessionId()).isNull();
            try(var capture=requests.capture(wrapped)){assertThat(capture.bearerId()).isNull();}
        });
    }

    @Test void allPublicConfigurationSettersAreFrozenAfterTrustedConfiguration()throws Exception{
        var configured=new AtomicReference<DefaultCookieSerializer>();new KnownLogoutRequests(configured::set);
        for(var method:DefaultCookieSerializer.class.getDeclaredMethods()){
            if(!java.lang.reflect.Modifier.isPublic(method.getModifiers())||!method.getName().startsWith("set"))continue;
            Class<?> type=method.getParameterTypes()[0];Object value=type==boolean.class?false:type==int.class?1:"value";
            assertThatThrownBy(()->method.invoke(configured.get(),value)).as(method.getName()).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test void replacementSerializerCannotSupplyUnobservedAbsenceAndAttemptIsAlwaysCleared(){
        var configured=new AtomicReference<DefaultCookieSerializer>();var requests=new KnownLogoutRequests(configured::set);
        requests.sessionIdResolver().setCookieSerializer(new DefaultCookieSerializer());
        assertThatThrownBy(()->requests.capture(new MockHttpServletRequest())).isInstanceOf(SessionSensitiveException.class);
        requests.sessionIdResolver().setCookieSerializer(configured.get());
        try(var capture=requests.capture(new MockHttpServletRequest())){assertThat(capture.bearerId()).isNull();}
    }

    @Test void duplicateObserverCallsOrChangedResolverResultAreRejectedAndDoNotLeakAttempt(){
        var configured=new AtomicReference<DefaultCookieSerializer>();var requests=new KnownLogoutRequests(configured::set);
        requests.sessionIdResolver().setCookieSerializer(new CookieSerializer(){
            @Override public List<String> readCookieValues(HttpServletRequest request){configured.get().readCookieValues(request);return configured.get().readCookieValues(request);}
            @Override public void writeCookieValue(CookieValue value){configured.get().writeCookieValue(value);}
        });
        assertThatThrownBy(()->requests.capture(new MockHttpServletRequest())).isInstanceOf(SessionSensitiveException.class);
        requests.sessionIdResolver().setCookieSerializer(new CookieSerializer(){
            @Override public List<String> readCookieValues(HttpServletRequest request){configured.get().readCookieValues(request);return List.of("forged-id");}
            @Override public void writeCookieValue(CookieValue value){configured.get().writeCookieValue(value);}
        });
        assertThatThrownBy(()->requests.capture(new MockHttpServletRequest())).isInstanceOf(SessionSensitiveException.class);
        requests.sessionIdResolver().setCookieSerializer(configured.get());
        try(var capture=requests.capture(new MockHttpServletRequest())){assertThat(capture.bearerId()).isNull();}
    }

    @Test void malformedCookieAfterCaptureIsRejectedDuringRecheck()throws Exception{
        var requests=new KnownLogoutRequests(serializer->{});var raw=new MockHttpServletRequest();
        filter(requests,repository(),raw,wrapped->{
            try(var capture=requests.capture(wrapped)){
                raw.setCookies(new Cookie("SESSION","!invalid!"));
                assertThatThrownBy(capture::requireUnchanged).isInstanceOf(SessionSensitiveException.class);
            }
        });
    }

    @Test void successiveRealFilterRequestsNeverReusePriorObservation()throws Exception{
        var requests=new KnownLogoutRequests(serializer->{});
        for(String id:List.of("first-retained-id","second-retained-id")){
            filter(requests,repository(),cookieRequest(requests,id),wrapped->{
                try(var capture=requests.capture(wrapped)){assertThat(capture.bearerId()).isEqualTo(id);}
            });
        }
        var malformed=new MockHttpServletRequest();malformed.setCookies(new Cookie("SESSION","!invalid!"));
        filter(requests,repository(),malformed,wrapped->assertThatThrownBy(()->requests.capture(wrapped)).isInstanceOf(SessionSensitiveException.class));
        filter(requests,repository(),new MockHttpServletRequest(),wrapped->{
            try(var capture=requests.capture(wrapped)){assertThat(capture.bearerId()).isNull();}
        });
    }

    @Test void resolverExceptionsNullAndDifferentRequestObservationsFailClosedAndClearAttempt(){
        var configured=new AtomicReference<DefaultCookieSerializer>();var requests=new KnownLogoutRequests(configured::set);
        for(String mode:List.of("throws","null","different-request")){
            requests.sessionIdResolver().setCookieSerializer(new CookieSerializer(){
                @Override public List<String> readCookieValues(HttpServletRequest request){
                    if(mode.equals("throws"))throw new IllegalStateException("Synthetic resolver failure");
                    if(mode.equals("null"))return null;
                    return configured.get().readCookieValues(new jakarta.servlet.http.HttpServletRequestWrapper(request){});
                }
                @Override public void writeCookieValue(CookieValue value){configured.get().writeCookieValue(value);}
            });
            assertThatThrownBy(()->requests.capture(new MockHttpServletRequest())).as(mode).isInstanceOf(SessionSensitiveException.class);
            requests.sessionIdResolver().setCookieSerializer(configured.get());
            try(var capture=requests.capture(new MockHttpServletRequest())){assertThat(capture.bearerId()).isNull();}
        }
    }

    private static MapSessionRepository repository(){return new MapSessionRepository(new ConcurrentHashMap<String,Session>());}
    private static MockHttpServletRequest cookieRequest(KnownLogoutRequests requests,String id){
        var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();
        requests.sessionIdResolver().setSessionId(request,response,id);
        var result=new MockHttpServletRequest();result.setCookies(MockCookie.parse(response.getHeader("Set-Cookie")));return result;
    }
    private static void filter(KnownLogoutRequests requests,MapSessionRepository repository,MockHttpServletRequest raw,
                               Consumer<HttpServletRequest> action)throws Exception{
        var filter=new SessionRepositoryFilter<>(repository);filter.setHttpSessionIdResolver(requests.sessionIdResolver());
        filter.doFilter(raw,new MockHttpServletResponse(),(request,response)->action.accept((HttpServletRequest)request));
    }
}
