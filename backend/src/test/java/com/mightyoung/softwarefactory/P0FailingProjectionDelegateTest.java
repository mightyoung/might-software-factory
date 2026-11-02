package com.mightyoung.softwarefactory;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class P0FailingProjectionDelegateTest {

    @Test
    void retryGatePreventsWritesUntilRollbackAssertionsCanFinish() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        P0FailingProjectionDelegate delegate = new P0FailingProjectionDelegate(jdbc);
        DelegateExecution execution = execution("paused-retry");
        assertThatThrownBy(() -> delegate.execute(execution))
                .isExactlyInstanceOf(P0FailingProjectionDelegate.ProjectionWriteFailure.class);
        var gate = delegate.pauseRetryFor("paused-retry");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var retry = executor.submit(() -> delegate.execute(execution));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!gate.isWaiting() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(gate.isWaiting()).isTrue();
            assertThat(retry.isDone()).isFalse();
            assertThat(delegate.successfulWriteStatementsFor("paused-retry")).isEqualTo(3);
            assertThat(jdbc.successfulStatements).hasSize(3);

            gate.release();
            retry.get(5, TimeUnit.SECONDS);

            assertThat(delegate.successfulWriteStatementsFor("paused-retry")).isEqualTo(6);
            assertThat(jdbc.successfulStatements).hasSize(6);
        } finally {
            gate.release();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void namedFlowableDelegateRunsAllThreeWritesBeforeRaisingTheExpectedFailure() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("jdbcTemplate", JdbcTemplate.class, () -> jdbc);
            context.register(P0FailingProjectionDelegateConfiguration.class);
            context.refresh();

            assertThat(context.containsBean("p0FailingProjectionDelegate")).isTrue();
            P0FailingProjectionDelegate delegate = context.getBean(
                    "p0FailingProjectionDelegate", P0FailingProjectionDelegate.class);
            assertThat(context.getBean(JavaDelegate.class)).isSameAs(delegate);

            DelegateExecution execution = execution("unit-test-process");
            assertThatThrownBy(() -> delegate.execute(execution))
                    .isExactlyInstanceOf(P0FailingProjectionDelegate.ProjectionWriteFailure.class);

            assertThat(jdbc.successfulStatements).containsExactly(
                    "insert into p0_finding_projection values (?, ?, ?, ?)",
                    "insert into p0_finding_transition values (?, ?)",
                    "insert into p0_outbox values (?, ?)");
            assertThat(delegate.successfulWriteStatementsFor("unit-test-process")).isEqualTo(3);
        }
    }

    private static DelegateExecution execution(String processId) {
        return (DelegateExecution) Proxy.newProxyInstance(
                DelegateExecution.class.getClassLoader(), new Class<?>[]{DelegateExecution.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getProcessInstanceId")) {
                        return processId;
                    }
                    throw new UnsupportedOperationException("Unexpected delegate call: " + method.getName());
                });
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> successfulStatements = new ArrayList<>();

        @Override
        public void afterPropertiesSet() {
            // This test double records calls and intentionally has no DataSource.
        }

        @Override
        public int update(String sql, Object... arguments) {
            successfulStatements.add(sql);
            return 1;
        }
    }
}
