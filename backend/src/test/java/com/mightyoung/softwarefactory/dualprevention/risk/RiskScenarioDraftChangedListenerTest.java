package com.mightyoung.softwarefactory.dualprevention.risk;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskScenarioDraftChangedListenerTest {
    private static final String SENSITIVE = "PRIVATE SQL password=secret tenant-payload";

    @Test
    void successfulDeliveryUsesTheExactEventAndTheFixedCaptureServiceOnce() {
        var service = mock(RiskChangeCaptureService.class);
        var event = event();
        when(service.capture(event)).thenReturn(new RiskChangeCaptureService.Capture(
                event.tenantId(), event.commandId(), 1, event.occurredAt(), UUID.randomUUID(), false));

        new RiskScenarioDraftChangedListener(service).capture(event);

        verify(service).capture(same(event));
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("sensitiveFailures")
    void deliveryFailureRemainsFailureWithoutOriginalExceptionOrEventData(Throwable original) {
        var service = mock(RiskChangeCaptureService.class);
        var event = event();
        when(service.capture(event)).thenThrow(original);

        Throwable failure = catchThrowable(() -> new RiskScenarioDraftChangedListener(service).capture(event));

        assertSafeFailure(failure);
        assertThat(failure).isNotSameAs(original);
        var trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain(SENSITIVE, event.tenantId().toString(),
                event.commandId().toString(), event.traceId().toString());
        verify(service).capture(same(event));
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingEventFailsBeforeCallingTheService() {
        var service = mock(RiskChangeCaptureService.class);

        assertSafeFailure(catchThrowable(() -> new RiskScenarioDraftChangedListener(service).capture(null)));

        verifyNoInteractions(service);
    }

    @Test
    void missingServiceCannotCreateAnUnusableListener() {
        assertSafeFailure(catchThrowable(() -> new RiskScenarioDraftChangedListener(null)));
    }

    @Test
    void entryHasSingleDirectAsyncAfterCommitContractWithoutAutomaticActivation() throws Exception {
        var type = RiskScenarioDraftChangedListener.class;
        var method = type.getDeclaredMethod("capture", RiskScenarioDraftChangedEvent.class);

        assertThat(Modifier.isFinal(type.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(method.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        assertThat(method.getReturnType()).isEqualTo(void.class);
        assertThat(type.getDeclaredAnnotations()).isEmpty();
        assertThat(method.getDeclaredAnnotations()).extracting(annotation -> annotation.annotationType().getName())
                .containsExactlyInAnyOrder(Async.class.getName(), TransactionalEventListener.class.getName());
        var listener = method.getDeclaredAnnotation(TransactionalEventListener.class);
        assertThat(listener.id()).isEqualTo("risk-scenario-draft-change");
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution()).isFalse();
        assertThat(listener.condition()).isEmpty();
        assertThat(method.getDeclaredAnnotation(Async.class).value()).isEmpty();
        assertThat(Stream.of(type.getDeclaredMethods()).filter(candidate -> candidate.getReturnType() == void.class))
                .containsExactly(method);
    }

    private static Stream<Throwable> sensitiveFailures() {
        var runtime = new IllegalStateException(SENSITIVE, new RuntimeException(SENSITIVE));
        runtime.addSuppressed(new RuntimeException(SENSITIVE));
        var error = new AssertionError(SENSITIVE, new RuntimeException(SENSITIVE));
        error.addSuppressed(new RuntimeException(SENSITIVE));
        return Stream.of(runtime, error);
    }

    private static void assertSafeFailure(Throwable failure) {
        assertThat(failure).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Risk change listener is unavailable").hasNoCause();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private static RiskScenarioDraftChangedEvent event() {
        return new RiskScenarioDraftChangedEvent(UUID.randomUUID(), UUID.randomUUID(), "risk_scenario",
                UUID.randomUUID(), "risk_scenario.create", 1, Instant.parse("2026-11-07T00:00:00Z"),
                UUID.randomUUID(), "CREATE");
    }
}
