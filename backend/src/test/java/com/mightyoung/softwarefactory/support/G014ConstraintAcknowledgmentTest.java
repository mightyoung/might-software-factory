package com.mightyoung.softwarefactory.support;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.core.CommandCompleteParser;
import org.postgresql.jdbc.ResultWrapper;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Source catalog and driver fixtures only: no PostgreSQL server or deferred constraint execution. */
class G014ConstraintAcknowledgmentTest {
    private static final TransactionSqlSealGate.SqlTemplate FLUSH=TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate();
    private final Object source=new Object();
    private final Connection driver=mock(Connection.class);
    private final PreparedStatement raw=mock(PreparedStatement.class);
    private G014JdbcGuard guard;
    private ConnectionHolder holder;
    @BeforeEach void bind()throws Exception {
        when(driver.getAutoCommit()).thenReturn(false);when(driver.isReadOnly()).thenReturn(false);
        when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        guard=new G014JdbcGuard(driver,List.of(FLUSH,
                new TransactionSqlSealGate.SqlTemplate("insert into proof values (1)",0,false,"proof"),
                new TransactionSqlSealGate.SqlTemplate("select 1",0,true,null)));
        guard.captureBaseline();
        holder=new ConnectionHolder(guard.connection);holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source,holder);
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        guard.bind(source,holder);
        when(driver.prepareStatement(FLUSH.sql())).thenReturn(raw);
        when(raw.execute()).thenReturn(false);when(raw.getLargeUpdateCount()).thenReturn(0L);when(raw.getResultSet()).thenReturn(null);
    }
    @AfterEach void clear(){TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear();}
    private void flush()throws SQLException {try(var statement=guard.connection.prepareStatement(FLUSH.sql())){assertThat(statement.execute()).isFalse();}}
    @Test void catalogNamesExactlyMatchAllFortyDeferredV21Constraints()throws Exception {
        String migration;
        try(var stream=getClass().getResourceAsStream("/db/migration/V21__asset_hazard_foundation.sql")){
            migration=new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);
        }
        var matcher=Pattern.compile("(?is)\\bconstraint\\s+(?:trigger\\s+)?(g014_\\w+)\\b[^;]*?\\bdeferrable\\s+initially\\s+deferred\\b").matcher(migration);
        var names=new HashSet<String>();while(matcher.find())names.add(matcher.group(1));
        String body=FLUSH.sql().strip().replaceFirst("^set constraints ","").replaceFirst(" immediate$","");
        List<String> registered=Arrays.stream(body.split(",")).map(String::strip).toList();
        assertThat(names).hasSize(40);assertThat(registered).hasSize(40).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(names);
        assertThat(FLUSH.parameterCount()).isZero();assertThat(FLUSH.finalRead()).isFalse();assertThat(FLUSH.effect()).isNull();
    }
    @Test void pinnedDriverSetCompletionPreservesZeroWithoutClaimingDmlRows()throws Exception {
        var parser=new CommandCompleteParser();parser.parse("SET");
        var result=new ResultWrapper(parser.getRows(),parser.getOid());
        assertThat(org.postgresql.Driver.getVersion()).contains("42.7.14");
        assertThat(parser.getRows()).isZero();assertThat(result.getUpdateCount()).isZero();assertThat(result.getResultSet()).isNull();
        when(raw.getLargeUpdateCount()).thenReturn(result.getUpdateCount());
        flush();var observed=guard.executions().getFirst();
        assertThat(observed.commandAcknowledgment()).isEqualTo(TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE);
        assertThat(observed.affectedRows()).isEqualTo(result.getUpdateCount());assertThat(observed.effect()).isNull();assertThat(observed.parameters()).isEmpty();
        assertThat(TransactionSqlSealGate.SqlExecution.class.getConstructors()).isEmpty();
        guard.beginFinal();
    }
    @ParameterizedTest @ValueSource(strings={"set constraints all immediate","set constraints all deferred","reset all","select 1"})
    void commandAcknowledgmentCannotRegisterArbitrarySql(String sql){
        assertThatThrownBy(()->new TransactionSqlSealGate.SqlTemplate(sql,0,false,null,
                TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyConstructorCannotDisguiseConstraintCommandsAsDml(){
        assertThatThrownBy(()->new TransactionSqlSealGate.SqlTemplate(FLUSH.sql(),0,false,"flush")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TransactionSqlSealGate.SqlTemplate("SET CONSTRAINTS ALL DEFERRED",0,false,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(longs={-1,-2,-3,1,2,Long.MAX_VALUE})
    void unknownOrDmlCountsCannotAcknowledgeFlush(long count)throws Exception {
        when(raw.getLargeUpdateCount()).thenReturn(count);
        assertThatThrownBy(this::flush).isInstanceOf(IllegalStateException.class);assertThat(guard.poisoned()).isTrue();
        assertThatThrownBy(guard::beginFinal).isInstanceOf(IllegalStateException.class);
    }
    @Test void unexpectedResultSetPoisonsWithoutInspectingMetadata()throws Exception {
        var rows=mock(ResultSet.class);when(raw.getResultSet()).thenReturn(rows);
        assertThatThrownBy(this::flush).isInstanceOf(IllegalStateException.class);verifyNoInteractions(rows);assertThat(guard.poisoned()).isTrue();
    }
    @Test void executeTruePoisonsBeforeAnyCompletionGetter()throws Exception {
        when(raw.execute()).thenReturn(true);
        assertThatThrownBy(this::flush).isInstanceOf(IllegalStateException.class);
        verify(raw,never()).getLargeUpdateCount();verify(raw,never()).getResultSet();
    }
    @ParameterizedTest @ValueSource(strings={"execute","count","result"})
    void actualDriverFailurePermanentlyPoisons(String step)throws Exception {
        switch(step){
            case "execute"->when(raw.execute()).thenThrow(new SQLException("failed flush"));
            case "count"->when(raw.getLargeUpdateCount()).thenThrow(new SQLException("lost count"));
            default->when(raw.getResultSet()).thenThrow(new SQLException("lost result shape"));
        }
        assertThatThrownBy(this::flush).isInstanceOf(SQLException.class);assertThat(guard.poisoned()).isTrue();
        holder.resetRollbackOnly();
        assertThatThrownBy(guard::beginFinal).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(guard::commit).isInstanceOf(IllegalStateException.class);
        verify(driver,never()).commit();
    }
    @Test void missingFlushRefusesFinalPhase(){assertThatThrownBy(guard::beginFinal).isInstanceOf(IllegalStateException.class);}
    @Test void repeatedFlushIsDeniedBeforeAnotherDriverExecution()throws Exception {
        flush();assertThatThrownBy(this::flush).isInstanceOf(IllegalStateException.class);verify(raw,times(1)).execute();
    }
    @Test void evenPreviouslyPreparedSqlCannotExecuteAfterFlush()throws Exception {
        var write=mock(PreparedStatement.class);when(driver.prepareStatement("insert into proof values (1)")).thenReturn(write);
        var old=guard.connection.prepareStatement("insert into proof values (1)");flush();
        assertThatThrownBy(old::executeUpdate).isInstanceOf(IllegalStateException.class);verify(write,never()).executeUpdate();
    }
    @Test void evenUnobservedReadCannotExecuteAfterFlush()throws Exception {
        flush();assertThatThrownBy(()->guard.connection.prepareStatement("select 1")).isInstanceOf(IllegalStateException.class);
        verify(driver,never()).prepareStatement("select 1");
    }
    @ParameterizedTest @ValueSource(strings={"update","query","batch"})
    void onlyExecuteCanAcknowledgeFlush(String method)throws Exception {
        var query=guard.connection.prepareStatement(FLUSH.sql());
        assertThatThrownBy(()->{switch(method){case "update"->query.executeUpdate();case "query"->query.executeQuery();default->query.addBatch();}})
                .isInstanceOf(IllegalStateException.class);
        verify(raw,never()).executeUpdate();verify(raw,never()).executeQuery();verify(raw,never()).addBatch();
    }
    @Test void negativeDmlCountStillPoisons()throws Exception {
        var write=mock(PreparedStatement.class);when(driver.prepareStatement("insert into proof values (1)")).thenReturn(write);
        when(write.executeUpdate()).thenReturn(-1);
        assertThatThrownBy(()->guard.connection.prepareStatement("insert into proof values (1)").executeUpdate()).isInstanceOf(IllegalStateException.class);
    }
    @Test void commandAckFollowsActualWriteObservation()throws Exception {
        var write=mock(PreparedStatement.class);when(driver.prepareStatement("insert into proof values (1)")).thenReturn(write);
        when(write.executeUpdate()).thenReturn(1);
        try(var query=guard.connection.prepareStatement("insert into proof values (1)")){query.executeUpdate();}
        flush();var observed=guard.executions();assertThat(observed).hasSize(2);
        assertThat(observed.getFirst().effect()).isEqualTo("proof");assertThat(observed.getFirst().commandAcknowledgment()).isNull();
        assertThat(observed.getLast().commandAcknowledgment()).isEqualTo(TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE);
    }
    @Test void exactNamedFlushRejectsMissingRenamedOrRepeatedConstraintBeforeDriverAccess()throws Exception {
        for(String sql:List.of(FLUSH.sql().replace("g014_workshop_last_command_fk,", ""),
                FLUSH.sql().replace("g014_workshop_last_command_fk", "g014_unknown_constraint"),
                FLUSH.sql().replace("g014_workshop_last_command_fk", "g014_workshop_revision_receipt_fk"))){
            assertThatThrownBy(()->new TransactionSqlSealGate.SqlTemplate(sql,0,false,null,
                    TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->guard.connection.prepareStatement(FLUSH.sql().replace("immediate", "deferred")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(raw);
    }
    @Test void acknowledgedFlushAllowsNormalResourceClosureFinalReadsAndCommit()throws Exception {
        when(driver.getAutoCommit()).thenReturn(false);when(driver.isReadOnly()).thenReturn(false);
        when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        var finalQuery=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(driver.prepareStatement("select 1")).thenReturn(finalQuery);when(finalQuery.executeQuery()).thenReturn(rows);
        flush();verify(raw).close();guard.beginFinal();
        try(var query=guard.connection.prepareStatement("select 1");var result=query.executeQuery()){
            assertThat(result.next()).isFalse();
        }
        guard.connection.getAutoCommit();guard.connection.isReadOnly();guard.connection.getTransactionIsolation();
        guard.sealAfterNormalReturn();guard.commit();
        assertThat(guard.phase()).isEqualTo(G014JdbcGuard.Phase.COMMITTED);
        var order=inOrder(raw,finalQuery,rows,driver);
        order.verify(raw).execute();order.verify(raw).getLargeUpdateCount();order.verify(raw).getResultSet();order.verify(raw).close();
        order.verify(driver).prepareStatement("select 1");order.verify(finalQuery).executeQuery();
        order.verify(rows).close();order.verify(finalQuery).close();order.verify(driver).commit();
    }
    @Test void statementExecuteAlsoRequiresActualZeroCompletion()throws Exception {
        var statement=mock(Statement.class);when(driver.createStatement()).thenReturn(statement);
        when(statement.execute(FLUSH.sql())).thenReturn(false);when(statement.getLargeUpdateCount()).thenReturn(0L);
        try(var guarded=guard.connection.createStatement()){assertThat(guarded.execute(FLUSH.sql())).isFalse();}
        assertThat(guard.executions()).singleElement().satisfies(observed->{
            assertThat(observed.affectedRows()).isZero();
            assertThat(observed.commandAcknowledgment()).isEqualTo(TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE);
        });
        guard.beginFinal();
    }

}
