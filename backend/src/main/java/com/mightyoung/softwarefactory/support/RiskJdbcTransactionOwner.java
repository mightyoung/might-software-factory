package com.mightyoung.softwarefactory.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Function;
import org.springframework.transaction.TransactionStatus;
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

/** Explicit owned lifecycle. No bean or foreign/G014 borrowed-holder permit. */
final class RiskJdbcTransactionOwner implements RiskPublicationJdbcAccess {
    private static final String SAFE = "Risk JDBC transaction unavailable";
    enum Outcome { NOT_STARTED, BEGIN_IN_PROGRESS, BEGIN_UNKNOWN, ACTIVE, COMMIT_SENT, COMMITTED,
        COMMIT_UNKNOWN, ROLLBACK_SENT, ROLLED_BACK, ROLLBACK_UNKNOWN }
    enum Cleanup { NOT_ATTEMPTED, COMPLETE, FAILED, CLOSE_UNKNOWN }
    /** Internal facts survive failure sanitization; no original Throwable is retained. */
    static final class Failure extends RiskNativePublicationTransactions.Failure {
        private final Outcome outcome;
        private final Cleanup cleanup;
        private Failure(Outcome outcome, Cleanup cleanup) {
            super(outcome.name(), cleanup.name());
            this.outcome = outcome;
            this.cleanup = cleanup;
        }
        Outcome outcome() { return outcome; }
        Cleanup cleanup() { return cleanup; }
    }
    private enum Permit { NONE, BEGIN, COMMIT, ROLLBACK, CLEANUP }
    private final DataSource canonical;
    private final LeaseSource source;
    private final TemplateSource templateSource = new TemplateSource();
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
                || TransactionSynchronizationManager.hasResource(templateSource)
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

    @Override public void poisonIfActive() {
        Frame frame = active.get();
        if (frame != null && frame.thread == Thread.currentThread()) frame.rejected = true;
    }
    boolean hasOwnedTransaction() { return active.get() != null; }
    @Override public boolean hasActiveTransaction() { return hasOwnedTransaction(); }

    TransactionStatus beginNative(TransactionDefinition definition) {
        Frame frame = active.get();
        boolean owned = frame == null;
        if (owned) {
            if (TransactionSynchronizationManager.hasResource(canonical)
                || TransactionSynchronizationManager.hasResource(templateSource)
                    || TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isSynchronizationActive())
                throw new Failure(Outcome.NOT_STARTED, Cleanup.NOT_ATTEMPTED);
            frame = new Frame();
            active.set(frame);
        } else {
            requireBound(frame);
            if (definition.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED) throw reject(frame);
        }
        var fixed = new DefaultTransactionDefinition(definition);
        fixed.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        fixed.setTimeout(2);
        try {
            TransactionStatus delegate = manager.getTransaction(fixed);
            if (!owned) frame.participants++;
            return new NativeStatus(frame, delegate, owned);
        } catch (RuntimeException | Error failure) {
            if (owned) releaseFrame(frame);
            throw failure(frame);
        }
    }
    void finishNative(TransactionStatus status, boolean commit) {
        if (!(status instanceof NativeStatus nativeStatus) || nativeStatus.finished
                || active.get() != nativeStatus.frame || nativeStatus.frame.thread != Thread.currentThread())
            throw new Failure(Outcome.NOT_STARTED, Cleanup.NOT_ATTEMPTED);
        Frame frame = nativeStatus.frame;
        if (nativeStatus.owned && frame.participants != 0) throw reject(frame);
        nativeStatus.finished = true;
        try {
            if (commit) manager.commit(nativeStatus.delegate); else manager.rollback(nativeStatus.delegate);
            if (nativeStatus.owned && (frame.cleanup != Cleanup.COMPLETE
                    || (frame.outcome != Outcome.COMMITTED && frame.outcome != Outcome.ROLLED_BACK))) throw failure(frame);
        } catch (RuntimeException | Error failure) { throw failure(frame); }
        finally {
            if (nativeStatus.owned) releaseFrame(frame); else frame.participants--;
        }
    }
    private void releaseFrame(Frame frame) {
        frame.permit = Permit.NONE;
        frame.operation = null;
        if (frame.lease != null && frame.cleanup != Cleanup.COMPLETE) retained.add(frame);
        active.remove();
    }
    private final class NativeStatus implements TransactionStatus {
        final Frame frame;
        final TransactionStatus delegate;
        final boolean owned;
        boolean finished;
        NativeStatus(Frame frame, TransactionStatus delegate, boolean owned) {
            this.frame = frame; this.delegate = delegate; this.owned = owned;
        }
        public String getTransactionName() { return delegate.getTransactionName(); }
        public boolean hasTransaction() { return delegate.hasTransaction(); }
        public boolean isNewTransaction() { return delegate.isNewTransaction(); }
        public boolean isNested() { return delegate.isNested(); }
        public boolean isReadOnly() { return delegate.isReadOnly(); }
        private void requireLive() { if (finished) throw reject(frame); requireBound(frame); }
        public void setRollbackOnly() { requireLive(); delegate.setRollbackOnly(); }
        public boolean isRollbackOnly() { return delegate.isRollbackOnly(); }
        public boolean isCompleted() { return finished || delegate.isCompleted(); }
        public boolean hasSavepoint() { return delegate.hasSavepoint(); }
        public void flush() { requireLive(); delegate.flush(); }
        public Object createSavepoint() { throw reject(frame); }
        public void rollbackToSavepoint(Object savepoint) { throw reject(frame); }
        public void releaseSavepoint(Object savepoint) { throw reject(frame); }
    }

    @Override public <T> T operation(RiskPublicationSqlCatalog.Operation operation, Supplier<T> callback) {
        Frame frame = frame();
        requireBound(frame);
        if (frame.rejected || frame.outcome != Outcome.ACTIVE || frame.operation != null || operation == null)
            throw reject(frame);
        frame.operation = operation;
        frame.statementIssued = false;
        frame.operationEpoch = new Object();
        try { T result = callback.get(); if (frame.rejected) throw failure(frame); return result; }
        catch (RuntimeException | Error failure) { frame.rejected = true; throw failure(frame); }
        finally { frame.operation = null; frame.operationEpoch = null; }
    }
    @Override public void requireOperation(RiskPublicationSqlCatalog.Operation operation) {
        Frame frame = frame();
        requireBound(frame);
        if (operation == null || frame.operation != operation || frame.rejected || frame.outcome != Outcome.ACTIVE
                || frame.permit != Permit.NONE) throw reject(frame);
    }

    @Override public void requireConnection(Connection connection) {
        Frame frame = frame();
        requireOperation(frame.operation);
        if (frame.lease == null || connection != frame.lease.connection) throw reject(frame);
    }
    @Override public Object operationEpoch() {
        Frame frame = frame();
        requireOperation(frame.operation);
        if (frame.operationEpoch == null) throw reject(frame);
        return new OperationEpoch(frame, frame.operationEpoch);
    }
    @Override public void requireOperationEpoch(Object epoch) {
        if (!(epoch instanceof OperationEpoch captured)) { poisonIfActive(); throw new IllegalStateException(SAFE); }
        Frame frame = captured.frame;
        if (frame.thread != Thread.currentThread() || active.get() != frame
                || captured.value == null || frame.operationEpoch != captured.value) {
            poisonIfActive();
            throw reject(frame);
        }
        requireOperation(frame.operation);
    }
    private record OperationEpoch(Frame frame, Object value) {}

    private <T> T child(T target, Class<T> type, Frame frame, RiskPublicationSqlCatalog.Operation operation) {
        if (target == null) throw reject(frame);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        Object epoch = frame.operationEpoch;
        return type.cast(Proxy.newProxyInstance(RiskJdbcTransactionOwner.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, incoming) -> {
                    Object[] args = incoming == null ? new Object[0] : incoming;
                    String name = method.getName();
                    if (method.getDeclaringClass() == Object.class) return switch (name) {
                        case "toString" -> "Risk owned JDBC child[redacted]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw childFailure(frame, null);
                    };
                    if (epoch == null || frame.operationEpoch != epoch) throw childFailure(frame, null);
                    try { requireOperation(operation); }
                    catch (RuntimeException | Error failure) { throw childFailure(frame, failure); }
                    if (name.equals("isWrapperFor")) return false;
                    if (name.equals("unwrap") || name.equals("getConnection") || name.equals("getStatement") || closed.get())
                        throw childFailure(frame, null);
                    boolean allowed;
                    if (type == PreparedStatement.class) {
                        boolean setter = (name.equals("setObject") || name.equals("setString") || name.equals("setTimestamp"))
                                && args.length == 2 && args[0] instanceof Integer index && index > 0
                                && index <= operation.arguments().size() && args[1] != null
                                && args[1].getClass() == operation.arguments().get(index - 1);
                        boolean config = name.equals("setQueryTimeout") && args.length == 1 && args[0] instanceof Integer seconds && seconds > 0 && seconds <= 2;
                        boolean execute = args.length == 0 && Set.of("executeUpdate", "executeQuery", "getWarnings", "clearWarnings", "close").contains(name);
                        allowed = setter || config || execute;
                    } else {
                        boolean row = type == ResultSet.class && Set.of("getString", "getTimestamp", "getObject").contains(name)
                                && args.length >= 1 && args.length <= 2 && args[0] instanceof String;
                        if (row && args.length == 2) row = name.equals("getObject") && args[1] == java.util.UUID.class;
                        allowed = row || type == ResultSet.class && args.length == 0 && Set.of("next", "close", "wasNull").contains(name);
                    }
                    if (!allowed) { frame.rejected = true; throw childFailure(frame, null); }
                    if (name.equals("close")) closed.set(true);
                    try {
                        Object result = method.invoke(target, args);
                        if (result instanceof ResultSet rows) return child(rows, ResultSet.class, frame, operation);
                        if (result instanceof java.sql.SQLWarning warning) return new java.sql.SQLWarning(SAFE, sql(warning).getSQLState());
                        if (result != null && !(result instanceof String || result instanceof Number || result instanceof Boolean
                                || result instanceof java.sql.Timestamp || result instanceof java.util.UUID)) throw childFailure(frame, null);
                        return result;
                    } catch (InvocationTargetException failure) { throw childFailure(frame, failure.getCause()); }
                    catch (RuntimeException | Error | IllegalAccessException failure) { throw childFailure(frame, failure); }
                }));
    }

    private SQLException childFailure(Frame frame, Throwable failure) { frame.rejected = true; return sql(failure); }

    RiskPublicationJdbcTemplate template(RiskPublicationSqlCatalog catalog, Set<Class<?>> eventTypes, ClassLoader loader) {
        return template(catalog, eventTypes, loader, id -> false);
    }
    RiskPublicationJdbcTemplate template(RiskPublicationSqlCatalog catalog, Set<Class<?>> eventTypes, ClassLoader loader,
            java.util.function.Predicate<java.util.UUID> completionIds) {
        return new RiskPublicationJdbcTemplate(templateSource, catalog, eventTypes, loader, this, completionIds);
    }
    private final class TemplateSource extends AbstractDataSource {
        @Override public Connection getConnection() {
            Frame frame = frame(); requireOperation(frame.operation); return frame.lease.connection;
        }
        @Override public Connection getConnection(String user, String password) throws SQLException { poisonIfActive(); throw sql(null); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { poisonIfActive(); throw sql(null); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
        @Override public String toString() { return "Risk owned JDBC template source[redacted]"; }
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
        Baseline baseline;
        boolean changedAutoCommit, changedReadOnly, changedIsolation;
        RiskPublicationSqlCatalog.Operation operation;
        int participants;
        boolean desiredReadOnly;
        boolean statementIssued;
        Object operationEpoch;
    }
    private record Baseline(boolean autoCommit, boolean readOnly, int isolation) {}
    private Frame frame() {
        Frame frame = active.get();
        if (frame == null || frame.thread != Thread.currentThread())
            throw new Failure(Outcome.NOT_STARTED, Cleanup.NOT_ATTEMPTED);
        return frame;
    }
    private void requireBound(Frame frame) {
        if (active.get() != frame || frame.thread != Thread.currentThread() || frame.transaction == null
                || frame.lease == null || frame.holder == null || frame.holder.getConnection() != frame.lease.connection
                || TransactionSynchronizationManager.getResource(canonical) != frame.holder
                || TransactionSynchronizationManager.getResource(templateSource) != frame.holder)
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
        Object alias = TransactionSynchronizationManager.getResource(templateSource);
        if (alias == frame.holder && alias != null) TransactionSynchronizationManager.unbindResource(templateSource);
        else if (alias != null) frame.cleanup = Cleanup.FAILED;
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
            if (frame.transaction != null) {
                requireBound(frame);
                if (frame.rejected || frame.outcome != Outcome.ACTIVE) throw reject(frame);
                return super.doGetTransaction();
            }
            if (TransactionSynchronizationManager.hasResource(canonical)
                    || TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isSynchronizationActive()) throw reject(frame);
            return super.doGetTransaction();
        }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            Frame frame = frame();
            frame.transaction = transaction;
            frame.desiredReadOnly = definition.isReadOnly();
            frame.permit = Permit.BEGIN;
            frame.outcome = Outcome.BEGIN_IN_PROGRESS;
            try {
                super.doBegin(transaction, definition);
                if (frame.rejected || frame.lease == null
                        || !(TransactionSynchronizationManager.getResource(canonical) instanceof ConnectionHolder holder)
                        || holder.getConnection() != frame.lease.connection) throw reject(frame);
                frame.holder = holder;
                if (TransactionSynchronizationManager.hasResource(templateSource)) throw reject(frame);
                TransactionSynchronizationManager.bindResource(templateSource, holder);
                frame.outcome = Outcome.ACTIVE;
            } catch (RuntimeException | Error failure) {
                if (frame.lease != null) {
                    frame.outcome = frame.baseline == null ? Outcome.NOT_STARTED : Outcome.BEGIN_UNKNOWN;
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
                        || TransactionSynchronizationManager.getResource(canonical) != frame.holder
                        || TransactionSynchronizationManager.getResource(templateSource) != frame.holder) {
                    frame.cleanup = Cleanup.FAILED;
                    return;
                }
                if (frame.outcome != Outcome.COMMITTED && frame.outcome != Outcome.ROLLED_BACK) return;
                frame.permit = Permit.CLEANUP;
                // Restore exactly the immutable baseline, only after a definite ACK.
                if (frame.changedAutoCommit) frame.lease.connection.setAutoCommit(frame.baseline.autoCommit());
                if (frame.changedIsolation) frame.lease.connection.setTransactionIsolation(frame.baseline.isolation());
                if (frame.changedReadOnly) frame.lease.connection.setReadOnly(frame.baseline.readOnly());
                frame.lease.connection.close();
                frame.cleanup = Cleanup.COMPLETE;
            } catch (SQLException | RuntimeException | Error failure) {
                if (frame.cleanup == Cleanup.NOT_ATTEMPTED) frame.cleanup = Cleanup.FAILED;
            }
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
                // All three observations precede every setter; a failed observation mutates nothing.
                frame.baseline = new Baseline(delegate.getAutoCommit(), delegate.isReadOnly(), delegate.getTransactionIsolation());
                // PgJDBC readOnly changes precede isolation/autoCommit setup and all statements.
                if (frame.baseline.readOnly() != frame.desiredReadOnly) frame.lease.connection.setReadOnly(frame.desiredReadOnly);
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
                if (args.length == 0) {
                    if (name.equals("getAutoCommit")) return frame.baseline.autoCommit();
                    if (name.equals("isReadOnly")) return frame.baseline.readOnly();
                    if (name.equals("getTransactionIsolation")) return frame.baseline.isolation();
                }
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
            if (frame.permit == Permit.NONE && !frame.rejected && frame.outcome == Outcome.ACTIVE
                    && name.equals("prepareStatement") && args.length == 1 && args[0] instanceof String statement) {
                requireOperation(frame.operation);
                if (!frame.operation.sql().equals(statement) || frame.statementIssued) throw refused();
                frame.statementIssued = true;
                try { return child(delegate.prepareStatement(statement), PreparedStatement.class, frame, frame.operation); }
                catch (SQLException | RuntimeException | Error failure) { throw sql(failure); }
            }
            throw refused();
        }
        private SQLException refused() { frame.rejected = true; return sql(null); }
        private Object call(Method method, Object[] args) throws SQLException {
            try {
                Object value = switch (method.getName()) {
                    case "getAutoCommit" -> delegate.getAutoCommit();
                    case "isReadOnly" -> delegate.isReadOnly();
                    case "getTransactionIsolation" -> delegate.getTransactionIsolation();
                    case "setAutoCommit" -> { if (frame.permit == Permit.BEGIN) frame.changedAutoCommit = true; delegate.setAutoCommit((Boolean) args[0]); yield null; }
                    case "setReadOnly" -> { if (frame.permit == Permit.BEGIN) frame.changedReadOnly = true; delegate.setReadOnly((Boolean) args[0]); yield null; }
                    case "setTransactionIsolation" -> { if (frame.permit == Permit.BEGIN) frame.changedIsolation = true; delegate.setTransactionIsolation((Integer) args[0]); yield null; }
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
