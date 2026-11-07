package com.mightyoung.softwarefactory.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import static com.mightyoung.softwarefactory.support.RiskPublicationSqlCatalog.Kind;

/**
 * Closed native publication execution boundary. No bean is registered here.
 * Connection acquisition, transaction ownership and reset/release outcomes remain separate activation gates.
 */
public final class RiskPublicationJdbcTemplate extends JdbcTemplate {
    private static final String SAFE = "Risk publication JDBC unavailable";
    private static final SQLExceptionTranslator TRANSLATOR = (task, sql, error) -> translateSafely(error);
    private final RiskPublicationSqlCatalog catalog;
    private final RiskPublicationJdbcAccess owner;
    private final java.util.function.Predicate<java.util.UUID> completionIds;
    private boolean ownedReady;
    private final Map<String, Class<?>> eventTypes;
    private final ClassLoader eventClassLoader;

    /** Standalone SQL/logging boundary only; not an owned-lease acceptance or runtime bean. */
    public RiskPublicationJdbcTemplate(DataSource source, RiskPublicationSqlCatalog catalog,
            Set<Class<?>> eventTypes, ClassLoader eventClassLoader) {
        this(source, catalog, eventTypes, eventClassLoader, null);
    }
    RiskPublicationJdbcTemplate(DataSource source, RiskPublicationSqlCatalog catalog,
            Set<Class<?>> eventTypes, ClassLoader eventClassLoader, RiskPublicationJdbcAccess owner) {
        this(source, catalog, eventTypes, eventClassLoader, owner, id -> false);
    }
    RiskPublicationJdbcTemplate(DataSource source, RiskPublicationSqlCatalog catalog,
            Set<Class<?>> eventTypes, ClassLoader eventClassLoader, RiskPublicationJdbcAccess owner,
            java.util.function.Predicate<java.util.UUID> completionIds) {
        super();
        this.owner = owner;
        this.completionIds = completionIds;
        try {
            if (source == null || catalog == null || eventTypes == null || eventTypes.isEmpty()
                    || eventClassLoader == null) throw refusal();
            this.catalog = catalog;
            this.eventClassLoader = eventClassLoader;
            this.eventTypes = Set.copyOf(eventTypes).stream().collect(Collectors.toUnmodifiableMap(Class::getName, type -> {
                try {
                    if (Class.forName(type.getName(), false, eventClassLoader) != type) throw refusal();
                    return type;
                } catch (ClassNotFoundException | LinkageError failure) { throw refusal(); }
            }));
            super.setDataSource(source);
            super.setExceptionTranslator(TRANSLATOR);
            super.setLazyInit(false);
            super.setIgnoreWarnings(false);
            super.setFetchSize(-1);
            super.setMaxRows(-1);
            super.setQueryTimeout(2);
            super.setSkipResultsProcessing(false);
            super.setSkipUndeclaredResults(false);
            super.setResultsMapCaseInsensitive(false);
            super.afterPropertiesSet();
            ownedReady = owner != null;
        } catch (RuntimeException | Error failure) { throw refusal(); }
    }

    @Override public void afterPropertiesSet() { if (!ownedReady) super.afterPropertiesSet(); }
    @Override public DataSource getDataSource() { return super.getDataSource(); }
    @Override protected DataSource obtainDataSource() {
        if (ownedReady && !owner.hasActiveTransaction()) throw refusal();
        return super.getDataSource();
    }

    @Override public int update(String sql, Object... arguments) throws DataAccessException {
        try {
            var operation = catalog.require(sql, Kind.UPDATE, arguments);
            if (operation.label().equals("Risk publication insert")) requireEventType((String) arguments[1]);
            requireOwnedTarget(operation, arguments);
            return executeOperation(operation, () -> super.update(new Creator(operation), setter(arguments.clone())));
        } catch (RuntimeException | Error failure) { throw safeFailure(failure); }
    }

    @Override public <T> T query(String sql, ResultSetExtractor<T> extractor) throws DataAccessException {
        return guardedQuery(sql, extractor, new Object[0], Kind.QUERY);
    }

    @Override public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... arguments) throws DataAccessException {
        return guardedQuery(sql, extractor, arguments, Kind.QUERY_ARGS);
    }

    private <T> T guardedQuery(String sql, ResultSetExtractor<T> extractor, Object[] arguments, Kind kind) {
        try {
            var operation = catalog.require(sql, kind, arguments);
            requireOwnedTarget(operation, arguments);
            if (extractor == null) throw refusal();
            return executeOperation(operation, () -> super.query(new Creator(operation), setter(arguments.clone()), rows -> {
                try { return extractor.extractData(rows); }
                catch (SQLException failure) { throw safeSql(failure); }
                catch (RuntimeException | Error failure) { throw refusal(); }
            }));
        } catch (RuntimeException | Error failure) { throw safeFailure(failure); }
    }
    private void requireOwnedTarget(RiskPublicationSqlCatalog.Operation operation, Object[] arguments) {
        if (owner == null) return;
        if (operation.label().equals("Risk publication complete-id") && !completionIds.test((java.util.UUID) arguments[1])) throw refusal();
        int index = switch (operation.label()) {
            case "Risk publication insert" -> 2;
            case "Risk publication event-target", "Risk publication complete-event-target" -> 1;
            default -> -1;
        };
        if (index >= 0 && !"risk-scenario-draft-change".equals(arguments[index])) throw refusal();
    }
    private <T> T executeOperation(RiskPublicationSqlCatalog.Operation operation, java.util.function.Supplier<T> body) {
        return owner == null ? body.get() : owner.operation(operation, body);
    }

    /** Legacy retention uses this overload; retention is deliberately unavailable in this slice. */
    @Override public void execute(String sql) throws DataAccessException { throw refusal(); }

    private PreparedStatementSetter setter(Object[] arguments) {
        var delegate = new ArgumentPreparedStatementSetter(arguments);
        return statement -> {
            try { delegate.setValues(statement); }
            catch (SQLException failure) { throw safeSql(failure); }
            catch (RuntimeException | Error failure) { throw refusal(); }
        };
    }

    private final class Creator implements PreparedStatementCreator, SqlProvider {
        private final RiskPublicationSqlCatalog.Operation operation;
        private Creator(RiskPublicationSqlCatalog.Operation operation) { this.operation = operation; }
        @Override public String getSql() { return operation.label(); }
        @Override public PreparedStatement createPreparedStatement(Connection connection) throws SQLException {
            try {
                if (owner != null) {
                    owner.requireOperation(operation);
                    owner.requireConnection(connection);
                }
                return handle(connection.prepareStatement(operation.sql()), PreparedStatement.class);
            }
            catch (SQLException failure) { throw safeSql(failure); }
            catch (RuntimeException | Error failure) { throw refusal(); }
        }
    }

    private <T> T handle(T target, Class<T> type) {
        if (target == null) throw refusal();
        var closed = new AtomicBoolean();
        Object epoch = owner == null ? null : owner.operationEpoch();
        return type.cast(Proxy.newProxyInstance(RiskPublicationJdbcTemplate.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, arguments) -> invokeHandle(proxy, target, type, method, arguments, closed, epoch)));
    }

    private Object invokeHandle(Object proxy, Object target, Class<?> type, Method method, Object[] arguments, AtomicBoolean closed, Object epoch) throws SQLException {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            return switch (name) {
                case "toString" -> "Risk publication JDBC handle";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw refusal();
            };
        }
        if (owner != null) owner.requireOperationEpoch(epoch);
        if (name.equals("isClosed") && closed.get()) return true;
        if (name.equals("close")) { if (!closed.compareAndSet(false, true)) return null; }
        else if (closed.get()) throw refusal();
        if (name.equals("unwrap") || name.equals("getConnection") || name.equals("getStatement")
                || (type == ResultSet.class && (name.startsWith("update") || name.equals("insertRow")
                    || name.equals("deleteRow") || name.equals("moveToInsertRow")))) throw refusal();
        if (name.equals("isWrapperFor")) return false;
        try {
            Object value = method.invoke(target, arguments);
            if (type == ResultSet.class && name.equals("next") && Boolean.TRUE.equals(value)) {
                requireEventType(((ResultSet) target).getString("EVENT_TYPE"));
                if (owner != null && !"risk-scenario-draft-change".equals(((ResultSet) target).getString("LISTENER_ID"))) throw refusal();
            }
            if (value instanceof SQLWarning warning) return new SQLWarning("Risk publication JDBC warning", state(warning));
            if (value instanceof ResultSet rows) return handle(rows, ResultSet.class);
            if (value instanceof ResultSetMetaData metadata) return handle(metadata, ResultSetMetaData.class);
            if (value instanceof ParameterMetaData metadata) return handle(metadata, ParameterMetaData.class);
            if (value instanceof Connection || value instanceof Statement || value instanceof Blob
                    || value instanceof Clob || value instanceof java.sql.Array || value instanceof SQLXML
                    || value instanceof Throwable) throw refusal();
            return value;
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof SQLException error) throw safeSql(error);
            throw refusal();
        } catch (SQLException failure) { throw safeSql(failure); }
        catch (RuntimeException | Error | IllegalAccessException failure) { throw refusal(); }
    }

    private void requireEventType(String name) {
        if (name == null || !eventTypes.containsKey(name)) throw refusal();
        try {
            if (Class.forName(name, false, eventClassLoader) != eventTypes.get(name)) throw refusal();
        } catch (ClassNotFoundException | LinkageError failure) { throw refusal(); }
    }

    @Override protected void handleWarnings(Statement statement) throws SQLException {
        try {
            SQLWarning warning = statement.getWarnings();
            if (warning != null) throw safeSql(warning);
        } catch (SQLException failure) { throw safeSql(failure); }
        catch (RuntimeException | Error failure) { throw refusal(); }
    }

    @Override protected void handleWarnings(Statement statement, SQLException primary) {
        // The primary error wins. Never chain a raw warning or invoke superclass warning logging.
        try { handleWarnings(statement); } catch (SQLException | RuntimeException | Error ignored) { }
    }

    @Override protected void handleWarnings(SQLWarning warning) {
        if (warning != null) throw translateSafely(warning);
    }

    @Override protected DataAccessException translateException(String task, String sql, SQLException error) {
        return translateSafely(error);
    }

    private static SQLException safeSql(SQLException error) { return new SQLException(SAFE, state(error)); }
    private static String state(SQLException error) {
        try {
            String state = error == null ? null : error.getSQLState();
            return state != null && state.matches("[0-9A-Z]{5}") ? state : null;
        } catch (RuntimeException | Error failure) { return null; }
    }
    private static DataAccessException translateSafely(SQLException error) {
        var safe = safeSql(error);
        DataAccessException translated = new SQLStateSQLExceptionTranslator().translate(
                "Risk publication JDBC", "Risk publication statement", safe);
        return translated == null ? unavailable() : translated;
    }
    private DataAccessException safeFailure(Throwable failure) {
        if (owner != null) owner.poisonIfActive();
        // Caller callbacks are sanitized before this cut. Rebuild, rather than trust, a translated error.
        try {
            if (failure instanceof DataAccessException && failure.getCause() instanceof SQLException error)
                return translateSafely(error);
        } catch (RuntimeException | Error ignored) { }
        return unavailable();
    }
    private DataAccessResourceFailureException refusal() {
        if (owner != null) owner.poisonIfActive();
        return unavailable();
    }
    private static DataAccessResourceFailureException unavailable() { return new DataAccessResourceFailureException(SAFE); }

    @Override public SQLExceptionTranslator getExceptionTranslator() { return TRANSLATOR; }
    @Override public void setDataSource(DataSource source) { throw refusal(); }
    @Override public void setExceptionTranslator(SQLExceptionTranslator translator) { throw refusal(); }
    @Override public void setDatabaseProductName(String name) { throw refusal(); }
    @Override public void setLazyInit(boolean value) { throw refusal(); }
    @Override public void setIgnoreWarnings(boolean value) { throw refusal(); }
    @Override public void setFetchSize(int value) { throw refusal(); }
    @Override public void setMaxRows(int value) { throw refusal(); }
    @Override public void setQueryTimeout(int value) { throw refusal(); }
    @Override public void setSkipResultsProcessing(boolean value) { throw refusal(); }
    @Override public void setSkipUndeclaredResults(boolean value) { throw refusal(); }
    @Override public void setResultsMapCaseInsensitive(boolean value) { throw refusal(); }

    // Exhaustive public execution deny-list: a reflection test pins this against JdbcOperations/JdbcTemplate.
    @Override public <T extends Object> T execute(ConnectionCallback<T> action) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T execute(StatementCallback<T> action) throws DataAccessException { throw refusal(); }
    @Override public void query(String sql, RowCallbackHandler rch) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> List<T> query(String sql, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> Stream<T> queryForStream(String sql, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public Map<String, Object> queryForMap(String sql) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T queryForObject(String sql, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public <T> T queryForObject(String sql, Class<T> requiredType) throws DataAccessException { throw refusal(); }
    @Override public <T> List<T> queryForList(String sql, Class<T> elementType) throws DataAccessException { throw refusal(); }
    @Override public List<Map<String, Object>> queryForList(String sql) throws DataAccessException { throw refusal(); }
    @Override public SqlRowSet queryForRowSet(String sql) throws DataAccessException { throw refusal(); }
    @Override public int update(String sql) throws DataAccessException { throw refusal(); }
    @Override public int[] batchUpdate(String... sql) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T execute(PreparedStatementCreator psc, PreparedStatementCallback<T> action) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T execute(String sql, PreparedStatementCallback<T> action) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T query( PreparedStatementCreator psc, PreparedStatementSetter pss, ResultSetExtractor<T> rse) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T query(PreparedStatementCreator psc, ResultSetExtractor<T> rse) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T query(String sql, PreparedStatementSetter pss, ResultSetExtractor<T> rse) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T query(String sql, Object [] args, int[] argTypes, ResultSetExtractor<T> rse) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T query(String sql, Object [] args, ResultSetExtractor<T> rse) throws DataAccessException { throw refusal(); }
    @Override public void query(PreparedStatementCreator psc, RowCallbackHandler rch) throws DataAccessException { throw refusal(); }
    @Override public void query(String sql, PreparedStatementSetter pss, RowCallbackHandler rch) throws DataAccessException { throw refusal(); }
    @Override public void query(String sql, Object [] args, int[] argTypes, RowCallbackHandler rch) throws DataAccessException { throw refusal(); }
    @Override public void query(String sql, Object [] args, RowCallbackHandler rch) throws DataAccessException { throw refusal(); }
    @Override public void query(String sql, RowCallbackHandler rch, Object ... args) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> List<T> query(PreparedStatementCreator psc, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> List<T> query(String sql, PreparedStatementSetter pss, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> List<T> query(String sql, Object [] args, int[] argTypes, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> List<T> query(String sql, Object [] args, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> List<T> query(String sql, RowMapper<T> rowMapper, Object ... args) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> Stream<T> queryForStream(PreparedStatementCreator psc, PreparedStatementSetter pss, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> Stream<T> queryForStream(PreparedStatementCreator psc, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> Stream<T> queryForStream(String sql, PreparedStatementSetter pss, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> Stream<T> queryForStream(String sql, RowMapper<T> rowMapper, Object ... args) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T queryForObject(String sql, Object [] args, int[] argTypes, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T queryForObject(String sql, Object [] args, RowMapper<T> rowMapper) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T queryForObject(String sql, RowMapper<T> rowMapper, Object ... args) throws DataAccessException { throw refusal(); }
    @Override public <T> T queryForObject(String sql, Object [] args, int[] argTypes, Class<T> requiredType) throws DataAccessException { throw refusal(); }
    @Override public <T> T queryForObject(String sql, Object [] args, Class<T> requiredType) throws DataAccessException { throw refusal(); }
    @Override public <T> T queryForObject(String sql, Class<T> requiredType, Object ... args) throws DataAccessException { throw refusal(); }
    @Override public Map<String, Object> queryForMap(String sql, Object [] args, int[] argTypes) throws DataAccessException { throw refusal(); }
    @Override public Map<String, Object> queryForMap(String sql, Object ... args) throws DataAccessException { throw refusal(); }
    @Override public <T> List<T> queryForList(String sql, Object [] args, int[] argTypes, Class<T> elementType) throws DataAccessException { throw refusal(); }
    @Override public <T> List<T> queryForList(String sql, Object [] args, Class<T> elementType) throws DataAccessException { throw refusal(); }
    @Override public <T> List<T> queryForList(String sql, Class<T> elementType, Object ... args) throws DataAccessException { throw refusal(); }
    @Override public List<Map<String, Object>> queryForList(String sql, Object [] args, int[] argTypes) throws DataAccessException { throw refusal(); }
    @Override public List<Map<String, Object>> queryForList(String sql, Object ... args) throws DataAccessException { throw refusal(); }
    @Override public SqlRowSet queryForRowSet(String sql, Object [] args, int[] argTypes) throws DataAccessException { throw refusal(); }
    @Override public SqlRowSet queryForRowSet(String sql, Object ... args) throws DataAccessException { throw refusal(); }
    @Override public int update(PreparedStatementCreator psc) throws DataAccessException { throw refusal(); }
    @Override public int update(PreparedStatementCreator psc, KeyHolder generatedKeyHolder) throws DataAccessException { throw refusal(); }
    @Override public int update(String sql, PreparedStatementSetter pss) throws DataAccessException { throw refusal(); }
    @Override public int update(String sql, Object [] args, int[] argTypes) throws DataAccessException { throw refusal(); }
    @Override public int[] batchUpdate(PreparedStatementCreator psc, BatchPreparedStatementSetter pss, KeyHolder generatedKeyHolder) throws DataAccessException { throw refusal(); }
    @Override public int[] batchUpdate(String sql, BatchPreparedStatementSetter pss) throws DataAccessException { throw refusal(); }
    @Override public int[] batchUpdate(String sql, List<? extends Object[]> batchArgs) throws DataAccessException { throw refusal(); }
    @Override public int[] batchUpdate(String sql, List<? extends Object[]> batchArgs, int[] argTypes) throws DataAccessException { throw refusal(); }
    @Override public <T> int[][] batchUpdate(String sql, Collection<T> batchArgs, int batchSize, ParameterizedPreparedStatementSetter<T> pss) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T execute(CallableStatementCreator csc, CallableStatementCallback<T> action) throws DataAccessException { throw refusal(); }
    @Override public <T extends Object> T execute(String callString, CallableStatementCallback<T> action) throws DataAccessException { throw refusal(); }
    @Override public Map<String, Object> call(CallableStatementCreator csc, List<SqlParameter> declaredParameters) throws DataAccessException { throw refusal(); }
}
