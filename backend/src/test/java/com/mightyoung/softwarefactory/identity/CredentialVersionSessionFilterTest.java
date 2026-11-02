package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialVersionSessionFilterTest {
    private final AccountStatusReadRepository accounts=mock(AccountStatusReadRepository.class);
    private final VerifiedSessionPrincipal principal=new VerifiedSessionPrincipal(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,4);
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void staleVersionIs401BeforeEveryProtectedHandlerAndInvalidatesCurrentSession()throws Exception {
        for(String path:List.of("/api/v1/session/current","/api/v1/session/bootstrap","/api/v1/account/password","/api/v1/entities/finding/schema","/api/v1/findings/anything","/api/v1/unknown")) {
            var request=authenticated(path);var response=new MockHttpServletResponse();var chain=new AtomicBoolean();
            when(accounts.findState(principal.accountId())).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",5)));
            new CredentialVersionSessionFilter(accounts).doFilter(request,response,(a,b)->chain.set(true));
            assertThat(response.getStatus()).isEqualTo(401);assertThat(chain).isFalse();
            assertThat(((MockHttpSession)request.getSession(false))).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }
    @Test void missingOrDisabledAccountRejectsEvenMatchingVersion()throws Exception {
        for(var state:List.of(Optional.<AccountSecurityState>empty(),Optional.of(new AccountSecurityState("DISABLED",4)))) {
            var request=authenticated("/api/v1/session/bootstrap");var response=new MockHttpServletResponse();
            when(accounts.findState(principal.accountId())).thenReturn(state);
            new CredentialVersionSessionFilter(accounts).doFilter(request,response,(a,b)->{throw new AssertionError();});
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }
    @Test void databaseUnknownIs503WithoutDestroyingOtherwiseValidAuthentication()throws Exception {
        var request=authenticated("/api/v1/session/bootstrap");var session=request.getSession();var response=new MockHttpServletResponse();
        when(accounts.findState(principal.accountId())).thenThrow(new IllegalStateException("sensitive database details"));
        new CredentialVersionSessionFilter(accounts).doFilter(request,response,(a,b)->{throw new AssertionError();});
        assertThat(response.getStatus()).isEqualTo(503);assertThat(request.getSession(false)).isSameAs(session);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(principal);
        assertThat(response.getContentAsString()).doesNotContain("sensitive","database details");
    }
    @Test void authCsrfLoginAndLogoutRemainUsableWithoutAccountLookup()throws Exception {
        for(String path:List.of("/api/v1/auth/csrf","/api/v1/auth/login","/api/v1/auth/logout")) {
            var chain=new AtomicBoolean();new CredentialVersionSessionFilter(accounts).doFilter(authenticated(path),new MockHttpServletResponse(),(a,b)->chain.set(true));
            assertThat(chain).isTrue();
        }
        verifyNoInteractions(accounts);
    }
    @Test void matchingGenerationReachesHandler()throws Exception {
        when(accounts.findState(principal.accountId())).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",4)));
        var chain=new AtomicBoolean();new CredentialVersionSessionFilter(accounts).doFilter(authenticated("/api/v1/session/current"),new MockHttpServletResponse(),(a,b)->chain.set(true));
        assertThat(chain).isTrue();
    }
    private MockHttpServletRequest authenticated(String path){var request=new MockHttpServletRequest("GET",path);request.getSession(true);SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));return request;}
}
