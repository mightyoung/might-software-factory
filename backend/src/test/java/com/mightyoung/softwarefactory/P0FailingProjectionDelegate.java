package com.mightyoung.softwarefactory;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class P0FailingProjectionDelegate implements JavaDelegate {

    private final JdbcTemplate jdbc;
    private final ConcurrentMap<String, AtomicInteger> successfulWrites = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> executions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RetryGate> retryGates = new ConcurrentHashMap<>();

    public P0FailingProjectionDelegate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String processInstanceId = execution.getProcessInstanceId();
        int attempt = executions.computeIfAbsent(processInstanceId, ignored -> new AtomicInteger()).incrementAndGet();
        RetryGate gate = retryGates.get(processInstanceId);
        if (attempt > 1 && gate != null) {
            gate.awaitRelease();
        }
        write(processInstanceId, "insert into p0_finding_projection values (?, ?, ?, ?)",
                processInstanceId, "tenant-a", "SAFETY_CONFIRM_PENDING", 2L);
        write(processInstanceId, "insert into p0_finding_transition values (?, ?)",
                processInstanceId, "timer-1");
        write(processInstanceId, "insert into p0_outbox values (?, ?)",
                processInstanceId + ":timer-1", "TIMER_TRIGGERED");
        if (attempt == 1) {
            throw new ProjectionWriteFailure();
        }
    }

    int successfulWriteStatementsFor(String processInstanceId) {
        AtomicInteger count = successfulWrites.get(processInstanceId);
        return count == null ? 0 : count.get();
    }

    RetryGate pauseRetryFor(String processInstanceId) {
        RetryGate gate = new RetryGate();
        retryGates.put(processInstanceId, gate);
        return gate;
    }

    /** Test-only barrier: keep an automatic retry from writing before rollback assertions finish. */
    static final class RetryGate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        boolean isWaiting() {
            return entered.getCount() == 0 && released.getCount() != 0;
        }

        void release() {
            released.countDown();
        }

        private void awaitRelease() {
            entered.countDown();
            try {
                if (!released.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for P0 rollback assertions");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("P0 retry interrupted", exception);
            }
        }
    }

    private void write(String processInstanceId, String sql, Object... arguments) {
        jdbc.update(sql, arguments);
        successfulWrites.computeIfAbsent(processInstanceId, ignored -> new AtomicInteger()).incrementAndGet();
    }

    static final class ProjectionWriteFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
