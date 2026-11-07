package com.mightyoung.softwarefactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.*;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.RiskPublicationSqlCatalogTest.Probe;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled JDBC plus actual native framework composition. Not a PostgreSQL/lease/cleanup acceptance substitute. */
class RiskPublicationJdbcTemplateTest {
    private static final String SECRET = "PRIVATE_SENTINEL";
    private static final String INSERT = RiskPublicationSqlCatalog.SQL_STATEMENT_INSERT.formatted("EVENT_PUBLICATION");
    private static final String READ = RiskPublicationSqlCatalog.SQL_STATEMENT_FIND_INCOMPLETE.formatted("EVENT_PUBLICATION");

    @Test void preservesBindingsCountsAndBoundConnectionHolderWithoutAnotherBorrow() throws Exception {
        var f = fixture();
        Object[] values = RiskPublicationSqlCatalogTest.samples("").getFirst().arguments();
        ConnectionHolder holder = new ConnectionHolder(f.connection());
        TransactionSynchronizationManager.bindResource(f.source(), holder);
        try {
            clearInvocations(f.source(), f.connection());
            assertThat(f.template().update(INSERT, values)).isEqualTo(7);
            assertThat(TransactionSynchronizationManager.getResource(f.source())).isSameAs(holder);
            verifyNoInteractions(f.source());
            verify(f.connection()).prepareStatement(INSERT);
            verify(f.statement()).setObject(1, values[0]);
            verify(f.statement()).setString(2, (String) values[1]);
            verify(f.statement()).setString(3, (String) values[2]);
            verify(f.statement()).setObject(4, values[3]);
            verify(f.statement()).setString(5, (String) values[4]);
            verify(f.connection(), never()).close();
            verify(f.connection(), never()).commit();
            verify(f.connection(), never()).rollback();
            verify(f.connection(), never()).setAutoCommit(anyBoolean());
        } finally {
            assertThat(TransactionSynchronizationManager.unbindResource(f.source())).isSameAs(holder);
        }
    }

    @Test void preservesKnownEventRowsAndOtherProjectedValues() throws Exception {
        var f = fixture();
        when(f.rows().next()).thenReturn(true, false);
        when(f.rows().getString("SERIALIZED_EVENT")).thenReturn("original payload");
        Timestamp date = Timestamp.valueOf("2026-11-07 00:00:00");
        when(f.rows().getTimestamp("PUBLICATION_DATE")).thenReturn(date);
        var result = f.template().query(READ, rows -> {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("EVENT_TYPE")).isEqualTo(Probe.class.getName());
            assertThat(rows.getTimestamp("PUBLICATION_DATE")).isSameAs(date);
            assertThat(rows.isWrapperFor(ResultSet.class)).isFalse();
            assertThatThrownBy(() -> rows.unwrap(ResultSet.class)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(rows::getStatement).isInstanceOf(DataAccessException.class);
            String payload = rows.getString("SERIALIZED_EVENT");
            assertThat(rows.next()).isFalse();
            return payload;
        });
        assertThat(result).isEqualTo("original payload");
    }

    @Test void escapedResultSetHandleCannotOperateAfterSynchronousExtraction() throws Exception {
        var f = fixture();
        ResultSet escaped = f.template().query(READ, rows -> rows);
        clearInvocations(f.rows());
        assertThatThrownBy(() -> escaped.getString("EVENT_TYPE")).isInstanceOf(DataAccessException.class);
        assertThat(escaped.isClosed()).isTrue();
        verifyNoInteractions(f.rows());
    }

    @Test void invalidSqlArgumentsAndUnknownInsertTypeAreRejectedBeforeJdbc() throws Exception {
        var f = fixture();
        for (Level level : List.of(Level.INFO, Level.DEBUG)) withLogs(level, captured -> {
        for (String sql : List.of(INSERT + " ", "SELECT " + SECRET, "DELETE FROM EVENT_PUBLICATION")) {
            assertThatThrownBy(() -> f.template().update(sql, new Object[0])).isInstanceOf(DataAccessException.class);
        }
        var values = RiskPublicationSqlCatalogTest.samples("").getFirst().arguments();
        values[1] = SECRET;
        assertThatThrownBy(() -> f.template().update(INSERT, values)).isInstanceOf(DataAccessException.class);
        values[1] = Probe.class.getName();
        values[0] = "not-uuid";
        assertThatThrownBy(() -> f.template().update(INSERT, values)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> f.template().query(READ, (ResultSetExtractor<Object>) null)).isInstanceOf(DataAccessException.class);
            assertThat(logs()).doesNotContain(SECRET);
        });
        verifyNoInteractions(f.source(), f.connection(), f.statement(), f.rows());
    }

    @Test void refusesUnknownOrNullTypeBeforeExtractorCanConsumeARow() throws Exception {
        for (String type : Arrays.asList(null, SECRET, String.class.getName())) {
            var f = fixture();
            when(f.rows().next()).thenReturn(true);
            when(f.rows().getString("EVENT_TYPE")).thenReturn(type);
            AtomicInteger consumed = new AtomicInteger();
            assertThatThrownBy(() -> f.template().query(READ, rows -> {
                if (rows.next()) consumed.incrementAndGet();
                return 1;
            })).isInstanceOf(DataAccessException.class);
            assertThat(consumed).hasValue(0);
        }
    }

    @Test void validatesNonemptyCompleteKnownClassInventoryWithExactLoaderIdentity() throws Exception {
        DataSource source = mock(DataSource.class);
        var catalog = RiskPublicationSqlCatalogTest.catalog();
        for (Set<Class<?>> types : List.<Set<Class<?>>>of(Set.of(), new HashSet<>(Arrays.asList(Probe.class, null)))) {
            assertThatThrownBy(() -> new RiskPublicationJdbcTemplate(source, catalog, types, getClass().getClassLoader()))
                    .isInstanceOf(DataAccessException.class).hasNoCause();
        }
        assertThatThrownBy(() -> new RiskPublicationJdbcTemplate(source, catalog, Set.of(Probe.class), new ClassLoader(null) {}))
                .isInstanceOf(DataAccessException.class).hasNoCause();
        verifyNoInteractions(source);
    }

    @Test void publicExecutionInventoryCannotInheritAnUnprotectedPath() throws Exception {
        assertThat(Modifier.isFinal(RiskPublicationJdbcTemplate.class.getModifiers())).isTrue();
        var f = fixture();
        AtomicInteger callbacks = new AtomicInteger();
        for (Level level : List.of(Level.INFO, Level.DEBUG)) withLogs(level, captured -> {
        Set<Method> inventory = new HashSet<>(Arrays.asList(JdbcOperations.class.getMethods()));
        Arrays.stream(JdbcTemplate.class.getMethods()).filter(RiskPublicationJdbcTemplateTest::execution).forEach(inventory::add);
        for (Method method : inventory) {
            Method implementation = RiskPublicationJdbcTemplate.class.getMethod(method.getName(), method.getParameterTypes());
            assertThat(implementation.getDeclaringClass()).as(method.toString()).isEqualTo(RiskPublicationJdbcTemplate.class);
            if (nativeSignature(method)) continue;
            Object[] arguments = Arrays.stream(method.getParameterTypes()).map(type -> argument(type, callbacks)).toArray();
            assertThatThrownBy(() -> implementation.invoke(f.template(), arguments))
                    .isInstanceOf(InvocationTargetException.class).satisfies(error ->
                            assertThat(error.getCause()).isInstanceOf(DataAccessException.class).hasMessage("Risk publication JDBC unavailable"));
        }
            assertThat(logs()).doesNotContain(SECRET);
        });
        assertThat(callbacks).hasValue(0);
        verifyNoInteractions(f.source(), f.connection(), f.statement(), f.rows());
    }

    @Test void freezesEveryPublicConfigurationSetterBeforeAnyExternalWork() throws Exception {
        var f = fixture();
        for (Level level : List.of(Level.INFO, Level.DEBUG)) withLogs(level, captured -> {
        for (Method method : JdbcTemplate.class.getMethods()) {
            if (!method.getName().startsWith("set")) continue;
            Method implementation = RiskPublicationJdbcTemplate.class.getMethod(method.getName(), method.getParameterTypes());
            assertThat(implementation.getDeclaringClass()).isEqualTo(RiskPublicationJdbcTemplate.class);
            Object[] arguments = Arrays.stream(method.getParameterTypes()).map(type -> argument(type, new AtomicInteger())).toArray();
            assertThatThrownBy(() -> implementation.invoke(f.template(), arguments)).isInstanceOf(InvocationTargetException.class);
        }
            assertThat(logs()).doesNotContain(SECRET);
        });
        assertThat(f.template().getDataSource()).isSameAs(f.source());
        assertThat(f.template().getQueryTimeout()).isEqualTo(2);
        assertThat(f.template().getFetchSize()).isEqualTo(-1);
        assertThat(f.template().getMaxRows()).isEqualTo(-1);
        assertThat(f.template().isIgnoreWarnings()).isFalse();
        assertThat(f.template().getExceptionTranslator()).isNotInstanceOf(AbstractFallbackSQLExceptionTranslator.class);
        verifyNoInteractions(f.source(), f.connection(), f.statement(), f.rows());
    }

    @Test void publicTranslatorIgnoresCallerSqlTaskAndOriginalErrorChain() throws Exception {
        var translator = fixture().template().getExceptionTranslator();
        for (String state : Arrays.asList("42601", "23505", SECRET, null)) {
            SQLException original = secretSql(state);
            DataAccessException safe = translator.translate(SECRET + " task", SECRET + " sql", original);
            assertThat(safe).isNotNull();
            assertSafe(safe);
            if ("42601".equals(state)) {
                assertThat(safe).isInstanceOf(BadSqlGrammarException.class);
                assertThat(((BadSqlGrammarException) safe).getSql()).isEqualTo("Risk publication statement");
                assertThat(((SQLException) safe.getCause()).getSQLState()).isEqualTo("42601");
            }
        }
    }

    @Test void infoAndDebugLogsExcludeSqlBindingsWarningAndCallbackFailureDetails() throws Exception {
        List<FailureCase> failures = List.of(
                f -> { when(f.connection().prepareStatement(anyString())).thenThrow(secretSql("42601")); insert(f); },
                f -> { when(f.statement().executeUpdate()).thenThrow(secretSql("23505")); insert(f); },
                f -> { doThrow(secretSql("22000")).when(f.statement()).setString(eq(2), anyString()); insert(f); },
                f -> { when(f.statement().getWarnings()).thenReturn(new SQLWarning(SECRET + " warning", "01000")); insert(f); },
                f -> { when(f.statement().getWarnings()).thenThrow(secretSql("01000")); insert(f); },
                f -> f.template().query(READ, (ResultSetExtractor<Object>) rows -> { throw new IllegalStateException(SECRET, secretSql("42601")); }),
                f -> { var metadata = mock(ResultSetMetaData.class); when(f.rows().getMetaData()).thenReturn(metadata); when(metadata.getColumnName(1)).thenThrow(secretSql("42601")); f.template().query(READ, (ResultSetExtractor<String>) rows -> rows.getMetaData().getColumnName(1)); },
                f -> { when(f.rows().next()).thenThrow(secretSql("42601")); f.template().query(READ, (ResultSetExtractor<Boolean>) ResultSet::next); },
                f -> { when(f.rows().next()).thenReturn(true); when(f.rows().getString("EVENT_TYPE")).thenReturn(SECRET); f.template().query(READ, (ResultSetExtractor<Boolean>) ResultSet::next); });
        for (Level level : List.of(Level.INFO, Level.DEBUG)) {
            for (FailureCase failure : failures) {
                var f = fixture();
                withLogs(level, logs -> {
                    Throwable thrown = catchThrowable(() -> failure.run(f));
                    assertThat(thrown).isInstanceOf(DataAccessException.class);
                    assertSafe(thrown);
                    assertThat(logs()).doesNotContain(SECRET, INSERT, READ);
                });
            }
        }
    }

    @Test void sanitizedStatementAndResultSetCloseErrorsCannotLeakThroughJdbcUtils() throws Exception {
        for (Level level : List.of(Level.INFO, Level.DEBUG)) {
            var f = fixture();
            doThrow(secretSql("58000")).when(f.statement()).close();
            doThrow(secretSql("58000")).when(f.rows()).close();
            withLogs(level, logs -> {
                String result = f.template().query(READ, rows -> "original");
                assertThat(result).isEqualTo("original");
                assertThat(logs()).doesNotContain(SECRET, INSERT, READ);
            });
        }
        // This assertion concerns logger inputs only; operation cleanup outcomes remain a separate lease gate.
    }

    @Test void actualNativeFactoryReplaysAllSevenShapesThroughOneDedicatedTemplate() throws Exception {
        var f = fixture();
        var serializer = mock(EventSerializer.class);
        var event = new Probe(SECRET);
        when(serializer.serialize(event)).thenReturn(SECRET + " serialized");
        var ordinary = spy(new JdbcTemplate(f.source()));
        nativeContext(f, serializer, ordinary).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(EventPublicationRepository.class)).hasSize(1);
                    assertThat(context.getBeansOfType(EventPublicationRegistry.class)).hasSize(1);
                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                    var repository = context.getBean(EventPublicationRepository.class);
                    assertThat(AopUtils.getTargetClass(repository).getName())
                            .isEqualTo("org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository");
                    var dedicated = context.getBean(RiskPublicationJdbcTemplate.class);
                    assertThat(dedicated.getDataSource()).isSameAs(f.source());
                    assertThat(context.getBean(JdbcTemplate.class)).isSameAs(ordinary);
                    clearInvocations(ordinary, f.connection());
                    var target = PublicationTargetIdentifier.of("risk-scenario-draft-change");
                    var registry = context.getBean(EventPublicationRegistry.class);
                    var stored = registry.store(event, Stream.of(target));
                    assertThat(stored).hasSize(1);
                    repository.findCompletedPublications();
                    repository.findIncompletePublications();
                    repository.findIncompletePublicationsPublishedBefore(java.time.Instant.now());
                    repository.findIncompletePublicationsByEventAndTargetIdentifier(event, target);
                    repository.markCompleted(event, target, java.time.Instant.now());
                    repository.markCompleted(stored.iterator().next().getIdentifier(), java.time.Instant.now());
                    var sql = org.mockito.ArgumentCaptor.forClass(String.class);
                    try { verify(f.connection(), times(7)).prepareStatement(sql.capture()); }
                    catch (SQLException failure) { throw new AssertionError(failure); }
                    assertThat(sql.getAllValues()).containsExactlyInAnyOrderElementsOf(
                            RiskPublicationSqlCatalogTest.samples("").stream().map(RiskPublicationSqlCatalogTest.Sample::sql).toList());
                    verifyNoInteractions(ordinary);
                    int before = sql.getAllValues().size();
                    assertThatThrownBy(repository::deleteCompletedPublications).isInstanceOf(DataAccessException.class);
                    assertThat(before).isEqualTo(7);
                    assertThatThrownBy(() -> repository.deleteCompletedPublicationsBefore(java.time.Instant.now())).isInstanceOf(DataAccessException.class);
                    assertThatThrownBy(() -> repository.deletePublications(List.of(UUID.randomUUID()))).isInstanceOf(DataAccessException.class);
                    try { verify(f.connection(), times(7)).prepareStatement(anyString()); }
                    catch (SQLException failure) { throw new AssertionError(failure); }
                });
    }

    @Test void nativeMapperPreservesConfiguredLegacyTypeAndProjectedFields() throws Exception {
        for (Level level : List.of(Level.INFO, Level.DEBUG)) {
            var f = fixture();
            UUID id = UUID.randomUUID();
            Timestamp at = Timestamp.valueOf("2026-11-07 00:00:00");
            when(f.rows().next()).thenReturn(true, false);
            when(f.rows().getString("EVENT_TYPE")).thenReturn(String.class.getName());
            when(f.rows().getObject("ID")).thenReturn(id);
            when(f.rows().getTimestamp("PUBLICATION_DATE")).thenReturn(at);
            when(f.rows().getString("LISTENER_ID")).thenReturn("legacy-listener");
            when(f.rows().getString("SERIALIZED_EVENT")).thenReturn(SECRET);
            var serializer = mock(EventSerializer.class);
            when(serializer.deserialize(SECRET, String.class)).thenReturn("legacy value");
            nativeContext(f, serializer, new JdbcTemplate(f.source())).run(context -> {
                assertThat(context).hasNotFailed();
                withLogs(level, captured -> {
                    assertThat(context.getBean(EventPublicationRepository.class).findIncompletePublications())
                            .singleElement().satisfies(publication -> {
                                assertThat(publication.getIdentifier()).isEqualTo(id);
                                assertThat(publication.getPublicationDate()).isEqualTo(at.toInstant());
                                assertThat(publication.getTargetIdentifier()).isEqualTo(PublicationTargetIdentifier.of("legacy-listener"));
                                assertThat(publication.getEvent()).isEqualTo("legacy value");
                            });
                    assertThat(logs()).doesNotContain(SECRET);
                });
            });
        }
    }

    @Test void unknownEventTypeCannotReachNativeMapperWarningAtInfoOrDebug() throws Exception {
        for (Level level : List.of(Level.INFO, Level.DEBUG)) {
            var f = fixture();
            when(f.rows().next()).thenReturn(true);
            when(f.rows().getString("EVENT_TYPE")).thenReturn(SECRET);
            var serializer = mock(EventSerializer.class);
            nativeContext(f, serializer, new JdbcTemplate(f.source())).run(context -> {
                assertThat(context).hasNotFailed();
                withLogs(level, captured -> {
                    assertThatThrownBy(() -> context.getBean(EventPublicationRepository.class).findIncompletePublications())
                            .isInstanceOf(DataAccessException.class);
                    assertThat(logs()).doesNotContain(SECRET, "of unknown type");
                });
                verifyNoInteractions(serializer);
            });
        }
    }

    private static ApplicationContextRunner nativeContext(Fixture f, EventSerializer serializer, JdbcTemplate ordinary) throws ClassNotFoundException {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"),
                EventPublicationAutoConfiguration.class))
                .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false",
                        "spring.modulith.events.jdbc.use-legacy-structure=true", "spring.modulith.events.completion-mode=UPDATE")
                .withUserConfiguration(NativeGraph.class)
                .withBean(DataSource.class, f::source)
                .withBean("ordinaryJdbc", JdbcTemplate.class, () -> ordinary, definition -> definition.setPrimary(true))
                .withBean(EventSerializer.class, () -> serializer);
    }

    @Configuration(proxyBeanMethods = false)
    static class NativeGraph {
        @Bean RiskPublicationSqlCatalog publicationCatalog(JdbcRepositorySettings settings, Environment environment) {
            return new RiskPublicationSqlCatalog(settings, environment);
        }
        @Bean RiskPublicationJdbcTemplate riskPublicationJdbc(DataSource source, RiskPublicationSqlCatalog catalog, ApplicationContext context) {
            return new RiskPublicationJdbcTemplate(source, catalog, Set.of(Probe.class, String.class), context.getClassLoader());
        }
        @Bean static BeanFactoryPostProcessor bindPublicationTemplate() {
            return factory -> {
                var definition = factory.getBeanDefinition("jdbcEventPublicationRepository");
                if (!"jdbcEventPublicationRepository".equals(definition.getFactoryMethodName())
                        || definition.getFactoryBeanName() == null || !definition.getConstructorArgumentValues().isEmpty())
                    throw new IllegalStateException("Unexpected native publication factory");
                definition.getConstructorArgumentValues().addIndexedArgumentValue(0, new RuntimeBeanReference("riskPublicationJdbc"));
            };
        }
    }

    private record Fixture(DataSource source, Connection connection, PreparedStatement statement, ResultSet rows,
                           RiskPublicationJdbcTemplate template) {}
    private static Fixture fixture() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(7);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.getString("EVENT_TYPE")).thenReturn(Probe.class.getName());
        var template = new RiskPublicationJdbcTemplate(source, RiskPublicationSqlCatalogTest.catalog(),
                Set.of(Probe.class), RiskPublicationJdbcTemplateTest.class.getClassLoader());
        clearInvocations(source, connection, statement, rows);
        return new Fixture(source, connection, statement, rows, template);
    }
    private static void insert(Fixture fixture) {
        fixture.template().update(INSERT, RiskPublicationSqlCatalogTest.samples("").getFirst().arguments());
    }
    private static boolean execution(Method method) {
        return Set.of("execute", "query", "queryForStream", "queryForObject", "queryForMap",
                "queryForList", "queryForRowSet", "update", "batchUpdate", "call").contains(method.getName());
    }
    private static boolean nativeSignature(Method method) {
        var types = List.of(method.getParameterTypes());
        return (method.getName().equals("update") && types.equals(List.of(String.class, Object[].class)))
                || (method.getName().equals("execute") && types.equals(List.of(String.class)))
                || (method.getName().equals("query") && (types.equals(List.of(String.class, ResultSetExtractor.class))
                    || types.equals(List.of(String.class, ResultSetExtractor.class, Object[].class))));
    }
    private static Object argument(Class<?> type, AtomicInteger callbacks) {
        if (type == String.class) return SECRET + " sql";
        if (type == int.class) return 1;
        if (type == boolean.class) return true;
        if (type == Object[].class) return new Object[]{SECRET};
        if (type == int[].class) return new int[]{Types.VARCHAR};
        if (type == String[].class) return new String[]{SECRET};
        if (type == Class.class) return String.class;
        if (type == List.class || type == Collection.class) return List.of(new Object[]{SECRET});
        if (type.isInterface()) return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            callbacks.incrementAndGet(); throw new AssertionError(SECRET + " callback");
        });
        return null;
    }
    private static SQLException secretSql(String state) {
        SQLException error = new SQLException(SECRET + " SQL/bind", state, new IllegalStateException(SECRET + " cause"));
        error.addSuppressed(new IllegalArgumentException(SECRET + " suppressed"));
        error.setNextException(new SQLException(SECRET + " next"));
        return error;
    }
    private static void assertSafe(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            assertThat(cursor.getMessage()).doesNotContain(SECRET);
            assertThat(cursor.getSuppressed()).isEmpty();
            if (cursor instanceof SQLException sql) {
                assertThat((Object) sql.getNextException()).isNull();
                assertThat(sql.getSQLState() == null || sql.getSQLState().matches("[0-9A-Z]{5}")).isTrue();
            }
        }
    }
    @FunctionalInterface private interface FailureCase { void run(Fixture fixture) throws Exception; }
    @FunctionalInterface private interface LogCase { void run(ListAppender<ILoggingEvent> logs) throws Exception; }
    private static ListAppender<ILoggingEvent> captured;
    private static String logs() {
        return captured.list.stream().map(event -> event.getFormattedMessage() + (event.getThrowableProxy() == null
                ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()))).collect(java.util.stream.Collectors.joining("\n"));
    }
    private static void withLogs(Level level, LogCase action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); captured = appender;
        logger.addAppender(appender); logger.setLevel(level);
        try { action.run(appender); }
        finally { logger.detachAppender(appender); logger.setLevel(before); appender.stop(); captured = null; }
    }
}
