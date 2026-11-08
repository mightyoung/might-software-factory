package com.mightyoung.softwarefactory.support;

import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.jdbc.support.rowset.SqlRowSet;

/** Actual auto-configuration requires JdbcTemplate, while the native repository only uses three closed overloads. */
final class NativePublicationDispatchTemplate extends JdbcTemplate {
    private final JdbcOperations delegate;
    private final Runnable reject;
    NativePublicationDispatchTemplate(JdbcOperations delegate, Runnable reject) { this.delegate = delegate; this.reject = reject; }
    @Override public void afterPropertiesSet() { }
    @Override public DataSource getDataSource() { throw refusal(); }
    @Override public SQLExceptionTranslator getExceptionTranslator() { throw refusal(); }
    @Override public void execute(String sql) { throw refusal(); }
    @Override public int update(String sql, Object... arguments) { return delegate.update(sql, arguments); }
    @Override public <T> T query(String sql, ResultSetExtractor<T> extractor) { return delegate.query(sql, extractor); }
    @Override public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... arguments) { return delegate.query(sql, extractor, arguments); }
    private IllegalStateException refusal() { reject.run(); return new IllegalStateException("Shared native publication unavailable"); }
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
