package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEventListener;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionProperties;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionWakeListener;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskChangeCaptureService;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedListener;
import com.mightyoung.softwarefactory.support.RiskNativePublicationTransactions;
import com.mightyoung.softwarefactory.testing.CiPg18EphemeralTarget;
import java.util.ArrayDeque;
import com.mightyoung.softwarefactory.support.RiskScenarioExecutor;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
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
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Actual PG18 command/auth/session/audit/native source acceptance. Async capture/restart is a separate gate. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class CiPg18RiskSourceIT {
    private CiPg18SourceFixture fixture;
    @BeforeAll void open() {
        fixture = CiPg18SourceFixture.open(CiPg18EphemeralTarget.from(System.getenv(), CiPg18EphemeralTarget.Lane.SOURCE));
    }
    @AfterAll void close() { if (fixture != null) fixture.close(); }

    @Test void actualCreateCommitsSourceAndNativePublicationTogether() throws Exception {
        graph("actualCreateCommitsSourceAndNativePublicationTogether",context -> {
            long[] before = counts();
            var controls = fixture.metrics();
            var input = input();
            var outcome = fixture.invokeIssuer(()->context.getBean(CiPg18SourceGraph.class).service().create(fixture.session().captured(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), input));
            assertThat(outcome.replay()).isFalse();
            assertThat(outcome.result().code()).isEqualTo(input.code());
            assertThat(outcome.result().version()).isEqualTo(1);
            assertThat(difference(counts(), before)).containsExactly(1,1,1,1,1,1,1,1);
            var after = fixture.metrics();
            assertThat(after.borrows()-controls.borrows()).isEqualTo(1);
            assertThat(after.commits()-controls.commits()).isEqualTo(1);
            assertThat(after.rollbacks()-controls.rollbacks()).isZero();
            assertThat(after.publicationInserts()-controls.publicationInserts()).isEqualTo(1);
            assertThat(context.getBean(RiskScenarioExecutor.class).pendingTaskCount()).isEqualTo(1);
        });
    }

    @Test void sameKeyReplayAddsNoEffectsAndChangedPayloadConflicts() throws Exception {
        graph("sameKeyReplayAddsNoEffectsAndChangedPayloadConflicts",context -> {
            var service = context.getBean(CiPg18SourceGraph.class).service();
            UUID key = UUID.randomUUID();
            UUID requestId = UUID.randomUUID(), traceId = UUID.randomUUID();
            var input = input();
            var original = fixture.invokeIssuer(()->service.create(fixture.session().captured(), key, requestId, traceId, input));
            long[] before = counts();
            var controls = fixture.metrics();
            var replay = fixture.invokeIssuer(()->service.create(fixture.session().captured(), key, requestId, traceId, input));
            assertThat(replay.replay()).isTrue();
            assertThat(replay.result()).isEqualTo(original.result());
            assertThat(replay.responseBody()).containsExactly(original.responseBody());
            assertThat(counts()).containsExactly(before);
            assertThat(fixture.metrics().publicationInserts()).isEqualTo(controls.publicationInserts());
            UUID nextRequestId = UUID.randomUUID(), nextTraceId = UUID.randomUUID();
            var refreshed = fixture.invokeIssuer(()->service.create(fixture.session().captured(), key, nextRequestId, nextTraceId, input));
            assertThat(refreshed.replay()).isTrue();
            assertThat(refreshed.result()).isEqualTo(original.result());
            var json = JsonMapper.builder().build();
            var originalWire = json.readTree(original.responseBody());
            var refreshedWire = json.readTree(refreshed.responseBody());
            assertThat(refreshedWire.get("data")).isEqualTo(originalWire.get("data"));
            assertThat(originalWire.get("meta").get("requestId").stringValue()).isEqualTo(requestId.toString());
            assertThat(originalWire.get("meta").get("traceId").stringValue()).isEqualTo(traceId.toString());
            assertThat(refreshedWire.get("meta").get("requestId").stringValue()).isEqualTo(nextRequestId.toString());
            assertThat(refreshedWire.get("meta").get("traceId").stringValue()).isEqualTo(nextTraceId.toString());
            assertThat(counts()).containsExactly(before);
            assertThat(fixture.metrics().publicationInserts()).isEqualTo(controls.publicationInserts());
            assertThat(context.getBean(RiskScenarioExecutor.class).pendingTaskCount()).isEqualTo(1);
            var changed = new RiskScenarioContracts.Create(input.code(),input.organizationId(),"changed payload",
                    input.triggeringConditions(),input.exposureDescription(),input.consequence(),input.hazardIds());
            assertThatThrownBy(() -> fixture.invokeIssuer(()->service.create(fixture.session().captured(), key, UUID.randomUUID(), UUID.randomUUID(), changed)))
                    .isInstanceOf(RiskScenarioContracts.Failure.class)
                    .satisfies(failure -> assertThat(((RiskScenarioContracts.Failure) failure).status()).isEqualTo(409));
            assertThat(counts()).containsExactly(before);
            assertThat(context.getBean(RiskScenarioExecutor.class).pendingTaskCount()).isEqualTo(1);
        });
    }

    @Test void latePublisherFailureRollsBackAllSourceEffects() throws Exception {
        graph("latePublisherFailureRollsBackAllSourceEffects",context -> {
            long[] before = counts();
            var controls = fixture.metrics();
            ApplicationListener<ApplicationEvent> lateFailure = event -> {
                if (event instanceof PayloadApplicationEvent<?> payload && payload.getPayload() instanceof RiskScenarioDraftChangedEvent)
                    throw new IllegalStateException("Injected post-registration source failure");
            };
            context.addApplicationListener(lateFailure);
            try {
                assertThatThrownBy(() -> fixture.invokeIssuer(()->context.getBean(CiPg18SourceGraph.class).service().create(fixture.session().captured(),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), input()))).isInstanceOf(RuntimeException.class);
            } finally { context.removeApplicationListener(lateFailure); }
            assertThat(counts()).containsExactly(before);
            var after = fixture.metrics();
            assertThat(after.borrows()-controls.borrows()).isEqualTo(1);
            assertThat(after.commits()-controls.commits()).isZero();
            assertThat(after.rollbacks()-controls.rollbacks()).isEqualTo(1);
            assertThat(after.publicationInserts()-controls.publicationInserts()).isEqualTo(1);
            assertThat(context.getBean(RiskScenarioExecutor.class).pendingTaskCount()).isZero();
        });
    }

    private RiskScenarioContracts.Create input() {
        return new RiskScenarioContracts.Create("RS-" + UUID.randomUUID(),fixture.identity().organization(),
                "Source acceptance draft","Controlled trigger","Controlled exposure","Unassessed consequence",List.of(fixture.hazard()));
    }
    private long[] counts() {
        List<String> queries = List.of(
                "select count(*) from public.risk_scenario where tenant_id=?",
                "select count(*) from public.risk_scenario_revision where tenant_id=?",
                "select count(*) from public.risk_hazard where tenant_id=?",
                "select count(*) from public.risk_hazard_revision where tenant_id=?",
                "select count(*) from public.risk_scenario_revision_hazard where tenant_id=?",
                "select count(*) from public.risk_scenario_receipt where tenant_id=?",
                "select count(*) from public.audit_event where tenant_id=? and resource_type='risk_scenario'",
                "select count(*) from public.event_publication where event_type='com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent' and serialized_event like ?");
        try (var connection = fixture.observer()) {
            long[] values = new long[queries.size()];
            for (int index = 0; index < queries.size(); index++) {
                try (var statement = connection.prepareStatement(queries.get(index))) {
                    statement.setObject(1,index == 7 ? "%" + fixture.identity().tenant() + "%" : fixture.identity().tenant());
                    try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); values[index]=rows.getLong(1); assertThat(rows.next()).isFalse(); }
                }
            }
            return values;
        } catch (Exception failure) { throw new IllegalStateException("Source observation rejected"); }
    }
    private static long[] difference(long[] after,long[] before) {
        return java.util.stream.IntStream.range(0,after.length).mapToLong(index -> after[index]-before[index]).toArray();
    }
    private void graph(String name,Consumer<AbstractApplicationContext> assertions) throws Exception {
        fixture.beginCase(name);
        Throwable failed=null;
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,
                TransactionAutoConfiguration.class,EventPublicationAutoConfiguration.class,
                Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"),
                Class.forName("org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration")))
                .withUserConfiguration(NativeConfiguration.class)
                .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false",
                        "spring.modulith.events.jdbc.use-legacy-structure=true","spring.modulith.events.completion-mode=UPDATE",
                        "spring.modulith.events.republish-outstanding-events-on-restart=false")
                .withBean(CiPg18SourceFixture.class,() -> fixture,definition -> definition.setDestroyMethodName(""))
                .withBean(javax.sql.DataSource.class,fixture::runtimeSource)
                .withBean("ordinaryJdbc",JdbcTemplate.class,() -> new JdbcTemplate(fixture.runtimeSource()),definition -> definition.setPrimary(true))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(EventPublicationRegistry.class)).hasSize(1);
                    assertThat(context.getBeansOfType(EventPublicationRepository.class)).hasSize(1);
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
                    assertThat(context.getBeansOfType(EventSerializer.class)).hasSize(1);
                    var started = new java.util.concurrent.CountDownLatch(1);
                    var hold = new java.util.concurrent.CountDownLatch(1);
                    context.getBean(RiskScenarioExecutor.class).execute(() -> {
                        started.countDown();
                        try { hold.await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    });
                    assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    // Context close interrupts this blocker and discards queued deliveries. No capture claim.
                    fixture.graphReady();
                    assertions.accept((AbstractApplicationContext) context.getSourceApplicationContext());
                });
            fixture.verifyHistory(); fixture.verifyRights(); fixture.verifySourceSchema();
        } catch(Exception|Error failure) {failed=failure;throw failure;}
        finally {fixture.finishCase(failed);}
    }

    @Configuration(proxyBeanMethods=false)
    @EnableAsync
    @EnableTransactionManagement(order=Ordered.LOWEST_PRECEDENCE-100)
    static class NativeConfiguration {
        @Bean RiskNativePublicationTransactions nativeTransactions(javax.sql.DataSource source,ObjectProvider<PlatformTransactionManager> original,
                ObjectProvider<EventPublicationRegistry> registry,ObjectProvider<EventPublicationRepository> repository,
                JdbcRepositorySettings settings,Environment environment,ApplicationContext context) {
            return RiskNativePublicationTransactions.shared(source,original::getObject,registry::getObject,repository::getObject,
                    settings,environment,context.getBean("ordinaryJdbc",JdbcTemplate.class),(AbstractApplicationContext)context);
        }
        @Bean JdbcOperations sharedPublicationJdbc(RiskNativePublicationTransactions transactions) { return transactions.jdbcOperations(); }
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) Advisor nativeOuter(RiskNativePublicationTransactions transactions) { return transactions.outerAdvisor(); }
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) Advisor nativeInner(RiskNativePublicationTransactions transactions) { return transactions.sanitizingAdvisor(); }
        @Bean TransactionManagementConfigurer nativeConfigurer(ObjectProvider<RiskNativePublicationTransactions> transactions) {
            return () -> new PlatformTransactionManager() {
                private PlatformTransactionManager router() { return (PlatformTransactionManager)transactions.getObject().transactionConfigurer().annotationDrivenTransactionManager(); }
                @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) { return router().getTransaction(definition); }
                @Override public void commit(org.springframework.transaction.TransactionStatus status) { router().commit(status); }
                @Override public void rollback(org.springframework.transaction.TransactionStatus status) { router().rollback(status); }
            };
        }
        @Bean CiPg18SourceGraph sourceGraph(javax.sql.DataSource source,EventPublicationRepository actualRepository,
                JdbcRepositorySettings settings,RiskNativePublicationTransactions transactions,CiPg18SourceFixture fixture) {
            return CiPg18SourceGraph.compose(source,actualRepository,settings,transactions,fixture::keys,JsonMapper.builder().build());
        }
        @Bean FindingWorkflowEventListener findingWorkflowEventListener() { return new FindingWorkflowEventListener(); }
        @Bean AssetFoundationEventListener assetFoundationEventListener() { return new AssetFoundationEventListener(); }
        @Bean FindingEvidenceIngestionWakeListener findingEvidenceIngestionWakeListener(CiPg18SourceFixture fixture) {
            return new FindingEvidenceIngestionWakeListener(new FindingEvidenceIngestionProperties(true,fixture.identity().actor().toString(),fixture.identity().tenant().toString(),1,2,""));
        }
        @Bean RiskScenarioDraftChangedListener riskScenarioDraftChangedListener(javax.sql.DataSource source,CiPg18SourceFixture fixture) {
            return new RiskScenarioDraftChangedListener(RiskChangeCaptureService.create(source,UUID.randomUUID(),Set.of(fixture.identity().tenant())));
        }
        @Bean(name="riskScenarioExecutor", destroyMethod="close")
        RiskScenarioExecutor riskScenarioExecutor(RiskNativePublicationTransactions transactions) { return transactions.riskExecutor(); }
        @Bean DeferredConsumers taskExecutor() { return new DeferredConsumers(); }
        @Bean static BeanFactoryPostProcessor nativeBinding() {
            return factory -> {
                var definition=factory.getBeanDefinition("jdbcEventPublicationRepository");
                definition.getConstructorArgumentValues().addIndexedArgumentValue(0,new RuntimeBeanReference("sharedPublicationJdbc"));
                var serializer=new RootBeanDefinition();serializer.setFactoryBeanName("nativeTransactions");serializer.setFactoryMethodName("serializer");
                serializer.getConstructorArgumentValues().addIndexedArgumentValue(0,new RuntimeBeanReference(EventSerializer.class));
                definition.getConstructorArgumentValues().addIndexedArgumentValue(1,serializer);
            };
        }
    }
    /** Tasks remain durably incomplete; these three cases do not claim asynchronous capture/ACK acceptance. */
    static final class DeferredConsumers implements java.util.concurrent.Executor {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        @Override public void execute(Runnable command) { pending.add(command); }
    }
}
