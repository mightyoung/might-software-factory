package com.mightyoung.softwarefactory.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.core.InfrastructureProxy;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owned empty-transaction lifecycle only. No bean, business SQL or borrowed-holder permit. */
final class RiskJdbcTransactionOwner {
    private static final String SAFE = "Risk JDBC transaction unavailable";
    enum Outcome { NOT_STARTED, BEGIN_IN_PROGRESS, BEGIN_UNKNOWN, ACTIVE, COMMIT_SENT, COMMITTED,
        COMMIT_UNKNOWN, ROLLBACK_SENT, ROLLED_BACK, ROLLBACK_UNKNOWN }
    enum Cleanup { NOT_ATTEMPTED, COMPLETE, FAILED, CLOSE_UNKNOWN }
    /** Internal facts survive failure sanitization; no original Throwable is retained. */
    static final class Failure extends TransactionSystemException {
        private final Outcome outcome;
        private final Cleanup cleanup;
        private Failure(Outcome outcome, Cleanup cleanup) {
            super(SAFE + " [" + outcome.name() + "," + cleanup.name() + "]");
            this.outcome = outcome;
            this.cleanup = cleanup;
        }
        Outcome outcome() { return outcome; }
        Cleanup cleanup() { return cleanup; }
    }
    private enum Permit { NONE, BEGIN, COMMIT, ROLLBACK, CLEANUP }
    private final DataSource canonical;
    private final LeaseSource source;
    private final Manager manager;
    private final ThreadLocal<Frame> active = new ThreadLocal<>();
    // Retaining a Java lease never claims pool eviction, particularly after close throws.
    private final Set<Frame> retained = ConcurrentHashMap.newKeySet();

    static RiskJdbcTransactionOwner create(DataSource canonical) {
        if (canonical == null) throw new Failure(Outcome.NOT_STARTED, Cleanup.NOT_ATTEMPTED);
        return new RiskJdbcTransactionOwner(canonical);
    }
    private RiskJdbcTransactionOwner(DataSource canonical) {
        this.canonical = canonical;
        this.source = new LeaseSource();
        this.manager = new Manager();
    }

    /** Trusted internal callback only; it receives no manager, status, datasource or raw handle. */
    <T> T executeOwned(Supplier<T> body) {
        if (body == null || active.get() != null || TransactionSynchronizationManager.hasResource(canonical)
                || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive())
            throw new Failure(Outcome.NOT_STARTED, Cleanup.NOT_ATTEMPTED);
        Frame frame = new Frame();
        active.set(frame);
        var definition = new DefaultTransactionDefinition();
        definition.setName("Risk owned JDBC lifecycle");
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        definition.setReadOnly(false);
        definition.setTimeout(2);
        try {
            T result = new TransactionTemplate(manager, definition).execute(status -> {
                requireBound(frame);
                try { return body.get(); }
                catch (RuntimeException | Error failure) { throw failure(frame); }
            });
            if (frame.outcome != Outcome.COMMITTED || frame.cleanup != Cleanup.COMPLETE)
                throw failure(frame);
            return result;
        } catch (RuntimeException | Error failure) {
            throw failure(frame);
        } finally {
            frame.permit = Permit.NONE;
            if (frame.lease != null && frame.cleanup != Cleanup.COMPLETE) retained.add(frame);
            active.remove();
        }
    }

    private final class Frame {
        final Thread thread = Thread.currentThread();
        Object transaction;
        Lease lease;
        ConnectionHolder holder;
        Permit permit = Permit.NONE;
        Outcome outcome = Outcome.NOT_STARTED;
        Cleanup cleanup = Cleanup.NOT_ATTEMPTED;
        volatile boolean rejected;
    }
    private Frame frame() {
        Frame frame = active.get();
        if (frame == null || frame.thread != Thread.currentThread())
            throw new Failure(Outcome.NOT_STARTED, Cleanup.NOT_ATTEMPTED);
        return frame;
    }
    private void requireBound(Frame frame) {
        if (active.get() != frame || frame.thread != Thread.currentThread() || frame.transaction == null
                || frame.lease == null || frame.holder == null || frame.holder.getConnection() != frame.lease.connection
                || TransactionSynchronizationManager.getResource(canonical) != frame.holder)
            throw reject(frame);
    }
    private Failure reject(Frame frame) { frame.rejected = true; return failure(frame); }
    private static Failure failure(Frame frame) { return new Failure(frame.outcome, frame.cleanup); }
    private static SQLException sql(Throwable original) {
        String state = null;
        if (original instanceof SQLException error) {
            try {
                String candidate = error.getSQLState();
                if (candidate != null && candidate.matches("[0-9A-Z]{5}")) state = candidate;
            } catch (RuntimeException | Error ignored) { }
        }
        return new SQLException(SAFE, state);
    }
    private void detachOwnHolder(Frame frame) {
        Object current = TransactionSynchronizationManager.getResource(canonical);
        if (frame.holder != null && current == frame.holder) {
            TransactionSynchronizationManager.unbindResource(canonical);
            frame.holder.clear();
        } else if (current != null) {
            // A foreign holder is neither removed nor cleared.
            frame.cleanup = Cleanup.FAILED;
        }
    }

    private final class Manager extends JdbcTransactionManager {
        Manager() {
            super();
            super.setDataSource(source);
            super.setNestedTransactionAllowed(false);
            super.setValidateExistingTransaction(true);
            super.setRollbackOnCommitFailure(false);
            super.setExceptionTranslator((task, statement, error) ->
                    new org.springframework.dao.DataAccessResourceFailureException(SAFE, sql(error)));
            super.afterPropertiesSet();
        }
        @Override protected Object doGetTransaction() {
            Frame frame = frame();
            if (frame.transaction != null || TransactionSynchronizationManager.hasResource(canonical)
                    || TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isSynchronizationActive()) throw reject(frame);
            return super.doGetTransaction();
        }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            Frame frame = frame();
            frame.transaction = transaction;
            frame.permit = Permit.BEGIN;
            frame.outcome = Outcome.BEGIN_IN_PROGRESS;
            try {
                super.doBegin(transaction, definition);
                if (frame.rejected || frame.lease == null
                        || !(TransactionSynchronizationManager.getResource(canonical) instanceof ConnectionHolder holder)
                        || holder.getConnection() != frame.lease.connection) throw reject(frame);
                frame.holder = holder;
                frame.outcome = Outcome.ACTIVE;
            } catch (RuntimeException | Error failure) {
                if (frame.lease != null) {
                    frame.outcome = Outcome.BEGIN_UNKNOWN;
                    retained.add(frame);
                    detachOwnHolder(frame);
                } else frame.outcome = Outcome.NOT_STARTED;
                throw failure(frame);
            } finally { frame.permit = Permit.NONE; }
        }
        @Override protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                throws SQLException {
            Frame frame = frame();
            if (frame.rejected || frame.outcome == Outcome.BEGIN_UNKNOWN) throw sql(null);
            super.prepareTransactionalConnection(connection, definition);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            Frame frame = frame();
            try {
                requireBound(frame);
                if (frame.transaction != status.getTransaction() || frame.rejected || frame.holder.isRollbackOnly()
                        || status.hasSavepoint() || status.isLocalRollbackOnly() || status.isGlobalRollbackOnly())
                    throw reject(frame);
            } catch (RuntimeException | Error refusal) {
                // Only a definite, still-owned ACTIVE transaction can be rolled back before sending commit.
                if (frame.outcome == Outcome.ACTIVE && TransactionSynchronizationManager.getResource(canonical) == frame.holder)
                    doRollback(status);
                throw failure(frame);
            }
            frame.permit = Permit.COMMIT;
            try { super.doCommit(status); }
            catch (RuntimeException | Error failure) { throw failure(frame); }
            finally { frame.permit = Permit.NONE; }
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            Frame frame = frame();
            requireBound(frame);
            if (frame.transaction != status.getTransaction() || frame.outcome != Outcome.ACTIVE) throw reject(frame);
            frame.permit = Permit.ROLLBACK;
            try { super.doRollback(status); }
            catch (RuntimeException | Error failure) { throw failure(frame); }
            finally { frame.permit = Permit.NONE; }
        }
        @Override protected RuntimeException translateException(String task, SQLException error) {
            return failure(frame()); // SQLException was sanitized at the delegate boundary; retain no cause.
        }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            Frame frame = frame();
            try {
                if (frame.transaction != transaction || frame.holder == null
                        || TransactionSynchronizationManager.getResource(canonical) != frame.holder) {
                    frame.cleanup = Cleanup.FAILED;
                    return;
                }
                if (frame.outcome != Outcome.COMMITTED && frame.outcome != Outcome.ROLLED_BACK) return;
                frame.permit = Permit.CLEANUP;
                super.doCleanupAfterCompletion(transaction);
                if (frame.cleanup == Cleanup.NOT_ATTEMPTED)
                    frame.cleanup = frame.lease.closed ? Cleanup.COMPLETE : Cleanup.FAILED;
            } catch (RuntimeException | Error failure) { frame.cleanup = Cleanup.FAILED; }
            finally {
                frame.permit = Permit.NONE;
                detachOwnHolder(frame);
                if (frame.cleanup != Cleanup.COMPLETE) retained.add(frame);
            }
        }
    }

    private final class LeaseSource extends AbstractDataSource implements InfrastructureProxy {
        @Override public Object getWrappedObject() { return canonical; }
        @Override public Connection getConnection() throws SQLException {
            Frame frame = frame();
            if (frame.permit != Permit.BEGIN || frame.lease != null) throw sql(null);
            try {
                Connection delegate = canonical.getConnection();
                if (delegate == null) throw sql(null);
                frame.lease = new Lease(frame, delegate);
                return frame.lease.connection;
            } catch (SQLException | RuntimeException | Error error) { throw sql(error); }
        }
        @Override public Connection getConnection(String username, String password) throws SQLException { throw sql(null); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { throw sql(null); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }
    private final class Lease implements InvocationHandler {
        final Frame frame;
        final Connection delegate;
        final Connection connection;
        boolean closed;
        Lease(Frame frame, Connection delegate) {
            this.frame = frame;
            this.delegate = delegate;
            this.connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, this);
        }
        @Override public Object invoke(Object proxy, Method method, Object[] incoming) throws Throwable {
            Object[] args = incoming == null ? new Object[0] : incoming;
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "toString" -> "Risk owned JDBC lease[redacted]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw sql(null);
                };
            }
            // This JDBC method declares only SQLClientInfoException. Keep every refusal,
            // including expired and wrong-thread calls, within that declared checked type.
            if (name.equals("setClientInfo")) {
                frame.rejected = true;
                throw new java.sql.SQLClientInfoException(SAFE, java.util.Map.of());
            }
            if (active.get() != frame || Thread.currentThread() != frame.thread || frame.lease != this || closed)
                throw refused();
            if (name.equals("isWrapperFor")) return false;
            if (name.equals("unwrap")) throw refused();
            if (name.equals("isClosed") && args.length == 0) return closed;
            if (frame.permit == Permit.BEGIN) {
                if (frame.rejected || frame.outcome == Outcome.BEGIN_UNKNOWN) throw sql(null);
                if (args.length == 0 && Set.of("getAutoCommit", "isReadOnly", "getTransactionIsolation").contains(name))
                    return call(method, args);
                if (args.length == 1 && (name.equals("setTransactionIsolation")
                        || name.equals("setReadOnly") || name.equals("setAutoCommit"))) return call(method, args);
                throw refused();
            }
            if (frame.permit == Permit.CLEANUP) {
                if ((frame.outcome != Outcome.COMMITTED && frame.outcome != Outcome.ROLLED_BACK)
                        || frame.cleanup != Cleanup.NOT_ATTEMPTED) throw sql(null);
                if (name.equals("close") && args.length == 0) return call(method, args);
                if (args.length == 1 && Set.of("setAutoCommit", "setReadOnly", "setTransactionIsolation").contains(name))
                    return call(method, args);
                throw refused();
            }
            requireBound(frame);
            if (name.equals("commit") && args.length == 0 && frame.permit == Permit.COMMIT && frame.outcome == Outcome.ACTIVE) {
                frame.outcome = Outcome.COMMIT_SENT;
                return call(method, args);
            }
            if (name.equals("rollback") && args.length == 0 && frame.permit == Permit.ROLLBACK && frame.outcome == Outcome.ACTIVE) {
                frame.outcome = Outcome.ROLLBACK_SENT;
                return call(method, args);
            }
            if (frame.permit == Permit.NONE && !frame.rejected && frame.outcome == Outcome.ACTIVE
                    && args.length == 0 && Set.of("getAutoCommit", "isReadOnly", "getTransactionIsolation").contains(name))
                return call(method, args);
            throw refused();
        }
        private SQLException refused() { frame.rejected = true; return sql(null); }
        private Object call(Method method, Object[] args) throws SQLException {
            try {
                Object value = switch (method.getName()) {
                    case "getAutoCommit" -> delegate.getAutoCommit();
                    case "isReadOnly" -> delegate.isReadOnly();
                    case "getTransactionIsolation" -> delegate.getTransactionIsolation();
                    case "setAutoCommit" -> { delegate.setAutoCommit((Boolean) args[0]); yield null; }
                    case "setReadOnly" -> { delegate.setReadOnly((Boolean) args[0]); yield null; }
                    case "setTransactionIsolation" -> { delegate.setTransactionIsolation((Integer) args[0]); yield null; }
                    case "commit" -> { delegate.commit(); frame.outcome = Outcome.COMMITTED; yield null; }
                    case "rollback" -> { delegate.rollback(); frame.outcome = Outcome.ROLLED_BACK; yield null; }
                    case "close" -> { delegate.close(); closed = true; yield null; }
                    default -> throw sql(null);
                };
                return value;
            } catch (SQLException | RuntimeException | Error failure) {
                if (frame.permit == Permit.BEGIN) frame.outcome = Outcome.BEGIN_UNKNOWN;
                else if (frame.outcome == Outcome.COMMIT_SENT) frame.outcome = Outcome.COMMIT_UNKNOWN;
                else if (frame.outcome == Outcome.ROLLBACK_SENT) frame.outcome = Outcome.ROLLBACK_UNKNOWN;
                else if (frame.permit == Permit.CLEANUP)
                    frame.cleanup = method.getName().equals("close") ? Cleanup.CLOSE_UNKNOWN : Cleanup.FAILED;
                frame.rejected = true;
                throw sql(failure);
            }
        }
    }
}
