package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mechanical profile isolation only; no real PostgreSQL or business catalog acceptance. */
class G014RiskSqlProfileTest {
    private static final G014SqlProfile ASSET=G014SqlProfile.ASSET_FOUNDATION;
    private static final G014SqlProfile RISK=G014SqlProfile.RISK_SCENARIO;
    private static final RegisteredEntityAction ACTION=RegisteredEntityAction.WORKSHOP_CREATE;
    private static TransactionSqlSealGate.SqlTemplate riskFlush(){return TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate(RISK);}

    @Test void originalFortyConstraintRuntimeBytesRemainGolden()throws Exception {
        var fixed=TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate();
        assertThat(fixed.profile()).isSameAs(ASSET);
        var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixed.sql().getBytes(StandardCharsets.UTF_8)));
        assertThat(hash).isEqualTo("1285942e9705e62a96dadfa4ad60ef396f404e40228d780a733da1d6b4437095");
        assertThat(fixed.sql().split(",")).hasSize(40);
    }
    @Test void riskConstraintNamesAreExactlyTheReviewedSixteenInOrder(){
        assertThat(riskFlush().sql()).isEqualTo("""
            set constraints g014r_scenario_last_command_fk,
                g014r_scenario_current_revision_fk,
                g014r_revision_receipt_fk,
                g014r_receipt_result_fk,
                g014r_link_last_command_fk,
                g014r_link_current_revision_fk,
                g014r_link_revision_receipt_fk,
                g014r_link_revision_scenario_fk,
                g014r_link_hazard_bind_fk,
                g014r_snapshot_scenario_fk,
                g014r_snapshot_link_fk,
                g014r_snapshot_hazard_bind_fk,
                g014r_snapshot_hazard_observed_fk,
                g014r_scenario_history_complete,
                g014r_link_history_complete,
                g014r_receipt_complete immediate
            """);
    }
    @Test void constraintAcknowledgmentCannotBeRelabeledOrMadeDynamic(){
        var ack=TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE;
        assertThatThrownBy(()->new TransactionSqlSealGate.SqlTemplate(riskFlush().sql(),0,false,null,ack,ASSET)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TransactionSqlSealGate.SqlTemplate(TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate().sql(),0,false,null,ack,RISK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TransactionSqlSealGate.SqlTemplate("set constraints all immediate",0,false,null,ack,RISK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TransactionSqlSealGate.SqlTemplate(riskFlush().sql(),0,false,null,null,RISK)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingRiskAcknowledgmentRefusesConstructionWithoutBorrowing(){
        var pool=mock(DataSource.class);
        assertThatThrownBy(()->new G014JdbcTransactionManager(RISK,pool,List.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pool);
    }
    @Test void mixedAndWrongCatalogsRefuseConstructionWithoutBorrowing(){
        var pool=mock(DataSource.class);
        var old=new TransactionSqlSealGate.SqlTemplate("select 1",0,true,null);
        assertThatThrownBy(()->new G014JdbcTransactionManager(RISK,pool,List.of(riskFlush(),old))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new G014JdbcTransactionManager(pool,List.of(riskFlush()))).isInstanceOf(IllegalArgumentException.class);
        var riskRead=new TransactionSqlSealGate.SqlTemplate("select 1",0,true,null,null,RISK);
        assertThatThrownBy(()->new G014JdbcTransactionManager(pool,List.of(riskRead))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pool);
    }
    @Test void explicitRiskManagerCannotBorrowBeforeItsGateExists(){
        var pool=mock(DataSource.class);
        var manager=new G014JdbcTransactionManager(RISK,pool,List.of(riskFlush()));
        var transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(()->transaction.execute(status->null)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(pool);
    }
    @Test void managerAndGateProfileMismatchIsRejectedBeforeBorrowing(){
        var pool=mock(DataSource.class);
        var manager=new G014JdbcTransactionManager(RISK,pool,List.of(riskFlush()));
        assertThatThrownBy(()->new TransactionSqlSealGate(manager,mock(HumanSessionAuthorityTransaction.class),mock(EntityActionAuthorization.class),Map.of(ACTION,mock(TransactionSqlSealGate.RegisteredCommandValidator.class))))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pool);
    }
    @Test void assetGateRejectsExplicitRiskValidatorAndRiskGateRejectsAssetAction(){
        var pool=mock(DataSource.class);
        var riskValidator=mock(TransactionSqlSealGate.ProfiledCommandValidator.class);when(riskValidator.profile()).thenReturn(RISK);
        assertThatThrownBy(()->new TransactionSqlSealGate(new G014JdbcTransactionManager(pool,List.of()),mock(HumanSessionAuthorityTransaction.class),mock(EntityActionAuthorization.class),Map.of(ACTION,riskValidator)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TransactionSqlSealGate(RISK,new G014JdbcTransactionManager(RISK,pool,List.of(riskFlush())),mock(HumanSessionAuthorityTransaction.class),mock(EntityActionAuthorization.class),Map.of(ACTION,mock(TransactionSqlSealGate.RegisteredCommandValidator.class))))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pool);
    }
    @Test void changedValidatorIdentityPoisonsAnAlreadyBorrowedFrame()throws Exception {
        var pool=mock(DataSource.class);var driver=mock(Connection.class);when(pool.getConnection()).thenReturn(driver);
        when(driver.getAutoCommit()).thenReturn(true);var jdbcIsolation=new java.util.concurrent.atomic.AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
        when(driver.getTransactionIsolation()).thenAnswer(call->jdbcIsolation.get());
        doAnswer(call->{jdbcIsolation.set(call.getArgument(0));return null;}).when(driver).setTransactionIsolation(anyInt());
        var manager=new G014JdbcTransactionManager(pool,List.of());
        var validator=mock(TransactionSqlSealGate.ProfiledCommandValidator.class);when(validator.profile()).thenReturn(ASSET);
        var gate=new TransactionSqlSealGate(manager,mock(HumanSessionAuthorityTransaction.class),mock(EntityActionAuthorization.class),Map.of(ACTION,validator));
        var plan=mock(TransactionSqlSealGate.CommandPlan.class);when(plan.action()).thenReturn(ACTION);
        when(plan.commandId()).thenReturn(UUID.randomUUID());when(plan.targetId()).thenReturn(UUID.randomUUID());
        var scope=mock(HumanSessionAuthorityTransaction.WriteScope.class);when(scope.guard()).thenReturn(HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND);
        var transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.execute(status->{
            var frame=manager.currentFrame();
            when(validator.profile()).thenReturn(RISK);
            assertThatThrownBy(()->gate.bindCommand(plan,scope)).isInstanceOf(IllegalStateException.class);
            assertThat(frame.jdbc.poisoned()).isTrue();
            status.setRollbackOnly();return null;
        });
        verify(driver,never()).commit();verify(driver).rollback();
    }
    @Test void riskGuardFlushesOnlyItsFixedCatalogAndRequiresActualAcknowledgment()throws Exception {
        var driver=mock(Connection.class);var statement=mock(PreparedStatement.class);
        when(driver.prepareStatement(riskFlush().sql())).thenReturn(statement);
        when(statement.execute()).thenReturn(false);when(statement.getLargeUpdateCount()).thenReturn(0L);
        when(driver.getAutoCommit()).thenReturn(false);when(driver.isReadOnly()).thenReturn(false);
        when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        var guard=new G014JdbcGuard(RISK,driver,List.of(riskFlush()));
        guard.captureBaseline();
        var source=new Object();var holder=new ConnectionHolder(guard.connection);holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source,holder);TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);guard.bind(source,holder);
        try {
            clearInvocations(driver); // Discard baseline acquisition observations, retain command-boundary assertions.
            assertThat(guard.profile()).isSameAs(RISK);
            assertThatThrownBy(guard::beginFinal).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(driver);
        }finally{TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear();}
    }
    @Test void riskGuardRecordsTheActualAcknowledgmentBeforeFinalPhase()throws Exception {
        var driver=mock(Connection.class);var statement=mock(PreparedStatement.class);
        when(driver.prepareStatement(riskFlush().sql())).thenReturn(statement);when(statement.execute()).thenReturn(false);
        when(statement.getLargeUpdateCount()).thenReturn(0L);
        when(driver.getAutoCommit()).thenReturn(false);when(driver.isReadOnly()).thenReturn(false);
        when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        var guard=new G014JdbcGuard(RISK,driver,List.of(riskFlush()));
        guard.captureBaseline();
        var source=new Object();var holder=new ConnectionHolder(guard.connection);holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source,holder);TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);guard.bind(source,holder);
        try {
            guard.flushDeferredConstraintsIfRegistered();guard.flushDeferredConstraintsIfRegistered();
            assertThat(guard.executions()).hasSize(1);
            assertThat(guard.executions().getFirst().commandAcknowledgment()).isEqualTo(TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE);
            assertThat(guard.executions().getFirst().affectedRows()).isZero();
            guard.beginFinal();verify(driver,times(1)).prepareStatement(riskFlush().sql());
            verify(driver,never()).prepareStatement(TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate().sql());
        }finally{TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear();}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=RegisteredEntityAction.class,names={"RISK_SCENARIO_CREATE","RISK_SCENARIO_UPDATE"})
    void realRiskCommandUsesTheSameProfileThroughGuardFrameGateAndCommit(RegisteredEntityAction action)throws Exception {
        var f=riskFixture(action);
        f.transaction.execute(status->{
            var frame=f.manager.currentFrame();
            assertThat(f.manager.profile()).isSameAs(RISK);
            assertThat(frame.profile).isSameAs(RISK);assertThat(frame.jdbc.profile()).isSameAs(RISK);
            assertThat(f.gate.profile()).isSameAs(RISK);
            f.gate.bindCommand(f.plan,f.scope);f.gate.finalizeCommand();
            assertThat(frame.jdbc.executions()).hasSize(1);
            assertThat(frame.jdbc.phase()).isEqualTo(G014JdbcGuard.Phase.SEALED);
            return null;
        });
        var order=inOrder(f.statement,f.driver);
        order.verify(f.statement).execute();order.verify(f.statement).getLargeUpdateCount();
        order.verify(f.driver).commit();order.verify(f.driver).setAutoCommit(true);order.verify(f.driver).close();
        verify(f.driver,times(1)).prepareStatement(riskFlush().sql());
        verify(f.driver,never()).prepareStatement(TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate().sql());
        verify(f.validator).requireStaged(eq(f.plan),argThat(executions->executions.size()==1
            && executions.getFirst().commandAcknowledgment()==TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE));
        assertThat(TransactionSynchronizationManager.hasResource(f.manager.commandDataSource())).isFalse();
    }
    @Test void riskActionCannotUseAnUndeclaredWrongOrNullValidatorIdentity(){
        var pool=mock(DataSource.class);var action=RegisteredEntityAction.RISK_SCENARIO_CREATE;
        var wrong=mock(TransactionSqlSealGate.ProfiledCommandValidator.class);when(wrong.profile()).thenReturn(ASSET);
        var absent=mock(TransactionSqlSealGate.ProfiledCommandValidator.class);
        for(var validator:List.of(mock(TransactionSqlSealGate.RegisteredCommandValidator.class),wrong,absent)){
            var manager=new G014JdbcTransactionManager(RISK,pool,List.of(riskFlush()));
            assertThatThrownBy(()->new TransactionSqlSealGate(RISK,manager,mock(HumanSessionAuthorityTransaction.class),mock(EntityActionAuthorization.class),Map.of(action,validator)))
                .isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(pool);
    }
    @Test void assetGateCannotAcceptRealRiskActionEvenWithAssetValidator(){
        var pool=mock(DataSource.class);
        assertThatThrownBy(()->new TransactionSqlSealGate(new G014JdbcTransactionManager(pool,List.of()),mock(HumanSessionAuthorityTransaction.class),mock(EntityActionAuthorization.class),
            Map.of(RegisteredEntityAction.RISK_SCENARIO_UPDATE,mock(TransactionSqlSealGate.RegisteredCommandValidator.class))))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pool);
    }
    @Test void changingTheIdentityOfAnAlreadyBoundRiskValidatorPoisonsBeforeFlush()throws Exception {
        var f=riskFixture(RegisteredEntityAction.RISK_SCENARIO_CREATE);
        assertThatThrownBy(()->f.transaction.execute(status->{
            var frame=f.manager.currentFrame();f.gate.bindCommand(f.plan,f.scope);
            when(f.validator.profile()).thenReturn(ASSET);
            assertThatThrownBy(f.gate::finalizeCommand).isInstanceOf(IllegalStateException.class);
            assertThat(frame.jdbc.poisoned()).isTrue();
            when(f.validator.profile()).thenReturn(RISK);
            assertThatThrownBy(f.gate::finalizeCommand).isInstanceOf(IllegalStateException.class);
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        verify(f.driver,never()).prepareStatement(anyString());verify(f.driver,never()).commit();verify(f.driver).rollback();
        verify(f.validator,never()).requireStaged(any(),any());
    }
    private record RiskFixture(Connection driver,PreparedStatement statement,G014JdbcTransactionManager manager,
            TransactionTemplate transaction,TransactionSqlSealGate gate,TransactionSqlSealGate.ProfiledCommandValidator validator,
            TransactionSqlSealGate.CommandPlan plan,HumanSessionAuthorityTransaction.WriteScope scope) {}
    private RiskFixture riskFixture(RegisteredEntityAction action)throws Exception {
        var pool=mock(DataSource.class);var driver=mock(Connection.class);var statement=mock(PreparedStatement.class);
        when(pool.getConnection()).thenReturn(driver);var jdbcAutoCommit=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(driver.getAutoCommit()).thenAnswer(call->jdbcAutoCommit.get());
        doAnswer(call->{jdbcAutoCommit.set(call.getArgument(0));return null;}).when(driver).setAutoCommit(anyBoolean());
        var jdbcReadOnly=new java.util.concurrent.atomic.AtomicBoolean(false);
        when(driver.isReadOnly()).thenAnswer(call->jdbcReadOnly.get());
        doAnswer(call->{jdbcReadOnly.set(call.getArgument(0));return null;}).when(driver).setReadOnly(anyBoolean());when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(driver.prepareStatement(riskFlush().sql())).thenReturn(statement);when(statement.execute()).thenReturn(false);
        when(statement.getLargeUpdateCount()).thenReturn(0L);
        var manager=new G014JdbcTransactionManager(RISK,pool,List.of(riskFlush()));
        var transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var authority=mock(HumanSessionAuthorityTransaction.class);var actions=mock(EntityActionAuthorization.class);
        var scopes=mock(EntityActionAuthorization.FinalActionScopes.class);
        when(scopes.observedAt()).thenReturn(java.time.Instant.parse("2026-11-07T00:00:00Z"));
        when(actions.requireFinalCommand(any(),any(),any())).thenReturn(scopes);
        when(authority.withGuarded(any(),any())).thenAnswer(invocation->{
            java.util.function.Function<HumanSessionAuthorityTransaction.GuardedBoundary,Object> work=invocation.getArgument(1);
            Object result=work.apply(mock(HumanSessionAuthorityTransaction.GuardedBoundary.class));
            var connection=manager.currentFrame().jdbc.connection;
            connection.getAutoCommit();connection.isReadOnly();connection.getTransactionIsolation();return result;
        });
        var validator=mock(TransactionSqlSealGate.ProfiledCommandValidator.class);when(validator.profile()).thenReturn(RISK);
        when(validator.requiredActions(any())).thenReturn(Set.of(action));
        var gate=new TransactionSqlSealGate(RISK,manager,authority,actions,Map.of(action,validator));
        var plan=mock(TransactionSqlSealGate.CommandPlan.class);when(plan.action()).thenReturn(action);
        when(plan.commandId()).thenReturn(UUID.randomUUID());when(plan.targetId()).thenReturn(UUID.randomUUID());
        var scope=mock(HumanSessionAuthorityTransaction.WriteScope.class);
        when(scope.guard()).thenReturn(HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND);
        return new RiskFixture(driver,statement,manager,transaction,gate,validator,plan,scope);
    }
}
