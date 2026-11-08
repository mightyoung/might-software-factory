package com.mightyoung.softwarefactory.support;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mechanical fixture proofs only; real PgJDBC/Spring/Modulith acceptance remains separate. */
class G014JdbcGuardTest {
    private final Object source=new Object();
    private final Connection driver=mock(Connection.class);
    private G014JdbcGuard guard;
    private ConnectionHolder holder;
    @BeforeEach void bind()throws Exception {
        when(driver.getAutoCommit()).thenReturn(false);when(driver.isReadOnly()).thenReturn(false);
        when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        guard=new G014JdbcGuard(driver,List.of(new TransactionSqlSealGate.SqlTemplate("select 1",0,true,null),
                new TransactionSqlSealGate.SqlTemplate("insert into proof values (?)",1,false,"proof")));
        guard.captureBaseline();
        holder=new ConnectionHolder(guard.connection);holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source,holder);
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        guard.bind(source,holder);
    }
    @AfterEach void clear(){TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear();}
    private void seal()throws SQLException {
        guard.beginFinal();guard.connection.getAutoCommit();guard.connection.isReadOnly();guard.connection.getTransactionIsolation();
        guard.sealAfterNormalReturn();
    }
    @Test void sealedDriverStateGettersUseOnlyTheTrustedFinalSnapshot()throws Exception {
        seal();clearInvocations(driver);
        assertThat(guard.connection.getAutoCommit()).isFalse();assertThat(guard.connection.isReadOnly()).isFalse();
        assertThat(guard.connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        verifyNoInteractions(driver);
    }
    @Test void finalSnapshotCannotReuseOnlyEarlierStagingGetterObservations()throws Exception {
        guard.connection.getAutoCommit();guard.connection.isReadOnly();guard.connection.getTransactionIsolation();guard.beginFinal();
        assertThatThrownBy(guard::sealAfterNormalReturn).isInstanceOf(IllegalStateException.class);
        assertThat(guard.poisoned()).isTrue();
    }
    @Test void swallowedOldPreparedStatementExecutionStillPreventsCommit()throws Exception {
        var raw=mock(PreparedStatement.class);when(driver.prepareStatement("select 1")).thenReturn(raw);
        var old=guard.connection.prepareStatement("select 1");old.close();seal();clearInvocations(driver,raw);
        assertThatThrownBy(old::executeQuery).isInstanceOf(IllegalStateException.class);
        holder.resetRollbackOnly();
        assertThatThrownBy(guard::commit).isInstanceOf(IllegalStateException.class);
        verify(driver,never()).commit();verify(raw,never()).executeQuery();
    }
    @Test void liveResourcesRefuseSealEvenIfTheCallerIntendsToCloseThemLater()throws Exception {
        when(driver.prepareStatement("select 1")).thenReturn(mock(PreparedStatement.class));
        guard.connection.prepareStatement("select 1");
        assertThatThrownBy(guard::beginFinal).isInstanceOf(IllegalStateException.class);
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void statementResultAndMetadataCannotReturnThePhysicalLease()throws Exception {
        var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);var metadata=mock(DatabaseMetaData.class);
        when(driver.prepareStatement("select 1")).thenReturn(statement);when(statement.executeQuery()).thenReturn(rows);
        when(driver.getMetaData()).thenReturn(metadata);
        var guarded=guard.connection.prepareStatement("select 1");var result=guarded.executeQuery();
        assertThat(guarded.getConnection()).isSameAs(guard.connection);
        assertThat(result.getStatement()).isSameAs(guarded);
        assertThat(guard.connection.getMetaData().getConnection()).isSameAs(guard.connection);
        assertThat(guard.connection.unwrap(Connection.class)).isSameAs(guard.connection);
        assertThat(guarded.unwrap(PreparedStatement.class)).isSameAs(guarded);
        assertThatThrownBy(()->guard.connection.unwrap(java.io.Closeable.class)).isInstanceOf(IllegalStateException.class);
        verify(driver,never()).unwrap(any());
    }
    @Test void unsupportedMethodsAndCallableStatementsPoisonBeforeDriverAccess()throws Exception {
        assertThatThrownBy(()->guard.connection.prepareCall("call unsafe()" )).isInstanceOf(IllegalStateException.class);
        verify(driver,never()).prepareCall(anyString());assertThat(guard.poisoned()).isTrue();
    }
    @Test void successfulWriteObservationsCarryActualBindingsAndCounts()throws Exception {
        var statement=mock(PreparedStatement.class);when(driver.prepareStatement("insert into proof values (?)")).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        try(var query=guard.connection.prepareStatement("insert into proof values (?)")){query.setString(1,"bound");query.executeUpdate();}
        var observation=guard.executions().getFirst();
        assertThat(observation.effect()).isEqualTo("proof");assertThat(observation.parameters()).containsEntry(1,"bound");
        assertThat(observation.affectedRows()).isEqualTo(1);
        assertThat(TransactionSqlSealGate.SqlExecution.class.getConstructors()).isEmpty();
    }
    @Test void rollbackFailureRetainsUnknownAndCannotRestoreAutoCommit()throws Exception {
        doThrow(new SQLException("lost acknowledgment")).when(driver).rollback();
        assertThatThrownBy(guard::rollback).isInstanceOf(SQLException.class);
        assertThat(guard.phase()).isEqualTo(G014JdbcGuard.Phase.UNKNOWN);
        assertThatThrownBy(guard::cleanup).isInstanceOf(IllegalStateException.class);
        verify(driver,never()).setAutoCommit(true);verify(driver,never()).close();
    }
    @Test void commitFailureCannotBeRelabeledAsRollbackSuccess()throws Exception {
        seal();doThrow(new SQLException("lost acknowledgment")).when(driver).commit();
        assertThatThrownBy(guard::commit).isInstanceOf(SQLException.class);guard.rollback();
        assertThat(guard.phase()).isEqualTo(G014JdbcGuard.Phase.UNKNOWN);
        verify(driver,never()).rollback();verify(driver,never()).setAutoCommit(true);
    }
    @Test void acknowledgedRollbackPrecedesAnyCleanupReset()throws Exception {
        guard.rollback();guard.cleanup();var order=inOrder(driver);
        order.verify(driver).rollback();order.verify(driver).close();
        verify(driver,never()).setAutoCommit(true); // Original manual-commit baseline is preserved.
    }
    @Test void timestampBindingSnapshotsCallerAndDriverMutationsAsImmutableInstant()throws Exception {
        var raw=mock(PreparedStatement.class);when(driver.prepareStatement("insert into proof values (?)")).thenReturn(raw);
        when(raw.executeUpdate()).thenReturn(1);
        var instant=java.time.Instant.parse("2026-11-04T01:02:03.123456789Z");var value=Timestamp.from(instant);
        var captured=new java.util.concurrent.atomic.AtomicReference<Timestamp>();
        doAnswer(i->{captured.set(i.getArgument(1));return null;}).when(raw).setTimestamp(eq(1),any(Timestamp.class));
        try(var query=guard.connection.prepareStatement("insert into proof values (?)")){
            query.setTimestamp(1,value);assertThat(captured.get()).isNotSameAs(value);value.setTime(0);
            captured.get().setTime(1);query.executeUpdate();
        }
        assertThat(guard.executions().getFirst().parameters()).containsEntry(1,instant);
    }
    @Test void timestampCalendarOverloadIsDeniedBeforeDriverAccess()throws Exception {
        var raw=mock(PreparedStatement.class);when(driver.prepareStatement("insert into proof values (?)")).thenReturn(raw);
        var query=guard.connection.prepareStatement("insert into proof values (?)");
        assertThatThrownBy(()->query.setTimestamp(1,new Timestamp(0),Calendar.getInstance())).isInstanceOf(IllegalStateException.class);
        verify(raw,never()).setTimestamp(anyInt(),any(),any());assertThat(guard.poisoned()).isTrue();
    }

    @Test void timestampNullRemainsTypedNullAndObservesNull()throws Exception {
        var raw=mock(PreparedStatement.class);when(driver.prepareStatement("insert into proof values (?)")).thenReturn(raw);
        when(raw.executeUpdate()).thenReturn(1);
        try(var query=guard.connection.prepareStatement("insert into proof values (?)")){query.setTimestamp(1,null);query.executeUpdate();}
        verify(raw).setTimestamp(1,null);assertThat(guard.executions().getFirst().parameters()).containsEntry(1,null);
    }

    @Test void pinnedSpringStatementCreatorUtilsUsesFiniteTimestampSetter()throws Exception {
        var raw=mock(PreparedStatement.class);when(driver.prepareStatement("insert into proof values (?)")).thenReturn(raw);
        when(raw.executeUpdate()).thenReturn(1);var instant=java.time.Instant.parse("2026-11-04T01:02:03.123456789Z");
        try(var query=guard.connection.prepareStatement("insert into proof values (?)")){
            org.springframework.jdbc.core.StatementCreatorUtils.setParameterValue(query,1,Types.TIMESTAMP,Timestamp.from(instant));
            query.executeUpdate();
        }
        verify(raw).setTimestamp(1,Timestamp.from(instant));
        assertThat(guard.executions().getFirst().parameters()).containsEntry(1,instant);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"int3","int4","type3","type4","date2"})
    void objectTimestampFixDoesNotOpenOtherOverloadsOrDateTypes(String variant)throws Exception {
        var raw=mock(PreparedStatement.class);when(driver.prepareStatement("insert into proof values (?)")).thenReturn(raw);
        var query=guard.connection.prepareStatement("insert into proof values (?)");var timestamp=Timestamp.from(java.time.Instant.EPOCH);
        assertThatThrownBy(()->{switch(variant){
            case "int3"->query.setObject(1,timestamp,Types.TIMESTAMP);
            case "int4"->query.setObject(1,timestamp,Types.TIMESTAMP,9);
            case "type3"->query.setObject(1,timestamp,JDBCType.TIMESTAMP);
            case "type4"->query.setObject(1,timestamp,JDBCType.TIMESTAMP,9);
            default->query.setObject(1,new java.sql.Date(0));
        }}).isInstanceOf(IllegalStateException.class);
        assertThat(guard.poisoned()).isTrue();verifyNoInteractions(raw);
        holder.resetRollbackOnly();assertThatThrownBy(guard::beginFinal).isInstanceOf(IllegalStateException.class);
    }

}
