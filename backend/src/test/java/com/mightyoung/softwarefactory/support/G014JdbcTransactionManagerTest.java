package com.mightyoung.softwarefactory.support;

import java.sql.*;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses the real pinned Spring manager pipeline with a recording JDBC fixture, not a real database. */
class G014JdbcTransactionManagerTest {
    private record Fixture(Connection driver,G014JdbcTransactionManager manager,TransactionTemplate transaction,TransactionSqlSealGate gate) {}
    private Fixture fixture()throws SQLException {
        return fixture(List.of());
    }
    private Fixture fixture(List<TransactionSqlSealGate.SqlTemplate> templates)throws SQLException {
        var pool=mock(DataSource.class);var driver=mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);when(driver.getAutoCommit()).thenReturn(true,false);
        when(driver.isReadOnly()).thenReturn(false);when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        var manager=new G014JdbcTransactionManager(pool,templates);var transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var authority=mock(com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction.class);
        var actions=mock(com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.class);
        var scopes=mock(com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.FinalActionScopes.class);
        when(scopes.observedAt()).thenReturn(java.time.Instant.parse("2026-11-04T00:00:00Z"));
        when(actions.requireFinalCommand(any(),any(),any())).thenReturn(scopes);
        var validator=mock(TransactionSqlSealGate.RegisteredCommandValidator.class);
        var action=com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.WORKSHOP_CREATE;
        when(validator.requiredActions(any())).thenReturn(java.util.Set.of(action));
        when(authority.withGuarded(any(),any())).thenAnswer(invocation->{
            java.util.function.Function<com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction.GuardedBoundary,Object> work=invocation.getArgument(1);
            Object result=work.apply(mock(com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction.GuardedBoundary.class));
            var connection=manager.currentFrame().jdbc.connection;
            connection.getAutoCommit();connection.isReadOnly();connection.getTransactionIsolation();return result;
        });
        var gate=new TransactionSqlSealGate(manager,authority,actions,java.util.Map.of(action,validator));
        return new Fixture(driver,manager,transaction,gate);
    }
    private void sealFixture(Fixture fixture) {bindFixture(fixture);fixture.gate.finalizeCommand();}
    private void bindFixture(Fixture fixture) {
        var plan=mock(TransactionSqlSealGate.CommandPlan.class);
        when(plan.commandId()).thenReturn(java.util.UUID.randomUUID());when(plan.targetId()).thenReturn(java.util.UUID.randomUUID());
        when(plan.action()).thenReturn(com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.WORKSHOP_CREATE);
        var scope=mock(com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction.WriteScope.class);
        when(scope.guard()).thenReturn(com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND);
        fixture.gate.bindCommand(plan,scope);
    }
    @Test void unsealedWorkCannotCommitAndRollbackPrecedesPoolReset()throws Exception {
        var f=fixture();
        assertThatThrownBy(()->f.transaction.execute(status->null)).isInstanceOf(UnexpectedRollbackException.class);
        verify(f.driver,never()).commit();var order=inOrder(f.driver);
        order.verify(f.driver).rollback();order.verify(f.driver).setAutoCommit(true);order.verify(f.driver).close();
        assertThat(TransactionSynchronizationManager.hasResource(f.manager.commandDataSource())).isFalse();
    }
    @Test void swallowedBeforeCompletionViolationStillRefusesCommit()throws Exception {
        var f=fixture();
        assertThatThrownBy(()->f.transaction.execute(status->{
            Connection connection=f.manager.currentFrame().jdbc.connection;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void beforeCompletion(){
                    try{connection.prepareStatement("select forbidden");}catch(SQLException failure){throw new IllegalStateException(failure);}
                }
            });
            sealFixture(f);return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        verify(f.driver,never()).commit();verify(f.driver).rollback();
    }
    @Test void transactionExecutionListenerCannotBypassLateBarrier()throws Exception {
        var f=fixture();
        f.manager.addListener(new TransactionExecutionListener(){
            @Override public void beforeCommit(TransactionExecution transaction){
                try{f.manager.currentFrame().jdbc.connection.createStatement();}catch(SQLException|RuntimeException ignored){/* poison survives */}
            }
        });
        assertThatThrownBy(()->f.transaction.execute(status->{sealFixture(f);return null;})).isInstanceOf(UnexpectedRollbackException.class);
        verify(f.driver,never()).commit();verify(f.driver).rollback();
    }
    @Test void acknowledgedCommitOccursExactlyOnceBeforeCleanup()throws Exception {
        var f=fixture();f.transaction.execute(status->{sealFixture(f);return null;});
        var order=inOrder(f.driver);order.verify(f.driver).commit();order.verify(f.driver).setAutoCommit(true);order.verify(f.driver).close();
        verify(f.driver,times(1)).commit();assertThat(f.manager.retainedUnusableLeaseCount()).isZero();
    }
    @Test void failedRollbackRetainsLeaseWithoutAutoCommitResetOrClose()throws Exception {
        var f=fixture();doThrow(new SQLException("acknowledgment lost")).when(f.driver).rollback();
        assertThatThrownBy(()->f.transaction.execute(status->null)).isInstanceOf(UnexpectedRollbackException.class);
        verify(f.driver,never()).commit();verify(f.driver,never()).setAutoCommit(true);verify(f.driver,never()).close();
        assertThat(f.manager.retainedUnusableLeaseCount()).isEqualTo(1);
    }
    @Test void failedCommitRetainsUnknownWithoutCompensatingRollbackOrPoolReturn()throws Exception {
        var f=fixture();doThrow(new SQLException("acknowledgment lost")).when(f.driver).commit();
        assertThatThrownBy(()->f.transaction.execute(status->{sealFixture(f);return null;})).isInstanceOf(TransactionSystemException.class);
        verify(f.driver,never()).rollback();verify(f.driver,never()).setAutoCommit(true);verify(f.driver,never()).close();
        assertThat(f.manager.retainedUnusableLeaseCount()).isEqualTo(1);
    }
    @Test void swallowedPostWriteRowCountFailureStillPoisonsAndRollsBack()throws Exception {
        String sql="insert into fixture_effect values (1)";
        var f=fixture(List.of(new TransactionSqlSealGate.SqlTemplate(sql,0,false,"business-effect")));
        var statement=mock(PreparedStatement.class);
        var staged=new java.util.concurrent.atomic.AtomicBoolean();var swallowed=new java.util.concurrent.atomic.AtomicBoolean();
        when(f.driver.prepareStatement(sql)).thenReturn(statement);
        when(statement.execute()).thenAnswer(invocation->{staged.set(true);return false;});
        when(statement.getLargeUpdateCount()).thenThrow(new SQLException("write completed, update-count observation lost"));
        doAnswer(invocation->{staged.set(false);return null;}).when(f.driver).rollback();
        Throwable failure=catchThrowable(()->f.transaction.execute(status->{
            try(var query=f.manager.currentFrame().jdbc.connection.prepareStatement(sql)){query.execute();}
            catch(SQLException expected){swallowed.set(true);}
            assertThat(staged.get()).as("driver has already staged the write").isTrue();
            sealFixture(f);return null;
        }));
        verify(statement).execute();verify(statement).getLargeUpdateCount();
        assertThat(swallowed.get()).as("application swallowed the observation exception").isTrue();
        assertThat(failure).as("lost write observation must permanently poison the transaction").isInstanceOf(RuntimeException.class);
        verify(f.driver,never()).commit();
        var order=inOrder(f.driver);order.verify(f.driver).rollback();order.verify(f.driver).setAutoCommit(true);order.verify(f.driver).close();
        assertThat(staged.get()).isFalse();
    }
    @Test void originalManagerRequiresNewCannotBorrowOrSuspendLaterSynchronizations()throws Exception {
        var f=fixture();var originalPool=mock(DataSource.class);
        var original=new org.springframework.jdbc.support.JdbcTransactionManager(originalPool);
        var nested=new TransactionTemplate(original);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var suspended=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(()->f.transaction.execute(status->{
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public int getOrder(){return org.springframework.core.Ordered.HIGHEST_PRECEDENCE;}
                @Override public void suspend(){suspended.incrementAndGet();}
            });
            f.manager.requirePublicationSynchronizationFirst();
            var holder=TransactionSynchronizationManager.getResource(f.manager.commandDataSource());
            TransactionSynchronizationManager.bindResource(originalPool,holder);
            try {
                // The first manager-owned veto must run before a same-order, later callback.
                assertThatThrownBy(()->nested.execute(inner->null)).isInstanceOf(IllegalStateException.class);
                assertThat(TransactionSynchronizationManager.getResource(originalPool)).isSameAs(holder);
                assertThat(TransactionSynchronizationManager.getResource(f.manager.commandDataSource())).isSameAs(holder);
                assertThat(suspended.get()).isZero();
            }finally{assertThat(TransactionSynchronizationManager.unbindResource(originalPool)).isSameAs(holder);}
            sealFixture(f);return null;
        })).isInstanceOf(RuntimeException.class);
        verify(originalPool,never()).getConnection();verify(f.driver,never()).commit();verify(f.driver).rollback();
    }

    @Test void publicationPreflightRejectsPreexistingSynchronizationWithoutSuspendingIt()throws Exception {
        var f=fixture();var suspended=new java.util.concurrent.atomic.AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        var existing=new TransactionSynchronization(){
            @Override public int getOrder(){return org.springframework.core.Ordered.HIGHEST_PRECEDENCE;}
            @Override public void suspend(){suspended.incrementAndGet();}
        };
        TransactionSynchronizationManager.registerSynchronization(existing);
        try {
            assertThatThrownBy(()->f.transaction.execute(status->{
                throw new AssertionError("must refuse before entering command");
            })).isInstanceOf(IllegalTransactionStateException.class);
            assertThat(TransactionSynchronizationManager.getSynchronizations().getFirst()).isSameAs(existing);
            assertThat(suspended.get()).isZero();verifyNoInteractions(f.driver);
        }finally{TransactionSynchronizationManager.clear();}
    }
    @Test void originalManagerRequiredJoinsAliasedHolderWithoutBorrowing()throws Exception {
        var f=fixture();var originalPool=mock(DataSource.class);
        var original=new org.springframework.jdbc.support.JdbcTransactionManager(originalPool);
        var required=new TransactionTemplate(original);
        f.transaction.execute(status->{
            f.manager.requirePublicationSynchronizationFirst();
            var holder=TransactionSynchronizationManager.getResource(f.manager.commandDataSource());
            TransactionSynchronizationManager.bindResource(originalPool,holder);
            try{required.execute(inner->{assertThat(inner.isNewTransaction()).isFalse();return null;});}
            finally{assertThat(TransactionSynchronizationManager.unbindResource(originalPool)).isSameAs(holder);}
            sealFixture(f);return null;
        });
        verify(originalPool,never()).getConnection();verify(f.driver).commit();
    }

    @Test void realJdbcTemplateObjectArrayTimestampUsesExactSetterAndImmutableObservation()throws Exception {
        String sql="insert into fixture_timestamp values (?)";
        var f=fixture(List.of(new TransactionSqlSealGate.SqlTemplate(sql,1,false,"publication-fixture")));
        var statement=mock(PreparedStatement.class);when(f.driver.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        var expected=java.time.Instant.parse("2026-11-04T01:02:03.123456789Z");var caller=Timestamp.from(expected);
        var forwarded=new java.util.concurrent.atomic.AtomicReference<Timestamp>();
        doAnswer(i->{Timestamp value=i.getArgument(1);forwarded.set(value);assertThat(value).isNotSameAs(caller);value.setTime(1);return null;})
                .when(statement).setObject(eq(1),any(Timestamp.class));
        f.transaction.execute(status->{
            bindFixture(f);
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(f.manager.commandDataSource());
            assertThat(jdbc.update(sql,new Object[]{caller})).isEqualTo(1);
            caller.setTime(0);
            assertThat(f.manager.currentFrame().jdbc.executions().getFirst().parameters()).containsEntry(1,expected);
            f.gate.finalizeCommand();return null;
        });
        assertThat(forwarded.get()).isNotNull();verify(statement).setObject(eq(1),same(forwarded.get()));
        verify(statement,never()).setTimestamp(anyInt(),any());verify(f.driver).commit();
    }

}
