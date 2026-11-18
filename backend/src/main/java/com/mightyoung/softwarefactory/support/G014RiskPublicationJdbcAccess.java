package com.mightyoung.softwarefactory.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Synchronous INSERT participation only. The real G014 manager retains every lifecycle decision. */
final class G014RiskPublicationJdbcAccess implements RiskPublicationJdbcAccess {
    private final RiskPublicationSqlCatalog catalog;
    private final ThreadLocal<Participation> active = new ThreadLocal<>();
    private final DataSource alias = new AbstractDataSource() {
        @Override public Connection getConnection() throws SQLException {
            // The witness binds the original holder. A fallback acquisition is never permitted.
            poisonIfActive();
            throw new SQLException("Risk source publication connection unavailable", "08004");
        }
        @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            poisonIfActive();
            throw new SQLException("Risk source publication unavailable", "08004");
        }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
        @Override public String toString() { return "Risk source publication alias[redacted]"; }
    };

    G014RiskPublicationJdbcAccess(RiskPublicationSqlCatalog catalog) {
        this.catalog = java.util.Objects.requireNonNull(catalog);
    }
    RiskPublicationJdbcTemplate template(RiskPublicationSqlCatalog catalog, Set<Class<?>> eventTypes, ClassLoader loader) {
        if (catalog != this.catalog) throw unavailable();
        return new RiskPublicationJdbcTemplate(alias, catalog, eventTypes, loader, this);
    }

    <T> T participate(G014JdbcTransactionManager.RiskPublicationWitness witness, Supplier<T> nativeStore) {
        if (witness == null || nativeStore == null) throw unavailable();
        if (active.get() != null) { poisonIfActive(); throw witness.reject(); }
        witness.claimNativePublication();
        witness.bindTemplateAlias(alias);
        var participation = new Participation(witness);
        active.set(participation);
        try {
            T result = nativeStore.get();
            witness.requireLive();
            if (!participation.insertIssued || participation.operation != null) throw witness.reject();
            return result;
        } catch (RuntimeException | Error failure) {
            throw witness.reject();
        } finally {
            try {
                // On rejection the manager's enclosing finally removes only its own remaining aliases.
                witness.releaseTemplateAlias(alias);
            } finally { active.remove(); }
        }
    }

    @Override public boolean hasActiveTransaction() {
        Participation participation = active.get();
        if (participation == null) return false;
        participation.witness.requireLive();
        return true;
    }
    @Override public <T> T operation(RiskPublicationSqlCatalog.Operation operation, Supplier<T> body) {
        Participation participation = current();
        if (operation == null || body == null || participation.operation != null || participation.insertIssued
                || operation != catalog.insertOperation())
            throw participation.witness.reject();
        participation.insertIssued = true;
        participation.operation = operation;
        participation.epoch = new Object();
        try {
            T result = body.get();
            participation.witness.requireLive();
            return result;
        } catch (RuntimeException | Error failure) { throw participation.witness.reject(); }
        finally { participation.operation = null; participation.epoch = null; }
    }
    @Override public void requireOperation(RiskPublicationSqlCatalog.Operation operation) {
        Participation participation = current();
        if (operation == null || participation.operation != operation || participation.epoch == null)
            throw participation.witness.reject();
    }
    @Override public void requireConnection(Connection connection) {
        Participation participation = current();
        requireOperation(participation.operation);
        participation.witness.requireConnection(connection);
    }
    @Override public Object operationEpoch() {
        Participation participation = current();
        requireOperation(participation.operation);
        return new OperationEpoch(participation, participation.epoch);
    }
    @Override public void requireOperationEpoch(Object epoch) {
        if (!(epoch instanceof OperationEpoch captured)) { poisonIfActive(); throw unavailable(); }
        Participation participation = captured.participation;
        participation.witness.requireLive();
        if (active.get() != participation || captured.value == null || participation.epoch != captured.value) {
            poisonIfActive();
            throw participation.witness.reject();
        }
        requireOperation(participation.operation);
    }
    @Override public void poisonIfActive() {
        Participation participation = active.get();
        if (participation != null) participation.witness.reject();
    }
    private Participation current() {
        Participation participation = active.get();
        if (participation == null) throw unavailable();
        participation.witness.requireLive();
        return participation;
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Risk source publication unavailable"); }
    private record OperationEpoch(Participation participation, Object value) {}
    private static final class Participation {
        final G014JdbcTransactionManager.RiskPublicationWitness witness;
        RiskPublicationSqlCatalog.Operation operation;
        Object epoch;
        boolean insertIssued;
        Participation(G014JdbcTransactionManager.RiskPublicationWitness witness) { this.witness = witness; }
    }
}
