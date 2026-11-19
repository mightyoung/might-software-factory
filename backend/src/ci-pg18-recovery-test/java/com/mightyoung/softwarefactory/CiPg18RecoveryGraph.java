package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener;
import com.mightyoung.softwarefactory.dualprevention.RiskListenerFailureBoundary;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEventListener;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionProperties;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionWakeListener;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskChangeCaptureService;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedListener;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeyProvider;
import com.mightyoung.softwarefactory.support.RiskNativePublicationTransactions;
import com.mightyoung.softwarefactory.support.RiskScenarioExecutor;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
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
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
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
import tools.jackson.databind.json.JsonMapper;

/** Real fixed Modulith graph. Consumer composition registers the real source gate without HUMAN state. */
final class CiPg18RecoveryGraph {
    private final AbstractApplicationContext context;
    private final CiPg18RecoveryFixture fixture;
    private final CiPg18RecoveryWorkerTarget target;
    private final CiPg18RecoveryAckProbe probe;
    private final AsyncFacts async;
    private final CiPg18RecoveryCaptureProbe capture;
    private final CountDownLatch release=new CountDownLatch(1);
    private boolean held;

    private CiPg18RecoveryGraph(AbstractApplicationContext context,CiPg18RecoveryFixture fixture,
            CiPg18RecoveryWorkerTarget target,CiPg18RecoveryAckProbe probe,CiPg18RecoveryCaptureProbe capture) {
        this.context=context;this.fixture=fixture;this.target=target;this.probe=probe;this.capture=capture;async=context.getBean(AsyncFacts.class);
    }

    static void run(CiPg18RecoveryFixture fixture,CiPg18RecoveryWorkerTarget target,Consumer<CiPg18RecoveryGraph> work)throws Exception {
        var probe=new CiPg18RecoveryAckProbe(fixture.canonicalSource(),target);
        var capture=new CiPg18RecoveryCaptureProbe(fixture.captureSource());
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,
                TransactionAutoConfiguration.class,EventPublicationAutoConfiguration.class,
                Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"),
                Class.forName("org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration")))
                .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false",
                        "spring.modulith.events.jdbc.use-legacy-structure=true","spring.modulith.events.completion-mode=UPDATE",
                        "spring.modulith.events.republish-outstanding-events-on-restart=false")
                .withUserConfiguration(NativeConfiguration.class)
                .withBean(CiPg18RecoveryFixture.class,()->fixture,definition->definition.setDestroyMethodName(""))
                .withBean(CiPg18RecoveryWorkerTarget.class,()->target)
                .withBean(CiPg18RecoveryCaptureProbe.class,()->capture)
                .withBean(DataSource.class,()->probe,definition->definition.setPrimary(true))
                .withBean("ordinaryJdbc",JdbcTemplate.class,()->new JdbcTemplate(probe),definition->definition.setPrimary(true))
                .run(context->{
                    if(context.getStartupFailure()!=null||context.getBeansOfType(EventPublicationRegistry.class).size()!=1
                            ||context.getBeansOfType(EventPublicationRepository.class).size()!=1)throw unavailable();
                    work.accept(new CiPg18RecoveryGraph((AbstractApplicationContext)context.getSourceApplicationContext(),fixture,target,probe,capture));
                });
    }

    void holdProducer() {
        if(!target.producer()||held)throw unavailable();held=true;
        var started=new CountDownLatch(1);
        executor().execute(()->{started.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        await(started);
    }
    void createSource() {
        if(!held||!target.producer())throw unavailable();
        var source=context.getBean(GateBinding.class).source;
        if(source==null)throw unavailable();
        var input=new RiskScenarioContracts.Create("REC-"+target.caseId(),fixture.identity().organization(),
                "Recovery acceptance draft","Controlled trigger","Controlled exposure","Controlled consequence",List.of(fixture.hazard()));
        var outcome=source.service().create(fixture.session().captured(),target.caseId(),UUID.randomUUID(),UUID.randomUUID(),input);
        if(outcome.replay()||outcome.result().version()!=1||executor().pendingTaskCount()!=1||!detached())throw unavailable();
    }
    void releaseDelivery(){if(!held)throw unavailable();release.countDown();held=false;}
    int recover(){if(target.producer()||!detached())throw unavailable();int before=executor().pendingTaskCount();
        context.getBean(RiskNativePublicationTransactions.class).recoverRiskPublications();return before;}
    Delivery awaitDelivery() {
        try{if(!executor().awaitQuiescence(Duration.ofSeconds(45))||!detached())throw unavailable();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        return new Delivery(async.failures.get(),probe.completionRows.get(),probe.ackCommits.get(),probe.ackRollbacks.get(),
                probe.unknownThrown.get(),probe.faultReached.get(),capture.borrows.get(),capture.commits.get(),capture.rollbacks.get());
    }
    private RiskScenarioExecutor executor(){return context.getBean(RiskScenarioExecutor.class);}
    record Delivery(int failures,int completionRows,int ackCommits,int ackRollbacks,boolean unknown,boolean faultReached,
            int captureBorrows,int captureCommits,int captureRollbacks){}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw unavailable();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}}
    private static boolean detached(){return !TransactionSynchronizationManager.isActualTransactionActive()
            &&!TransactionSynchronizationManager.isSynchronizationActive()&&TransactionSynchronizationManager.getResourceMap().isEmpty();}
    static IllegalStateException unavailable(){return new IllegalStateException("Recovery graph unavailable");}
    private record GateBinding(CiPg18SourceGraph source){}
    private static final class AsyncFacts {final AtomicInteger failures=new AtomicInteger();}

    @Configuration(proxyBeanMethods=false)
    @EnableAsync
    @EnableTransactionManagement(order=Ordered.LOWEST_PRECEDENCE-100)
    static class NativeConfiguration {
        @Bean RiskNativePublicationTransactions nativeTransactions(DataSource source,ObjectProvider<PlatformTransactionManager> original,
                ObjectProvider<EventPublicationRegistry> registry,ObjectProvider<EventPublicationRepository> repository,
                JdbcRepositorySettings settings,Environment environment,ApplicationContext context) {
            return RiskNativePublicationTransactions.shared(source,original::getObject,registry::getObject,repository::getObject,
                    settings,environment,context.getBean("ordinaryJdbc",JdbcTemplate.class),(AbstractApplicationContext)context);
        }
        @Bean JdbcOperations sharedPublicationJdbc(RiskNativePublicationTransactions transactions){return transactions.jdbcOperations();}
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) Advisor nativeOuter(RiskNativePublicationTransactions transactions){return transactions.outerAdvisor();}
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) Advisor nativeInner(RiskNativePublicationTransactions transactions){return transactions.sanitizingAdvisor();}
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) RiskListenerFailureBoundary riskFailureBoundary(){return new RiskListenerFailureBoundary();}
        @Bean TransactionManagementConfigurer nativeConfigurer(ObjectProvider<RiskNativePublicationTransactions> transactions){return ()->new PlatformTransactionManager(){
            private PlatformTransactionManager router(){return (PlatformTransactionManager)transactions.getObject().transactionConfigurer().annotationDrivenTransactionManager();}
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition){return router().getTransaction(definition);}
            @Override public void commit(org.springframework.transaction.TransactionStatus status){router().commit(status);}
            @Override public void rollback(org.springframework.transaction.TransactionStatus status){router().rollback(status);}
        };}
        @Bean GateBinding sourceGate(DataSource source,EventPublicationRepository repository,JdbcRepositorySettings settings,
                RiskNativePublicationTransactions transactions,CiPg18RecoveryFixture fixture,CiPg18RecoveryWorkerTarget target){
            SessionSensitiveKeyProvider provider=target.producer()?fixture::keys:()->{throw unavailable();};
            var actual=CiPg18SourceGraph.compose(source,repository,settings,transactions,provider,JsonMapper.builder().build());
            return new GateBinding(target.producer()?actual:null);
        }
        @Bean FindingWorkflowEventListener findingWorkflowEventListener(){return new FindingWorkflowEventListener();}
        @Bean AssetFoundationEventListener assetFoundationEventListener(){return new AssetFoundationEventListener();}
        @Bean FindingEvidenceIngestionWakeListener findingEvidenceIngestionWakeListener(CiPg18RecoveryFixture fixture){return new FindingEvidenceIngestionWakeListener(
                new FindingEvidenceIngestionProperties(true,fixture.serviceActor().toString(),fixture.tenant().toString(),1,2,""));}
        @Bean RiskScenarioDraftChangedListener riskScenarioDraftChangedListener(CiPg18RecoveryFixture fixture,CiPg18RecoveryCaptureProbe capture){return new RiskScenarioDraftChangedListener(
                RiskChangeCaptureService.create(capture,fixture.serviceActor(),Set.of(fixture.tenant())));}
        @Bean(name="riskScenarioExecutor",destroyMethod="close") RiskScenarioExecutor riskScenarioExecutor(RiskNativePublicationTransactions transactions){return transactions.riskExecutor();}
        @Bean AsyncFacts asyncFacts(){return new AsyncFacts();}
        @Bean AsyncConfigurer asyncConfigurer(AsyncFacts facts){return new AsyncConfigurer(){
            @Override public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler(){return (failure,method,args)->facts.failures.incrementAndGet();}
        };}
        @Bean static BeanFactoryPostProcessor nativeBinding(){return factory->{
            var definition=factory.getBeanDefinition("jdbcEventPublicationRepository");
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0,new RuntimeBeanReference("sharedPublicationJdbc"));
            var serializer=new RootBeanDefinition();serializer.setFactoryBeanName("nativeTransactions");serializer.setFactoryMethodName("serializer");
            serializer.getConstructorArgumentValues().addIndexedArgumentValue(0,new RuntimeBeanReference(EventSerializer.class));
            definition.getConstructorArgumentValues().addIndexedArgumentValue(1,serializer);
        };}
    }
}
