package com.mightyoung.softwarefactory;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.jdbc.core.JdbcTemplate;

public class P0FailingProjectionDelegate implements JavaDelegate {

    private final JdbcTemplate jdbc;

    public P0FailingProjectionDelegate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String processInstanceId = execution.getProcessInstanceId();
        jdbc.update("insert into p0_finding_projection values (?, ?, ?, ?)",
                processInstanceId, "tenant-a", "SAFETY_CONFIRM_PENDING", 2L);
        jdbc.update("insert into p0_finding_transition values (?, ?)", processInstanceId, "timer-1");
        jdbc.update("insert into p0_outbox values (?, ?)", processInstanceId + ":timer-1", "TIMER_TRIGGERED");
        throw new ProjectionWriteFailure();
    }

    private static final class ProjectionWriteFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
