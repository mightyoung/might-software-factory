package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener;
import com.mightyoung.softwarefactory.dualprevention.ModulithFindingWorkflowEventPublisher;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEventListener;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowState;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent;
import com.mightyoung.softwarefactory.support.RiskNativePublicationTransactions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcOperations;
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
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real native singleton graph, Jackson, registered production consumers and H2 only. Not source/PG/restart acceptance. */
class SharedNativePublicationTransactionsTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String RISK = "risk-scenario-draft-change";

    @Test void realFindingPublisherAndThreeProductionConsumersPersistAndAcknowledgeThroughOneNativeGraph() throws Exception {
        graph(graph -> {
            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); root.addAppender(logs);
            try {
                List<Object> events = events();
                for (int index = 0; index < 3; index++) {
                    int family = index;
                    new TransactionTemplate(graph.boot).execute(status -> {
                        Object holder = TransactionSynchronizationManager.getResource(graph.source);
                        int acquisitions = graph.source.controls.size();
                        if (family == 0) graph.context.getBean(ModulithFindingWorkflowEventPublisher.class).publish((FindingWorkflowChangedEvent) events.get(family));
                        else graph.context.publishEvent(events.get(family));
                        assertThat(TransactionSynchronizationManager.getResource(graph.source)).isSameAs(holder);
                        // The native REQUIRED create joins the real original source holder.
                        assertThat(graph.source.controls).hasSize(acquisitions);
                        return null;
                    });
                }
                assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class)).isEqualTo(3);
                assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION where COMPLETION_DATE is not null", Integer.class)).isEqualTo(3);
                assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                        .contains("Finding workflow command committed", "Asset foundation committed change traced");
                FindingEvidenceQuarantinedEvent evidence = (FindingEvidenceQuarantinedEvent) events.get(2);
                assertThat(graph.context.getBean(FindingEvidenceIngestionWakeListener.class).drain(TENANT, 32)).containsExactly(evidence.intentId());
            } finally { root.detachAppender(logs); logs.stop(); }
        });
    }

    @Test void fourFamilyHistoricalRowsAreReadWithRealLazyJacksonAndOriginalRequiredVsNoneTransactions() throws Exception {
        graph(graph -> {
            seed(graph);
            graph.source.controls.clear();
            var incomplete = graph.repository.findIncompletePublications();
            assertThat(incomplete).hasSize(4);
            assertThat(incomplete).extracting(publication -> publication.getEvent().getClass().getName()).containsExactlyInAnyOrderElementsOf(events().stream().map(event -> event.getClass().getName()).toList());
            assertThat(graph.source.controls).containsExactly("commit", "close");
            graph.source.controls.clear();
            assertThat(graph.repository.findCompletedPublications()).isEmpty();
            assertThat(graph.source.controls).containsExactly("close");
            graph.source.controls.clear();
            assertThat(graph.repository.findIncompletePublicationsPublishedBefore(Instant.now().plusSeconds(60))).hasSize(4);
            assertThat(graph.source.controls).containsExactly("close");
            graph.source.controls.clear();
            new TransactionTemplate(graph.boot).execute(status -> {
                Object holder = TransactionSynchronizationManager.getResource(graph.source);
                assertThat(graph.repository.findCompletedPublications()).isEmpty();
                assertThat(graph.repository.findIncompletePublications()).hasSize(4);
                assertThat(TransactionSynchronizationManager.getResource(graph.source)).isSameAs(holder);
                assertThat(graph.source.controls).isEmpty();
                return null;
            });
            assertThat(graph.source.controls).containsExactly("commit", "close");
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION where COMPLETION_DATE is not null", Integer.class)).isZero();
        });
    }

    @Test void riskSourceAndAcknowledgementStayClosedWithoutRealSourceAuthorityAndUnknownPairsNeverReachSql() throws Exception {
        graph(graph -> {
            RiskScenarioDraftChangedEvent risk = (RiskScenarioDraftChangedEvent) events().get(3);
            assertThatThrownBy(() -> graph.registry.store(risk, Stream.of(PublicationTargetIdentifier.of(RISK)))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> graph.registry.markCompleted(risk, PublicationTargetIdentifier.of(RISK))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> graph.registry.store(events().getFirst(), Stream.of(PublicationTargetIdentifier.of(AssetFoundationEventListener.ID))))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(graph.repository::deleteCompletedPublications).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> graph.repository.markCompleted(UUID.randomUUID(), Instant.now())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> graph.transactions.jdbcOperations().query("select 1", rows -> 1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(graph.transactions::template).isInstanceOf(IllegalStateException.class);
            assertThat(graph.source.controls).isEmpty();
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class)).isZero();
        });
    }

    @Test void rawNativeDestroyReadsMixedRowsDuringRealContextCloseWithoutWritingOrSkippingCallback() throws Exception {
        graph(graph -> {
            seed(graph);
            graph.source.controls.clear();
            graph.close.run();
            // Native raw destroy performs its real REQUIRED read; no publication completion is granted.
            assertThat(graph.source.controls).containsExactly("commit", "close");
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION where COMPLETION_DATE is not null", Integer.class)).isZero();
        });
    }

    @Test void swallowedSecondNativeBeginIsStickyAndRollsBackTheOriginalNativeTransaction() throws Exception {
        graph(graph -> {
            graph.source.onPrepare = () -> {
                var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
                definition.setName("org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository.create");
                assertThatThrownBy(() -> ((PlatformTransactionManager) graph.transactions.transactionConfigurer().annotationDrivenTransactionManager()).getTransaction(definition))
                        .isInstanceOf(IllegalStateException.class);
            };
            Object finding = events().getFirst();
            String id = "com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener.record(com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent)";
            assertThatThrownBy(() -> graph.registry.store(finding, Stream.of(PublicationTargetIdentifier.of(id))))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(graph.source.controls).containsExactly("rollback", "close");
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class)).isZero();
        });
    }

    @Test void swallowedMismatchedNativeDefinitionStillPoisonsAndRollsBackTheOriginalHolder() throws Exception {
        graph(graph -> {
            graph.source.onPrepare = () -> {
                var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
                definition.setName("org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository.create");
                definition.setTimeout(1); // Actual native REQUIRED create has TIMEOUT_DEFAULT.
                assertThatThrownBy(() -> ((PlatformTransactionManager) graph.transactions.transactionConfigurer().annotationDrivenTransactionManager()).getTransaction(definition))
                        .isInstanceOf(IllegalStateException.class);
            };
            String id = "com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener.record(com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent)";
            assertThatThrownBy(() -> graph.registry.store(events().getFirst(), Stream.of(PublicationTargetIdentifier.of(id))))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(graph.source.controls).containsExactly("rollback", "close");
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION", Integer.class)).isZero();
        });
    }

    @Test void expiredNativeStatusCannotControlTheNextIndependentOriginalTransaction() throws Exception {
        graph(graph -> {
            Object finding = events().getFirst();
            String id = "com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener.record(com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent)";
            graph.registry.store(finding, Stream.of(PublicationTargetIdentifier.of(id)));
            StatusCapture capture = graph.context.getBean(StatusCapture.class);
            var stale = capture.statuses.getFirst();
            new TransactionTemplate(graph.boot).execute(status -> {
                Object holder = TransactionSynchronizationManager.getResource(graph.source);
                assertThatThrownBy(stale::setRollbackOnly).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> ((PlatformTransactionManager) graph.transactions.transactionConfigurer().annotationDrivenTransactionManager()).commit(stale))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(TransactionSynchronizationManager.getResource(graph.source)).isSameAs(holder);
                assertThat(status.isRollbackOnly()).isFalse();
                new JdbcTemplate(graph.source).update("insert into ORDINARY values(1)");
                return null;
            });
            assertThat(graph.observer.queryForObject("select count(*) from ORDINARY", Integer.class)).isEqualTo(1);
        });
    }

    private static List<Object> events() {
        UUID command = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID resource = UUID.fromString("00000000-0000-0000-0000-000000000003");
        Instant time = Instant.parse("2026-11-07T00:00:00Z");
        return List.of(new FindingWorkflowChangedEvent(TENANT, resource, command, TENANT, "submit", FindingWorkflowState.DISCOVERED,
                        FindingWorkflowState.PERSON_CONFIRM_PENDING, 1, 1),
                new AssetFoundationChangedEvent(TENANT, command, "asset", resource, "asset.create", 1, time, command, "CREATE"),
                new FindingEvidenceQuarantinedEvent(TENANT, resource, 1),
                new RiskScenarioDraftChangedEvent(TENANT, command, "risk_scenario", resource, "risk_scenario.create", 1, time, command, "CREATE"));
    }
    private static void seed(Graph graph) {
        for (Object event : events()) {
            String listener = switch (event) {
                case FindingWorkflowChangedEvent ignored -> "com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener.record(com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent)";
                case AssetFoundationChangedEvent ignored -> AssetFoundationEventListener.ID;
                case FindingEvidenceQuarantinedEvent ignored -> "com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionWakeListener.wake(com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceQuarantinedEvent)";
                case RiskScenarioDraftChangedEvent ignored -> RISK;
                default -> throw new IllegalStateException();
            };
            graph.observer.update("insert into EVENT_PUBLICATION(ID,EVENT_TYPE,LISTENER_ID,PUBLICATION_DATE,SERIALIZED_EVENT) values(?,?,?,?,?)",
                    UUID.randomUUID(), event.getClass().getName(), listener, java.sql.Timestamp.from(Instant.now()), graph.serializer.serialize(event).toString());
        }
    }
    private record Graph(ObservedSource source, JdbcTemplate observer, EventPublicationRegistry registry, EventPublicationRepository repository,
            RiskNativePublicationTransactions transactions, PlatformTransactionManager boot, EventSerializer serializer, ApplicationContext context, Runnable close) {}
    private static void graph(Consumer<Graph> assertions) throws Exception {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            JdbcTemplate observer = new JdbcTemplate(database);
            observer.execute("create table EVENT_PUBLICATION(ID uuid primary key, EVENT_TYPE varchar(512), LISTENER_ID varchar(512), PUBLICATION_DATE timestamp, SERIALIZED_EVENT varchar(4096), COMPLETION_DATE timestamp)");
            observer.execute("create table ORDINARY(ID integer)");
            ObservedSource source = new ObservedSource(database);
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,
                    TransactionAutoConfiguration.class, EventPublicationAutoConfiguration.class,
                    Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"),
                    Class.forName("org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration")))
                .withUserConfiguration(SharedConfiguration.class)
                .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false", "spring.modulith.events.jdbc.use-legacy-structure=true",
                        "spring.modulith.events.completion-mode=UPDATE", "spring.modulith.events.republish-outstanding-events-on-restart=false")
                .withBean(DataSource.class, () -> source)
                .withBean("ordinaryJdbc", JdbcTemplate.class, () -> new JdbcTemplate(source), definition -> definition.setPrimary(true))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(EventPublicationRegistry.class)).hasSize(1);
                    assertThat(context.getBeansOfType(EventPublicationRepository.class)).hasSize(1);
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
                    assertThat(context.getBean(PlatformTransactionManager.class)).isInstanceOf(JdbcTransactionManager.class);
                    assertThat(context.getBeansOfType(EventSerializer.class)).hasSize(1);
                    EventSerializer serializer = context.getBean(EventSerializer.class);
                    assertThat(serializer.getClass().getName()).isEqualTo("org.springframework.modulith.events.jackson.JacksonEventSerializer");
                    source.controls.clear();
                    assertions.accept(new Graph(source, observer, context.getBean(EventPublicationRegistry.class), context.getBean(EventPublicationRepository.class),
                            context.getBean(RiskNativePublicationTransactions.class), context.getBean(PlatformTransactionManager.class), serializer,
                            context.getSourceApplicationContext(), context::close));
                });
        } finally { database.shutdown(); }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    @EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 100)
    static class SharedConfiguration {
        @Bean RiskNativePublicationTransactions nativeTransactions(DataSource source, ObjectProvider<PlatformTransactionManager> original,
                ObjectProvider<EventPublicationRegistry> registry, ObjectProvider<EventPublicationRepository> repository,
                JdbcRepositorySettings settings, Environment environment, ApplicationContext context) {
            return RiskNativePublicationTransactions.shared(source, original::getObject, registry::getObject, repository::getObject,
                    settings, environment, context.getBean("ordinaryJdbc", JdbcTemplate.class), (AbstractApplicationContext) context);
        }
        @Bean JdbcOperations sharedPublicationJdbc(RiskNativePublicationTransactions transactions) { return transactions.jdbcOperations(); }
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) Advisor nativeOuter(RiskNativePublicationTransactions transactions) { return transactions.outerAdvisor(); }
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) Advisor nativeInner(RiskNativePublicationTransactions transactions) { return transactions.sanitizingAdvisor(); }
        @Bean StatusCapture statusCapture() { return new StatusCapture(); }
        @Bean TransactionManagementConfigurer nativeConfigurer(ObjectProvider<RiskNativePublicationTransactions> transactions, StatusCapture capture) {
            return () -> new PlatformTransactionManager() {
                private PlatformTransactionManager router() { return (PlatformTransactionManager) transactions.getObject().transactionConfigurer().annotationDrivenTransactionManager(); }
                @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                    var status = router().getTransaction(definition);
                    if (definition != null && definition.getName() != null && definition.getName().startsWith("org.springframework.modulith.events.")) capture.statuses.add(status);
                    return status;
                }
                @Override public void commit(org.springframework.transaction.TransactionStatus status) { router().commit(status); }
                @Override public void rollback(org.springframework.transaction.TransactionStatus status) { router().rollback(status); }
            };
        }
        @Bean FindingWorkflowEventListener findingWorkflowEventListener() { return new FindingWorkflowEventListener(); }
        @Bean AssetFoundationEventListener assetFoundationEventListener() { return new AssetFoundationEventListener(); }
        @Bean FindingEvidenceIngestionWakeListener findingEvidenceIngestionWakeListener() {
            return new FindingEvidenceIngestionWakeListener(new FindingEvidenceIngestionProperties(true, TENANT.toString(), TENANT.toString(), 1, 2, ""));
        }
        @Bean ModulithFindingWorkflowEventPublisher findingPublisher(ApplicationContext context) { return new ModulithFindingWorkflowEventPublisher(context); }
        @Bean java.util.concurrent.Executor taskExecutor() { return new SyncTaskExecutor(); }
        @Bean static BeanFactoryPostProcessor nativeBinding() {
            return factory -> {
                var definition = factory.getBeanDefinition("jdbcEventPublicationRepository");
                definition.getConstructorArgumentValues().addIndexedArgumentValue(0, new RuntimeBeanReference("sharedPublicationJdbc"));
                var serializer = new RootBeanDefinition(); serializer.setFactoryBeanName("nativeTransactions"); serializer.setFactoryMethodName("serializer");
                serializer.getConstructorArgumentValues().addIndexedArgumentValue(0, new RuntimeBeanReference(EventSerializer.class));
                definition.getConstructorArgumentValues().addIndexedArgumentValue(1, serializer);
            };
        }
    }
    static final class StatusCapture { final List<org.springframework.transaction.TransactionStatus> statuses = new ArrayList<>(); }
    private static final class ObservedSource extends AbstractDataSource {
        final DataSource delegate;
        final List<String> controls = new ArrayList<>();
        Runnable onPrepare;
        ObservedSource(DataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException {
            Connection raw = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("toString")) return "Shared native test connection";
                if (method.getName().equals("getMetaData")) {
                    DatabaseMetaData metadata = raw.getMetaData();
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (ignored, call, values) -> {
                        if (call.getName().equals("getDatabaseProductName")) return "PostgreSQL";
                        try { return call.invoke(metadata, values); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
                if (method.getName().equals("prepareStatement") && onPrepare != null) { Runnable hook = onPrepare; onPrepare = null; hook.run(); }
                if (List.of("commit", "rollback", "close").contains(method.getName())) controls.add(method.getName());
                try { return method.invoke(raw, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("Unavailable"); }
    }
}
