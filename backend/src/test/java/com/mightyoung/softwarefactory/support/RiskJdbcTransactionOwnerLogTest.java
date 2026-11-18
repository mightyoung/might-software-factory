package com.mightyoung.softwarefactory.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static com.mightyoung.softwarefactory.support.RiskJdbcTransactionOwnerTest.*;
import static org.assertj.core.api.Assertions.*;

class RiskJdbcTransactionOwnerLogTest {
    @Test void actualSpringInfoAndDebugLogsContainNoOriginalFailureOrConnectionDiagnostics() throws Exception {
        for (Level level : List.of(Level.INFO, Level.DEBUG)) {
            withLogs(level, events -> {
                for (Fault fault : Fault.values()) {
                    Fixture fixture = fixture(fault);
                    if (fault == Fault.NONE) {
                        String result = fixture.owner().executeOwned(() -> "value");
                        assertThat(result).isEqualTo("value");
                    } else {
                        RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> {
                            if (fault.name().startsWith("ROLLBACK")) throw secretRuntime();
                            return "value";
                        }));
                        assertSafe(failure);
                    }
                    assertUnbound(fixture);
                }
                for (boolean error : List.of(false, true)) {
                    Fixture fixture = fixture(Fault.NONE);
                    RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> {
                        if (error) throw new AssertionError(SECRET, secretRuntime());
                        throw secretRuntime();
                    }));
                    assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
                    assertSafe(failure);
                    assertUnbound(fixture);
                }
                String rendered = rendered(events);
                assertThat(rendered).doesNotContain(SECRET);
                if (level == Level.DEBUG) {
                    assertThat(rendered).contains("Risk owned JDBC lease[redacted]", "Initiating transaction rollback");
                }
            });
        }
    }

    @Test void wrongThreadAndEscapedLeaseDiagnosticsRemainFixed() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        java.util.concurrent.atomic.AtomicReference<Connection> captured = new java.util.concurrent.atomic.AtomicReference<>();
        fixture.owner().executeOwned(() -> { captured.set(bound(fixture)); return null; });
        withLogs(Level.DEBUG, events -> {
            Connection connection = captured.get();
            assertThat(connection.toString()).isEqualTo("Risk owned JDBC lease[redacted]");
            assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class).satisfies(RiskJdbcTransactionOwnerTest::assertSafe);
            assertThatThrownBy(connection::close).isInstanceOf(SQLException.class).satisfies(RiskJdbcTransactionOwnerTest::assertSafe);
            assertThat(rendered(events)).doesNotContain(SECRET);
        });
        assertUnbound(fixture);
    }

    private static String rendered(ListAppender<ILoggingEvent> events) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : events.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) text.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        return text.toString();
    }
    private static void withLogs(Level level, LogCase action) throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        List<Logger> loggers = List.of(root,
                (Logger) LoggerFactory.getLogger("com.mightyoung.softwarefactory.support.RiskJdbcTransactionOwner$Manager"),
                (Logger) LoggerFactory.getLogger("org.springframework.jdbc.datasource.DataSourceUtils"),
                (Logger) LoggerFactory.getLogger("org.springframework.transaction.support.TransactionTemplate"));
        List<Level> previous = new ArrayList<>();
        for (Logger logger : loggers) { previous.add(logger.getLevel()); logger.setLevel(level); }
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start(); root.addAppender(events);
        try { action.run(events); }
        finally {
            root.detachAppender(events); events.stop();
            for (int i = 0; i < loggers.size(); i++) loggers.get(i).setLevel(previous.get(i));
        }
    }
    @FunctionalInterface private interface LogCase { void run(ListAppender<ILoggingEvent> events) throws Exception; }
}
