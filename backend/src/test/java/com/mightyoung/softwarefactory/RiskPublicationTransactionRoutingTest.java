package com.mightyoung.softwarefactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.InfrastructureProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.function.SingletonSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Native transaction-routing prototype only. Its observed connections are not secure leases.
 * H2 and a Spring borrowed holder do not prove the sealed risk command or PG recovery path.
 */
class RiskPublicationTransactionRoutingTest {
    private static final String REGISTRY = "org.springframework.modulith.events.core.DefaultEventPublicationRegistry";
    private static final String REPOSITORY = "org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository";
    private static final String BORROWED = "prototype.borrowed-required";
    private static final String OWNER = "prototype.owner-after-scope";
    private static final Set<String> RISK_DEFINITIONS = Set.of(
            REGISTRY + ".markProcessing", REGISTRY + ".markCompleted", REGISTRY + ".markFailed",
            REPOSITORY + ".create", REPOSITORY + ".markCompleted",
            REPOSITORY + ".findIncompletePublicationsByEventAndTargetIdentifier",
            REPOSITORY + ".findIncompletePublications", BORROWED, OWNER);
    private static final PublicationTargetIdentifier TARGET = PublicationTargetIdentifier.of("risk-scenario-draft-change");

    @Test
    void ordinaryAnnotatedTransactionsKeepBootManagerAndOriginalDefinitions() throws Exception {
        withGraph(graph -> {
            graph.business().write(1, false);
            assertThatThrownBy(() -> graph.business().write(2, true)).isInstanceOf(ProbeFailure.class);
            assertThat(graph.jdbc().queryForObject("select count(*) from ROUTING_PROBE", Integer.class)).isEqualTo(1);
            assertThat(graph.harness().facade.leases).isEmpty();
            assertThat(graph.harness().router.routes).hasSize(2).allSatisfy(route -> {
                assertThat(route.owner()).isSameAs(graph.harness().original());
                assertThat(route.definition().getName()).endsWith("OrdinaryBusiness.write");
                assertThat(route.definition().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                assertThat(route.definition().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                assertThat(route.definition().getTimeout()).isEqualTo(5);
                assertThat(route.definition().isReadOnly()).isFalse();
            });
            assertThat(graph.business().observedNames()).hasSize(2).allSatisfy(name -> assertThat(name).endsWith("OrdinaryBusiness.write"));
            assertThat(graph.business().observedHolders()).hasSize(2).allSatisfy(holder -> assertThat(holder).isNotNull());
            assertThat(graph.harness().risk.begins.get()).isZero();
            assertThat(graph.harness().risk.commits.get()).isZero();
            assertThat(graph.harness().risk.rollbacks.get()).isZero();
            assertThat(graph.harness().router.finished).hasSize(2).allSatisfy(owner -> assertThat(owner).isSameAs(graph.harness().original()));
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        });
    }

    @Test
    void nativeRegistryRequiresNewUsesFacadeWhileCanonicalTemplateFindsItsHolder() throws Exception {
        withGraph(graph -> {
            var event = new ProbeEvent("requires-new");
            graph.registry().store(event, Stream.of(TARGET));
            graph.harness().router.routes.clear();
            graph.harness().router.finished.clear();
            graph.jdbc().observations.clear();
            clearInvocations(graph.source());
            var outer = new TransactionTemplate(graph.harness().original());
            outer.executeWithoutResult(ignored -> {
                var originalHolder = holder(graph.source());
                var originalConnection = originalHolder.getConnection();
                try (var scope = RiskScope.open()) {
                    graph.registry().markProcessing(event, TARGET);
                    assertThat(holder(graph.source())).isSameAs(originalHolder);
                    graph.registry().markFailed(event, TARGET);
                    assertThat(holder(graph.source())).isSameAs(originalHolder);
                    graph.registry().markCompleted(event, TARGET);
                    assertThat(holder(graph.source())).isSameAs(originalHolder);
                }
                assertThat(originalConnection).isNotNull();
                assertThat(graph.harness().facade.leases).hasSize(3).allSatisfy(lease -> {
                    assertThat(lease.connection).isNotSameAs(originalConnection);
                    assertThat(lease.commits.get()).isEqualTo(1);
                    assertThat(lease.rollbacks.get()).isZero();
                    assertThat(lease.closes.get()).isEqualTo(1);
                });
            });
            assertThat(graph.harness().router.routes).hasSize(4).allSatisfy(route ->
                    assertThat(route.owner()).isSameAs(graph.harness().risk));
            assertThat(graph.harness().router.routes.stream()
                    .filter(route -> route.definition().getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW)
                    .map(route -> route.definition().getName()))
                    .containsExactly(REGISTRY + ".markProcessing", REGISTRY + ".markFailed", REGISTRY + ".markCompleted");
            assertThat(graph.harness().router.routes.stream()
                    .filter(route -> route.definition().getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED)
                    .map(route -> route.definition().getName())).containsExactly(REPOSITORY + ".markCompleted");
            assertThat(graph.harness().risk.begins.get()).isEqualTo(3);
            assertThat(graph.harness().risk.commits.get()).isEqualTo(3);
            assertThat(graph.harness().risk.cleanups.get()).isEqualTo(3);
            assertThat(graph.jdbc().observations).isNotEmpty().allSatisfy(observation -> {
                assertThat(observation.holder()).isNotNull();
                assertThat(graph.harness().facade.leases.stream().map(lease -> lease.connection))
                        .anySatisfy(connection -> assertThat(observation.connection()).isSameAs(connection));
            });
            // One original outer connection plus exactly one canonical-pool borrow per facade lease.
            assertThat(borrows(graph.source())).isEqualTo(4);
            assertThat(graph.jdbc().queryForObject("select count(*) from EVENT_PUBLICATION where COMPLETION_DATE is not null", Integer.class))
                    .isEqualTo(1);
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        });
    }

    @Test
    void requiredParticipationBorrowsOriginalHolderWithoutOwningItsCompletion() throws Exception {
        withGraph(graph -> {
            graph.jdbc().observations.clear();
            clearInvocations(graph.source());
            new TransactionTemplate(graph.harness().original()).executeWithoutResult(ignored -> {
                var borrowed = holder(graph.source());
                var definition = new DefaultTransactionDefinition();
                definition.setName(BORROWED);
                definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                try (var scope = RiskScope.open()) {
                    var participant = graph.harness().router.getTransaction(definition);
                    assertThat(participant.isNewTransaction()).isFalse();
                    assertThat(holder(graph.source())).isSameAs(borrowed);
                    assertThat(holder(graph.harness().facade)).isSameAs(borrowed);
                    var acquired = DataSourceUtils.getConnection(graph.source());
                    try {
                        assertThat(acquired).isSameAs(borrowed.getConnection());
                        assertThat(graph.jdbc().queryForObject("select 1", Integer.class)).isEqualTo(1);
                    } finally {
                        DataSourceUtils.releaseConnection(acquired, graph.source());
                    }
                    graph.harness().router.commit(participant);
                    assertThat(graph.jdbc().observations).isNotEmpty().allSatisfy(observation -> {
                        assertThat(observation.holder()).isSameAs(borrowed);
                        assertThat(observation.connection()).isSameAs(borrowed.getConnection());
                    });
                    assertThat(borrows(graph.source())).isEqualTo(1);
                    assertThat(holder(graph.source())).isSameAs(borrowed);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                }
                assertThat(graph.harness().facade.leases).isEmpty();
                assertThat(graph.harness().risk.begins.get()).isZero();
                assertThat(graph.harness().risk.commits.get()).isZero();
                assertThat(graph.harness().risk.rollbacks.get()).isZero();
                assertThat(graph.harness().risk.cleanups.get()).isZero();
            });
            assertThat(graph.harness().router.routes).singleElement().satisfies(route ->
                    assertThat(route.owner()).isSameAs(graph.harness().risk));
            assertThat(borrows(graph.source())).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void completionRetainsRecordedManagerAfterRiskScopeHasClosed(boolean rollback) throws Exception {
        withGraph(graph -> {
            var definition = new DefaultTransactionDefinition();
            definition.setName(OWNER);
            TransactionStatus status;
            try (var scope = RiskScope.open()) {
                status = graph.harness().router.getTransaction(definition);
                assertThat(status.isNewTransaction()).isTrue();
            }
            assertThat(RiskScope.active()).isFalse();
            if (rollback) graph.harness().router.rollback(status);
            else graph.harness().router.commit(status);
            assertThat(graph.harness().risk.commits.get()).isEqualTo(rollback ? 0 : 1);
            assertThat(graph.harness().risk.rollbacks.get()).isEqualTo(rollback ? 1 : 0);
            assertThat(graph.harness().risk.cleanups.get()).isEqualTo(1);
            assertThat(graph.harness().router.finished).containsExactly(graph.harness().risk);
            assertThat(graph.harness().facade.leases).singleElement().satisfies(lease -> {
                assertThat(lease.commits.get()).isEqualTo(rollback ? 0 : 1);
                assertThat(lease.rollbacks.get()).isEqualTo(rollback ? 1 : 0);
                assertThat(lease.closes.get()).isEqualTo(1);
            });
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        });
    }

    @Test
    void unknownRiskDefinitionRefusesBeforeEitherManagerBorrows() throws Exception {
        withGraph(graph -> {
            var definition = new DefaultTransactionDefinition();
            definition.setName("prototype.unknown");
            try (var scope = RiskScope.open()) {
                assertThatThrownBy(() -> graph.harness().router.getTransaction(definition))
                        .isInstanceOf(IllegalStateException.class).hasMessage("Unsupported prototype transaction");
            }
            assertThat(graph.harness().router.routes).isEmpty();
            assertThat(graph.harness().facade.leases).isEmpty();
            assertThat(borrows(graph.source())).isZero();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        });
    }

    private static void withGraph(Consumer<Graph> assertion) throws Exception {
        var embedded = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        var source = spy(embedded);
        try {
            var setup = new JdbcTemplate(source);
            setup.execute("create table ROUTING_PROBE (ID integer primary key)");
            setup.execute("""
                    create table EVENT_PUBLICATION (
                        ID uuid primary key,
                        EVENT_TYPE varchar(512) not null,
                        LISTENER_ID varchar(512) not null,
                        PUBLICATION_DATE timestamp not null,
                        SERIALIZED_EVENT varchar(4096) not null,
                        COMPLETION_DATE timestamp
                    )
                    """);
            var serializer = mock(EventSerializer.class);
            when(serializer.serialize(any(ProbeEvent.class))).thenAnswer(invocation -> ((ProbeEvent) invocation.getArgument(0)).marker());
            when(serializer.deserialize(anyString(), eq(ProbeEvent.class))).thenAnswer(invocation -> new ProbeEvent(invocation.getArgument(0)));
            var jdbc = new ObservedTemplate(source);
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,
                            TransactionAutoConfiguration.class, EventPublicationAutoConfiguration.class,
                            Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration")))
                    .withUserConfiguration(RoutingConfiguration.class)
                    .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false",
                            "spring.modulith.events.jdbc.use-legacy-structure=true")
                    .withBean(DataSource.class, () -> source)
                    .withBean(JdbcTemplate.class, () -> jdbc)
                    .withBean(EventSerializer.class, () -> serializer)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                        assertThat(context.getBeansOfType(TransactionManager.class)).hasSize(1);
                        var original = context.getBean("transactionManager", PlatformTransactionManager.class);
                        assertThat(original).isInstanceOf(JdbcTransactionManager.class);
                        var harness = context.getBean(RoutingHarness.class);
                        assertThat(harness.original()).isSameAs(original);
                        assertThat(harness.router).isNotSameAs(original);
                        assertThat(harness.facade.getWrappedObject()).isSameAs(source);
                        assertThat(context.getBeansOfType(EventPublicationRegistry.class)).hasSize(1);
                        assertThat(context.getBeansOfType(EventPublicationRepository.class)).hasSize(1);
                        assertThat(AopUtils.getTargetClass(context.getBean(EventPublicationRepository.class)).getName())
                                .isEqualTo("org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository");
                        var registry = context.getBean(EventPublicationRegistry.class);
                        assertThat(AopUtils.getTargetClass(registry).getName()).isEqualTo(REGISTRY);
                        assertThat(registry).isInstanceOf(Advised.class);
                        assertThat(Arrays.stream(((Advised) registry).getAdvisors())
                                .filter(advisor -> advisor.getAdvice() instanceof TransactionInterceptor)).hasSize(1);
                        harness.router.routes.clear();
                        harness.router.finished.clear();
                        jdbc.observations.clear();
                        clearInvocations(source);
                        assertion.accept(new Graph(source, jdbc, registry, harness, context.getBean(OrdinaryBusiness.class)));
                    });
        } finally {
            embedded.shutdown();
        }
    }

    private static int borrows(DataSource source) {
        return (int) mockingDetails(source).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("getConnection")).count();
    }

    private static ConnectionHolder holder(DataSource source) {
        return (ConnectionHolder) TransactionSynchronizationManager.getResource(source);
    }

    private record Graph(DataSource source, ObservedTemplate jdbc, EventPublicationRegistry registry,
            RoutingHarness harness, OrdinaryBusiness business) {}
    record ProbeEvent(String marker) {}
    static final class ProbeFailure extends RuntimeException {}

    @Configuration(proxyBeanMethods = false)
    static class RoutingConfiguration {
        @Bean
        RoutingHarness routingHarness(ObjectProvider<PlatformTransactionManager> original, DataSource source) {
            return new RoutingHarness(original, source);
        }
        @Bean
        TransactionManagementConfigurer routingConfigurer(ObjectProvider<RoutingHarness> harness) {
            return () -> harness.getObject().router;
        }
        @Bean
        OrdinaryBusiness ordinaryBusiness(JdbcTemplate jdbc, DataSource source) {
            return new OrdinaryBusiness(jdbc, source);
        }
    }

    public static class OrdinaryBusiness {
        private final JdbcTemplate jdbc;
        private final DataSource source;
        final List<String> names = new ArrayList<>();
        final List<ConnectionHolder> holders = new ArrayList<>();
        OrdinaryBusiness(JdbcTemplate jdbc, DataSource source) { this.jdbc = jdbc; this.source = source; }
        public List<String> observedNames() { return List.copyOf(names); }
        public List<ConnectionHolder> observedHolders() { return List.copyOf(holders); }
        @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
        public void write(int id, boolean fail) {
            names.add(TransactionSynchronizationManager.getCurrentTransactionName());
            holders.add(holder(source));
            jdbc.update("insert into ROUTING_PROBE(ID) values (?)", id);
            if (fail) throw new ProbeFailure();
        }
    }

    private static final class RiskScope implements AutoCloseable {
        private static final ThreadLocal<RiskScope> CURRENT = new ThreadLocal<>();
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        private RiskScope() {}
        static RiskScope open() {
            if (CURRENT.get() != null) throw new IllegalStateException("Prototype scope already open");
            var scope = new RiskScope();
            CURRENT.set(scope);
            return scope;
        }
        static boolean active() { return CURRENT.get() != null; }
        @Override public void close() {
            if (closed || Thread.currentThread() != owner || CURRENT.get() != this)
                throw new IllegalStateException("Prototype scope is invalid");
            closed = true;
            CURRENT.remove();
        }
    }

    private record Route(PlatformTransactionManager owner, TransactionDefinition definition) {}
    private static final class Router implements PlatformTransactionManager {
        private final Supplier<PlatformTransactionManager> original;
        private final PlatformTransactionManager risk;
        final List<Route> routes = new ArrayList<>();
        final List<PlatformTransactionManager> finished = new ArrayList<>();
        Router(Supplier<PlatformTransactionManager> original, PlatformTransactionManager risk) { this.original = original; this.risk = risk; }
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            var owner = RiskScope.active() ? risk : original.get();
            if (owner == risk && (definition == null || !RISK_DEFINITIONS.contains(definition.getName())))
                throw new IllegalStateException("Unsupported prototype transaction");
            routes.add(new Route(owner, definition));
            return new OwnedStatus(this, owner, owner.getTransaction(definition));
        }
        @Override public void commit(TransactionStatus status) {
            var owned = requireOwned(status);
            finished.add(owned.owner());
            owned.owner().commit(owned.delegate());
        }
        @Override public void rollback(TransactionStatus status) {
            var owned = requireOwned(status);
            finished.add(owned.owner());
            owned.owner().rollback(owned.delegate());
        }
        private OwnedStatus requireOwned(TransactionStatus status) {
            if (!(status instanceof OwnedStatus owned) || owned.router() != this)
                throw new IllegalStateException("Foreign prototype status");
            return owned;
        }
    }

    private record OwnedStatus(Router router, PlatformTransactionManager owner, TransactionStatus delegate)
            implements TransactionStatus {
        @Override public String getTransactionName() { return delegate.getTransactionName(); }
        @Override public boolean hasTransaction() { return delegate.hasTransaction(); }
        @Override public boolean isNewTransaction() { return delegate.isNewTransaction(); }
        @Override public boolean isNested() { return delegate.isNested(); }
        @Override public boolean isReadOnly() { return delegate.isReadOnly(); }
        @Override public boolean hasSavepoint() { return delegate.hasSavepoint(); }
        @Override public void setRollbackOnly() { delegate.setRollbackOnly(); }
        @Override public boolean isRollbackOnly() { return delegate.isRollbackOnly(); }
        @Override public void flush() { delegate.flush(); }
        @Override public boolean isCompleted() { return delegate.isCompleted(); }
        @Override public Object createSavepoint() { return delegate.createSavepoint(); }
        @Override public void rollbackToSavepoint(Object savepoint) { delegate.rollbackToSavepoint(savepoint); }
        @Override public void releaseSavepoint(Object savepoint) { delegate.releaseSavepoint(savepoint); }
    }

    private static final class RoutingHarness {
        private final Supplier<PlatformTransactionManager> original;
        final LeaseDataSource facade;
        final CountingManager risk;
        final Router router;
        RoutingHarness(ObjectProvider<PlatformTransactionManager> original, DataSource source) {
            // Configurer infrastructure can initialize early; resolve Boot's sole manager only after startup.
            this.original = SingletonSupplier.of(original::getObject);
            facade = new LeaseDataSource(source);
            risk = new CountingManager(facade);
            router = new Router(this.original, risk);
        }
        PlatformTransactionManager original() { return original.get(); }
    }

    private static final class CountingManager extends JdbcTransactionManager {
        final AtomicInteger begins = new AtomicInteger(), commits = new AtomicInteger(),
                rollbacks = new AtomicInteger(), cleanups = new AtomicInteger();
        CountingManager(DataSource source) { super(source); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins.incrementAndGet(); super.doBegin(transaction, definition);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits.incrementAndGet(); super.doCommit(status); }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks.incrementAndGet(); super.doRollback(status); }
        @Override protected void doCleanupAfterCompletion(Object transaction) { cleanups.incrementAndGet(); super.doCleanupAfterCompletion(transaction); }
    }

    private static final class LeaseDataSource extends AbstractDataSource implements InfrastructureProxy {
        private final DataSource canonical;
        final List<Lease> leases = new ArrayList<>();
        LeaseDataSource(DataSource canonical) { this.canonical = canonical; }
        @Override public Object getWrappedObject() { return canonical; }
        @Override public Connection getConnection() throws SQLException {
            var lease = new Lease(canonical.getConnection());
            leases.add(lease);
            return lease.connection;
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("Prototype identity is fixed", "08004");
        }
    }

    private static final class Lease {
        final AtomicInteger commits = new AtomicInteger(), rollbacks = new AtomicInteger(), closes = new AtomicInteger();
        final Connection connection;
        Lease(Connection delegate) {
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                    (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                            case "toString" -> "Observed prototype connection";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> throw new UnsupportedOperationException();
                        };
                        switch (method.getName()) {
                            case "commit" -> commits.incrementAndGet();
                            case "rollback" -> rollbacks.incrementAndGet();
                            case "close" -> closes.incrementAndGet();
                            default -> { }
                        }
                        try { return method.invoke(delegate, arguments); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }

    private record JdbcObservation(ConnectionHolder holder, Connection connection) {}
    private static final class ObservedTemplate extends JdbcTemplate {
        private final DataSource canonical;
        final List<JdbcObservation> observations = new ArrayList<>();
        ObservedTemplate(DataSource canonical) { super(canonical); this.canonical = canonical; }
        private void recordHolder() {
            var holder = holder(canonical);
            observations.add(new JdbcObservation(holder, holder == null ? null : holder.getConnection()));
        }
        @Override public int update(String sql, Object... arguments) { recordHolder(); return super.update(sql, arguments); }
        @Override public <T> T query(String sql, ResultSetExtractor<T> extractor) { recordHolder(); return super.query(sql, extractor); }
        @Override public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... arguments) {
            recordHolder(); return super.query(sql, extractor, arguments);
        }
    }
}
