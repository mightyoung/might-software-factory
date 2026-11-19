package com.mightyoung.softwarefactory.identity;

import java.util.List;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/** Synthetic verified principal over actual persisted framework row. Not HTTP login or browser proof. */
final class CiPg18ReauthSession implements AutoCloseable {
    private final SecurityContext previous;
    private final Thread thread=Thread.currentThread();
    final MockHttpServletRequest request=new MockHttpServletRequest();
    CiPg18ReauthSession(CiPg18ReauthFixture.Actor actor,CiPg18ReauthFixture.Session session){
        previous=SecurityContextHolder.getContext();var current=SecurityContextHolder.createEmptyContext();
        current.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new VerifiedSessionPrincipal(actor.actor(),actor.account(),actor.tenant(),actor.member(),1,1,1),"",List.of()));
        SecurityContextHolder.setContext(current);request.setSession(new MockHttpSession(null,session.bearer()));request.setRemoteAddr("192.0.2.17");
        request.getSession().setAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE,session.authenticated());
    }
    @Override public void close(){if(thread!=Thread.currentThread())throw CiPg18ReauthFixture.rejected();SecurityContextHolder.setContext(previous);}
    @Override public String toString(){return "SyntheticReauthCurrentRequest[private]";}
}
