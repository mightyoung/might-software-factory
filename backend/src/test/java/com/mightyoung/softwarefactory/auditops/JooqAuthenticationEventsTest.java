package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.AuthenticationEvent;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JooqAuthenticationEventsTest {
    private final UUID attempt = UUID.randomUUID();
    private final Instant occurredAt = Instant.parse("2026-11-03T12:00:00Z");
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final List<String> statements = new ArrayList<>();
    private final List<List<Object>> bindings = new ArrayList<>();
    private final List<String> order = new ArrayList<>();
    private int insertedRows = 1;
    private SQLException insertFailure;

    private DSLContext dsl() {
        return DSL.using(new MockConnection(call -> {
            statements.add(call.sql());
            bindings.add(Arrays.asList(call.bindings()));
            order.add("insert");
            if (insertFailure != null) throw insertFailure;
            return new MockResult[] {new MockResult(insertedRows)};
        }), SQLDialect.POSTGRES);
    }

    private JooqAuthenticationEvents records() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new JooqAuthenticationEvents(dsl(), manager);
    }

    private AuthenticationEvent anonymous(AuthenticationEvent.Outcome outcome) {
        return new AuthenticationEvent(attempt, occurredAt, outcome, null);
    }

    @AfterEach void clearThread() {
        List.copyOf(TransactionSynchronizationManager.getResourceMap().keySet())
                .forEach(TransactionSynchronizationManager::unbindResource);
        TransactionSynchronizationManager.clear();
    }

    @Test void anonymousOutcomesAppendExactlyOneInsertWithNoAttributedIdentifiers() {
        var records = records();
        for (var outcome : List.of(AuthenticationEvent.Outcome.AUTHENTICATION_DENIED,
                AuthenticationEvent.Outcome.AUTHENTICATION_UNAVAILABLE)) {
            records.record(anonymous(outcome));
            assertThat(bindings.getLast()).hasSize(7);
            assertThat(bindings.getLast().subList(0, 6)).containsExactly(attempt, outcome.name(), null, null, null, null);
            assertTimestampBinding();
        }
        assertThat(statements).hasSize(2).allSatisfy(sql -> {
            assertThat(sql.stripLeading()).startsWith("insert into public.authentication_event");
            assertThat(sql).contains("attempt_id,outcome,account_id,actor_id,tenant_id,member_id,occurred_at,policy_version",
                    "'login-audit-v1'");
            assertThat(sql.toLowerCase()).doesNotContain("select", "on conflict", "set_config", "session_id",
                    "password", "cookie", "hmac");
        });
        verify(manager, times(2)).commit(any());
        verify(manager, never()).rollback(any());
    }

    @Test void verifiedOutcomesPersistAllFourServerVerifiedIdentifiers() {
        var records = records();
        UUID account = UUID.randomUUID(), actor = UUID.randomUUID(), tenant = UUID.randomUUID(), member = UUID.randomUUID();
        var subject = AuthenticationEvent.Subject.from(
                VerifiedSessionPrincipalTestFixture.create(actor, account, tenant, member, 1, 1));
        for (var outcome : List.of(AuthenticationEvent.Outcome.SESSION_PERSISTED,
                AuthenticationEvent.Outcome.SESSION_OUTCOME_UNKNOWN)) {
            records.record(new AuthenticationEvent(attempt, occurredAt, outcome, subject));
            assertThat(bindings.getLast()).hasSize(7);
            assertThat(bindings.getLast().subList(0, 6)).containsExactly(attempt, outcome.name(), account, actor, tenant, member);
            assertTimestampBinding();
        }
        assertThat(statements).hasSize(2);
    }

    private void assertTimestampBinding() {
        // The PostgreSQL jOOQ binding serializes OffsetDateTime as a JDBC timestamp string.
        assertThat(bindings.getLast().get(6)).isInstanceOfSatisfying(String.class, value ->
                assertThat(OffsetDateTime.parse(value.replace(' ', 'T')).toInstant()).isEqualTo(occurredAt));
    }

    @Test void transactionIsIndependentReadCommittedAndWritable() {
        var records = records();
        doAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(definition.isReadOnly()).isFalse();
            order.add("begin");
            return new SimpleTransactionStatus();
        }).when(manager).getTransaction(any());
        doAnswer(call -> { order.add("commit"); return null; }).when(manager).commit(any());
        records.record(anonymous(AuthenticationEvent.Outcome.AUTHENTICATION_DENIED));
        order.add("returned");
        assertThat(order).containsExactly("begin", "insert", "commit", "returned");
    }

    @Test void rejectsActualTransactionSynchronizationOrBoundResourceBeforeOpeningOrWriting() {
        var records = records();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertRejected(records);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.initSynchronization();
        assertRejected(records);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.bindResource(new Object(), new Object());
        assertRejected(records);
        assertThat(statements).isEmpty();
        verifyNoInteractions(manager);
    }

    private void assertRejected(JooqAuthenticationEvents records) {
        assertThatThrownBy(() -> records.record(anonymous(AuthenticationEvent.Outcome.AUTHENTICATION_DENIED)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void anyUnexpectedAffectedRowCountRollsBack() {
        var records = records();
        for (int count : List.of(0, 2, -1)) {
            insertedRows = count;
            assertRejected(records);
        }
        assertThat(statements).hasSize(3);
        verify(manager, times(3)).rollback(any());
        verify(manager, never()).commit(any());
    }

    @Test void duplicateInsertFailurePropagatesWithoutRetryOrUpsert() {
        var records = records();
        insertFailure = new SQLException("duplicate attempt", "23505");
        assertThatThrownBy(() -> records.record(anonymous(AuthenticationEvent.Outcome.AUTHENTICATION_DENIED)))
                .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(statements).hasSize(1);
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
    }

    @Test void completedInsertDoesNotReportSuccessBeforePhysicalCommitAndNeverRetriesUnknownCommit() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        doAnswer(call -> {
            order.add("physical commit");
            throw new SQLException("commit acknowledgement lost", "08006");
        }).when(connection).commit();
        var records = new JooqAuthenticationEvents(dsl(), new DataSourceTransactionManager(source));
        assertThatThrownBy(() -> records.record(anonymous(AuthenticationEvent.Outcome.AUTHENTICATION_DENIED)))
                .isInstanceOf(TransactionSystemException.class)
                .hasRootCauseInstanceOf(SQLException.class);
        assertThat(order).containsExactly("insert", "physical commit");
        assertThat(statements).hasSize(1);
        verify(source).getConnection();
        verify(connection).commit();
        verify(connection, never()).rollback();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test void nullEventDoesNotOpenTransactionOrWrite() {
        var records = records();
        assertThatNullPointerException().isThrownBy(() -> records.record(null));
        assertThat(statements).isEmpty();
        verifyNoInteractions(manager);
    }
}
