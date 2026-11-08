package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioServiceTest.Fixture;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual RiskScenarioService validator/gate/manager issuer. Recording JDBC is not native/PG acceptance. */
class G014RiskPublicationBridgeTest {
    @Test void nativeRouteRefusalPoisonsSourceBeforeAnyChildStatusEvenWhenSwallowed() throws Exception {
        Fixture fixture=new Fixture();
        var participation=new G014RiskNativeParticipation(fixture.manager(),fixture.publicationAlias(),catalog(true),
                java.util.Set.of(RiskScenarioDraftChangedEvent.class),getClass().getClassLoader());
        participation.rejectIfSourceActive();
        fixture.onPublication(event->{
            assertThatThrownBy(participation::rejectIfSourceActive).hasMessage("G014 publication boundary is unavailable");
            ((ConnectionHolder)TransactionSynchronizationManager.getResource(fixture.manager().commandDataSource())).resetRollbackOnly();
        });
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
        assertThat(fixture.publicationWrites()).isZero();
        assertThat(fixture.commits()).isZero();
        assertThat(fixture.rollbacks()).isEqualTo(1);
        participation.rejectIfSourceActive();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test void inactiveForeignManagerRouteRefusalCannotPoisonAnotherSource() throws Exception {
        Fixture fixture=new Fixture(),other=new Fixture();
        var participation=new G014RiskNativeParticipation(other.manager(),other.publicationAlias(),catalog(true),
                java.util.Set.of(RiskScenarioDraftChangedEvent.class),getClass().getClassLoader());
        fixture.onPublication(event->participation.rejectIfSourceActive());
        fixture.executeCreate();
        assertThat(fixture.commits()).isEqualTo(1);
        assertThat(fixture.publicationWrites()).isEqualTo(1);
        assertThat(other.commits()).isZero();
    }
    @Test void actualPrivateIssuerWindowBindsBothAliasesAndExpiresBeforeCommandCompletes() throws Exception {
        Fixture fixture=new Fixture();var escaped=new AtomicReference<G014JdbcTransactionManager.RiskPublicationWitness>();
        DataSource templateAlias=mock(DataSource.class);
        fixture.onPublication(event->{
            var witness=fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event);
            assertThat(witness).isNotNull();witness.requireLive();witness.requireConnection(fixture.guardedConnection());
            escaped.set(witness);
            Object holder=TransactionSynchronizationManager.getResource(fixture.manager().commandDataSource());
            assertThat(TransactionSynchronizationManager.getResource(fixture.publicationAlias())).isSameAs(holder);
            witness.bindTemplateAlias(templateAlias);
            assertThat(TransactionSynchronizationManager.getResource(templateAlias)).isSameAs(holder);
            witness.releaseTemplateAlias(templateAlias);
            assertThat(TransactionSynchronizationManager.hasResource(templateAlias)).isFalse();
        });
        fixture.executeCreate();
        assertThat(fixture.commits()).isEqualTo(1);assertThat(fixture.publicationWrites()).isEqualTo(1);
        assertThat(fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),new Object())).isNull();
        assertThatThrownBy(escaped.get()::requireLive).hasMessage("G014 publication boundary is unavailable");
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();verifyNoInteractions(templateAlias);
    }
    @Test void managerCannotPublishOutsideActualSourcePlanWindow() throws Exception {
        Fixture fixture=new Fixture();
        assertThatThrownBy(()->fixture.manager().publishRiskEvent(new Object())).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.commits()).isZero();assertThat(fixture.publicationWrites()).isZero();
    }
    @Test void fixedConfigurationCannotBeRepeatedEvenWithSameArguments() throws Exception {
        Fixture fixture=new Fixture();
        assertThatThrownBy(()->fixture.configurePublication(fixture.publicationAlias())).hasMessage("G014 publication configuration is unavailable");
        fixture.executeCreate();assertThat(fixture.commits()).isEqualTo(1);
    }
    @Test void unconfiguredActualServiceRefusesRatherThanLazilyBindingPublisher() throws Exception {
        Fixture fixture=new Fixture(false);
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
        assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
        assertThatThrownBy(()->fixture.configurePublication(mock(DataSource.class))).hasMessage("G014 publication configuration is unavailable");
    }
    @Test void preexistingForeignPublicationAliasIsNeverAdoptedOrRemoved() throws Exception {
        Fixture fixture=new Fixture(false);DataSource alias=mock(DataSource.class);fixture.configurePublication(alias);
        ConnectionHolder foreign=new ConnectionHolder(mock(Connection.class));TransactionSynchronizationManager.bindResource(alias,foreign);
        try {
            assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
            assertThat(TransactionSynchronizationManager.getResource(alias)).isSameAs(foreign);
            assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
        }finally{assertThat(TransactionSynchronizationManager.unbindResource(alias)).isSameAs(foreign);}
        verifyNoInteractions(alias);
    }
    @Test void alteredEventReferenceCannotUseEvenAnEqualProductionTuple() throws Exception {
        Fixture fixture=new Fixture();
        fixture.onPublication(value->{
            var event=(RiskScenarioDraftChangedEvent)value;
            var copy=new RiskScenarioDraftChangedEvent(event.tenantId(),event.commandId(),event.resourceType(),event.resourceId(),event.actionCode(),event.version(),event.occurredAt(),event.traceId(),event.changeKind());
            assertThat(copy).isEqualTo(event).isNotSameAs(event);
            assertThatThrownBy(()->fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),copy)).isInstanceOf(IllegalStateException.class);
            ((ConnectionHolder)TransactionSynchronizationManager.getResource(fixture.manager().commandDataSource())).resetRollbackOnly();
        });
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
        assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
    }
    @Test void wrongExpectedAliasPoisonsOriginalOwnerEvenIfRefusalIsSwallowed() throws Exception {
        Fixture fixture=new Fixture();
        fixture.onPublication(event->{assertThatThrownBy(()->fixture.manager().currentRiskPublicationWitness(mock(DataSource.class),event)).isInstanceOf(IllegalStateException.class);});
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
        assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();
    }
    @Test void wrongManagerHasNoWitnessAndCannotBorrowOrPoisonAnotherManager() throws Exception {
        Fixture fixture=new Fixture(),other=new Fixture();
        fixture.onPublication(event->assertThat(other.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event)).isNull());
        fixture.executeCreate();assertThat(fixture.commits()).isEqualTo(1);assertThat(other.commits()).isZero();
    }
    @Test void wrongThreadEscapedWitnessPoisonsItsOriginalLifetime() throws Exception {
        Fixture fixture=new Fixture();
        fixture.onPublication(event->{
            var witness=fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event);
            var error=new AtomicReference<Throwable>();Thread thread=new Thread(()->{try{witness.requireLive();}catch(Throwable refusal){error.set(refusal);}});
            thread.start();try{thread.join(5000);}catch(InterruptedException failure){throw new AssertionError(failure);}
            assertThat(thread.isAlive()).isFalse();assertThat(error.get()).isInstanceOf(IllegalStateException.class);
            ((ConnectionHolder)TransactionSynchronizationManager.getResource(fixture.manager().commandDataSource())).resetRollbackOnly();
        });
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
        assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
    }
    @Test void expiredWitnessDoesNotPoisonNextIndependentCommand() throws Exception {
        Fixture first=new Fixture();var escaped=new AtomicReference<G014JdbcTransactionManager.RiskPublicationWitness>();
        first.onPublication(event->escaped.set(first.manager().currentRiskPublicationWitness(first.publicationAlias(),event)));first.executeCreate();
        Fixture next=new Fixture();next.onPublication(event->{assertThatThrownBy(escaped.get()::requireLive).isInstanceOf(IllegalStateException.class);next.manager().currentRiskPublicationWitness(next.publicationAlias(),event).requireLive();});
        next.executeCreate();assertThat(first.commits()).isEqualTo(1);assertThat(next.commits()).isEqualTo(1);
    }
    @Test void missingOrReplacedPublicationAliasFailsAndPreservesForeignResource() throws Exception {
        for(boolean replace:new boolean[]{false,true}){
            Fixture fixture=new Fixture();ConnectionHolder foreign=new ConnectionHolder(mock(Connection.class));
            fixture.onPublication(event->{TransactionSynchronizationManager.unbindResource(fixture.publicationAlias());if(replace)TransactionSynchronizationManager.bindResource(fixture.publicationAlias(),foreign);});
            try{
                assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);assertThat(fixture.commits()).isZero();
                if(replace)assertThat(TransactionSynchronizationManager.getResource(fixture.publicationAlias())).isSameAs(foreign);
                else assertThat(TransactionSynchronizationManager.hasResource(fixture.publicationAlias())).isFalse();
            }finally{if(replace)assertThat(TransactionSynchronizationManager.unbindResource(fixture.publicationAlias())).isSameAs(foreign);}
        }
    }
    @Test void foreignTemplateAliasIsPreservedAndOriginalOwnerRollsBack() throws Exception {
        Fixture fixture=new Fixture();DataSource alias=mock(DataSource.class);ConnectionHolder foreign=new ConnectionHolder(mock(Connection.class));
        fixture.onPublication(event->{var witness=fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event);witness.bindTemplateAlias(alias);TransactionSynchronizationManager.unbindResource(alias);TransactionSynchronizationManager.bindResource(alias,foreign);});
        try{assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);assertThat(fixture.commits()).isZero();assertThat(TransactionSynchronizationManager.getResource(alias)).isSameAs(foreign);}
        finally{assertThat(TransactionSynchronizationManager.unbindResource(alias)).isSameAs(foreign);}
    }
    @Test void nestedPublicationIsRejectedBeforeAnotherNativeCallback() throws Exception {
        Fixture fixture=new Fixture();fixture.onPublication(event->{assertThatThrownBy(()->fixture.manager().publishRiskEvent(event)).isInstanceOf(IllegalStateException.class);});
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
        assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();
    }
    @Test void suspensionFirstIdentityMustRemainPinnedDuringThePublication() throws Exception {
        Fixture fixture=new Fixture();fixture.onPublication(event->{
            var witness=fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event);
            TransactionSynchronization first=new TransactionSynchronization(){@Override public int getOrder(){return Integer.MIN_VALUE;}};
            // Remove the real first witness without calling/suspending any callback.
            var originals=TransactionSynchronizationManager.getSynchronizations();TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.registerSynchronization(first);originals.forEach(TransactionSynchronizationManager::registerSynchronization);
            assertThatThrownBy(witness::requireLive).isInstanceOf(IllegalStateException.class);
        });
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();
    }
    @Test void replacedCommandHolderIsPreservedWhileOwnedPublicationAliasIsRemoved() throws Exception {
        Fixture fixture=new Fixture();ConnectionHolder foreign=new ConnectionHolder(mock(Connection.class));
        DataSource commands=fixture.manager().commandDataSource();
        fixture.onPublication(event->{TransactionSynchronizationManager.unbindResource(commands);TransactionSynchronizationManager.bindResource(commands,foreign);});
        try{
            assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
            assertThat(TransactionSynchronizationManager.getResource(commands)).isSameAs(foreign);
            assertThat(TransactionSynchronizationManager.hasResource(fixture.publicationAlias())).isFalse();
            assertThat(fixture.commits()).isZero();
        }finally{assertThat(TransactionSynchronizationManager.unbindResource(commands)).isSameAs(foreign);}
    }
    @Test void readOnlyInactiveAndMissingSynchronizationAreStickyRefusals() throws Exception {
        for(String fault:java.util.List.of("readOnly","inactive","synchronization","missingHolder","unsynchronized","rollbackOnly")){
            Fixture fixture=new Fixture();fixture.onPublication(event->{
                var witness=fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event);
                var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(fixture.manager().commandDataSource());
                var synchronizations=TransactionSynchronizationManager.getSynchronizations();
                switch(fault){
                    case "readOnly"->TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
                    case "inactive"->TransactionSynchronizationManager.setActualTransactionActive(false);
                    case "synchronization"->TransactionSynchronizationManager.clearSynchronization();
                    case "missingHolder"->TransactionSynchronizationManager.unbindResource(fixture.manager().commandDataSource());
                    case "unsynchronized"->holder.setSynchronizedWithTransaction(false);
                    case "rollbackOnly"->holder.setRollbackOnly();
                }
                assertThatThrownBy(witness::requireLive).isInstanceOf(IllegalStateException.class);
                // Restore only test-mutated bookkeeping; the real guard poison must survive this attempt.
                switch(fault){
                    case "readOnly"->TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
                    case "inactive"->TransactionSynchronizationManager.setActualTransactionActive(true);
                    case "synchronization"->{TransactionSynchronizationManager.initSynchronization();synchronizations.forEach(TransactionSynchronizationManager::registerSynchronization);}
                    case "missingHolder"->TransactionSynchronizationManager.bindResource(fixture.manager().commandDataSource(),holder);
                    case "unsynchronized"->holder.setSynchronizedWithTransaction(true);
                    case "rollbackOnly"->holder.resetRollbackOnly();
                }
            });
            assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
            assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
        }
    }
    @Test void publisherRuntimeAndErrorStaySafeAndPreserveForeignAlias() throws Exception {
        for(var level:java.util.List.of(ch.qos.logback.classic.Level.INFO,ch.qos.logback.classic.Level.DEBUG))for(boolean error:new boolean[]{false,true}){
            Fixture fixture=new Fixture();ConnectionHolder foreign=new ConnectionHolder(mock(Connection.class));
            fixture.onPublication(event->{
                TransactionSynchronizationManager.unbindResource(fixture.publicationAlias());TransactionSynchronizationManager.bindResource(fixture.publicationAlias(),foreign);
                if(error)throw new AssertionError("PRIVATE_SOURCE_PUBLICATION_SENTINEL");
                throw new IllegalStateException("PRIVATE_SOURCE_PUBLICATION_SENTINEL",new java.sql.SQLException("PRIVATE_SOURCE_PUBLICATION_SENTINEL"));
            });
            try{
                sourceDiagnostics(level,()->assertThatThrownBy(fixture::executeCreate).hasMessage("G014 publication boundary is unavailable")
                        .satisfies(failure->{assertThat(failure.getCause()).isNull();assertThat(failure.getSuppressed()).isEmpty();}));
                assertThat(TransactionSynchronizationManager.getResource(fixture.publicationAlias())).isSameAs(foreign);
                assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
            }finally{assertThat(TransactionSynchronizationManager.unbindResource(fixture.publicationAlias())).isSameAs(foreign);}
        }
    }
    @Test void firstSynchronizationPreflightRejectsBeforeAliasOrPublisherCallback() throws Exception {
        Fixture fixture=new Fixture();fixture.beforePublication(event->{
            assertThat(TransactionSynchronizationManager.hasResource(fixture.publicationAlias())).isFalse();
            var originals=TransactionSynchronizationManager.getSynchronizations();TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public int getOrder(){return Integer.MIN_VALUE;}});
            originals.forEach(TransactionSynchronizationManager::registerSynchronization);
        });
        fixture.onPublication(event->{throw new AssertionError("Native callback must not run");});
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
        assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.hasResource(fixture.publicationAlias())).isFalse();
    }
    @Test void commandSourceCannotBeConfiguredAsPublicationAlias() throws Exception {
        Fixture fixture=new Fixture(false);
        assertThatThrownBy(()->fixture.configurePublication(fixture.manager().commandDataSource())).hasMessage("G014 publication configuration is unavailable");
        assertThat(fixture.commits()).isZero();assertThat(fixture.publicationWrites()).isZero();
    }
    @Test void nativeStoreClaimIsOneShotAndCannotBeResetByTheCaller() throws Exception {
        Fixture fixture=new Fixture();fixture.onPublication(event->{
            var witness=fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event);witness.claimNativePublication();
            assertThatThrownBy(witness::claimNativePublication).isInstanceOf(IllegalStateException.class);
        });
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
        assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
    }
    @Test void publicationExitExpiresWitnessWhileOuterCommandIsStillActive() throws Exception {
        Fixture fixture=new Fixture();var escaped=new AtomicReference<G014JdbcTransactionManager.RiskPublicationWitness>();
        fixture.onPublication(event->escaped.set(fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event)));
        fixture.afterPublication(()->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.hasResource(fixture.manager().commandDataSource())).isTrue();
            assertThat(TransactionSynchronizationManager.hasResource(fixture.publicationAlias())).isFalse();
            assertThatThrownBy(escaped.get()::requireLive).isInstanceOf(IllegalStateException.class);
        });
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
        assertThat(fixture.publicationWrites()).isEqualTo(1);assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
    }
    @Test void sourceTemplateUsesActualIssuerAndGuardForBothNativeSchemaQualifiers() throws Exception {
        for(boolean qualified:new boolean[]{false,true}){
            Fixture fixture=new Fixture(true,qualified);RiskPublicationSqlCatalog catalog=catalog(qualified);
            var access=new G014RiskPublicationJdbcAccess(catalog);
            var template=access.template(catalog,java.util.Set.of(RiskScenarioDraftChangedEvent.class),getClass().getClassLoader());
            fixture.executePublicationWith(event->{
                var witness=fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event);
                Object original=TransactionSynchronizationManager.getResource(fixture.manager().commandDataSource());
                var before=controls(fixture);long borrows=fixture.poolBorrows();
                int affected=access.participate(witness,()->insert(template,catalog,fixture,(RiskScenarioDraftChangedEvent)event));
                assertThat(affected).isEqualTo(1);assertThat(controls(fixture)).isEqualTo(before);assertThat(fixture.poolBorrows()).isEqualTo(borrows);
                assertThat(TransactionSynchronizationManager.getResource(fixture.manager().commandDataSource())).isSameAs(original);
                assertThat(TransactionSynchronizationManager.getResource(fixture.publicationAlias())).isSameAs(original);
                assertThat(TransactionSynchronizationManager.hasResource(template.getDataSource())).isFalse();
                witness.requireLive();
            });
            fixture.executeCreate();assertThat(fixture.publicationWrites()).isEqualTo(1);assertThat(fixture.commits()).isEqualTo(1);assertThat(fixture.rollbacks()).isZero();
            assertThat(fixture.poolBorrows()).isEqualTo(1);assertThat(fixture.physicalCalls("close")).isEqualTo(1);assertThat(fixture.warningReads()).isEqualTo(1);
        }
    }
    @Test void swallowedTemplateAliasUnwrapRefusalPreventsSourceCommit() throws Exception {
        Fixture fixture=new Fixture();var catalog=catalog(true);var access=new G014RiskPublicationJdbcAccess(catalog);
        var template=access.template(catalog,java.util.Set.of(RiskScenarioDraftChangedEvent.class),getClass().getClassLoader());
        fixture.executePublicationWith(event->{
            var witness=fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event);
            access.participate(witness,()->{
                assertThatThrownBy(()->template.getDataSource().unwrap(DataSource.class))
                        .isInstanceOf(java.sql.SQLException.class).hasNoCause();
                return insert(template,catalog,fixture,(RiskScenarioDraftChangedEvent)event);
            });
        });
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
        assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
    }
    @Test void secondParticipationWithSameRealWitnessPoisonsBeforeASecondInsert() throws Exception {
        Fixture fixture=new Fixture();var catalog=catalog(true);var access=new G014RiskPublicationJdbcAccess(catalog);
        var template=access.template(catalog,java.util.Set.of(RiskScenarioDraftChangedEvent.class),getClass().getClassLoader());
        fixture.executePublicationWith(event->{
            var witness=fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event);
            access.participate(witness,()->insert(template,catalog,fixture,(RiskScenarioDraftChangedEvent)event));
            assertThatThrownBy(()->access.participate(witness,()->{throw new AssertionError("Must not run");})).isInstanceOf(IllegalStateException.class);
        });
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);assertThat(fixture.publicationWrites()).isEqualTo(1);assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
    }
    @Test void expiredRealWitnessCannotReenterJdbcParticipation() throws Exception {
        Fixture fixture=new Fixture();var catalog=catalog(true);var access=new G014RiskPublicationJdbcAccess(catalog);
        var template=access.template(catalog,java.util.Set.of(RiskScenarioDraftChangedEvent.class),getClass().getClassLoader());
        var escaped=new AtomicReference<G014JdbcTransactionManager.RiskPublicationWitness>();
        fixture.executePublicationWith(event->{var witness=fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event);escaped.set(witness);access.participate(witness,()->insert(template,catalog,fixture,(RiskScenarioDraftChangedEvent)event));});
        fixture.executeCreate();var controls=controls(fixture);long borrows=fixture.poolBorrows();
        assertThatThrownBy(()->access.participate(escaped.get(),()->{throw new AssertionError("Must not run");})).isInstanceOf(IllegalStateException.class);
        assertThat(controls(fixture)).isEqualTo(controls);assertThat(fixture.poolBorrows()).isEqualTo(borrows);
    }
    @Test void wrongThreadRealWitnessCannotAcquireAnAccessAliasOrExecuteSql() throws Exception {
        Fixture fixture=new Fixture();var access=new G014RiskPublicationJdbcAccess(catalog(true));
        fixture.executePublicationWith(event->{
            var witness=fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event);var error=new AtomicReference<Throwable>();
            Thread thread=new Thread(()->{try{access.participate(witness,()->{throw new AssertionError("Must not run");});}catch(Throwable failure){error.set(failure);}});
            thread.start();try{thread.join(5000);}catch(InterruptedException failure){throw new AssertionError(failure);}
            assertThat(thread.isAlive()).isFalse();assertThat(error.get()).isInstanceOf(IllegalStateException.class);
        });
        assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
    }
    @Test void nativeCreateParticipantUsesSameHolderAndPerformsNoLifecycleControl() throws Exception {
        Fixture fixture=new Fixture();var catalog=catalog(true);
        var participation=new G014RiskNativeParticipation(fixture.manager(),fixture.publicationAlias(),catalog,java.util.Set.of(RiskScenarioDraftChangedEvent.class),getClass().getClassLoader());
        fixture.executePublicationWith(event->{
            var controls=controls(fixture);long borrows=fixture.poolBorrows();Object holder=TransactionSynchronizationManager.getResource(fixture.manager().commandDataSource());
            int affected=participation.store(event,()->{
                var status=participation.beginCreate(nativeCreateDefinition());
                assertThat(status.hasTransaction()).isTrue();assertThat(status.isNewTransaction()).isFalse();
                assertThat(TransactionSynchronizationManager.getResource(fixture.publicationAlias())).isSameAs(holder);
                int result=insert(participation.template(),catalog,fixture,(RiskScenarioDraftChangedEvent)event);
                participation.commit(status);assertThat(status.isCompleted()).isTrue();return result;
            });
            assertThat(affected).isEqualTo(1);assertThat(controls(fixture)).isEqualTo(controls);assertThat(fixture.poolBorrows()).isEqualTo(borrows);
        });
        fixture.executeCreate();assertThat(fixture.commits()).isEqualTo(1);assertThat(fixture.rollbacks()).isZero();assertThat(fixture.publicationWrites()).isEqualTo(1);
    }
    @Test void nativeCreateRefusesRequiresNewAndDefinitionDriftBeforeInsert() throws Exception {
        for(String fault:java.util.List.of("requiresNew","method","readOnly","isolation","timeout")){
            Fixture fixture=new Fixture();var catalog=catalog(true);
            var participation=new G014RiskNativeParticipation(fixture.manager(),fixture.publicationAlias(),catalog,java.util.Set.of(RiskScenarioDraftChangedEvent.class),getClass().getClassLoader());
            fixture.executePublicationWith(event->participation.store(event,()->{
                var definition=nativeCreateDefinition();
                switch(fault){
                    case "requiresNew"->definition.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    case "method"->definition.setName("arbitrary.create");
                    case "readOnly"->definition.setReadOnly(true);
                    case "isolation"->definition.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
                    case "timeout"->definition.setTimeout(1);
                }
                return participation.beginCreate(definition);
            }));
            assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
        }
    }
    @Test void completedNativeStatusCannotMutateOrCommitAgainWhileSourceRemainsActive() throws Exception {
        for(boolean mutate:new boolean[]{false,true}){
            Fixture fixture=new Fixture();var catalog=catalog(true);
            var participation=new G014RiskNativeParticipation(fixture.manager(),fixture.publicationAlias(),catalog,java.util.Set.of(RiskScenarioDraftChangedEvent.class),getClass().getClassLoader());
            fixture.executePublicationWith(event->participation.store(event,()->{
                var status=participation.beginCreate(nativeCreateDefinition());insert(participation.template(),catalog,fixture,(RiskScenarioDraftChangedEvent)event);participation.commit(status);
                if(mutate)assertThatThrownBy(status::setRollbackOnly).isInstanceOf(IllegalStateException.class);
                else assertThatThrownBy(()->participation.commit(status)).isInstanceOf(IllegalStateException.class);
                return 1;
            }));
            assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);assertThat(fixture.publicationWrites()).isEqualTo(1);assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
        }
    }
    @Test void expiredNativeStatusCannotAffectTheNextIndependentCommand() throws Exception {
        Fixture fixture=new Fixture();var catalog=catalog(true);
        var participation=new G014RiskNativeParticipation(fixture.manager(),fixture.publicationAlias(),catalog,java.util.Set.of(RiskScenarioDraftChangedEvent.class),getClass().getClassLoader());
        var escaped=new AtomicReference<org.springframework.transaction.TransactionStatus>();
        fixture.executePublicationWith(event->participation.store(event,()->{var status=participation.beginCreate(nativeCreateDefinition());escaped.set(status);int result=insert(participation.template(),catalog,fixture,(RiskScenarioDraftChangedEvent)event);participation.commit(status);return result;}));
        fixture.executeCreate();var controls=controls(fixture);
        Fixture next=new Fixture();next.onPublication(event->{assertThatThrownBy(()->participation.commit(escaped.get())).isInstanceOf(IllegalStateException.class);});
        next.executeCreate();assertThat(fixture.commits()).isEqualTo(1);assertThat(next.commits()).isEqualTo(1);assertThat(controls(fixture)).isEqualTo(controls);
    }
    @Test void nonnullOrThrowingPublicationWarningRollsBackWithoutLeakingDriverDiagnostic() throws Exception {
        String secret="PRIVATE_SOURCE_PUBLICATION_SENTINEL";
        for(var level:java.util.List.of(ch.qos.logback.classic.Level.INFO,ch.qos.logback.classic.Level.DEBUG)){
            for(String fault:java.util.List.of("warning","sql","runtime","error")){
                Fixture fixture=new Fixture();var catalog=catalog(true);var access=new G014RiskPublicationJdbcAccess(catalog);
                var template=access.template(catalog,java.util.Set.of(RiskScenarioDraftChangedEvent.class),getClass().getClassLoader());
                if(fault.equals("warning"))fixture.warning(new java.sql.SQLWarning(secret,"XX000",new IllegalArgumentException(secret)));
                else fixture.warningFailure(switch(fault){case "sql"->new java.sql.SQLException(secret,"XX000",new IllegalStateException(secret));case "runtime"->new IllegalStateException(secret,new java.sql.SQLException(secret));default->new AssertionError(secret,new IllegalStateException(secret));});
                fixture.executePublicationWith(event->access.participate(fixture.manager().currentRiskPublicationWitness(fixture.publicationAlias(),event),()->insert(template,catalog,fixture,(RiskScenarioDraftChangedEvent)event)));
                sourceDiagnostics(level,()->assertThatThrownBy(fixture::executeCreate).hasMessage("G014 publication boundary is unavailable").satisfies(failure->{assertThat(failure.getCause()).isNull();assertThat(failure.getSuppressed()).isEmpty();}));
                assertThat(fixture.warningReads()).isEqualTo(1);assertThat(fixture.publicationWrites()).isEqualTo(1);
                assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            }
        }
    }
    private static org.springframework.transaction.support.DefaultTransactionDefinition nativeCreateDefinition(){
        var definition=new org.springframework.transaction.support.DefaultTransactionDefinition();definition.setName("org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository.create");return definition;
    }
    private static void sourceDiagnostics(ch.qos.logback.classic.Level level,Runnable action){
        var loggers=java.util.List.of((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME),
                (ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("com.mightyoung.softwarefactory.support.G014JdbcTransactionManager"),
                (ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.springframework.transaction.support.TransactionTemplate"));
        var previous=loggers.stream().map(ch.qos.logback.classic.Logger::getLevel).toList();
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();events.start();loggers.getFirst().addAppender(events);loggers.forEach(logger->logger.setLevel(level));
        var stdout=new java.io.ByteArrayOutputStream();var stderr=new java.io.ByteArrayOutputStream();
        java.io.PrintStream oldOut=System.out,oldErr=System.err;
        var out=new java.io.PrintStream(stdout,true,java.nio.charset.StandardCharsets.UTF_8);var err=new java.io.PrintStream(stderr,true,java.nio.charset.StandardCharsets.UTF_8);
        System.setOut(out);System.setErr(err);
        try{action.run();}
        finally{
            System.setOut(oldOut);System.setErr(oldErr);out.close();err.close();loggers.getFirst().detachAppender(events);events.stop();
            for(int index=0;index<loggers.size();index++)loggers.get(index).setLevel(previous.get(index));
            var rendered=new StringBuilder();for(var event:events.list){rendered.append(event.getFormattedMessage());if(event.getThrowableProxy()!=null)rendered.append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()));}
            assertThat(rendered.toString()).doesNotContain("PRIVATE_SOURCE_PUBLICATION_SENTINEL");
            assertThat(stdout.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("PRIVATE_SOURCE_PUBLICATION_SENTINEL");
            assertThat(stderr.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("PRIVATE_SOURCE_PUBLICATION_SENTINEL");
        }
    }
    private static RiskPublicationSqlCatalog catalog(boolean qualified) throws Exception {
        var value=new AtomicReference<RiskPublicationSqlCatalog>();var context=RiskPublicationSqlCatalogTest.fixture(true);
        if(qualified)context=context.withPropertyValues("spring.modulith.events.jdbc.schema=public");
        context.run(application->value.set(new RiskPublicationSqlCatalog(application.getBean(org.springframework.modulith.events.jdbc.JdbcRepositorySettings.class),application.getEnvironment())));
        return value.get();
    }
    private static int insert(RiskPublicationJdbcTemplate template,RiskPublicationSqlCatalog catalog,Fixture fixture,RiskScenarioDraftChangedEvent event){
        return template.update(catalog.insertOperation().sql(),java.util.UUID.randomUUID(),RiskScenarioDraftChangedEvent.class.getName(),"risk-scenario-draft-change",java.sql.Timestamp.from(event.occurredAt()),fixture.serializeEvent(event));
    }
    private static java.util.Map<String,Long> controls(Fixture fixture){
        var result=new java.util.LinkedHashMap<String,Long>();for(String name:java.util.List.of("commit","rollback","close","setAutoCommit","setReadOnly","setTransactionIsolation"))result.put(name,fixture.physicalCalls(name));return result;
    }
    @Test void invalidEntryFlagsRefuseBeforeTheFixedNativePublisherRuns() throws Exception {
        for(String fault:java.util.List.of("readOnly","inactive","rollbackOnly")){
            Fixture fixture=new Fixture();fixture.beforePublication(event->{
                switch(fault){
                    case "readOnly"->TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
                    case "inactive"->TransactionSynchronizationManager.setActualTransactionActive(false);
                    case "rollbackOnly"->((ConnectionHolder)TransactionSynchronizationManager.getResource(fixture.manager().commandDataSource())).setRollbackOnly();
                }
            });
            fixture.onPublication(event->{throw new AssertionError("Native callback must not run");});
            assertThatThrownBy(fixture::executeCreate).isInstanceOf(RuntimeException.class);
            assertThat(fixture.publicationWrites()).isZero();assertThat(fixture.commits()).isZero();assertThat(fixture.rollbacks()).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.hasResource(fixture.publicationAlias())).isFalse();
        }
    }
    @Test void configurationCannotOccurInsideAnotherTransactionOrSynchronizationScope() throws Exception {
        Fixture fixture=new Fixture(false);TransactionSynchronizationManager.initSynchronization();
        try{assertThatThrownBy(()->fixture.configurePublication(mock(DataSource.class))).hasMessage("G014 publication configuration is unavailable");}
        finally{TransactionSynchronizationManager.clearSynchronization();}
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->fixture.configurePublication(mock(DataSource.class))).hasMessage("G014 publication configuration is unavailable");}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        fixture.configurePublication(mock(DataSource.class));fixture.executeCreate();assertThat(fixture.commits()).isEqualTo(1);
    }
    @Test void witnessConstructionAndResourcesAreNotPublicCapabilities(){
        var type=G014JdbcTransactionManager.RiskPublicationWitness.class;
        assertThat(Modifier.isPublic(type.getModifiers())).isFalse();
        assertThat(type.getDeclaredConstructors()).allSatisfy(constructor->assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
        assertThat(java.util.Arrays.stream(type.getDeclaredMethods()).filter(method->!method.getName().equals("toString")).toList()).noneSatisfy(method->assertThat(Modifier.isPublic(method.getModifiers())).isTrue());
        assertThat(type.getDeclaredMethods()).noneSatisfy(method->assertThat(method.getReturnType()).isIn(Connection.class,ConnectionHolder.class,DataSource.class));
    }
}
