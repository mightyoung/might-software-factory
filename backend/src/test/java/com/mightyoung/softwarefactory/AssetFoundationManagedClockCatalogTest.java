package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager;
import com.mightyoung.softwarefactory.support.TransactionSqlSealGate;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Managed authority/guard/manager callbacks over a fixture driver, not persisted HUMAN or PostgreSQL acceptance. */
class AssetFoundationManagedClockCatalogTest {
    private static final Instant T0=Instant.parse("2026-11-04T06:00:00.123456Z");
    private static final Instant T1=T0.plusSeconds(5);

    @Test void realManagedPreliminaryAndFinalCallbacksReadTheirOwnClockOnTheSameGuardedHolder()throws Exception {
        AssetFoundationSqlCatalogTest.fixture(true).run(context->{
            assertThat(context).hasNotFailed();
            var fixture=new DriverFixture();
            var catalog=AssetFoundationSqlCatalog.commandTemplates(DSL.using(SQLDialect.POSTGRES),
                    context.getBean(EventPublicationRepository.class),context.getBean(JdbcRepositorySettings.class));
            var manager=new G014JdbcTransactionManager(fixture.pool,catalog);
            var source=manager.commandDataSource();var guard=new PostgresAuthorizationWriteGuard(source);
            var authority=new ManagedSessionSensitiveSecurityTransaction(source,manager,guard);
            var scope=scope(fixture.tenant);var actions=mock(EntityActionAuthorization.class);
            Object[] holder={null};Connection[] lease={null};
            when(actions.requireFinalCommand(any(),any(),any())).thenAnswer(call->{
                var boundary=call.getArgument(1,HumanSessionAuthorityTransaction.GuardedBoundary.class);
                authority.requireGuarded(scope,boundary,source,lease[0]);
                assertThat(TransactionSynchronizationManager.getResource(source)).isSameAs(holder[0]);
                Instant finalAt=boundary.observedAt();assertThat(finalAt).isEqualTo(T1);
                var scopes=mock(EntityActionAuthorization.FinalActionScopes.class);when(scopes.observedAt()).thenReturn(finalAt);return scopes;
            });
            var plan=mock(TransactionSqlSealGate.CommandPlan.class);
            when(plan.action()).thenReturn(RegisteredEntityAction.WORKSHOP_CREATE);
            when(plan.commandId()).thenReturn(UUID.randomUUID());when(plan.targetId()).thenReturn(UUID.randomUUID());
            var validator=new TransactionSqlSealGate.RegisteredCommandValidator(){
                public void requireOwnedPlan(TransactionSqlSealGate.CommandPlan candidate){assertThat(candidate).isSameAs(plan);}
                public Set<RegisteredEntityAction> requiredActions(TransactionSqlSealGate.CommandPlan ignored){return Set.of(RegisteredEntityAction.WORKSHOP_CREATE);}
                public void requireStaged(TransactionSqlSealGate.CommandPlan ignored,List<TransactionSqlSealGate.SqlExecution> actual){
                    assertThat(actual).singleElement().satisfies(e->assertThat(e.commandAcknowledgment())
                            .isEqualTo(TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE));
                }
                public void requireFinal(TransactionSqlSealGate.CommandPlan ignored,EntityActionAuthorization.FinalActionScopes scopes){assertThat(scopes.observedAt()).isEqualTo(T1);}
            };
            var gate=new TransactionSqlSealGate(manager,authority,actions,Map.of(RegisteredEntityAction.WORKSHOP_CREATE,validator));
            assertThatCode(()->transaction(manager).execute(status->{
                guard.forBusinessCommand(fixture.tenant);
                holder[0]=TransactionSynchronizationManager.getResource(source);lease[0]=((ConnectionHolder)holder[0]).getConnection();
                assertThat(lease[0]).isNotSameAs(fixture.driver);
                Instant first=authority.withGuarded(scope,boundary->{
                    authority.requireGuarded(scope,boundary,source,lease[0]);
                    assertThat(TransactionSynchronizationManager.getResource(source)).isSameAs(holder[0]);return boundary.observedAt();
                });
                assertThat(first).isEqualTo(T0);gate.bindCommand(plan,scope);
                try(var flush=lease[0].prepareStatement(TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate().sql())){flush.execute();}
                catch(SQLException failure){throw new IllegalStateException(failure);}
                gate.finalizeCommand();return null;
            })).doesNotThrowAnyException();
            assertThat(fixture.clockReads.get()).isEqualTo(2);
            assertThat(fixture.prepared).contains("select current_setting('transaction_isolation')",
                    "select current_setting('transaction_read_only')","select current_setting('app.tenant_id',true)");
            try{verify(fixture.driver).commit();verify(fixture.driver,never()).rollback();verify(fixture.pool,times(1)).getConnection();}
            catch(SQLException failure){throw new AssertionError(failure);}
        });
    }

    @ParameterizedTest @ValueSource(strings={"select transaction_timestamp()","select clock_timestamp()","select now()","select statement_timestamp() where true"})
    void otherClockShapesAreDeniedBeforeTheDriverEvenAfterRealManagedCallback(String forbidden)throws Exception {
        AssetFoundationSqlCatalogTest.fixture(true).run(context->{
            var fixture=new DriverFixture();var catalog=AssetFoundationSqlCatalog.commandTemplates(DSL.using(SQLDialect.POSTGRES),
                    context.getBean(EventPublicationRepository.class),context.getBean(JdbcRepositorySettings.class));
            var manager=new G014JdbcTransactionManager(fixture.pool,catalog);var source=manager.commandDataSource();
            var guard=new PostgresAuthorizationWriteGuard(source);var authority=new ManagedSessionSensitiveSecurityTransaction(source,manager,guard);
            var scope=scope(fixture.tenant);
            assertThatThrownBy(()->transaction(manager).execute(status->{
                guard.forBusinessCommand(fixture.tenant);
                Instant observed=authority.withGuarded(scope,boundary->boundary.observedAt());assertThat(observed).isEqualTo(T0);
                var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);
                try {holder.getConnection().prepareStatement(forbidden);throw new AssertionError("Unregistered clock was admitted");}
                catch(IllegalStateException expected){holder.resetRollbackOnly();}
                catch(SQLException failure){throw new IllegalStateException(failure);}
                return authority.withGuarded(scope,boundary->boundary.observedAt());
            })).isInstanceOf(RuntimeException.class);
            assertThat(fixture.prepared).doesNotContain(forbidden);assertThat(fixture.clockReads.get()).isEqualTo(1);
            try{verify(fixture.driver,never()).commit();verify(fixture.driver).rollback();verify(fixture.pool,times(1)).getConnection();}
            catch(SQLException failure){throw new AssertionError(failure);}
        });
    }

    private static TransactionTemplate transaction(G014JdbcTransactionManager manager){
        var transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return transaction;
    }
    private static HumanSessionAuthorityTransaction.WriteScope scope(UUID tenant){
        var request=mock(CapturedHumanRequest.class);
        when(request.context()).thenReturn(new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),tenant,UUID.randomUUID(),0,0,1));
        return new HumanSessionAuthorityTransaction.WriteScope(request,HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND,List.of(tenant));
    }
    private static final class DriverFixture {
        final UUID tenant=UUID.randomUUID();final DataSource pool=mock(DataSource.class);final Connection driver=mock(Connection.class);
        final AtomicInteger clockReads=new AtomicInteger();final List<String> prepared=new ArrayList<>();
        DriverFixture(){
            try{
                when(pool.getConnection()).thenReturn(driver);when(driver.getAutoCommit()).thenReturn(true,false);
                when(driver.isReadOnly()).thenReturn(false);when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
                when(driver.prepareStatement(anyString())).thenAnswer(call->{
                    String sql=call.getArgument(0);prepared.add(sql);var statement=mock(PreparedStatement.class);
                    when(statement.execute()).thenReturn(false);when(statement.getLargeUpdateCount()).thenReturn(0L);
                    when(statement.executeQuery()).thenAnswer(query->{
                        var rows=mock(ResultSet.class);when(rows.next()).thenReturn(true,false);
                        String value=sql.contains("transaction_isolation")?"read committed":sql.contains("transaction_read_only")?"off":
                                sql.equals("select current_setting('app.tenant_id',true)")?tenant.toString():sql.contains("select case")?"5000ms":"0";
                        when(rows.getString(1)).thenReturn(value);
                        if(sql.equals("select statement_timestamp()")){
                            Instant instant=clockReads.getAndIncrement()==0?T0:T1;
                            when(rows.getObject(1,OffsetDateTime.class)).thenReturn(instant.atOffset(ZoneOffset.UTC));
                        }
                        return rows;
                    });return statement;
                });
            }catch(SQLException failure){throw new IllegalStateException(failure);}
        }
    }
}
