package com.mightyoung.softwarefactory.dualprevention.risk;

import java.sql.Connection;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Resource lifetime tests only; actual persistent Modulith/async execution needs the full context fixture. */
class ModulithRiskScenarioEventPublisherTest {
    private final G014JdbcTransactionManager manager=mock(G014JdbcTransactionManager.class);
    private final DataSource commands=mock(DataSource.class);
    private final DataSource publications=mock(DataSource.class);
    private final Connection connection=mock(Connection.class);
    private final ConnectionHolder holder=new ConnectionHolder(connection);
    private final RiskScenarioDraftChangedEvent event=new RiskScenarioDraftChangedEvent(UUID.randomUUID(),UUID.randomUUID(),
            "risk_scenario",UUID.randomUUID(),"risk_scenario.create",1,Instant.parse("2026-11-04T00:00:00Z"),UUID.randomUUID(),"CREATE");

    @BeforeEach void begin() {
        when(manager.commandDataSource()).thenReturn(commands);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(commands,holder);
    }
    @AfterEach void cleanup() {
        TransactionSynchronizationManager.unbindResourceIfPossible(commands);
        TransactionSynchronizationManager.unbindResourceIfPossible(publications);
        TransactionSynchronizationManager.clear();
    }
    @Test void aliasExistsOnlyInsideActualPublisherCall()throws Exception {
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,published->{
            assertSame(event,published);
            assertSame(holder,TransactionSynchronizationManager.getResource(commands));
            assertSame(holder,TransactionSynchronizationManager.getResource(publications));
            assertSame(connection,((ConnectionHolder)TransactionSynchronizationManager.getResource(publications)).getConnection());
        });
        publisher.publish(event);
        verify(manager).requirePublicationSynchronizationFirst();
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
        assertSame(holder,TransactionSynchronizationManager.getResource(commands));
        assertFalse(holder.isRollbackOnly());
        verifyNoInteractions(commands,publications,connection);
    }
    @Test void publishingFailureRemovesAliasAndMarksOwnerRollbackOnly() {
        var expected=new IllegalStateException("publication refused");
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->{throw expected;});
        assertSame(expected,assertThrows(IllegalStateException.class,()->publisher.publish(event)));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
        assertTrue(holder.isRollbackOnly());
    }
    @Test void preexistingOriginalResourceIsNeverOverwritten() {
        var existing=new ConnectionHolder(mock(Connection.class));
        TransactionSynchronizationManager.bindResource(publications,existing);
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->fail("must not publish"));
        assertThrows(RiskScenarioContracts.Failure.class,()->publisher.publish(event));
        assertSame(existing,TransactionSynchronizationManager.getResource(publications));
        assertTrue(holder.isRollbackOnly());
    }
    @Test void replacedAliasFailsClosedWithoutRemovingAnotherOwnersResource() {
        var replacement=new ConnectionHolder(mock(Connection.class));
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->{
            TransactionSynchronizationManager.unbindResource(publications);
            TransactionSynchronizationManager.bindResource(publications,replacement);
        });
        assertThrows(RiskScenarioContracts.Failure.class,()->publisher.publish(event));
        assertSame(replacement,TransactionSynchronizationManager.getResource(publications));
        assertTrue(holder.isRollbackOnly());
    }
    @Test void replacedCommandOwnerStillCleansTheOwnedAlias() {
        var replacement=new ConnectionHolder(mock(Connection.class));
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->{
            TransactionSynchronizationManager.unbindResource(commands);
            TransactionSynchronizationManager.bindResource(commands,replacement);
        });
        assertThrows(RiskScenarioContracts.Failure.class,()->publisher.publish(event));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
        assertSame(replacement,TransactionSynchronizationManager.getResource(commands));
        assertTrue(holder.isRollbackOnly());
    }
    @Test void suspensionPreflightRefusesBeforeAnyAliasOrPublication() {
        doThrow(new IllegalStateException("unsafe synchronization order")).when(manager).requirePublicationSynchronizationFirst();
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->fail("must not publish"));
        assertThrows(IllegalStateException.class,()->publisher.publish(event));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
    }
    @Test void readOnlyOrInactiveBoundaryCannotPublish() {
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->fail("must not publish"));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(RiskScenarioContracts.Failure.class,()->publisher.publish(event));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(RiskScenarioContracts.Failure.class,()->publisher.publish(event));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
    }
    @Test void missingHolderOrSynchronizationCannotPublish() {
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->fail("must not publish"));
        TransactionSynchronizationManager.unbindResource(commands);
        assertEquals(503,assertThrows(RiskScenarioContracts.Failure.class,()->publisher.publish(event)).status());
        TransactionSynchronizationManager.bindResource(commands,holder);
        holder.setSynchronizedWithTransaction(false);
        assertThrows(RiskScenarioContracts.Failure.class,()->publisher.publish(event));
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.clearSynchronization();
        assertThrows(RiskScenarioContracts.Failure.class,()->publisher.publish(event));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
        verify(manager,never()).requirePublicationSynchronizationFirst();
    }
    @Test void rollbackOnlyOwnerCannotPublish() {
        holder.setRollbackOnly();
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->fail("must not publish"));
        assertThrows(RiskScenarioContracts.Failure.class,()->publisher.publish(event));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
    }
    @Test void originalFailureSurvivesAliasTamperingAndPreservesOtherOwner() {
        var expected=new IllegalStateException("publication refused");
        var replacement=new ConnectionHolder(mock(Connection.class));
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->{
            TransactionSynchronizationManager.unbindResource(publications);
            TransactionSynchronizationManager.bindResource(publications,replacement);
            throw expected;
        });
        assertSame(expected,assertThrows(IllegalStateException.class,()->publisher.publish(event)));
        assertSame(replacement,TransactionSynchronizationManager.getResource(publications));
        assertTrue(holder.isRollbackOnly());
        assertEquals(1,expected.getSuppressed().length);
        assertInstanceOf(RiskScenarioContracts.Failure.class,expected.getSuppressed()[0]);
    }
    @Test void errorPoisonsAndCleansAliasWithoutReplacingOriginal() {
        var expected=new AssertionError("listener failed");
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->{throw expected;});
        assertSame(expected,assertThrows(AssertionError.class,()->publisher.publish(event)));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
        assertSame(holder,TransactionSynchronizationManager.getResource(commands));
        assertTrue(holder.isRollbackOnly());
    }
    @Test void missingAliasFailsClosedWithoutLeakingAnAlias() {
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->
                TransactionSynchronizationManager.unbindResource(publications));
        assertThrows(RiskScenarioContracts.Failure.class,()->publisher.publish(event));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
        assertTrue(holder.isRollbackOnly());
    }
    @Test void identicalSourcesAndNullEventAreRejectedBeforePublishing() {
        assertThrows(IllegalArgumentException.class,()->new ModulithRiskScenarioEventPublisher(manager,commands,event->fail("must not publish")));
        var publisher=new ModulithRiskScenarioEventPublisher(manager,publications,event->fail("must not publish"));
        assertThrows(NullPointerException.class,()->publisher.publish(null));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
        assertFalse(holder.isRollbackOnly());
    }
}
