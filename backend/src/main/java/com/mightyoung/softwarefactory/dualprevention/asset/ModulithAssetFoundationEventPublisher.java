package com.mightyoung.softwarefactory.dualprevention.asset;

import java.util.Objects;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager;
import javax.sql.DataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publishes through the actual application multicaster while its JDBC store borrows the command holder. */
public final class ModulithAssetFoundationEventPublisher implements AssetFoundationEventPublisher {
    private final G014JdbcTransactionManager manager;
    private final DataSource commands;
    private final DataSource publications;
    private final ApplicationEventPublisher events;

    public ModulithAssetFoundationEventPublisher(G014JdbcTransactionManager manager,DataSource publications,ApplicationEventPublisher events) {
        this.manager=Objects.requireNonNull(manager);
        this.commands=manager.commandDataSource();
        this.publications=Objects.requireNonNull(publications);
        this.events=Objects.requireNonNull(events);
        if(this.commands==publications)throw new IllegalArgumentException("Distinct command and publication sources required");
    }

    @Override public void publish(AssetFoundationChangedEvent event) {
        Objects.requireNonNull(event);
        if(!(TransactionSynchronizationManager.getResource(commands) instanceof ConnectionHolder holder)
                ||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()
                ||!TransactionSynchronizationManager.isActualTransactionActive()
                ||!TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw unavailable();
        // getResource applies Spring's resource-key normalization. Never overwrite another owner or alias.
        if(TransactionSynchronizationManager.hasResource(publications)) {
            holder.setRollbackOnly();throw unavailable();
        }
        manager.requirePublicationSynchronizationFirst();
        TransactionSynchronizationManager.bindResource(publications,holder);
        Throwable failure=null;
        try {
            events.publishEvent(event);
        } catch(RuntimeException|Error rejected) {
            failure=rejected;holder.setRollbackOnly();throw rejected;
        } finally {
            boolean ownsAlias=TransactionSynchronizationManager.getResource(publications)==holder;
            boolean ownsCommand=TransactionSynchronizationManager.getResource(commands)==holder;
            if(ownsAlias)TransactionSynchronizationManager.unbindResource(publications);
            if(!ownsAlias||!ownsCommand) {
                holder.setRollbackOnly();
                var refusal=unavailable();
                if(failure!=null)failure.addSuppressed(refusal);else throw refusal;
            }
        }
    }

    private static AssetFoundationFailure unavailable() { return new AssetFoundationFailure(503); }
}
