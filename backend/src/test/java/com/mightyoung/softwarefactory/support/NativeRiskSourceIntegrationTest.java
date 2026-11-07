package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.RiskScenarioSqlCatalog;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEventListener;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionProperties;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionWakeListener;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskChangeCaptureService;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedListener;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioServiceTest.Fixture;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.Advisor;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
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
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/**
 * Actual service/issuer/context/native SDK/Jackson. Only EVENT_PUBLICATION is durable H2 SQL;
 * business repository, human authorization and audit remain the explicitly synthetic source fixture.
 * The one physical source connection is opened during composition; fixture pool counts are logical
 * source-manager borrows, not proof of production pool lazy acquisition.
 * Actual PG18-only capture rejects H2, so these tests never claim successful consumption or PG acceptance.
 */
class NativeRiskSourceIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void actualServicePublishesOnceThroughNativeSdkOnTheOriginalSourceHolder() throws Exception {
        graph(graph->{
            Fixture fixture=graph.fixture;
            int startupBorrows=graph.source.borrows.get();
            fixture.beforePublication(event->{
                Object holder=TransactionSynchronizationManager.getResource(fixture.manager().commandDataSource());
                assertThat(holder).isNotNull();
                assertThat(TransactionSynchronizationManager.hasResource(graph.source)).isFalse();
            });
            fixture.afterPublication(()->{
                assertThat(graph.source.borrows).hasValue(startupBorrows);
                assertThat(fixture.commits()).isZero();
                assertThat(fixture.physicalCalls("close")).isZero();
                assertThat(TransactionSynchronizationManager.hasResource(graph.source)).isFalse();
            });
            fixture.executeCreate();
            assertThat(fixture.publicationWrites()).isEqualTo(1);
            assertThat(fixture.poolBorrows()).isEqualTo(1);
            assertThat(fixture.commits()).isEqualTo(1);assertThat(fixture.rollbacks()).isZero();
            assertThat(fixture.physicalCalls("close")).isEqualTo(1);
            assertThat(graph.consumerFailures).hasValue(0);
            int majorReads=graph.source.majorVersionReads.get();
            graph.runConsumers.run(); // Actually execute the real consumer after source resources are detached.
            assertThat(graph.source.majorVersionReads).hasValue(majorReads+1); // Actual capture reached its PG18 check.
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION",Integer.class)).isEqualTo(1);
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION where COMPLETION_DATE is null",Integer.class)).isEqualTo(1);
            assertThat(graph.consumerFailures).hasValue(1); // Actual capture rejects, never a no-op acknowledgement.
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        });
    }

    @Test void lateRealSourceFailureRollsBackTheNativePublicationOnThatSamePhysicalConnection() throws Exception {
        graph(graph->{
            graph.fixture.afterPublication(()->{throw new IllegalStateException("private late source failure");});
            assertThatThrownBy(graph.fixture::executeCreate).isInstanceOf(RuntimeException.class);
            assertThat(graph.fixture.publicationWrites()).isEqualTo(1);
            assertThat(graph.fixture.commits()).isZero();assertThat(graph.fixture.rollbacks()).isEqualTo(1);
            assertThat(graph.fixture.poolBorrows()).isEqualTo(1);assertThat(graph.fixture.physicalCalls("close")).isEqualTo(1);
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION",Integer.class)).isZero();
            assertThat(graph.consumerFailures).hasValue(0);
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        });
    }

    @Test void publicationAfterTheIssuerWindowCannotReuseTheStillActiveSourceTransaction() throws Exception {
        graph(graph->{
            var event=new java.util.concurrent.atomic.AtomicReference<Object>();
            graph.fixture.beforePublication(event::set);
            graph.fixture.afterPublication(()->graph.context.publishEvent(event.get()));
            assertThatThrownBy(graph.fixture::executeCreate).isInstanceOf(RuntimeException.class);
            assertThat(graph.fixture.publicationWrites()).isEqualTo(1);
            assertThat(graph.fixture.commits()).isZero();assertThat(graph.fixture.rollbacks()).isEqualTo(1);
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION",Integer.class)).isZero();
            assertThat(graph.consumerFailures).hasValue(0);
        });
    }

    @Test void nativeSourceConfigurationIsFixedAndCannotBeReopenedAfterRefresh() throws Exception {
        graph(graph->{
            assertThatThrownBy(()->graph.transactions.configureRiskSource(graph.fixture.manager()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(graph.fixture.poolBorrows()).isZero();
            assertThat(graph.context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
            assertThat(graph.context.getBeansOfType(EventPublicationRegistry.class)).hasSize(1);
            assertThat(graph.context.getBeansOfType(EventPublicationRepository.class)).hasSize(1);
            assertThat(graph.context.getBeansOfType(EventSerializer.class)).hasSize(1);
        });
    }

    enum ForbiddenNativeCall { MIXED_READ, ACK }
    @Test void statusSentToAnotherNativeGraphPoisonsItsOriginalSourceWithoutPhysicalControls() throws Exception {
        graph(other->graphUnchecked(graph->{
            graph.serializationHooks.onSerialize=event->{
                var status=graph.context.getBean(NativeStatusCapture.class).statuses.getLast();
                long commits=graph.fixture.physicalCalls("commit"),rollbacks=graph.fixture.physicalCalls("rollback"),closes=graph.fixture.physicalCalls("close");
                PlatformTransactionManager wrong=(PlatformTransactionManager)other.transactions.transactionConfigurer().annotationDrivenTransactionManager();
                assertThatThrownBy(()->wrong.commit(status)).isInstanceOf(IllegalStateException.class);
                assertThat(graph.fixture.physicalCalls("commit")).isEqualTo(commits);
                assertThat(graph.fixture.physicalCalls("rollback")).isEqualTo(rollbacks);
                assertThat(graph.fixture.physicalCalls("close")).isEqualTo(closes);
            };
            assertThatThrownBy(graph.fixture::executeCreate).isInstanceOf(RuntimeException.class);
            assertThat(graph.fixture.publicationWrites()).isZero();assertThat(graph.fixture.commits()).isZero();
            assertThat(graph.fixture.rollbacks()).isEqualTo(1);
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION",Integer.class)).isZero();
            assertThat(other.fixture.poolBorrows()).isZero();
        }));
    }
    @ParameterizedTest @EnumSource(ForbiddenNativeCall.class)
    void swallowedNativeCallDuringActualSourceSerializationPoisonsTheOriginalCommand(ForbiddenNativeCall attack) throws Exception {
        graph(graph->{
            graph.serializationHooks.onSerialize=event->{
                assertThatThrownBy(()->{
                    if(attack==ForbiddenNativeCall.MIXED_READ)graph.registry.findIncompletePublications();
                    else graph.registry.markCompleted(event,org.springframework.modulith.events.core.PublicationTargetIdentifier.of("risk-scenario-draft-change"));
                }).isInstanceOf(IllegalStateException.class);
            };
            assertThatThrownBy(graph.fixture::executeCreate).isInstanceOf(RuntimeException.class);
            assertThat(graph.fixture.publicationWrites()).isZero();assertThat(graph.fixture.commits()).isZero();
            assertThat(graph.fixture.rollbacks()).isEqualTo(1);
            assertThat(graph.observer.queryForObject("select count(*) from EVENT_PUBLICATION",Integer.class)).isZero();
            assertThat(graph.consumerFailures).hasValue(0);
        });
    }

    private record Graph(Fixture fixture,NativeSource source,JdbcTemplate observer,AbstractApplicationContext context,
                         RiskNativePublicationTransactions transactions,AtomicInteger consumerFailures,Runnable runConsumers,
                         EventPublicationRegistry registry,SerializationHooks serializationHooks){}
    private record SourceComposition(Fixture fixture,Connection physical){}
    private static void graphUnchecked(Consumer<Graph> assertions){
        try{graph(assertions);}catch(Exception failure){throw new AssertionError(failure);}
    }
    private static void graph(Consumer<Graph> assertions) throws Exception {
        var database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            JdbcTemplate observer=new JdbcTemplate(database);
            observer.execute("create table EVENT_PUBLICATION(ID uuid primary key, EVENT_TYPE varchar(512), LISTENER_ID varchar(512), PUBLICATION_DATE timestamp, SERIALIZED_EVENT varchar(4096), COMPLETION_DATE timestamp)");
            NativeSource source=new NativeSource(database);
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,
                    TransactionAutoConfiguration.class,EventPublicationAutoConfiguration.class,
                    Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"),
                    Class.forName("org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration")))
                .withUserConfiguration(NativeConfiguration.class)
                .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false","spring.modulith.events.jdbc.use-legacy-structure=true",
                        "spring.modulith.events.completion-mode=UPDATE","spring.modulith.events.republish-outstanding-events-on-restart=false")
                .withBean(DataSource.class,()->source)
                .withBean("ordinaryJdbc",JdbcTemplate.class,()->new JdbcTemplate(source),definition->definition.setPrimary(true))
                .run(context->{
                    assertThat(context).hasNotFailed();
                    var composition=context.getBean(SourceComposition.class);
                    try {
                        assertions.accept(new Graph(composition.fixture,source,observer,(AbstractApplicationContext)context.getSourceApplicationContext(),
                                context.getBean(RiskNativePublicationTransactions.class),context.getBean(ConsumerDiagnostics.class).failures,
                                context.getBean(ConsumerDiagnostics.class).executor::drain,context.getBean(EventPublicationRegistry.class),context.getBean(SerializationHooks.class)));
                    } finally {
                        // Even configuration-only cases own the one source lease opened during fixture composition.
                        if(composition.fixture.poolBorrows()==0)composition.physical.close();
                    }
                });
        } finally {database.shutdown();}
    }

    @Configuration(proxyBeanMethods=false)
    @EnableAsync
    @EnableTransactionManagement(order=Ordered.LOWEST_PRECEDENCE-100)
    static class NativeConfiguration {
        @Bean RiskNativePublicationTransactions nativeTransactions(DataSource source,ObjectProvider<PlatformTransactionManager> original,
                ObjectProvider<EventPublicationRegistry> registry,ObjectProvider<EventPublicationRepository> repository,
                JdbcRepositorySettings settings,Environment environment,ApplicationContext context){
            return RiskNativePublicationTransactions.shared(source,original::getObject,registry::getObject,repository::getObject,
                    settings,environment,context.getBean("ordinaryJdbc",JdbcTemplate.class),(AbstractApplicationContext)context);
        }
        @Bean JdbcOperations sharedPublicationJdbc(RiskNativePublicationTransactions transactions){return transactions.jdbcOperations();}
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) Advisor nativeOuter(RiskNativePublicationTransactions transactions){return transactions.outerAdvisor();}
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) Advisor nativeInner(RiskNativePublicationTransactions transactions){return transactions.sanitizingAdvisor();}
        @Bean NativeStatusCapture nativeStatusCapture(){return new NativeStatusCapture();}
        @Bean TransactionManagementConfigurer nativeConfigurer(ObjectProvider<RiskNativePublicationTransactions> transactions,NativeStatusCapture capture){
            return ()->new PlatformTransactionManager(){
                private PlatformTransactionManager router(){return (PlatformTransactionManager)transactions.getObject().transactionConfigurer().annotationDrivenTransactionManager();}
                @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition){
                    var status=router().getTransaction(definition);capture.statuses.add(status);return status;
                }
                @Override public void commit(org.springframework.transaction.TransactionStatus status){router().commit(status);}
                @Override public void rollback(org.springframework.transaction.TransactionStatus status){router().rollback(status);}
            };
        }
        @Bean SourceComposition nativeSourceComposition(DataSource source,EventPublicationRepository actualRepository,
                JdbcRepositorySettings settings,RiskNativePublicationTransactions transactions)throws Exception {
            // Force the actual native repository/schema witness before constructing the private source manager.
            assertThat(RiskScenarioSqlCatalog.commandTemplates(DSL.using(SQLDialect.POSTGRES),actualRepository,settings)).isNotEmpty();
            Connection physical=source.getConnection();
            try {
                Fixture fixture=new Fixture(false,false,physical);
                transactions.configureRiskSource(fixture.manager());
                return new SourceComposition(fixture,physical);
            } catch(Throwable failure){physical.close();throw failure;}
        }
        @Bean FindingWorkflowEventListener findingWorkflowEventListener(){return new FindingWorkflowEventListener();}
        @Bean AssetFoundationEventListener assetFoundationEventListener(){return new AssetFoundationEventListener();}
        @Bean FindingEvidenceIngestionWakeListener findingEvidenceIngestionWakeListener(){
            return new FindingEvidenceIngestionWakeListener(new FindingEvidenceIngestionProperties(true,TENANT.toString(),TENANT.toString(),1,2,""));
        }
        @Bean RiskScenarioDraftChangedListener riskScenarioDraftChangedListener(DataSource source){
            return new RiskScenarioDraftChangedListener(RiskChangeCaptureService.create(source,UUID.randomUUID(),java.util.Set.of(TENANT)));
        }
        @Bean ConsumerDiagnostics consumerDiagnostics(){return new ConsumerDiagnostics();}
        @Bean SerializationHooks nativeSerializationHooks(){return new SerializationHooks();}
        @Bean AsyncConfigurer asyncConfigurer(ConsumerDiagnostics diagnostics){
            return new AsyncConfigurer(){
                @Override public java.util.concurrent.Executor getAsyncExecutor(){return diagnostics.executor;}
                @Override public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler(){return (failure,method,args)->diagnostics.failures.incrementAndGet();}
            };
        }
        @Bean static BeanFactoryPostProcessor nativeBinding(){return factory->{
            var definition=factory.getBeanDefinition("jdbcEventPublicationRepository");
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0,new RuntimeBeanReference("sharedPublicationJdbc"));
            var serializer=new RootBeanDefinition();serializer.setFactoryBeanName("nativeTransactions");serializer.setFactoryMethodName("serializer");
            var hooked=new RootBeanDefinition();hooked.setFactoryBeanName("nativeSerializationHooks");hooked.setFactoryMethodName("wrap");
            hooked.getConstructorArgumentValues().addIndexedArgumentValue(0,new RuntimeBeanReference(EventSerializer.class));
            serializer.getConstructorArgumentValues().addIndexedArgumentValue(0,hooked);
            definition.getConstructorArgumentValues().addIndexedArgumentValue(1,serializer);
        };}
    }
    static final class ConsumerDiagnostics {
        final AtomicInteger failures=new AtomicInteger();final DeferredExecutor executor=new DeferredExecutor();
    }
    static final class NativeStatusCapture {final java.util.List<org.springframework.transaction.TransactionStatus> statuses=new java.util.ArrayList<>();}
    static final class DeferredExecutor implements java.util.concurrent.Executor {
        private final java.util.ArrayDeque<Runnable> pending=new java.util.ArrayDeque<>();
        @Override public void execute(Runnable task){pending.add(task);}
        void drain(){while(!pending.isEmpty())pending.remove().run();}
    }
    static final class SerializationHooks {
        Consumer<Object> onSerialize;
        public EventSerializer wrap(EventSerializer actualJackson){
            assertThat(actualJackson.getClass().getName()).isEqualTo("org.springframework.modulith.events.jackson.JacksonEventSerializer");
            return new EventSerializer(){
                @Override public Object serialize(Object event){
                    Consumer<Object> hook=onSerialize;onSerialize=null;if(hook!=null)hook.accept(event);
                    return actualJackson.serialize(event);
                }
                @Override public <T>T deserialize(Object serialized,Class<T> type){return actualJackson.deserialize(serialized,type);}
            };
        }
    }
    private static final class NativeSource extends AbstractDataSource {
        final DataSource delegate;final AtomicInteger borrows=new AtomicInteger(),majorVersionReads=new AtomicInteger();
        NativeSource(DataSource delegate){this.delegate=delegate;}
        @Override public Connection getConnection()throws SQLException {
            borrows.incrementAndGet();Connection raw=delegate.getConnection();
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("getMetaData")){
                    DatabaseMetaData metadata=raw.getMetaData();
                    // Native SQL dialect selection only. This is H2, and its actual major version remains unchanged.
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(ignored,call,values)->{
                        if(call.getName().equals("getDatabaseProductName"))return "PostgreSQL";
                        if(call.getName().equals("getDatabaseMajorVersion"))majorVersionReads.incrementAndGet();
                        try{return call.invoke(metadata,values);}catch(InvocationTargetException failure){throw failure.getCause();}
                    });
                }
                try{return method.invoke(raw,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }
        @Override public Connection getConnection(String user,String password)throws SQLException {throw new SQLException("Unavailable");}
    }
}
