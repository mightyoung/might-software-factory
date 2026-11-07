package com.mightyoung.softwarefactory.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.SmartDataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.core.InfrastructureProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A read-only operation epoch preserving original DataSourceUtils ownership and ordinary JDBC settings. */
final class NativePublicationReadJdbc {
    private final DataSource canonical;
    private final JdbcTemplate ordinary;
    private final NativePublicationInventory inventory;
    private final Supplier<?> invocation;
    private final DataSource safeSource = new SafeReadSource();

    NativePublicationReadJdbc(DataSource canonical, JdbcTemplate ordinary, NativePublicationInventory inventory, Supplier<?> invocation) {
        this.canonical = canonical; this.ordinary = ordinary; this.inventory = inventory; this.invocation = invocation;
    }

    Object query(RiskPublicationSqlCatalog.Operation operation, Object[] arguments, ResultSetExtractor<?> extractor) {
        if (extractor == null || operation.kind() == RiskPublicationSqlCatalog.Kind.UPDATE) throw unavailable();
        var lease = new ReadLease(invocation.get(), TransactionSynchronizationManager.getResource(canonical));
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet rows = null;
        try {
            // The public throwing variants avoid framework logging of a raw close/acquisition failure.
            connection = DataSourceUtils.doGetConnection(safeSource);
            lease.attach(connection);
            statement = connection.prepareStatement(operation.sql());
            PreparedStatement guarded = wrap(statement, PreparedStatement.class, lease);
            if (ordinary.getFetchSize() != -1) guarded.setFetchSize(ordinary.getFetchSize());
            if (ordinary.getMaxRows() != -1) guarded.setMaxRows(ordinary.getMaxRows());
            DataSourceUtils.applyTimeout(guarded, canonical, ordinary.getQueryTimeout());
            new ArgumentPreparedStatementSetter(arguments).setValues(guarded);
            lease.require();
            rows = statement.executeQuery();
            Object result = extractor.extractData(wrap(rows, ResultSet.class, lease));
            lease.require();
            SQLWarning warning = statement.getWarnings();
            if (warning != null && !ordinary.isIgnoreWarnings()) throw safeSql(warning);
            return result;
        } catch (SQLException | RuntimeException | Error failure) { throw lease.reject(); }
        finally {
            boolean cleanupFailed = false;
            // These raw handles are private cleanup references, never supplied to the mapper.
            try { if (rows != null) lease.closeOnce(rows); } catch (Throwable failure) { cleanupFailed = true; }
            try { if (statement != null) lease.closeOnce(statement); } catch (Throwable failure) { cleanupFailed = true; }
            try {
                if (connection != null) {
                    if (lease.attached && TransactionSynchronizationManager.getResource(canonical) != lease.holder) throw lease.reject();
                    DataSourceUtils.doReleaseConnection(connection, safeSource);
                }
            } catch (Throwable failure) { cleanupFailed = true; }
            lease.live = false;
            if (cleanupFailed) throw lease.reject();
        }
    }

    /**
     * Only the resource key is canonicalized. Fresh synchronization-owned connections are sanitized before
     * Spring registers its delayed ConnectionSynchronization; an existing canonical holder is never replaced.
     */
    private final class SafeReadSource extends AbstractDataSource implements InfrastructureProxy, SmartDataSource {
        @Override public Object getWrappedObject() { return canonical; }
        @Override public Connection getConnection() throws SQLException {
            try { return safeConnection(canonical.getConnection()); }
            catch (SQLException failure) { throw safeSql(failure); }
            catch (RuntimeException | Error failure) { throw new SQLException("Native publication read unavailable"); }
        }
        @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("Native publication read unavailable"); }
        @Override public boolean shouldClose(Connection connection) {
            try {
                Connection raw = connection;
                if (Proxy.isProxyClass(connection.getClass()) && Proxy.getInvocationHandler(connection) instanceof SafeConnection handle && handle.issuer == NativePublicationReadJdbc.this)
                    raw = handle.raw;
                return !(canonical instanceof SmartDataSource smart) || smart.shouldClose(raw);
            } catch (RuntimeException | Error failure) { throw unavailable(); }
        }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("Native publication read unavailable"); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
        @Override public String toString() { return "Native publication read source"; }
    }
    private Connection safeConnection(Connection raw) {
        if (raw == null) throw unavailable();
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, new SafeConnection(raw));
    }
    private final class SafeConnection implements java.lang.reflect.InvocationHandler {
        final Connection raw;
        final NativePublicationReadJdbc issuer = NativePublicationReadJdbc.this;
        SafeConnection(Connection raw) { this.raw = raw; }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "Native publication read connection";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw unavailable();
            };
            if (method.getName().equals("unwrap")) throw new SQLException("Native publication read unavailable");
            if (method.getName().equals("isWrapperFor")) return false;
            try { return method.invoke(raw, args); }
            catch (InvocationTargetException failure) {
                if (method.getName().equals("setClientInfo")) throw new java.sql.SQLClientInfoException();
                if (failure.getCause() instanceof SQLException error) throw safeSql(error);
                throw unavailable();
            } catch (RuntimeException | Error | IllegalAccessException failure) { throw unavailable(); }
        }
    }

    private final class ReadLease {
        final Object frame;
        final Object initialHolder;
        final Thread thread = Thread.currentThread();
        Object holder;
        boolean attached, live = true;
        volatile boolean poisoned;
        final java.util.IdentityHashMap<Object, CloseState> handles = new java.util.IdentityHashMap<>();
        CloseState state(Object raw) { return handles.computeIfAbsent(raw, ignored -> new CloseState()); }
        void closeOnce(AutoCloseable raw) throws Exception {
            CloseState state = state(raw);
            if (state.sent) return;
            state.sent = true;
            raw.close();
        }
        ReadLease(Object frame, Object initialHolder) { this.frame = frame; this.initialHolder = initialHolder; }
        void attach(Connection connection) {
            requireFrame();
            if (attached || connection == null) throw reject();
            Object actual = TransactionSynchronizationManager.getResource(canonical);
            if (initialHolder != null && initialHolder != actual) throw reject();
            if (actual != null && (!(actual instanceof ConnectionHolder value) || value.getConnection() != connection)) throw reject();
            holder = actual;
            attached = true;
        }
        void require() {
            requireFrame();
            if (!attached || TransactionSynchronizationManager.getResource(canonical) != holder) throw reject();
        }
        void requireFrame() {
            if (!live || poisoned || thread != Thread.currentThread()) throw reject();
            try { if (invocation.get() != frame) throw reject(); }
            catch (RuntimeException | Error failure) { throw reject(); }
        }
        IllegalStateException reject() {
            poisoned = true;
            if (holder instanceof ConnectionHolder value) value.setRollbackOnly();
            else if (initialHolder instanceof ConnectionHolder value) value.setRollbackOnly();
            return unavailable();
        }
    }

    private <T> T wrap(T raw, Class<T> type, ReadLease lease) {
        if (raw == null) throw unavailable();
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, new java.lang.reflect.InvocationHandler() {
            private final CloseState closed = lease.state(raw);
            @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (method.getDeclaringClass() == Object.class) return switch (name) {
                    case "toString" -> "Native publication read handle";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw unavailable();
                };
                // Closed-state shortcuts also require the exact live invocation/epoch/holder/thread.
                lease.require();
                if (name.equals("isClosed") && closed.sent) return true;
                if (name.equals("close") && closed.sent) return null;
                if (closed.sent) throw lease.reject();
                if (name.equals("unwrap") || name.equals("getConnection") || name.equals("getStatement")) throw lease.reject();
                if (name.equals("isWrapperFor")) return false;
                if (type == PreparedStatement.class && (name.startsWith("execute") && !name.equals("executeQuery")
                        || name.equals("executeQuery") && method.getParameterCount() != 0 || name.contains("Batch")
                        || name.equals("getGeneratedKeys") || name.equals("getResultSet") || name.equals("getMoreResults")
                        || name.equals("getUpdateCount") || name.equals("getLargeUpdateCount"))) throw lease.reject();
                if (type == ResultSet.class && !rowMethod(method, args)) throw lease.reject();
                try {
                    if (name.equals("close")) closed.sent = true;
                    Object result = method.invoke(raw, args);
                    if (type == ResultSet.class && name.equals("next") && Boolean.TRUE.equals(result)) {
                        inventory.requireRow(((ResultSet) raw).getString("EVENT_TYPE"), ((ResultSet) raw).getString("LISTENER_ID"));
                    }
                    if (result instanceof SQLWarning warning) return new SQLWarning("Native publication read warning", state(warning));
                    if (result instanceof ResultSet rows) return wrap(rows, ResultSet.class, lease);
                    if (result instanceof ResultSetMetaData metadata) return wrap(metadata, ResultSetMetaData.class, lease);
                    if (result instanceof ParameterMetaData metadata) return wrap(metadata, ParameterMetaData.class, lease);
                    if (result instanceof Connection || result instanceof Statement || result instanceof java.sql.Blob
                            || result instanceof java.sql.Clob || result instanceof java.sql.Array || result instanceof java.sql.SQLXML
                            || result instanceof Throwable) throw unavailable();
                    lease.require();
                    return result;
                } catch (InvocationTargetException failure) {
                    lease.reject();
                    if (failure.getCause() instanceof SQLException error) throw safeSql(error);
                    throw unavailable();
                } catch (SQLException failure) { lease.reject(); throw safeSql(failure); }
                catch (RuntimeException | Error | IllegalAccessException failure) { throw lease.reject(); }
            }
        }));
    }
    private static final class CloseState { boolean sent; }
    private static boolean rowMethod(Method method, Object[] args) {
        String name = method.getName();
        if (method.getParameterCount() == 0) return Set.of("next", "close", "isClosed").contains(name);
        if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != String.class || args == null) return false;
        return switch (name) {
            case "getObject" -> "ID".equals(args[0]);
            case "getTimestamp" -> Set.of("COMPLETION_DATE", "PUBLICATION_DATE").contains(args[0]);
            case "getString" -> Set.of("EVENT_TYPE", "LISTENER_ID", "SERIALIZED_EVENT").contains(args[0]);
            default -> false;
        };
    }
    private static String state(SQLException failure) {
        try { String value = failure.getSQLState(); return value != null && value.matches("[A-Z0-9]{5}") ? value : null; }
        catch (RuntimeException | Error ignored) { return null; }
    }
    private static SQLException safeSql(SQLException failure) { return new SQLException("Native publication read unavailable", state(failure)); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Native publication read unavailable"); }
}
