package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Managed callbacks and authorization guard, recording JDBC fixture; not SQL/identity acceptance. */
class G014ManagedConstraintFlushTest {
    private static final TransactionSqlSealGate.SqlTemplate FLUSH=TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate();
    private static final Instant INITIAL=Instant.parse("2026-11-04T01:00:00Z");
    private static final String TENANT_SQL="select current_setting('app.tenant_id',true)";
    private static final String CLOCK_SQL="select statement_timestamp()";
    private static final String EFFECT_SQL="insert into fixture_effect values (1)";
    private final class Fixture {
        final UUID tenant=UUID.randomUUID();
        final Connection driver=mock(Connection.class);
        final DataSource pool=mock(DataSource.class);
        final List<String> calls=new ArrayList<>();
        final AtomicBoolean autoCommit=new AtomicBoolean(true);
        final AtomicReference<String> failureStep=new AtomicReference<>();
        final AtomicInteger clocks=new AtomicInteger(),finals=new AtomicInteger(),flushes=new AtomicInteger();
        final G014JdbcTransactionManager manager;
        final ManagedSessionSensitiveSecurityTransaction authority;
        final PostgresAuthorizationWriteGuard authorization;
        final HumanSessionAuthorityTransaction.WriteScope scope;
        final TransactionSqlSealGate gate;
        final TransactionTemplate transaction;
        final TransactionSqlSealGate.CommandPlan plan=mock(TransactionSqlSealGate.CommandPlan.class);
        final AtomicReference<Instant> finalTime=new AtomicReference<>();
        Fixture()throws Exception {
            when(pool.getConnection()).thenReturn(driver);when(driver.getAutoCommit()).thenAnswer(i->autoCommit.get());
            doAnswer(i->{autoCommit.set(i.getArgument(0));return null;}).when(driver).setAutoCommit(anyBoolean());
            when(driver.isReadOnly()).thenReturn(false);when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(driver.prepareStatement(anyString())).thenAnswer(i->{
                String sql=i.getArgument(0);calls.add(sql);var statement=mock(PreparedStatement.class);
                when(statement.execute()).thenAnswer(x->{if(sql.equals(FLUSH.sql())){flushes.incrementAndGet();if("execute".equals(failureStep.get()))throw new SQLException("deferred constraint rejected","23514");}return false;});
                when(statement.getLargeUpdateCount()).thenAnswer(x->{if(sql.equals(FLUSH.sql())&&"count".equals(failureStep.get()))throw new SQLException("lost flush count");return sql.equals(EFFECT_SQL)?1L:0L;});
                when(statement.getResultSet()).thenAnswer(x->{if(sql.equals(FLUSH.sql())&&"result".equals(failureStep.get()))throw new SQLException("lost flush shape");return null;});
                doAnswer(x->{if(sql.equals(FLUSH.sql())&&"close".equals(failureStep.get()))throw new SQLException("flush statement close failed");return null;}).when(statement).close();
                when(statement.executeUpdate()).thenReturn(1);
                when(statement.executeQuery()).thenAnswer(x->{
                    var rows=mock(ResultSet.class);when(rows.next()).thenReturn(true,false);
                    when(rows.getString(1)).thenReturn(sql.contains("transaction_isolation")?"read committed":sql.contains("transaction_read_only")?"off":sql.equals(TENANT_SQL)?tenant.toString():sql.contains("case when")?"5000ms":"0");
                    if(sql.equals(CLOCK_SQL))when(rows.getObject(1,OffsetDateTime.class)).thenReturn(INITIAL.plusSeconds(clocks.getAndIncrement()).atOffset(ZoneOffset.UTC));
                    return rows;
                });return statement;
            });
            var catalog=new LinkedHashMap<String,TransactionSqlSealGate.SqlTemplate>();
            PostgresAuthorizationWriteGuard.commandReadSql().forEach((sql,n)->catalog.put(sql,new TransactionSqlSealGate.SqlTemplate(sql,n,true,null)));
            PostgresAuthorizationWriteGuard.commandStagingSql().forEach((sql,n)->catalog.put(sql,new TransactionSqlSealGate.SqlTemplate(sql,n,false,null)));
            catalog.put(TENANT_SQL,new TransactionSqlSealGate.SqlTemplate(TENANT_SQL,0,true,null));
            catalog.put(CLOCK_SQL,new TransactionSqlSealGate.SqlTemplate(CLOCK_SQL,0,true,null));
            catalog.put(EFFECT_SQL,new TransactionSqlSealGate.SqlTemplate(EFFECT_SQL,0,false,"effect"));catalog.put(FLUSH.sql(),FLUSH);
            manager=new G014JdbcTransactionManager(pool,catalog.values());authorization=new PostgresAuthorizationWriteGuard(manager.commandDataSource());
            authority=new ManagedSessionSensitiveSecurityTransaction(manager.commandDataSource(),manager,authorization);
            var request=mock(CapturedHumanRequest.class);
            when(request.context()).thenReturn(new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),tenant,UUID.randomUUID(),0,0,1));
            scope=new HumanSessionAuthorityTransaction.WriteScope(request,HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND,List.of(tenant));
            var action=RegisteredEntityAction.WORKSHOP_CREATE;
            when(plan.action()).thenReturn(action);when(plan.commandId()).thenReturn(UUID.randomUUID());when(plan.targetId()).thenReturn(UUID.randomUUID());
            var actions=mock(EntityActionAuthorization.class);
            when(actions.requireFinalCommand(any(),any(),any())).thenAnswer(i->{
                HumanSessionAuthorityTransaction.GuardedBoundary boundary=i.getArgument(1);
                Instant checked=boundary.observedAt();var scopes=mock(EntityActionAuthorization.FinalActionScopes.class);when(scopes.observedAt()).thenReturn(checked);return scopes;
            });
            var validator=new TransactionSqlSealGate.RegisteredCommandValidator(){
                public void requireOwnedPlan(TransactionSqlSealGate.CommandPlan candidate){if(candidate!=plan)throw new IllegalStateException();}
                public Set<RegisteredEntityAction> requiredActions(TransactionSqlSealGate.CommandPlan ignored){return Set.of(action);}
                public void requireStaged(TransactionSqlSealGate.CommandPlan ignored,List<TransactionSqlSealGate.SqlExecution> observations){
                    assertThat(observations).hasSize(2);
                    assertThat(observations.getFirst().effect()).isEqualTo("effect");
                    assertThat(observations.getLast().commandAcknowledgment()).isEqualTo(TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE);
                }
                public void requireFinal(TransactionSqlSealGate.CommandPlan ignored,EntityActionAuthorization.FinalActionScopes current){
                    finals.incrementAndGet();finalTime.set(current.observedAt());
                }
            };
            gate=new TransactionSqlSealGate(manager,authority,actions,Map.of(action,validator));
            transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        }
        void stage(boolean earlyFlush){
            authorization.forBusinessCommand(tenant);
            authority.withGuarded(scope,boundary->{
                gate.bindCommand(plan,scope);assertThat(boundary.observedAt()).isEqualTo(INITIAL);
                try(var statement=manager.currentFrame().jdbc.connection.prepareStatement(EFFECT_SQL)){statement.executeUpdate();}
                catch(SQLException failure){throw new IllegalStateException(failure);}
                if(earlyFlush)manualFlush();return null;
            });
        }
        void manualFlush(){try(var statement=manager.currentFrame().jdbc.connection.prepareStatement(FLUSH.sql())){statement.execute();}
            catch(SQLException failure){throw new IllegalStateException(failure);}}
    }
    @Test void gateFlushFollowsNormalManagedStagingPostvalidationAndPrecedesFinalClock()throws Exception {
        var f=new Fixture();var closedAt=new AtomicInteger();
        f.transaction.execute(status->{f.stage(false);closedAt.set(f.calls.size());assertThat(f.flushes.get()).isZero();f.gate.finalizeCommand();return null;});
        assertThat(f.flushes.get()).isEqualTo(1);assertThat(f.clocks.get()).isEqualTo(2);assertThat(f.finals.get()).isEqualTo(1);
        assertThat(f.finalTime.get()).isEqualTo(INITIAL.plusSeconds(1));verify(f.driver).commit();verify(f.driver,never()).rollback();
        int flush=f.calls.indexOf(FLUSH.sql());
        assertThat(flush).isEqualTo(closedAt.get());
        assertThat(f.calls.subList(0,flush)).contains(TENANT_SQL);
        assertThat(f.calls.subList(flush+1,f.calls.size())).contains(CLOCK_SQL).doesNotContain(EFFECT_SQL,FLUSH.sql());
    }
    @Test void oldInsideCallbackFlushPreventsManagedPostvalidationAndCannotCommit()throws Exception {
        var f=new Fixture();assertThatThrownBy(()->f.transaction.execute(status->{f.stage(true);return null;})).isInstanceOf(RuntimeException.class);
        assertThat(f.flushes.get()).isEqualTo(1);assertThat(f.clocks.get()).isEqualTo(1);assertThat(f.finals.get()).isZero();
        verify(f.driver,never()).commit();verify(f.driver).rollback();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"execute","count","result","close"})
    void swallowedDeferredFailureCannotResetPoisonOrEnterFinalTwice(String step)throws Exception {
        var f=new Fixture();f.failureStep.set(step);
        assertThatThrownBy(()->f.transaction.execute(status->{
            f.stage(false);assertThatThrownBy(f.gate::finalizeCommand).isInstanceOf(RuntimeException.class);
            ((ConnectionHolder)TransactionSynchronizationManager.getResource(f.manager.commandDataSource())).resetRollbackOnly();
            assertThatThrownBy(f.gate::finalizeCommand).isInstanceOf(RuntimeException.class);return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(f.flushes.get()).isEqualTo(1);assertThat(f.finals.get()).isZero();assertThat(f.clocks.get()).isEqualTo(1);
        verify(f.driver,never()).commit();verify(f.driver).rollback();
    }
    @Test void legacyAcknowledgmentAfterClosedStagingIsNotExecutedTwice()throws Exception {
        var f=new Fixture();f.transaction.execute(status->{f.stage(false);f.manualFlush();f.gate.finalizeCommand();return null;});
        assertThat(f.flushes.get()).isEqualTo(1);assertThat(f.finals.get()).isEqualTo(1);verify(f.driver).commit();
    }
}
