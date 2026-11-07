package com.mightyoung.softwarefactory.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.SmartDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/** Capability misuse and ordinary lifecycle tests only; the actual listener graph is tested separately. */
class NativePublicationReadJdbcTest {
    private static final String SECRET = "PRIVATE_READ_CONNECTION_SENTINEL";
    private static final String SELECT = "select ID, EVENT_TYPE, LISTENER_ID from EVENT_PUBLICATION";
    private static final RiskPublicationSqlCatalog.Operation READ = new RiskPublicationSqlCatalog.Operation(
            "Risk publication incomplete", SELECT, RiskPublicationSqlCatalog.Kind.QUERY, List.of());

    @Test void ordinaryNoneClosesOneConnectionWithoutTransactionControlsAndPassesRawIdentityToSmartSource() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.read.query(READ, new Object[0], rows -> rows.next())).isEqualTo(true);
            assertThat(fixture.source.controls).containsExactly("close");
            assertThat(fixture.source.shouldCloseArguments).hasSize(1);
            assertThat(fixture.source.shouldCloseArguments.getFirst()).isSameAs(fixture.source.raw.getFirst());
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        }
    }

    @Test void existingHolderAndReferenceCountArePreservedWithoutClosingIt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection original = fixture.source.getConnection();
            var holder = new ConnectionHolder(original);
            holder.requested();
            TransactionSynchronizationManager.bindResource(fixture.source, holder);
            try {
                assertThat(fixture.read.query(READ, new Object[0], rows -> rows.next())).isEqualTo(true);
                assertThat(TransactionSynchronizationManager.getResource(fixture.source)).isSameAs(holder);
                assertThat(holder.getConnection()).isSameAs(original);
                assertThat(holder.isOpen()).isTrue();
                holder.released();
                assertThat(holder.isOpen()).isFalse();
                assertThat(fixture.source.raw).hasSize(1);
                assertThat(fixture.source.controls).isEmpty();
            } finally { TransactionSynchronizationManager.unbindResource(fixture.source); original.close(); }
        }
    }

    @Test void syncOnlyDelaysReleaseAndSanitizesRealAfterCompletionCloseFailureAtInfoAndDebug() {
        for (Level level : List.of(Level.INFO, Level.DEBUG)) {
            try (Fixture fixture = new Fixture()) {
                Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                Level previous = root.getLevel(); root.setLevel(level);
                ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); root.addAppender(logs);
                TransactionSynchronizationManager.initSynchronization();
                try {
                    assertThat(fixture.read.query(READ, new Object[0], rows -> rows.next())).isEqualTo(true);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(fixture.source);
                    assertThat(holder).isNotNull();
                    assertThat(fixture.source.controls).isEmpty();
                    // Keep the real holder open through beforeCompletion so actual afterCompletion performs close.
                    holder.requested();
                    var synchronizations = TransactionSynchronizationManager.getSynchronizations();
                    synchronizations.forEach(TransactionSynchronization::beforeCompletion);
                    assertThat(TransactionSynchronizationManager.getResource(fixture.source)).isSameAs(holder);
                    fixture.source.failClose = true;
                    synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
                    assertThat(fixture.source.controls).containsExactly("close");
                    assertThat(fixture.source.shouldCloseArguments).hasSize(1);
            assertThat(fixture.source.shouldCloseArguments.getFirst()).isSameAs(fixture.source.raw.getFirst());
                    assertThat(TransactionSynchronizationManager.getResource(fixture.source)).isNull();
                    assertThat(render(logs)).doesNotContain(SECRET);
                } finally {
                    TransactionSynchronizationManager.clearSynchronization();
                    root.detachAppender(logs); logs.stop(); root.setLevel(previous);
                }
            }
        }
    }

    @Test void acquisitionFailureIsSafeBeforeAnyFrameworkLogging() {
        for (Level level : List.of(Level.INFO, Level.DEBUG)) {
            try (Fixture fixture = new Fixture()) {
                Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                Level previous = root.getLevel(); root.setLevel(level);
                ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); root.addAppender(logs);
                try {
                    fixture.source.failAcquire = true;
                    assertThatThrownBy(() -> fixture.read.query(READ, new Object[0], rows -> rows.next()))
                            .isInstanceOf(IllegalStateException.class).hasMessage("Native publication read unavailable")
                            .hasNoCause();
                    assertThat(render(logs)).doesNotContain(SECRET);
                } finally { root.detachAppender(logs); logs.stop(); root.setLevel(previous); }
            }
        }
    }

    @Test void forbiddenCursorMovementIsStickyEvenWhenExtractorSwallowsTheRefusal() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection original = fixture.source.getConnection();
            var holder = new ConnectionHolder(original);
            TransactionSynchronizationManager.bindResource(fixture.source, holder);
            try {
                assertThatThrownBy(() -> fixture.read.query(READ, new Object[0], rows -> {
                    assertThatThrownBy(rows::first).isInstanceOf(IllegalStateException.class);
                    return "swallowed";
                })).isInstanceOf(IllegalStateException.class);
                assertThat(holder.isRollbackOnly()).isTrue();
            } finally { TransactionSynchronizationManager.unbindResource(fixture.source); original.close(); }
        }
    }

    @Test void retainedRowsAndClosedStateCannotOutliveTheReadEpoch() {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<ResultSet> retained = new AtomicReference<>();
            fixture.read.query(READ, new Object[0], rows -> { retained.set(rows); return rows.next(); });
            assertThatThrownBy(retained.get()::next).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(retained.get()::isClosed).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(retained.get()::close).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void crossThreadSwallowedRefusalPoisonsTheActualRead() {
        try (Fixture fixture = new Fixture()) {
            assertThatThrownBy(() -> fixture.read.query(READ, new Object[0], rows -> {
                AtomicReference<Throwable> refusal = new AtomicReference<>();
                Thread thread = new Thread(() -> { try { rows.next(); } catch (Throwable failure) { refusal.set(failure); } });
                thread.start();
                try { thread.join(); } catch (InterruptedException failure) { throw new IllegalStateException(); }
                assertThat(refusal.get()).isInstanceOf(IllegalStateException.class);
                return "swallowed";
            })).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void unknownAndCrossPairedRowsAreRejectedBeforeMapping() {
        try (Fixture fixture = new Fixture()) {
            fixture.observer.update("update EVENT_PUBLICATION set LISTENER_ID='asset-foundation-change-trace'");
            AtomicReference<Boolean> mapped = new AtomicReference<>(false);
            assertThatThrownBy(() -> fixture.read.query(READ, new Object[0], rows -> {
                if (rows.next()) mapped.set(true);
                return null;
            })).isInstanceOf(IllegalStateException.class);
            assertThat(mapped.get()).isFalse();
        }
    }

    @Test void mapperCloseIsSentOnceAndAFailedCloseCannotBeSwallowedOrRetried() {
        for (boolean fail : List.of(false, true)) {
            try (Fixture fixture = new Fixture()) {
                fixture.source.failRowsClose = fail;
                Runnable query = () -> fixture.read.query(READ, new Object[0], rows -> {
                    try { rows.close(); } catch (RuntimeException | SQLException ignored) { }
                    return 1;
                });
                if (fail) assertThatThrownBy(query::run).isInstanceOf(IllegalStateException.class);
                else query.run();
                assertThat(fixture.source.rowCloseCalls).isEqualTo(1);
                assertThat(fixture.source.statementCloseCalls).isEqualTo(1);
            }
        }
    }

    @Test void statementCleanupFailureIsNeverRetriedAndDoesNotReturnSuccess() {
        try (Fixture fixture = new Fixture()) {
            fixture.source.failStatementClose = true;
            assertThatThrownBy(() -> fixture.read.query(READ, new Object[0], rows -> rows.next())).isInstanceOf(IllegalStateException.class);
            assertThat(fixture.source.rowCloseCalls).isEqualTo(1);
            assertThat(fixture.source.statementCloseCalls).isEqualTo(1);
        }
    }

    private static String render(ListAppender<ILoggingEvent> logs) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            text.append(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) text.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        return text.toString();
    }
    private static final class Fixture implements AutoCloseable {
        final EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        final JdbcTemplate observer = new JdbcTemplate(database);
        final ObservedSource source = new ObservedSource(database);
        final GenericApplicationContext metadataContext = new GenericApplicationContext();
        final Object frame = new Object();
        final NativePublicationReadJdbc read = new NativePublicationReadJdbc(source, new JdbcTemplate(source),
                new NativePublicationInventory(metadataContext), () -> frame);
        Fixture() {
            observer.execute("create table EVENT_PUBLICATION(ID uuid, EVENT_TYPE varchar(512), LISTENER_ID varchar(512))");
            observer.update("insert into EVENT_PUBLICATION values(?,?,?)", UUID.randomUUID(),
                    "com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceQuarantinedEvent",
                    "com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionWakeListener.wake(com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceQuarantinedEvent)");
        }
        @Override public void close() { metadataContext.close(); database.shutdown(); }
    }
    private static final class ObservedSource extends AbstractDataSource implements SmartDataSource {
        final DataSource delegate;
        final List<Connection> raw = new ArrayList<>();
        final List<Connection> shouldCloseArguments = new ArrayList<>();
        final List<String> controls = new ArrayList<>();
        boolean failAcquire, failClose, failRowsClose, failStatementClose;
        int rowCloseCalls, statementCloseCalls;
        ObservedSource(DataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException {
            if (failAcquire) throw new SQLException(SECRET, "08001");
            Connection actual = delegate.getConnection();
            Connection result = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("toString")) return SECRET;
                if (List.of("commit", "rollback", "close").contains(method.getName())) controls.add(method.getName());
                try {
                    Object value = method.invoke(actual, args);
                    if (method.getName().equals("prepareStatement")) {
                        java.sql.PreparedStatement statement = (java.sql.PreparedStatement) value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class}, (statementProxy, statementMethod, parameters) -> {
                            if (statementMethod.getName().equals("close")) statementCloseCalls++;
                            try {
                                Object statementValue = statementMethod.invoke(statement, parameters);
                                if (statementMethod.getName().equals("close") && failStatementClose) throw new SQLException(SECRET, "XX000");
                                if (statementValue instanceof ResultSet rows) {
                                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (rowProxy, rowMethod, rowParameters) -> {
                                        if (rowMethod.getName().equals("close")) rowCloseCalls++;
                                        try {
                                            Object rowValue = rowMethod.invoke(rows, rowParameters);
                                            if (rowMethod.getName().equals("close") && failRowsClose) throw new SQLException(SECRET, "XX000");
                                            return rowValue;
                                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                                    });
                                }
                                return statementValue;
                            } catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
                    }
                    if (method.getName().equals("close") && failClose) throw new SQLException(SECRET, "XX000");
                    return value;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            raw.add(result); return result;
        }
        @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("Unavailable"); }
        @Override public boolean shouldClose(Connection connection) { shouldCloseArguments.add(connection); return true; }
    }
}
