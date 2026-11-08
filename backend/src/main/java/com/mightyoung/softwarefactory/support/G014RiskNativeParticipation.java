package com.mightyoung.softwarefactory.support;

import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/** Native store/create participation in an issuer-owned source transaction; never a transaction manager. */
final class G014RiskNativeParticipation {
    private static final String CREATE = "org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository.create";
    private final G014JdbcTransactionManager manager;
    private final DataSource publicationAlias;
    private final G014RiskPublicationJdbcAccess access;
    private final RiskPublicationJdbcTemplate template;
    private final ThreadLocal<Call> current = new ThreadLocal<>();

    G014RiskNativeParticipation(G014JdbcTransactionManager manager, DataSource publicationAlias,
            RiskPublicationSqlCatalog catalog, Set<Class<?>> eventTypes, ClassLoader loader) {
        this.manager = java.util.Objects.requireNonNull(manager);
        this.publicationAlias = java.util.Objects.requireNonNull(publicationAlias);
        if (manager.profile() != G014SqlProfile.RISK_SCENARIO) throw unavailable();
        access = new G014RiskPublicationJdbcAccess(catalog);
        template = access.template(catalog, eventTypes, loader);
    }
    RiskPublicationJdbcTemplate template() { return template; }

    boolean hasSourceWitness(Object event) {
        return manager.currentRiskPublicationWitness(publicationAlias, event) != null;
    }
    void rejectIfSourceActive() { manager.rejectRiskPublicationIfActive(); }
    <T> T store(Object event, Supplier<T> nativeStore) {
        var witness = manager.currentRiskPublicationWitness(publicationAlias, event);
        if (witness == null) throw unavailable();
        if (current.get() != null) throw witness.reject();
        var call = new Call(witness);
        current.set(call);
        try {
            return access.participate(witness, () -> {
                T value = nativeStore.get();
                witness.requireLive();
                if (call.status == null || !call.status.completed || call.status.rollbackOnly) throw witness.reject();
                return value;
            });
        } catch (RuntimeException | Error failure) { throw witness.reject(); }
        finally { current.remove(); }
    }

    TransactionStatus beginCreate(TransactionDefinition definition) {
        Call call = requireCurrent();
        if (call.status != null || definition == null || !CREATE.equals(definition.getName())
                || definition.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || definition.isReadOnly() || definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT
                || definition.getTimeout() != TransactionDefinition.TIMEOUT_DEFAULT) throw call.witness.reject();
        call.status = new ParticipantStatus(call);
        return call.status;
    }
    void commit(TransactionStatus status) { finish(status, false); }
    void rollback(TransactionStatus status) { finish(status, true); }
    private void finish(TransactionStatus status, boolean rollback) {
        Call call = current.get();
        if (call == null || !(status instanceof ParticipantStatus participant) || participant.call != call
                || call.status != participant || participant.thread != Thread.currentThread() || participant.completed) {
            if (status instanceof ParticipantStatus issued) issued.call.witness.reject();
            if (call != null) throw call.witness.reject();
            throw unavailable();
        }
        participant.completed = true;
        if (rollback || participant.rollbackOnly) {
            participant.rollbackOnly = true;
            call.witness.reject();
            return;
        }
        call.witness.requireLive();
    }
    private Call requireCurrent() {
        Call call = current.get();
        if (call == null) throw unavailable();
        call.witness.requireLive();
        return call;
    }
    private final class ParticipantStatus implements TransactionStatus {
        final Call call;
        final Thread thread = Thread.currentThread();
        boolean completed, rollbackOnly;
        ParticipantStatus(Call call) { this.call = call; }
        private void requireLive() {
            if (thread != Thread.currentThread() || current.get() != call || call.status != this || completed)
                throw call.witness.reject();
            call.witness.requireLive();
        }
        @Override public String getTransactionName() { return CREATE; }
        @Override public boolean hasTransaction() { return true; }
        @Override public boolean isNewTransaction() { return false; }
        @Override public boolean isNested() { return false; }
        @Override public boolean isReadOnly() { return false; }
        @Override public boolean isCompleted() { return completed; }
        @Override public boolean isRollbackOnly() { return rollbackOnly; }
        @Override public boolean hasSavepoint() { return false; }
        @Override public void setRollbackOnly() { requireLive(); rollbackOnly = true; call.witness.reject(); }
        @Override public void flush() { requireLive(); }
        @Override public Object createSavepoint() { throw call.witness.reject(); }
        @Override public void rollbackToSavepoint(Object savepoint) { throw call.witness.reject(); }
        @Override public void releaseSavepoint(Object savepoint) { throw call.witness.reject(); }
    }
    private final class Call {
        final G014JdbcTransactionManager.RiskPublicationWitness witness;
        ParticipantStatus status;
        Call(G014JdbcTransactionManager.RiskPublicationWitness witness) { this.witness = witness; }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Risk native source participation unavailable"); }
}
