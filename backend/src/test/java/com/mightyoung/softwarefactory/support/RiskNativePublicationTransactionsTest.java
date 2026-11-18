package com.mightyoung.softwarefactory.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual native singleton graph plus real H2 SQL. Does not prove PG/NAS, source borrowing or recovery. */
class RiskNativePublicationTransactionsTest {
    static final String SECRET = "PRIVATE_NATIVE_GRAPH_SENTINEL";
    static final PublicationTargetIdentifier TARGET = PublicationTargetIdentifier.of("risk-scenario-draft-change");
    record Probe(String marker) {}

    @Test void nativeOwnedGraphExecutesAllSevenSqlShapesAndRequiredCreateUsesOneLease() throws Exception {
        graph(graph -> {
            Probe event = new Probe("first");
            var stored = graph.registry.store(event, Stream.of(TARGET));
            assertThat(stored).hasSize(1);
            assertThat(graph.source.leases).hasSize(1);
            assertThat(graph.source.leases.getFirst().controls).containsExactly("commit", "close");
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class)).isEqualTo(1);
            assertThat(graph.repository.findCompletedPublications()).isEmpty();
            assertThat(graph.repository.findIncompletePublications()).hasSize(1);
            assertThat(graph.repository.findIncompletePublicationsPublishedBefore(Instant.now().plusSeconds(60))).hasSize(1);
            assertThat(graph.repository.findIncompletePublicationsByEventAndTargetIdentifier(event, TARGET)).isPresent();
            graph.repository.markCompleted(event, TARGET, Instant.now());
            graph.registry.markCompleted(event, TARGET);
            assertThat(graph.repository.findCompletedPublications()).hasSize(1);
            assertThat(graph.owner.template().getDataSource()).isNotSameAs(graph.source)
                    .isNotInstanceOf(org.springframework.core.InfrastructureProxy.class);
            assertThat(graph.source.sql).containsAll(RiskPublicationSqlCatalogTest.samples("").stream().map(RiskPublicationSqlCatalogTest.Sample::sql).toList());
            unbound();
        });
    }

    @Test void bootTransactionsKeepOriginalManagerAndNativeForeignHolderIsRefused() throws Exception {
        graph(graph -> {
            assertThat(graph.boot).isInstanceOf(JdbcTransactionManager.class);
            graph.business.write();
            assertThat(graph.observer.queryForObject("select count(*) from ORDINARY", Integer.class)).isEqualTo(1);
            assertThat(graph.source.leases).hasSize(1);
            graph.source.leases.clear();
            new org.springframework.transaction.support.TransactionTemplate(graph.boot).execute(status -> {
                Object holder = TransactionSynchronizationManager.getResource(graph.source);
                assertThatThrownBy(() -> graph.registry.store(new Probe("foreign"), Stream.of(TARGET)))
                        .isInstanceOf(RiskNativePublicationTransactions.Failure.class);
                assertThat(TransactionSynchronizationManager.getResource(graph.source)).isSameAs(holder);
                return null;
            });
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class)).isZero();
            unbound();
        });
    }

    @Test void boundTemplateHasNoStandaloneFallbackAndUnsupportedNativeMethodsRefuseBeforeBorrow() throws Exception {
        graph(graph -> {
            assertThatThrownBy(() -> graph.owner.template().update(RiskPublicationSqlCatalog.SQL_STATEMENT_INSERT.formatted("EVENT_PUBLICATION"),
                    UUID.randomUUID(), Probe.class.getName(), TARGET.getValue(), java.sql.Timestamp.from(Instant.now()), "x"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(graph.repository::deleteCompletedPublications).isInstanceOf(IllegalStateException.class);
            assertThat(graph.source.leases).isEmpty();
            unbound();
        });
    }

    @Test void registryLegacyProcessingAndFailedRemainNoOpButUseTheirOwnedTransaction() throws Exception {
        graph(graph -> {
            Probe event = new Probe("lifecycle");
            graph.registry.store(event, Stream.of(TARGET));
            graph.source.leases.clear();
            graph.source.sql.clear();
            graph.registry.markProcessing(event, TARGET);
            graph.registry.markFailed(event, TARGET);
            assertThat(graph.source.leases).hasSize(2).allSatisfy(lease -> assertThat(lease.controls).containsExactly("commit", "close"));
            assertThat(graph.source.sql).isEmpty();
            assertThat(graph.registry.findIncompletePublications()).hasSize(1);
            unbound();
        });
    }

    @Test void commitThenThrowPreservesUnknownAndDoesNotRollbackResetOrClose() throws Exception {
        graph(graph -> {
            graph.source.failCommit = true;
            assertThatThrownBy(() -> graph.registry.store(new Probe("unknown"), Stream.of(TARGET)))
                    .isInstanceOf(RiskNativePublicationTransactions.Failure.class)
                    .satisfies(error -> assertThat(((RiskNativePublicationTransactions.Failure) error).transactionOutcome()).isEqualTo("COMMIT_UNKNOWN"));
            assertThat(graph.source.leases).singleElement().satisfies(lease -> assertThat(lease.controls).containsExactly("commit"));
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class)).isEqualTo(1);
            unbound();
        });
    }

    @Test void completedCommitAndCloseFailurePreserveCommittedFact() throws Exception {
        graph(graph -> {
            graph.source.failClose = true;
            assertThatThrownBy(() -> graph.registry.store(new Probe("committed"), Stream.of(TARGET)))
                    .isInstanceOf(RiskNativePublicationTransactions.Failure.class)
                    .satisfies(error -> {
                        var safe = (RiskNativePublicationTransactions.Failure) error;
                        assertThat(safe.transactionOutcome()).isEqualTo("COMMITTED");
                        assertThat(safe.cleanupOutcome()).isEqualTo("CLOSE_UNKNOWN");
                    });
            assertThat(graph.source.leases).singleElement().satisfies(lease -> assertThat(lease.controls).containsExactly("commit", "close"));
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class)).isEqualTo(1);
            unbound();
        });
    }

    @Test void registryRequiresNewCompletionAcknowledgesOnlyAfterItsOwnedCommit() throws Exception {
        graph(graph -> {
            Probe event = new Probe("registry-ack");
            graph.registry.store(event, Stream.of(TARGET));
            graph.source.leases.clear(); graph.source.sql.clear();
            graph.registry.markCompleted(event, TARGET);
            assertThat(graph.source.leases).singleElement().satisfies(lease -> assertThat(lease.controls).containsExactly("commit", "close"));
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION where COMPLETION_DATE is not null", Integer.class)).isEqualTo(1);
            assertThat(graph.source.sql).contains(RiskPublicationSqlCatalog.SQL_STATEMENT_UPDATE_BY_ID.formatted("EVENT_PUBLICATION"));
            unbound();
        });
    }

    @Test void incorrectAdvisorOrderingFailsClosedBeforeBorrowing() throws Exception {
        graph(graph -> {
            assertThatThrownBy(() -> graph.registry.store(new Probe("order"), Stream.of(TARGET)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Risk native publication unavailable");
            assertThat(graph.source.leases).isEmpty();
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class)).isZero();
            unbound();
        }, true);
    }

    @Test void serializerRuntimeAndErrorAreSanitizedBeforeNativeTransactionDebugLogging() throws Exception {
        for (Level level : List.of(Level.INFO, Level.DEBUG)) {
            for (boolean error : List.of(false, true)) {
                graph(graph -> {
                    EventSerializer serializer = graph.serializer;
                    when(serializer.serialize(any(Probe.class))).thenAnswer(call -> {
                        if (error) throw new AssertionError(SECRET, new IllegalStateException(SECRET));
                        throw new IllegalStateException(SECRET, new SQLException(SECRET, "XX000"));
                    });
                    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                    List<Logger> loggers = List.of(root,
                            (Logger) LoggerFactory.getLogger("org.springframework.transaction.interceptor.TransactionInterceptor"),
                            (Logger) LoggerFactory.getLogger("org.springframework.modulith.events.core.DefaultEventPublicationRegistry"),
                            (Logger) LoggerFactory.getLogger("com.mightyoung.softwarefactory.support.RiskJdbcTransactionOwner$Manager"));
                    List<Level> previous = loggers.stream().map(Logger::getLevel).toList();
                    ListAppender<ILoggingEvent> events = new ListAppender<>(); events.start(); root.addAppender(events);
                    for (Logger logger : loggers) logger.setLevel(level);
                    try {
                        assertThatThrownBy(() -> graph.registry.store(new Probe("sanitized"), Stream.of(TARGET)))
                                .isInstanceOf(IllegalStateException.class).hasMessage("Risk native publication unavailable")
                                .satisfies(failure -> { assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty(); });
                        StringBuilder rendered = new StringBuilder();
                        for (ILoggingEvent event : events.list) {
                            rendered.append(event.getFormattedMessage());
                            if (event.getThrowableProxy() != null) rendered.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
                        }
                        assertThat(rendered.toString()).doesNotContain(SECRET, "INSERT INTO", "SERIALIZED_EVENT");
                    } finally {
                        root.detachAppender(events); events.stop();
                        for (int i = 0; i < loggers.size(); i++) loggers.get(i).setLevel(previous.get(i));
                    }
                    assertThat(graph.source.leases).singleElement().satisfies(lease -> assertThat(lease.controls).containsExactly("rollback", "close"));
                    assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class)).isZero();
                    unbound();
                });
            }
        }
    }

    static final class PRIVATE_NATIVE_GRAPH_SENTINEL {}
    @Test void nativeUnknownEventAndListenerAreRejectedBeforeDebugLogAndConnectionBorrow() throws Exception {
        graph(graph -> {
            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            Level previous = root.getLevel(); root.setLevel(Level.DEBUG);
            ListAppender<ILoggingEvent> events = new ListAppender<>(); events.start(); root.addAppender(events);
            try {
                var unknown = PublicationTargetIdentifier.of(SECRET);
                assertThatThrownBy(() -> graph.registry.store(new PRIVATE_NATIVE_GRAPH_SENTINEL(), Stream.of(TARGET))).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> graph.registry.store(new Probe("ok"), Stream.of(unknown))).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> graph.registry.markCompleted(new Probe("ok"), unknown)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> graph.registry.store(new Probe("ok"), Stream.of(TARGET, unknown))).isInstanceOf(IllegalStateException.class);
                assertThat(graph.source.leases).isEmpty();
                String rendered = events.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
                assertThat(rendered).doesNotContain(SECRET);
            } finally { root.detachAppender(events); events.stop(); root.setLevel(previous); }
            unbound();
        });
    }
    @Test void storedUnknownListenerIsRejectedBeforeNativeMappingOrDeserialization() throws Exception {
        graph(graph -> {
            graph.observer.update("insert into EVENT_PUBLICATION values(?,?,?,?,?,null)", UUID.randomUUID(), Probe.class.getName(), SECRET,
                    java.sql.Timestamp.from(Instant.now()), "fixture");
            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            Level previous = root.getLevel(); root.setLevel(Level.DEBUG);
            ListAppender<ILoggingEvent> events = new ListAppender<>(); events.start(); root.addAppender(events);
            try {
                assertThatThrownBy(graph.repository::findIncompletePublications).isInstanceOf(IllegalStateException.class);
                verify(graph.serializer, never()).deserialize(anyString(), any());
                StringBuilder rendered = new StringBuilder();
                for (ILoggingEvent event : events.list) {
                    rendered.append(event.getFormattedMessage());
                    if (event.getThrowableProxy() != null) rendered.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
                }
                assertThat(rendered.toString()).doesNotContain(SECRET);
            } finally { root.detachAppender(events); events.stop(); root.setLevel(previous); }
            unbound();
        });
    }

    @Test void directUuidAndPublicationCompletionCannotAcknowledgeOutsideRegistryWitness() throws Exception {
        graph(graph -> {
            Probe event = new Probe("unwitnessed");
            var publication = graph.registry.store(event, Stream.of(TARGET)).iterator().next();
            graph.source.leases.clear(); graph.source.sql.clear();
            assertThatThrownBy(() -> graph.repository.markCompleted(publication.getIdentifier(), Instant.now())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> graph.repository.markCompleted(publication, Instant.now())).isInstanceOf(IllegalStateException.class);
            var custom = mock(org.springframework.modulith.events.core.TargetEventPublication.class);
            assertThatThrownBy(() -> graph.repository.create(custom)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(custom);
            assertThat(graph.source.leases).isEmpty();
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION where COMPLETION_DATE is not null", Integer.class)).isZero();
            unbound();
        });
    }
    @Test void registryCacheMissCompletesExistingPublicationThroughEventAndFixedTarget() throws Exception {
        graph(graph -> {
            graph.observer.update("insert into EVENT_PUBLICATION values(?,?,?,?,?,null)", UUID.randomUUID(), Probe.class.getName(), TARGET.getValue(),
                    java.sql.Timestamp.from(Instant.now()), "fallback");
            graph.registry.markCompleted(new Probe("fallback"), TARGET);
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION where COMPLETION_DATE is not null", Integer.class)).isEqualTo(1);
            assertThat(graph.source.sql).contains(RiskPublicationSqlCatalog.SQL_STATEMENT_UPDATE_BY_EVENT_AND_LISTENER_ID.formatted("EVENT_PUBLICATION"));
            assertThat(graph.source.leases).singleElement().satisfies(lease -> assertThat(lease.controls).containsExactly("commit", "close"));
            unbound();
        });
    }

    @Test void emptyRegistryDestroyUsesOneOwnedReadOnlyTransactionWithoutDisposalWarning() throws Exception {
        verifyDestroy(false);
    }
    @Test void knownIncompleteRegistryDestroyRetainsPublicationAndDoesNotLogPayload() throws Exception {
        verifyDestroy(true);
    }
    private static void verifyDestroy(boolean pending) throws Exception {
        for (Level level : List.of(Level.INFO, Level.DEBUG)) {
            graph(graph -> {
                if (pending) graph.registry.store(new Probe(SECRET), Stream.of(TARGET));
                int before = graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class);
                graph.source.leases.clear(); graph.source.sql.clear();
                Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                Logger nativeLogger = (Logger) LoggerFactory.getLogger("org.springframework.modulith.events.core.DefaultEventPublicationRegistry");
                Level previous = root.getLevel(), nativePrevious = nativeLogger.getLevel();
                root.setLevel(level); nativeLogger.setLevel(level);
                ListAppender<ILoggingEvent> events = new ListAppender<>(); events.start(); root.addAppender(events);
                try {
                    graph.close.run();
                    assertThat(graph.source.leases).singleElement().satisfies(lease -> assertThat(lease.controls).containsExactly("commit", "close"));
                    assertThat(graph.source.sql).containsExactly(RiskPublicationSqlCatalog.SQL_STATEMENT_FIND_INCOMPLETE.formatted("EVENT_PUBLICATION"));
                    assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class)).isEqualTo(before);
                    assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION where COMPLETION_DATE is not null", Integer.class)).isZero();
                    StringBuilder rendered = new StringBuilder();
                    for (ILoggingEvent event : events.list) {
                        rendered.append(event.getFormattedMessage());
                        if (event.getThrowableProxy() != null) rendered.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
                    }
                    assertThat(rendered.toString()).doesNotContain(SECRET, "Invocation of destroy method failed", "Risk native publication unavailable");
                    unbound();
                } finally { root.detachAppender(events); events.stop(); root.setLevel(previous); nativeLogger.setLevel(nativePrevious); }
            });
        }
    }

    enum DeserializeFault { SQL, RUNTIME, ERROR, NULL, WRONG_TYPE }
    @ParameterizedTest @EnumSource(DeserializeFault.class)
    void rawNativeDestroyLazyDeserializationUsesSafeRepositoryLocalSerializer(DeserializeFault fault) throws Exception {
        for (Level level : List.of(Level.INFO, Level.DEBUG)) {
            graph(graph -> {
                graph.registry.store(new Probe(SECRET), Stream.of(TARGET));
                when(graph.serializer.deserialize(anyString(), eq(Probe.class))).thenAnswer(call -> {
                    return switch (fault) {
                        case SQL -> throw new SQLException(SECRET, "XX000", new IllegalStateException(SECRET));
                        case RUNTIME -> throw new IllegalStateException(SECRET, new SQLException(SECRET, "XX000"));
                        case ERROR -> throw new AssertionError(SECRET, new IllegalStateException(SECRET));
                        case NULL -> null;
                        case WRONG_TYPE -> SECRET;
                    };
                });
                graph.source.leases.clear(); graph.source.sql.clear();
                Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                Logger nativeLogger = (Logger) LoggerFactory.getLogger("org.springframework.modulith.events.core.DefaultEventPublicationRegistry");
                Level previous = root.getLevel(), nativePrevious = nativeLogger.getLevel();
                root.setLevel(level); nativeLogger.setLevel(level);
                ListAppender<ILoggingEvent> events = new ListAppender<>(); events.start(); root.addAppender(events);
                java.io.PrintStream previousOut = System.out, previousErr = System.err;
                var stdout = new java.io.ByteArrayOutputStream(); var stderr = new java.io.ByteArrayOutputStream();
                var captureOut = new java.io.PrintStream(stdout, true, java.nio.charset.StandardCharsets.UTF_8);
                var captureErr = new java.io.PrintStream(stderr, true, java.nio.charset.StandardCharsets.UTF_8);
                System.setOut(captureOut); System.setErr(captureErr);
                try {
                    graph.close.run();
                    StringBuilder rendered = new StringBuilder();
                    for (ILoggingEvent event : events.list) {
                        rendered.append(event.getFormattedMessage());
                        if (event.getThrowableProxy() != null) rendered.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
                    }
                    assertThat(rendered.toString()).contains("Invocation of destroy method failed", "Risk native publication unavailable").doesNotContain(SECRET);
                    assertThat(stdout.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(SECRET);
                    assertThat(stderr.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(SECRET);
                    assertThat(graph.source.leases).singleElement().satisfies(lease -> assertThat(lease.controls).containsExactly("commit", "close"));
                    assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION where COMPLETION_DATE is null", Integer.class)).isEqualTo(1);
                    verify(graph.serializer, times(1)).deserialize(anyString(), eq(Probe.class));
                    unbound();
                } finally {
                    System.setOut(previousOut); System.setErr(previousErr); captureOut.close(); captureErr.close();
                    root.detachAppender(events); events.stop(); root.setLevel(previous); nativeLogger.setLevel(nativePrevious);
                }
            });
        }
    }

    @Test void repositorySerializerNormalizesNativeToStringWithinItsFailureBoundary() throws Exception {
        graph(graph -> {
            EventSerializer safe = graph.owner.serializer(graph.serializer);
            Probe event = new Probe("serialization");
            Object value = mock(Object.class);
            when(value.toString()).thenReturn("normalized");
            when(graph.serializer.serialize(event)).thenReturn(value);
            assertThat(safe.serialize(event)).isEqualTo("normalized");
            for (DeserializeFault fault : List.of(DeserializeFault.SQL, DeserializeFault.RUNTIME, DeserializeFault.ERROR, DeserializeFault.NULL)) {
                doAnswer(call -> {
                    return switch (fault) {
                        case SQL -> throw new SQLException(SECRET, "XX000");
                        case RUNTIME -> throw new IllegalStateException(SECRET);
                        case ERROR -> throw new AssertionError(SECRET);
                        case NULL -> null;
                        default -> throw new AssertionError("unsupported");
                    };
                }).when(value).toString();
                assertThatThrownBy(() -> safe.serialize(event)).isInstanceOf(IllegalStateException.class)
                        .hasMessage("Risk native publication unavailable")
                        .satisfies(failure -> { assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty(); });
            }
        });
    }

    private static void unbound() {
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }
    private record Graph(ObservedSource source, JdbcTemplate observer, EventPublicationRegistry registry,
            EventPublicationRepository repository, RiskNativePublicationTransactions owner, PlatformTransactionManager boot,
            OrdinaryBusiness business, EventSerializer serializer, Runnable close) {}

    private static void graph(Consumer<Graph> assertions) throws Exception { graph(assertions, false); }
    private static void graph(Consumer<Graph> assertions, boolean wrongOrder) throws Exception {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            JdbcTemplate observer = new JdbcTemplate(database);
            observer.execute("create table EVENT_PUBLICATION(ID uuid primary key, EVENT_TYPE varchar(512), LISTENER_ID varchar(512), PUBLICATION_DATE timestamp, SERIALIZED_EVENT varchar(4096), COMPLETION_DATE timestamp)");
            observer.execute("create table ORDINARY(ID integer)");
            ObservedSource source = new ObservedSource(database);
            EventSerializer serializer = mock(EventSerializer.class);
            when(serializer.serialize(any(Probe.class))).thenAnswer(invocation -> ((Probe) invocation.getArgument(0)).marker());
            when(serializer.deserialize(anyString(), eq(Probe.class))).thenAnswer(invocation -> new Probe(invocation.getArgument(0)));
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,
                            TransactionAutoConfiguration.class, EventPublicationAutoConfiguration.class,
                            Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration")))
                    .withUserConfiguration(NativeConfiguration.class)
                    .withInitializer(context -> {
                        if (wrongOrder) context.addBeanFactoryPostProcessor(factory -> factory
                                .getBeanDefinition("org.springframework.transaction.config.internalTransactionAdvisor")
                                .getPropertyValues().add("order", Ordered.LOWEST_PRECEDENCE));
                    })
                    .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false",
                            "spring.modulith.events.jdbc.use-legacy-structure=true", "spring.modulith.events.completion-mode=UPDATE")
                    .withBean(DataSource.class, () -> source)
                    .withBean("ordinaryJdbc", JdbcTemplate.class, () -> new JdbcTemplate(source), definition -> definition.setPrimary(true))
                    .withBean(EventSerializer.class, () -> serializer)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
                        assertThat(context.getBeansOfType(EventPublicationRegistry.class)).hasSize(1);
                        assertThat(context.getBeansOfType(EventPublicationRepository.class)).hasSize(1);
                        assertThat(context.getBeansOfType(EventSerializer.class)).hasSize(1);
                        assertThat(context.getBean(EventSerializer.class)).isSameAs(serializer);
                        source.leases.clear(); source.sql.clear();
                        assertions.accept(new Graph(source, observer, context.getBean(EventPublicationRegistry.class),
                                context.getBean(EventPublicationRepository.class), context.getBean(RiskNativePublicationTransactions.class),
                                context.getBean("transactionManager", PlatformTransactionManager.class), context.getBean(OrdinaryBusiness.class), serializer, context::close));
                    });
        } finally { database.shutdown(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 100)
    static class NativeConfiguration {
        @Bean RiskNativePublicationTransactions nativeTransactions(DataSource source, ObjectProvider<PlatformTransactionManager> original,
                ObjectProvider<EventPublicationRegistry> registry, ObjectProvider<EventPublicationRepository> repository,
                JdbcRepositorySettings settings, Environment environment, ApplicationContext context) {
            return new RiskNativePublicationTransactions(source, original::getObject, registry::getObject, repository::getObject,
                    settings, environment, Set.of(Probe.class), context.getClassLoader());
        }
        @Bean RiskPublicationJdbcTemplate riskPublicationJdbc(RiskNativePublicationTransactions owner) { return owner.template(); }
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        Advisor nativeOuter(RiskNativePublicationTransactions owner) { return owner.outerAdvisor(); }
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        Advisor nativeInner(RiskNativePublicationTransactions owner) { return owner.sanitizingAdvisor(); }
        @Bean TransactionManagementConfigurer nativeConfigurer(ObjectProvider<RiskNativePublicationTransactions> owner) {
            return () -> owner.getObject().transactionConfigurer().annotationDrivenTransactionManager();
        }
        @Bean OrdinaryBusiness business(JdbcTemplate jdbc) { return new OrdinaryBusiness(jdbc); }
        @Bean static BeanFactoryPostProcessor nativeTemplateBinding() {
            return factory -> {
                var definition = factory.getBeanDefinition("jdbcEventPublicationRepository");
                definition.getConstructorArgumentValues().addIndexedArgumentValue(0, new RuntimeBeanReference("riskPublicationJdbc"));
                var serializer = new RootBeanDefinition();
                serializer.setFactoryBeanName("nativeTransactions");
                serializer.setFactoryMethodName("serializer");
                serializer.getConstructorArgumentValues().addIndexedArgumentValue(0, new RuntimeBeanReference(EventSerializer.class));
                definition.getConstructorArgumentValues().addIndexedArgumentValue(1, serializer);
            };
        }
    }
    public static class OrdinaryBusiness {
        private final JdbcTemplate jdbc;
        OrdinaryBusiness(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Transactional public void write() { jdbc.update("insert into ORDINARY values(1)"); }
    }
    private static final class ObservedSource extends AbstractDataSource {
        final DataSource target;
        final List<ObservedLease> leases = new ArrayList<>();
        final List<String> sql = new ArrayList<>();
        boolean failCommit, failClose;
        ObservedSource(DataSource target) { this.target = target; }
        @Override public Connection getConnection() throws SQLException {
            Connection raw = target.getConnection();
            ObservedLease lease = new ObservedLease(); leases.add(lease);
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("toString")) return SECRET;
                if (method.getName().equals("getMetaData")) {
                    DatabaseMetaData real = raw.getMetaData();
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (metadata, call, values) -> {
                        if (call.getName().equals("getDatabaseProductName")) return "PostgreSQL";
                        try { return call.invoke(real, values); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
                if (method.getName().equals("prepareStatement")) sql.add((String) args[0]);
                if (Set.of("commit", "rollback", "close").contains(method.getName())) lease.controls.add(method.getName());
                try {
                    Object result = method.invoke(raw, args);
                    if (method.getName().equals("commit") && failCommit) throw new SQLException(SECRET, "XX000");
                    if (method.getName().equals("close") && failClose) throw new SQLException(SECRET, "XX000");
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        @Override public Connection getConnection(String username, String password) throws SQLException { throw new SQLException("Unavailable"); }
    }
    private static final class ObservedLease { final List<String> controls = new ArrayList<>(); }
}
