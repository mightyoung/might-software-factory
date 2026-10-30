package com.mightyoung.softwarefactory;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class P0FailingProjectionDelegate implements JavaDelegate {

    private final JdbcTemplate jdbc;
    private final ConcurrentMap<String, AtomicInteger> successfulWrites = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> executions = new ConcurrentHashMap<>();

    public P0FailingProjectionDelegate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String processInstanceId = execution.getProcessInstanceId();
        int attempt = executions.computeIfAbsent(processInstanceId, ignored -> new AtomicInteger()).incrementAndGet();
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

    private void write(String processInstanceId, String sql, Object... arguments) {
        jdbc.update(sql, arguments);
        successfulWrites.computeIfAbsent(processInstanceId, ignored -> new AtomicInteger()).incrementAndGet();
    }

    static final class ProjectionWriteFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
