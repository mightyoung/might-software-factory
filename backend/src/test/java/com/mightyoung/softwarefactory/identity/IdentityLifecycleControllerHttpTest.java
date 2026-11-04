package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** Production servlet security/parser wiring with mocked domain port; not real session/PG acceptance. */
@WebMvcTest(controllers={IdentityLifecycleController.class,AuthenticationSessionController.class})
@Import({SecurityConfiguration.class,SessionContextResolver.class,IdentityLifecycleExceptionHandler.class,IdentityLifecycleControllerHttpTest.Dependencies.class})
class IdentityLifecycleControllerHttpTest {
    private static final String RESET="/api/v1/auth/password-resets/consume",REQUEST="/api/v1/auth/password-reset-requests",INVITATION="/api/v1/auth/invitations/consume";
    private static final UUID ID=UUID.fromString("10000000-0000-0000-0000-000000000001"),KEY=UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String TOKEN="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",PASSWORD="a sufficiently long password";
    private static final UUID ACTOR=UUID.randomUUID(),ACCOUNT=UUID.randomUUID(),TENANT=UUID.randomUUID(),MEMBER=UUID.randomUUID();
    @Autowired MockMvc mvc;
    @Autowired IdentityLifecycleService service;
    @Autowired IdentityLifecycleRequestParser parser;
    @Autowired AccountStatusReadRepository accounts;
    @Autowired CommitState commit;
    @BeforeEach void setup() {
        reset(service,accounts);clearInvocations(parser);commit.fail.set(false);commit.handlerKeys.clear();
        when(accounts.findState(any())).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",1)));
        doAnswer(call->{call.<Supplier<String>>getArgument(1).get();return null;}).when(service).requestReset(anyString(),any(),any());
        doAnswer(call->{try(var command=call.<Supplier<ConsumeCommand>>getArgument(1).get()){commit.handlerKeys.add(command.commandKey());}return null;}).when(service).consumeReset(anyString(),any(),any());
        doAnswer(call->{try(var command=call.<Supplier<ConsumeCommand>>getArgument(1).get()){}return null;}).when(service).consumeInvitation(anyString(),any(),any());
        when(service.invite(anyString(),any(),any())).thenAnswer(call->{call.<Supplier<InvitationCommand>>getArgument(1).get();return result(false);});
        when(service.revoke(anyString(),any(),any())).thenAnswer(call->{call.<Supplier<MutationCommand>>getArgument(1).get();return result(false);});
        when(service.reissue(anyString(),any(),any())).thenAnswer(call->{call.<Supplier<MutationCommand>>getArgument(1).get();return result(false);});
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void onlyExactThreePostRoutesAcceptAnonymousCsrfAndNeverCreateAuthentication()throws Exception {
        mvc.perform(post(REQUEST).with(csrf()).contentType("application/json").content("{\"loginKey\":\"person\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("ACCEPTED")).andExpect(header().string("Cache-Control","no-store"));
        var reset=mvc.perform(post(RESET).with(csrf()).header("Idempotency-Key",KEY).contentType("application/json").content(resetBody())).andExpect(status().isNoContent()).andReturn();
        assertThat(reset.getRequest().getSession(false)).isNull();
        mvc.perform(post(INVITATION).with(csrf()).header("Idempotency-Key",KEY).contentType("application/json").content(invitationBody()))
                .andExpect(status().isNoContent());
        for(String path:List.of(REQUEST,RESET,INVITATION))mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(RESET+"/other").with(csrf())).andExpect(status().isUnauthorized());
    }
    @Test void everyWriteRequiresCsrfAndManagementRemainsAuthenticated()throws Exception {
        for(String path:List.of(REQUEST,RESET,INVITATION,"/api/v1/account-invitations"))mvc.perform(post(path).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        for(String path:List.of("/api/v1/account-invitations","/api/v1/account-invitations/"+ID+"/revoke","/api/v1/account-invitations/"+ID+"/reissue"))
            mvc.perform(post(path).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);verify(parser,never()).resetRequest(any());verify(parser,never()).invitation(any());
    }
    @Test void managementReturnsOnlyCommittedSafeFactsAndStableReplayStatus()throws Exception {
        String body="{\"kind\":\"NEW_ACCOUNT\",\"loginKey\":\"person\",\"displayName\":\"Person\",\"destination\":{\"channel\":\"EMAIL\",\"value\":\"private@example.invalid\"}}";
        var created=mvc.perform(post("/api/v1/account-invitations").session(session()).with(csrf()).header("Idempotency-Key",KEY).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.invitationId").value(ID.toString())).andExpect(jsonPath("$.deliveryState").value("PREPARED")).andReturn();
        assertThat(created.getResponse().getContentAsString()).doesNotContain("private@",TOKEN,"targetAccountId","replayed");
        doReturn(result(true)).when(service).invite(anyString(),any(),any());
        mvc.perform(post("/api/v1/account-invitations").session(session()).with(csrf()).header("Idempotency-Key",KEY).contentType("application/json").content(body)).andExpect(status().isOk());
        for(String suffix:List.of("revoke","reissue"))mvc.perform(post("/api/v1/account-invitations/"+ID+"/"+suffix).session(session()).with(csrf()).header("Idempotency-Key",KEY).contentType("application/json").content("{\"expectedVersion\":1}"))
                .andExpect(status().is(suffix.equals("revoke")?200:201));
    }
    @Test void staleCookieWithIndependentCsrfReachesTheExactResetCommandAndUnknownDependenciesDoNot()throws Exception {
        when(accounts.findState(ACCOUNT)).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",2)));
        mvc.perform(post(RESET).session(session()).with(csrf()).header("Idempotency-Key",KEY).contentType("application/json").content(resetBody()))
                .andExpect(status().isNoContent());
        assertThat(commit.handlerKeys).containsExactly(KEY);verify(accounts).findState(ACCOUNT);
        reset(service,accounts);when(accounts.findState(ACCOUNT)).thenThrow(new DataAccessResourceFailureException("private SQL/password"));
        var unavailable=mvc.perform(post(RESET).session(session()).with(csrf()).header("Idempotency-Key",KEY).contentType("application/json").content(resetBody()))
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(unavailable.getResponse().getContentAsString()).doesNotContain("private","SQL/password");verifyNoInteractions(service);
    }
    @Test void staleExistingInvitationCannotFallbackToAnonymousAcceptance()throws Exception {
        when(accounts.findState(ACCOUNT)).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",2)));
        doAnswer(call->{
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isInstanceOf(AnonymousAuthenticationToken.class);
            assertThat(authentication.getPrincipal()).isNotInstanceOf(VerifiedSessionPrincipal.class);
            throw new UnauthenticatedSessionException();
        })
                .when(service).consumeInvitation(anyString(),any(),any());
        mvc.perform(post(INVITATION).session(session()).with(csrf()).header("Idempotency-Key",KEY).contentType("application/json").content(invitationBody()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }
    @Test void admissionRejectionPrecedesParserAndErrorTraceNeverUsesClientHeaders()throws Exception {
        doThrow(new IdentityLifecycleException(IdentityLifecycleException.Reason.RATE_LIMITED)).when(service).requestReset(anyString(),any(),any());
        var result=mvc.perform(post(REQUEST).with(csrf()).header("X-Trace-Id","untrusted-token-value").contentType("application/json").content("not JSON"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","60")).andReturn();
        verify(parser,never()).resetRequest(any());
        String trace=result.getResponse().getHeader("X-Trace-Id");assertThat(UUID.fromString(trace)).isNotNull();
        assertThat(result.getResponse().getContentAsString()).contains(trace).doesNotContain("untrusted-token-value","not JSON");
    }
    @Test void actualOuterSessionOrderProducesUnknownWithoutRepeatingHandlerThenAllowsExactRetry()throws Exception {
        commit.fail.set(true);
        var failed=mvc.perform(post(RESET).with(csrf()).header("Idempotency-Key",KEY).contentType("application/json").content(resetBody()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("OUTCOME_UNKNOWN")).andReturn();
        assertThat(commit.handlerKeys).containsExactly(KEY);assertThat(failed.getResponse().getContentAsString()).doesNotContain("private-save");
        verify(service,times(1)).consumeReset(anyString(),any(),any());commit.fail.set(false);
        mvc.perform(post(RESET).with(csrf()).header("Idempotency-Key",KEY).contentType("application/json").content(resetBody())).andExpect(status().isNoContent());
        assertThat(commit.handlerKeys).containsExactly(KEY,KEY); // transport replay only; real idempotency is a separate domain/PG gate.
    }
    @Test void strictParserFailuresRemainRedactedAndTransportSourceIgnoresForwardingHeaders()throws Exception {
        var invalid=mvc.perform(post(REQUEST).with(csrf()).contentType("application/json").content("{\"loginKey\":\"private@example.invalid\",\"extra\":true}"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        assertThat(invalid.getResponse().getContentAsString()).doesNotContain("private@","extra");
        mvc.perform(post(REQUEST).with(csrf()).header("X-Forwarded-For","untrusted-forwarder").contentType("application/json").content("{\"loginKey\":\"person\"}"))
                .andExpect(status().isAccepted());
        verify(service,times(2)).requestReset(eq("127.0.0.1"),any(),any());
    }
    @Test void postCommitCleanupFailureCannotTurnTheResetIntoAClaimedRollback()throws Exception {
        var session=new MockHttpSession(){@Override public void invalidate(){throw new DataAccessResourceFailureException("private cleanup store");}};
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new VerifiedSessionPrincipal(ACTOR,ACCOUNT,TENANT,MEMBER,1,1,1),null,List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(session);
        mvc.perform(post(RESET).session(session).with(csrf()).header("Idempotency-Key",KEY).contentType("application/json").content(resetBody()))
                .andExpect(status().isNoContent());
        assertThat(commit.handlerKeys).containsExactly(KEY);verify(service,times(1)).consumeReset(anyString(),any(),any());
    }
    @Test void disabledLifecycleReturnsUnavailableBeforeBodyParsingWithoutBreakingApplicationStartup()throws Exception {
        var parser=mock(IdentityLifecycleRequestParser.class);var missing=new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(IdentityLifecycleService.class);
        var controller=new IdentityLifecycleController(parser,missing);
        var disabled=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new IdentityLifecycleExceptionHandler()).build();
        disabled.perform(post(REQUEST).contentType("application/json").content("not JSON").with(request->{
                    var trap=spy(request);doThrow(new AssertionError("disabled feature must not read body")).when(trap).getInputStream();return trap;
                }))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(parser);
    }
    private static InvitationResult result(boolean replay){return new InvitationResult(ID,1,Instant.parse("2026-11-03T15:00:00Z"),DeliveryState.PREPARED,replay);}
    private static MockHttpSession session(){var session=new MockHttpSession();var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new VerifiedSessionPrincipal(ACTOR,ACCOUNT,TENANT,MEMBER,1,1,1),null,List.of()));session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(session);return session;}
    private static RequestPostProcessor csrf(){return request->{request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN","synthetic-csrf"));request.addHeader("X-XSRF-TOKEN","synthetic-csrf");return request;};}
    private static String resetBody(){return "{\"resetId\":\""+ID+"\",\"token\":\""+TOKEN+"\",\"newPassword\":\""+PASSWORD+"\"}";}
    private static String invitationBody(){return "{\"invitationId\":\""+ID+"\",\"token\":\""+TOKEN+"\"}";}

    static final class CommitState {final AtomicBoolean fail=new AtomicBoolean();final List<UUID> handlerKeys=new ArrayList<>();}
    @TestConfiguration(proxyBeanMethods=false)
    static class Dependencies {
        @Bean IdentityLifecycleService lifecycleService(){return mock(IdentityLifecycleService.class);}
        @Bean IdentityLifecycleRequestParser lifecycleParser(ObjectMapper json){return spy(new IdentityLifecycleRequestParser(json));}
        @Bean AccountStatusReadRepository accounts(){return mock(AccountStatusReadRepository.class);}
        @Bean(name="localPasswordAuthenticationProvider") AuthenticationProvider loginProvider(){return mock(AuthenticationProvider.class);}
        @Bean AuthenticationEvents authenticationEvents(){return event->{};}
        @Bean CommitState commitState(){return new CommitState();}
        @Bean FilterRegistrationBean<Filter> syntheticSessionCommitBoundary(CommitState state,org.springframework.core.env.Environment environment) {
            Filter filter=(request,response,chain)->{chain.doFilter(request,response);if(state.fail.get())throw new DataAccessResourceFailureException("private-save SQL/token");};
            var registration=new FilterRegistrationBean<>(filter);
            registration.setOrder(environment.getProperty("spring.session.servlet.filter-order",Integer.class,org.springframework.session.web.http.SessionRepositoryFilter.DEFAULT_ORDER));
            return registration;
        }
    }
}
