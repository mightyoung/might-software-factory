package com.mightyoung.softwarefactory.dualprevention.asset;

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
class ModulithAssetFoundationEventPublisherTest {
    private final G014JdbcTransactionManager manager=mock(G014JdbcTransactionManager.class);
    private final DataSource commands=mock(DataSource.class);
    private final DataSource publications=mock(DataSource.class);
    private final Connection connection=mock(Connection.class);
    private final ConnectionHolder holder=new ConnectionHolder(connection);
    private final AssetFoundationChangedEvent event=new AssetFoundationChangedEvent(UUID.randomUUID(),UUID.randomUUID(),
            "workshop",UUID.randomUUID(),"workshop.create",1,Instant.parse("2026-11-04T00:00:00Z"),UUID.randomUUID(),"CREATE");

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
        var publisher=new ModulithAssetFoundationEventPublisher(manager,publications,published->{
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
        var publisher=new ModulithAssetFoundationEventPublisher(manager,publications,event->{throw expected;});
        assertSame(expected,assertThrows(IllegalStateException.class,()->publisher.publish(event)));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
        assertTrue(holder.isRollbackOnly());
    }
    @Test void preexistingOriginalResourceIsNeverOverwritten() {
        var existing=new ConnectionHolder(mock(Connection.class));
        TransactionSynchronizationManager.bindResource(publications,existing);
        var publisher=new ModulithAssetFoundationEventPublisher(manager,publications,event->fail("must not publish"));
        assertThrows(AssetFoundationFailure.class,()->publisher.publish(event));
        assertSame(existing,TransactionSynchronizationManager.getResource(publications));
        assertTrue(holder.isRollbackOnly());
    }
    @Test void replacedAliasFailsClosedWithoutRemovingAnotherOwnersResource() {
        var replacement=new ConnectionHolder(mock(Connection.class));
        var publisher=new ModulithAssetFoundationEventPublisher(manager,publications,event->{
            TransactionSynchronizationManager.unbindResource(publications);
            TransactionSynchronizationManager.bindResource(publications,replacement);
        });
        assertThrows(AssetFoundationFailure.class,()->publisher.publish(event));
        assertSame(replacement,TransactionSynchronizationManager.getResource(publications));
        assertTrue(holder.isRollbackOnly());
    }
    @Test void replacedCommandOwnerStillCleansTheOwnedAlias() {
        var replacement=new ConnectionHolder(mock(Connection.class));
        var publisher=new ModulithAssetFoundationEventPublisher(manager,publications,event->{
            TransactionSynchronizationManager.unbindResource(commands);
            TransactionSynchronizationManager.bindResource(commands,replacement);
        });
        assertThrows(AssetFoundationFailure.class,()->publisher.publish(event));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
        assertSame(replacement,TransactionSynchronizationManager.getResource(commands));
        assertTrue(holder.isRollbackOnly());
    }
    @Test void suspensionPreflightRefusesBeforeAnyAliasOrPublication() {
        doThrow(new IllegalStateException("unsafe synchronization order")).when(manager).requirePublicationSynchronizationFirst();
        var publisher=new ModulithAssetFoundationEventPublisher(manager,publications,event->fail("must not publish"));
        assertThrows(IllegalStateException.class,()->publisher.publish(event));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
    }
    @Test void readOnlyOrInactiveBoundaryCannotPublish() {
        var publisher=new ModulithAssetFoundationEventPublisher(manager,publications,event->fail("must not publish"));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(AssetFoundationFailure.class,()->publisher.publish(event));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(AssetFoundationFailure.class,()->publisher.publish(event));
        assertFalse(TransactionSynchronizationManager.hasResource(publications));
    }
}
