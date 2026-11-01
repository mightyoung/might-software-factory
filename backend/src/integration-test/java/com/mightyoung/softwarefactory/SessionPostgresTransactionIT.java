package com.mightyoung.softwarefactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL probes for the independent security-session transaction boundary.
 * Spring Session JDBC deliberately uses REQUIRES_NEW; sharing the main DataSource
 * and transaction manager does not make a session part of the business rollback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("lan-postgres-compatibility")
class SessionPostgresTransactionIT {

    private static final String PROBE_ATTRIBUTE = "p0SessionTransactionProbe";
    private static final String ORIGINAL_VALUE = "committed-before-business-transaction";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FindByIndexNameSessionRepository<?> sessionRepository;

    @DynamicPropertySource
    static void lanPostgresProperties(DynamicPropertyRegistry properties) {
        var target = LanPostgresCompatibilityIT.DatabaseTarget.preflightFromEnvironment(System.getenv());
        properties.add("spring.datasource.url", target::jdbcUrl);
        properties.add("spring.datasource.username", () -> System.getenv("LAN_PG_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("LAN_PG_PASSWORD"));
    }

    @Test
    void configuredSessionStoreSharesTheDatasourceAndManagerButUsesIndependentTransactions() {
        assertThat(sessionRepository).isInstanceOf(JdbcIndexedSessionRepository.class);
        assertThat(ReflectionTestUtils.getField(sessionRepository, "cleanupCron")).isEqualTo("-");
        assertThat(ReflectionTestUtils.getField(sessionRepository, "taskScheduler")).isNull();
        Object sessionJdbc = ReflectionTestUtils.getField(sessionRepository, "jdbcOperations");
        assertThat(sessionJdbc).isInstanceOfSatisfying(JdbcTemplate.class,
                template -> assertThat(template.getDataSource()).isSameAs(dataSource));

        Object sessionTransactions = ReflectionTestUtils.getField(sessionRepository, "transactionOperations");
        assertThat(sessionTransactions).isInstanceOfSatisfying(TransactionTemplate.class, template -> {
            assertThat(template.getTransactionManager()).isSameAs(transactionManager);
            assertThat(template.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        });
    }

    @Test
    void aSavedSessionUpdateSurvivesBusinessRollbackWhileTheOuterConnectionAndOutboxAreRestored() {
        String eventKey = "p0-session-update-" + UUID.randomUUID();
        try {
            withProbeSession(sessionRepository, sessionId -> {
                assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    int outerPid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                    insertProbeEvent(eventKey);

                    // ON_SAVE requires an explicit save while the business transaction is still active.
                    saveAttribute(sessionRepository, sessionId, "independently-committed-update");

                    assertOuterTransactionResumed(outerPid, eventKey);
                    throw new DeliberateBusinessRollback();
                })).isInstanceOf(DeliberateBusinessRollback.class);

                Session reloaded = sessionRepository.findById(sessionId);
                assertThat(reloaded).isNotNull();
                assertThat((String) reloaded.getAttribute(PROBE_ATTRIBUTE))
                        .isEqualTo("independently-committed-update");
                assertThat(probeEventCount(eventKey)).isZero();
            });
        } finally {
            deleteProbeEvent(eventKey);
        }
    }

    @Test
    void sessionInvalidationSurvivesBusinessRollbackAndDoesNotCommitTheBusinessOutbox() {
        String eventKey = "p0-session-invalidation-" + UUID.randomUUID();
        try {
            withProbeSession(sessionRepository, sessionId -> {
                assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    int outerPid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                    insertProbeEvent(eventKey);

                    sessionRepository.deleteById(sessionId);

                    assertOuterTransactionResumed(outerPid, eventKey);
                    throw new DeliberateBusinessRollback();
                })).isInstanceOf(DeliberateBusinessRollback.class);

                assertThat(sessionRepository.findById(sessionId)).isNull();
                assertThat(probeEventCount(eventKey)).isZero();
            });
        } finally {
            deleteProbeEvent(eventKey);
        }
    }

    private void assertOuterTransactionResumed(int expectedPid, String eventKey) {
        assertThat(jdbc.queryForObject("select pg_backend_pid()", Integer.class)).isEqualTo(expectedPid);
        assertThat(probeEventCount(eventKey)).isEqualTo(1);
    }

    private void insertProbeEvent(String eventKey) {
        jdbc.update("insert into p0_outbox (event_key, event_type) values (?, ?)",
                eventKey, "SESSION_TRANSACTION_PROBE");
    }

    private int probeEventCount(String eventKey) {
        return jdbc.queryForObject("select count(*) from p0_outbox where event_key = ?", Integer.class, eventKey);
    }

    private void deleteProbeEvent(String eventKey) {
        jdbc.update("delete from p0_outbox where event_key = ?", eventKey);
    }

    private static <S extends Session> void withProbeSession(
            FindByIndexNameSessionRepository<S> repository, Consumer<String> probe) {
        S session = repository.createSession();
        String sessionId = session.getId();
        try {
            session.setAttribute(PROBE_ATTRIBUTE, ORIGINAL_VALUE);
            repository.save(session);
            Session reloaded = repository.findById(sessionId);
            assertThat(reloaded).isNotNull();
            assertThat((String) reloaded.getAttribute(PROBE_ATTRIBUTE)).isEqualTo(ORIGINAL_VALUE);
            probe.accept(sessionId);
        } finally {
            repository.deleteById(sessionId);
        }
    }

    private static <S extends Session> void saveAttribute(
            FindByIndexNameSessionRepository<S> repository, String sessionId, String value) {
        S session = repository.findById(sessionId);
        assertThat(session).isNotNull();
        session.setAttribute(PROBE_ATTRIBUTE, value);
        repository.save(session);
    }

    private static final class DeliberateBusinessRollback extends RuntimeException { }
}
