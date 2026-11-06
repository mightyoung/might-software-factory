package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.SecurityConfiguration;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeyProvider;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeys;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controller/parser/capture/provider, mocked domain invocation. No SQL, HUMAN grant or security-filter acceptance. */
class AssetFoundationKeyDependencyHttpTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void missingRetainedKeysReturnGeneric503AndCloseTheCapturedRequest()throws Exception {
        var beans=new DefaultListableBeanFactory();
        demandFailsSafely(()->provider(beans).requireRetainedKeys(),503);
    }

    @Test void ambiguousRetainedKeysReturnGeneric503RatherThanChoosingOne()throws Exception {
        var beans=new DefaultListableBeanFactory();
        beans.registerSingleton("one",new SessionSensitiveKeys("one",Map.of("one",new byte[32])));
        beans.registerSingleton("two",new SessionSensitiveKeys("two",Map.of("two",new byte[32])));
        demandFailsSafely(()->provider(beans).requireRetainedKeys(),503);
    }

    @ParameterizedTest @EnumSource(SessionSensitiveException.Reason.class)
    void finiteIdentityFailuresKeepTheExistingGenericHttpEnvelope(SessionSensitiveException.Reason reason)throws Exception {
        demandFailsSafely(()->{throw new SessionSensitiveException(reason);},switch(reason) {
            case UNAVAILABLE->503;case CONFLICT->409;case REJECTED,NO_TRUSTED_LINEAGE->403;
        });
    }

    private static SessionSensitiveKeyProvider provider(DefaultListableBeanFactory beans) {
        // Invoke the actual identity @Bean factory through Spring, without instantiating an unrelated web security chain.
        beans.registerSingleton("securityConfiguration",new SecurityConfiguration());
        var definition=new RootBeanDefinition();
        definition.setFactoryBeanName("securityConfiguration");definition.setFactoryMethodName("sessionSensitiveKeyProvider");
        definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        beans.registerBeanDefinition("retainedProvider",definition);
        return beans.getBean("retainedProvider",SessionSensitiveKeyProvider.class);
    }

    private static void demandFailsSafely(Runnable demand,int expected)throws Exception {
        var commands=mock(AssetFoundationService.class);var reads=mock(AssetFoundationReadService.class);
        var capture=new AtomicReference<CapturedHumanRequest>();
        when(commands.createWorkshop(any(),any(),any(),any())).thenAnswer(invocation->{
            capture.set(invocation.getArgument(0));capture.get().requireCurrent();demand.run();
            throw new AssertionError("Fixture demand must refuse");
        });
        var controller=new AssetFoundationController(commands,reads,new AssetFoundationRequestParser(JsonMapper.builder().build()),
                new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(12)));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        var principal=VerifiedSessionPrincipalTestFixture.create(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",List.of()));
        var session=new MockHttpSession(null,"PRIVATE-SYNTHETIC-SESSION-"+UUID.randomUUID());VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(session);
        var response=mvc.perform(post("/api/v1/assets/workshops").session(session).header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content("{\"code\":\"SYNTHETIC\",\"name\":\"Synthetic workshop\",\"organizationId\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(status().is(expected)).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(jsonPath("$.traceId").isString()).andExpect(jsonPath("$.fieldErrors").isArray()).andReturn();
        AssetFoundationHttpWireContractTest.validate("ErrorResponse",response.getResponse().getContentAsString());
        assertThat(response.getResponse().getContentAsString()).doesNotContain(session.getId(),"NO_TRUSTED_LINEAGE","SessionSensitiveException","java.");
        verify(commands).createWorkshop(any(),any(),any(),any());verifyNoInteractions(reads);
        assertThat(capture.get()).isNotNull();assertThatThrownBy(capture.get()::requireCurrent).isInstanceOf(SessionSensitiveException.class);
    }
}
