package com.mightyoung.softwarefactory;

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
import static com.mightyoung.softwarefactory.RiskPublicationSqlCatalog.Kind;

/**
 * Closed native publication execution boundary. No bean is registered here.
 * Connection acquisition, transaction ownership and reset/release outcomes remain separate activation gates.
 */
public final class RiskPublicationJdbcTemplate extends JdbcTemplate {
    private static final String SAFE = "Risk publication JDBC unavailable";
    private static final SQLExceptionTranslator TRANSLATOR = (task, sql, error) -> translateSafely(error);
    private final RiskPublicationSqlCatalog catalog;
    private final Map<String, Class<?>> eventTypes;
    private final ClassLoader eventClassLoader;

    public RiskPublicationJdbcTemplate(DataSource source, RiskPublicationSqlCatalog catalog,
            Set<Class<?>> eventTypes, ClassLoader eventClassLoader) {
        super();
        try {
            if (source == null || catalog == null || eventTypes == null || eventTypes.isEmpty()
                    || eventClassLoader == null) throw unavailable();
            this.catalog = catalog;
            this.eventClassLoader = eventClassLoader;
            this.eventTypes = Set.copyOf(eventTypes).stream().collect(Collectors.toUnmodifiableMap(Class::getName, type -> {
                try {
                    if (Class.forName(type.getName(), false, eventClassLoader) != type) throw unavailable();
                    return type;
                } catch (ClassNotFoundException | LinkageError failure) { throw unavailable(); }
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
        } catch (RuntimeException | Error failure) { throw unavailable(); }
    }

    @Override public int update(String sql, Object... arguments) throws DataAccessException {
        try {
            var operation = catalog.require(sql, Kind.UPDATE, arguments);
            if (operation.label().equals("Risk publication insert")) requireEventType((String) arguments[1]);
            return super.update(new Creator(operation), setter(arguments.clone()));
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
            if (extractor == null) throw unavailable();
            return super.query(new Creator(operation), setter(arguments.clone()), rows -> {
                try { return extractor.extractData(rows); }
                catch (SQLException failure) { throw safeSql(failure); }
                catch (RuntimeException | Error failure) { throw unavailable(); }
            });
        } catch (RuntimeException | Error failure) { throw safeFailure(failure); }
    }

    /** Legacy retention uses this overload; retention is deliberately unavailable in this slice. */
    @Override public void execute(String sql) throws DataAccessException { throw unavailable(); }

    private PreparedStatementSetter setter(Object[] arguments) {
        var delegate = new ArgumentPreparedStatementSetter(arguments);
        return statement -> {
            try { delegate.setValues(statement); }
            catch (SQLException failure) { throw safeSql(failure); }
            catch (RuntimeException | Error failure) { throw unavailable(); }
        };
    }

    private final class Creator implements PreparedStatementCreator, SqlProvider {
        private final RiskPublicationSqlCatalog.Operation operation;
        private Creator(RiskPublicationSqlCatalog.Operation operation) { this.operation = operation; }
        @Override public String getSql() { return operation.label(); }
        @Override public PreparedStatement createPreparedStatement(Connection connection) throws SQLException {
            try { return handle(connection.prepareStatement(operation.sql()), PreparedStatement.class); }
            catch (SQLException failure) { throw safeSql(failure); }
            catch (RuntimeException | Error failure) { throw unavailable(); }
        }
    }

    private <T> T handle(T target, Class<T> type) {
        if (target == null) throw unavailable();
        var closed = new AtomicBoolean();
        return type.cast(Proxy.newProxyInstance(RiskPublicationJdbcTemplate.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, arguments) -> invokeHandle(proxy, target, type, method, arguments, closed)));
    }

    private Object invokeHandle(Object proxy, Object target, Class<?> type, Method method, Object[] arguments, AtomicBoolean closed) throws SQLException {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            return switch (name) {
                case "toString" -> "Risk publication JDBC handle";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw unavailable();
            };
        }
        if (name.equals("isClosed") && closed.get()) return true;
        if (name.equals("close")) { if (!closed.compareAndSet(false, true)) return null; }
        else if (closed.get()) throw unavailable();
        if (name.equals("unwrap") || name.equals("getConnection") || name.equals("getStatement")
                || (type == ResultSet.class && (name.startsWith("update") || name.equals("insertRow")
                    || name.equals("deleteRow") || name.equals("moveToInsertRow")))) throw unavailable();
        if (name.equals("isWrapperFor")) return false;
        try {
            Object value = method.invoke(target, arguments);
            if (type == ResultSet.class && name.equals("next") && Boolean.TRUE.equals(value)) {
                requireEventType(((ResultSet) target).getString("EVENT_TYPE"));
            }
            if (value instanceof SQLWarning warning) return new SQLWarning("Risk publication JDBC warning", state(warning));
            if (value instanceof ResultSet rows) return handle(rows, ResultSet.class);
            if (value instanceof ResultSetMetaData metadata) return handle(metadata, ResultSetMetaData.class);
            if (value instanceof ParameterMetaData metadata) return handle(metadata, ParameterMetaData.class);
            if (value instanceof Connection || value instanceof Statement || value instanceof Blob
                    || value instanceof Clob || value instanceof java.sql.Array || value instanceof SQLXML
                    || value instanceof Throwable) throw unavailable();
            return value;
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof SQLException error) throw safeSql(error);
            throw unavailable();
        } catch (SQLException failure) { throw safeSql(failure); }
        catch (RuntimeException | Error | IllegalAccessException failure) { throw unavailable(); }
    }

    private void requireEventType(String name) {
        if (name == null || !eventTypes.containsKey(name)) throw unavailable();
        try {
            if (Class.forName(name, false, eventClassLoader) != eventTypes.get(name)) throw unavailable();
        } catch (ClassNotFoundException | LinkageError failure) { throw unavailable(); }
    }

    @Override protected void handleWarnings(Statement statement) throws SQLException {
        try {
            SQLWarning warning = statement.getWarnings();
            if (warning != null) throw safeSql(warning);
        } catch (SQLException failure) { throw safeSql(failure); }
        catch (RuntimeException | Error failure) { throw unavailable(); }
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
    private static DataAccessException safeFailure(Throwable failure) {
        // Caller callbacks are sanitized before this cut. Rebuild, rather than trust, a translated error.
        try {
            if (failure instanceof DataAccessException && failure.getCause() instanceof SQLException error)
                return translateSafely(error);
        } catch (RuntimeException | Error ignored) { }
        return unavailable();
    }
    private static DataAccessResourceFailureException unavailable() { return new DataAccessResourceFailureException(SAFE); }

    @Override public SQLExceptionTranslator getExceptionTranslator() { return TRANSLATOR; }
    @Override public void setDataSource(DataSource source) { throw unavailable(); }
    @Override public void setExceptionTranslator(SQLExceptionTranslator translator) { throw unavailable(); }
    @Override public void setDatabaseProductName(String name) { throw unavailable(); }
    @Override public void setLazyInit(boolean value) { throw unavailable(); }
    @Override public void setIgnoreWarnings(boolean value) { throw unavailable(); }
    @Override public void setFetchSize(int value) { throw unavailable(); }
    @Override public void setMaxRows(int value) { throw unavailable(); }
    @Override public void setQueryTimeout(int value) { throw unavailable(); }
    @Override public void setSkipResultsProcessing(boolean value) { throw unavailable(); }
    @Override public void setSkipUndeclaredResults(boolean value) { throw unavailable(); }
    @Override public void setResultsMapCaseInsensitive(boolean value) { throw unavailable(); }

    // Exhaustive public execution deny-list: a reflection test pins this against JdbcOperations/JdbcTemplate.
    @Override public <T extends Object> T execute(ConnectionCallback<T> action) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T execute(StatementCallback<T> action) throws DataAccessException { throw unavailable(); }
    @Override public void query(String sql, RowCallbackHandler rch) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> List<T> query(String sql, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> Stream<T> queryForStream(String sql, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public Map<String, Object> queryForMap(String sql) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T queryForObject(String sql, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public <T> T queryForObject(String sql, Class<T> requiredType) throws DataAccessException { throw unavailable(); }
    @Override public <T> List<T> queryForList(String sql, Class<T> elementType) throws DataAccessException { throw unavailable(); }
    @Override public List<Map<String, Object>> queryForList(String sql) throws DataAccessException { throw unavailable(); }
    @Override public SqlRowSet queryForRowSet(String sql) throws DataAccessException { throw unavailable(); }
    @Override public int update(String sql) throws DataAccessException { throw unavailable(); }
    @Override public int[] batchUpdate(String... sql) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T execute(PreparedStatementCreator psc, PreparedStatementCallback<T> action) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T execute(String sql, PreparedStatementCallback<T> action) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T query( PreparedStatementCreator psc, PreparedStatementSetter pss, ResultSetExtractor<T> rse) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T query(PreparedStatementCreator psc, ResultSetExtractor<T> rse) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T query(String sql, PreparedStatementSetter pss, ResultSetExtractor<T> rse) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T query(String sql, Object [] args, int[] argTypes, ResultSetExtractor<T> rse) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T query(String sql, Object [] args, ResultSetExtractor<T> rse) throws DataAccessException { throw unavailable(); }
    @Override public void query(PreparedStatementCreator psc, RowCallbackHandler rch) throws DataAccessException { throw unavailable(); }
    @Override public void query(String sql, PreparedStatementSetter pss, RowCallbackHandler rch) throws DataAccessException { throw unavailable(); }
    @Override public void query(String sql, Object [] args, int[] argTypes, RowCallbackHandler rch) throws DataAccessException { throw unavailable(); }
    @Override public void query(String sql, Object [] args, RowCallbackHandler rch) throws DataAccessException { throw unavailable(); }
    @Override public void query(String sql, RowCallbackHandler rch, Object ... args) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> List<T> query(PreparedStatementCreator psc, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> List<T> query(String sql, PreparedStatementSetter pss, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> List<T> query(String sql, Object [] args, int[] argTypes, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> List<T> query(String sql, Object [] args, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> List<T> query(String sql, RowMapper<T> rowMapper, Object ... args) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> Stream<T> queryForStream(PreparedStatementCreator psc, PreparedStatementSetter pss, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> Stream<T> queryForStream(PreparedStatementCreator psc, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> Stream<T> queryForStream(String sql, PreparedStatementSetter pss, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> Stream<T> queryForStream(String sql, RowMapper<T> rowMapper, Object ... args) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T queryForObject(String sql, Object [] args, int[] argTypes, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T queryForObject(String sql, Object [] args, RowMapper<T> rowMapper) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T queryForObject(String sql, RowMapper<T> rowMapper, Object ... args) throws DataAccessException { throw unavailable(); }
    @Override public <T> T queryForObject(String sql, Object [] args, int[] argTypes, Class<T> requiredType) throws DataAccessException { throw unavailable(); }
    @Override public <T> T queryForObject(String sql, Object [] args, Class<T> requiredType) throws DataAccessException { throw unavailable(); }
    @Override public <T> T queryForObject(String sql, Class<T> requiredType, Object ... args) throws DataAccessException { throw unavailable(); }
    @Override public Map<String, Object> queryForMap(String sql, Object [] args, int[] argTypes) throws DataAccessException { throw unavailable(); }
    @Override public Map<String, Object> queryForMap(String sql, Object ... args) throws DataAccessException { throw unavailable(); }
    @Override public <T> List<T> queryForList(String sql, Object [] args, int[] argTypes, Class<T> elementType) throws DataAccessException { throw unavailable(); }
    @Override public <T> List<T> queryForList(String sql, Object [] args, Class<T> elementType) throws DataAccessException { throw unavailable(); }
    @Override public <T> List<T> queryForList(String sql, Class<T> elementType, Object ... args) throws DataAccessException { throw unavailable(); }
    @Override public List<Map<String, Object>> queryForList(String sql, Object [] args, int[] argTypes) throws DataAccessException { throw unavailable(); }
    @Override public List<Map<String, Object>> queryForList(String sql, Object ... args) throws DataAccessException { throw unavailable(); }
    @Override public SqlRowSet queryForRowSet(String sql, Object [] args, int[] argTypes) throws DataAccessException { throw unavailable(); }
    @Override public SqlRowSet queryForRowSet(String sql, Object ... args) throws DataAccessException { throw unavailable(); }
    @Override public int update(PreparedStatementCreator psc) throws DataAccessException { throw unavailable(); }
    @Override public int update(PreparedStatementCreator psc, KeyHolder generatedKeyHolder) throws DataAccessException { throw unavailable(); }
    @Override public int update(String sql, PreparedStatementSetter pss) throws DataAccessException { throw unavailable(); }
    @Override public int update(String sql, Object [] args, int[] argTypes) throws DataAccessException { throw unavailable(); }
    @Override public int[] batchUpdate(PreparedStatementCreator psc, BatchPreparedStatementSetter pss, KeyHolder generatedKeyHolder) throws DataAccessException { throw unavailable(); }
    @Override public int[] batchUpdate(String sql, BatchPreparedStatementSetter pss) throws DataAccessException { throw unavailable(); }
    @Override public int[] batchUpdate(String sql, List<? extends Object[]> batchArgs) throws DataAccessException { throw unavailable(); }
    @Override public int[] batchUpdate(String sql, List<? extends Object[]> batchArgs, int[] argTypes) throws DataAccessException { throw unavailable(); }
    @Override public <T> int[][] batchUpdate(String sql, Collection<T> batchArgs, int batchSize, ParameterizedPreparedStatementSetter<T> pss) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T execute(CallableStatementCreator csc, CallableStatementCallback<T> action) throws DataAccessException { throw unavailable(); }
    @Override public <T extends Object> T execute(String callString, CallableStatementCallback<T> action) throws DataAccessException { throw unavailable(); }
    @Override public Map<String, Object> call(CallableStatementCreator csc, List<SqlParameter> declaredParameters) throws DataAccessException { throw unavailable(); }
}
