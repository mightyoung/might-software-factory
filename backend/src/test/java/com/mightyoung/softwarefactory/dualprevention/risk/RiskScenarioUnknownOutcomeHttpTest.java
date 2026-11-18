package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.dualprevention.FindingApiExceptionHandler;
import com.mightyoung.softwarefactory.dualprevention.RiskScenarioHttpTransport;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.IdentityRequestIds;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager.LifecycleOutcome;
import com.mightyoung.softwarefactory.support.G014UnknownOutcomeTestFixture;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real local MVC handler and staged encoder with controlled transport; no production filter/PG/recovery claim. */
class RiskScenarioUnknownOutcomeHttpTest {
    private static final UUID ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    static Stream<Arguments> routes() {
        return Stream.of(LifecycleOutcome.COMMIT_UNKNOWN, LifecycleOutcome.ROLLBACK_UNKNOWN).flatMap(outcome ->
                Stream.of(Arguments.of(outcome, false), Arguments.of(outcome, true)));
    }

    @ParameterizedTest @MethodSource("routes")
    void localTransactionHandlerUsesTransportForRealOwnerUnknown(
            LifecycleOutcome outcome, boolean metadata) throws Exception {
        var requests = mock(SessionSensitiveRequests.class);
        var capture = mock(CapturedHumanRequest.class);
        when(requests.capture(any())).thenReturn(capture);
        var transport = new RecordingTransport();
        var parser = new RiskScenarioParser(JsonMapper.builder().build());
        Object controller;
        String path;
        if (metadata) {
            var service = mock(RiskScenarioMetadataService.class);
            when(service.schema(eq(capture), any(), any())).thenAnswer(invocation -> {
                throw G014UnknownOutcomeTestFixture.failure(outcome);
            });
            controller = new RiskScenarioMetadataController(service, parser, requests, transport);
            path = "/api/v1/entities/risk_scenario/schema";
        } else {
            var service = mock(RiskScenarioReadService.class);
            when(service.detail(eq(capture), eq(ID), any(), any())).thenAnswer(invocation -> {
                throw G014UnknownOutcomeTestFixture.failure(outcome);
            });
            controller = new RiskScenarioReadController(service, parser, requests, transport);
            path = "/api/v1/entities/risk_scenario/" + ID;
        }
        var result = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new FindingApiExceptionHandler()).build()
                .perform(get(path)).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMAND_OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.message").value("操作结果暂无法确认，请查看结果状态")).andReturn();
        String body = result.getResponse().getContentAsString();
        var error = JsonMapper.builder().build().readTree(body);
        assertThat(error.get("traceId").asString()).isEqualTo(IdentityRequestIds.get(result.getRequest()).traceId().toString());
        assertThat(body).doesNotContain("PRIVATE", "08006", "请求未完成", "LifecycleException");
        assertThat(transport.calls).isEqualTo(1);
        assertThat(transport.length).isEqualTo(result.getResponse().getContentAsByteArray().length).isLessThanOrEqualTo(8192);
        verify(capture).close();
    }

    static Stream<Arguments> cleanupRoutes() {
        return Stream.of(G014UnknownOutcomeTestFixture.CleanupFault.values()).flatMap(fault ->
                Stream.of(Arguments.of(fault, false), Arguments.of(fault, true)));
    }

    @ParameterizedTest @MethodSource("cleanupRoutes")
    void localTransportPreservesKnownCommitAfterResetOrCloseFailure(
            G014UnknownOutcomeTestFixture.CleanupFault fault, boolean metadata) throws Exception {
        var requests = mock(SessionSensitiveRequests.class);
        var capture = mock(CapturedHumanRequest.class);
        when(requests.capture(any())).thenReturn(capture);
        var transport = new RecordingTransport();
        var parser = new RiskScenarioParser(JsonMapper.builder().build());
        Object controller;
        String path;
        if (metadata) {
            var service = mock(RiskScenarioMetadataService.class);
            when(service.schema(eq(capture), any(), any())).thenAnswer(invocation -> {
                throw G014UnknownOutcomeTestFixture.committedCleanupFailure(fault);
            });
            controller = new RiskScenarioMetadataController(service, parser, requests, transport);
            path = "/api/v1/entities/risk_scenario/schema";
        } else {
            var service = mock(RiskScenarioReadService.class);
            when(service.detail(eq(capture), eq(ID), any(), any())).thenAnswer(invocation -> {
                throw G014UnknownOutcomeTestFixture.committedCleanupFailure(fault);
            });
            controller = new RiskScenarioReadController(service, parser, requests, transport);
            path = "/api/v1/entities/risk_scenario/" + ID;
        }
        var result = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new FindingApiExceptionHandler()).build()
                .perform(get(path)).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.code").value("COMMAND_COMMITTED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.message").value("操作已提交，请查看结果"))
                .andExpect(jsonPath("$.cleanup").doesNotExist()).andReturn();
        String body = result.getResponse().getContentAsString();
        var error = JsonMapper.builder().build().readTree(body);
        assertThat(error.get("traceId").asString()).isEqualTo(IdentityRequestIds.get(result.getRequest()).traceId().toString());
        assertThat(body).doesNotContain("PRIVATE", "08006", "请求未完成", "COMMAND_OUTCOME_UNKNOWN", "RESET_FAILED", "CLOSE_UNKNOWN");
        assertThat(transport.calls).isEqualTo(1);
        assertThat(transport.length).isEqualTo(result.getResponse().getContentAsByteArray().length).isLessThanOrEqualTo(8192);
        verify(capture).close();
    }

    @Test void ordinaryRiskDependencyFailureRetainsItsExistingRetryableResponse() throws Exception {
        var requests = mock(SessionSensitiveRequests.class);
        var capture = mock(CapturedHumanRequest.class);
        when(requests.capture(any())).thenReturn(capture);
        var service = mock(RiskScenarioReadService.class);
        when(service.detail(eq(capture), eq(ID), any(), any())).thenThrow(new RiskScenarioContracts.Failure(503));
        var transport = new RecordingTransport();
        var controller = new RiskScenarioReadController(service, new RiskScenarioParser(JsonMapper.builder().build()), requests, transport);
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new FindingApiExceptionHandler()).build()
                .perform(get("/api/v1/entities/risk_scenario/" + ID))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
        assertThat(transport.calls).isEqualTo(1);
        verify(capture).close();
    }

    private static final class RecordingTransport implements RiskScenarioHttpTransport {
        int calls;
        int length;
        @Override public void stage(HttpServletRequest request, HttpServletResponse response, int status,
                Map<String, List<String>> headers, int bodyLength, BodyWriter body) throws IOException {
            calls++;
            length = bodyLength;
            response.setStatus(status);
            headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
            response.setContentLength(bodyLength);
            body.writeTo(response.getOutputStream());
        }
    }
}
