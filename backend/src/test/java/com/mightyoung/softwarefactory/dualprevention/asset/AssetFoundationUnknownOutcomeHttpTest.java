package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.dualprevention.FindingApiExceptionHandler;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager.LifecycleOutcome;
import com.mightyoung.softwarefactory.support.G014UnknownOutcomeTestFixture;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real owner/template failure through real asset MVC/capture; mocked domain entry, no security-filter/PG proof. */
class AssetFoundationUnknownOutcomeHttpTest {
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @EnumSource(value = LifecycleOutcome.class, names = {"COMMIT_UNKNOWN", "ROLLBACK_UNKNOWN"})
    void localTypedHandlerPreservesCommandTraceWithGlobalAdvicePresent(LifecycleOutcome outcome) throws Exception {
        var trace = new AtomicReference<UUID>();
        var capture = new AtomicReference<CapturedHumanRequest>();
        var commands = mock(AssetFoundationService.class);
        when(commands.createWorkshop(any(), any(), any(), any())).thenAnswer(invocation -> {
            capture.set(invocation.getArgument(0));
            trace.set(invocation.getArgument(2));
            throw G014UnknownOutcomeTestFixture.failure(outcome);
        });
        var response = mvc(commands).perform(request())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMAND_OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.message").value("操作结果暂无法确认，请查看结果状态")).andReturn();
        String body = response.getResponse().getContentAsString();
        AssetFoundationHttpWireContractTest.validate("ErrorResponse", body);
        assertThat(JsonMapper.builder().build().readTree(body).get("traceId").asString()).isEqualTo(trace.get().toString());
        assertThat(body).doesNotContain("PRIVATE", "08006", "请求未完成", "LifecycleException", "data", "meta");
        assertThatThrownBy(capture.get()::requireCurrent).isInstanceOf(SessionSensitiveException.class);
        verify(commands).createWorkshop(any(), any(), any(), any());
    }

    @ParameterizedTest @EnumSource(G014UnknownOutcomeTestFixture.CleanupFault.class)
    void committedCleanupFailureKeepsCommandTraceAndDoesNotInviteRetry(G014UnknownOutcomeTestFixture.CleanupFault fault) throws Exception {
        var trace = new AtomicReference<UUID>();
        var capture = new AtomicReference<CapturedHumanRequest>();
        var commands = mock(AssetFoundationService.class);
        when(commands.createWorkshop(any(), any(), any(), any())).thenAnswer(invocation -> {
            capture.set(invocation.getArgument(0));
            trace.set(invocation.getArgument(2));
            throw G014UnknownOutcomeTestFixture.committedCleanupFailure(fault);
        });
        var response = mvc(commands).perform(request())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.code").value("COMMAND_COMMITTED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.message").value("操作已提交，请查看结果"))
                .andExpect(jsonPath("$.cleanup").doesNotExist()).andReturn();
        String body = response.getResponse().getContentAsString();
        AssetFoundationHttpWireContractTest.validate("ErrorResponse", body);
        assertThat(JsonMapper.builder().build().readTree(body).get("traceId").asString()).isEqualTo(trace.get().toString());
        assertThat(body).doesNotContain("PRIVATE", "08006", "请求未完成", "COMMAND_OUTCOME_UNKNOWN", "RESET_FAILED", "CLOSE_UNKNOWN");
        assertThatThrownBy(capture.get()::requireCurrent).isInstanceOf(SessionSensitiveException.class);
        verify(commands).createWorkshop(any(), any(), any(), any());
    }

    @Test void unrelatedTransactionSystemFailureKeepsItsExistingGlobalDependencyMapping() throws Exception {
        var commands = mock(AssetFoundationService.class);
        when(commands.createWorkshop(any(), any(), any(), any()))
                .thenThrow(new org.springframework.transaction.TransactionSystemException("PRIVATE unrelated dependency"));
        mvc(commands).perform(request()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    private static org.springframework.test.web.servlet.MockMvc mvc(AssetFoundationService commands) {
        return MockMvcBuilders.standaloneSetup(new AssetFoundationController(commands, mock(AssetFoundationReadService.class),
                new AssetFoundationRequestParser(JsonMapper.builder().build()),
                new SessionSensitiveRequests(new SessionContextResolver(), Duration.ofHours(12))))
                .setControllerAdvice(new FindingApiExceptionHandler()).build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        var principal = VerifiedSessionPrincipalTestFixture.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, "", List.of()));
        var session = new MockHttpSession();
        VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(session);
        return post("/api/v1/assets/workshops").session(session).header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content("{\"code\":\"W1\",\"name\":\"Synthetic workshop\",\"organizationId\":\"" + UUID.randomUUID() + "\"}");
    }
}
