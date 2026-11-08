package com.mightyoung.softwarefactory.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
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
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.*;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent;
import static org.assertj.core.api.Assertions.*;

/** Strict owned PG18 target, real original SQL/native classes/Jackson; no source, listener or capture activation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class RiskNativePublicationPostgresIT {
    private static final String SECRET = "PRIVATE_PG_REGISTRY_SENTINEL";
    private static final PublicationTargetIdentifier TARGET = PublicationTargetIdentifier.of("risk-scenario-draft-change");
    private CiPg18RegistryFixture fixture;
    @BeforeAll void open() { fixture = CiPg18RegistryFixture.open(CiPg18RegistryTarget.fromEnvironment()); }
    @AfterAll void close() {
        if (fixture != null) try { fixture.verifyHistory(); fixture.verifySchema(); fixture.verifyRights(); }
        finally { fixture.close(); }
    }

    @Test void originalMigrationsAndRegistryPrivilegesAreExact() {
        fixture.verifyHistory(); fixture.verifySchema(); fixture.verifyRights();
    }
    @Test void actualJacksonAndSevenNativeSqlPaths() {
        graph(new RecordingSource(fixture.source(false)), graph -> {
            var event = event(); String json = graph.serializer.serialize(event).toString();
            UUID id = graph.registry.store(event, Stream.of(TARGET)).iterator().next().getIdentifier();
            var stored = fixture.observe(id);
            assertThat(stored.serialized()).isEqualTo(json);
            assertThat(stored.type()).isEqualTo(RiskScenarioDraftChangedEvent.class.getName());
            assertThat(stored.listener()).isEqualTo(TARGET.getValue());
            assertThat(graph.serializer.deserialize(stored.serialized(), RiskScenarioDraftChangedEvent.class)).isEqualTo(event);
            graph.repository.findCompletedPublications();
            assertThat(graph.repository.findIncompletePublications()).anyMatch(value -> value.getIdentifier().equals(id));
            assertThat(graph.repository.findIncompletePublicationsPublishedBefore(Instant.now().plusSeconds(10))).anyMatch(value -> value.getIdentifier().equals(id));
            assertThat(graph.repository.findIncompletePublicationsByEventAndTargetIdentifier(event,TARGET)).isPresent();
            graph.repository.markCompleted(event,TARGET,Instant.now());
            graph.registry.markCompleted(event,TARGET);
            assertThat(fixture.observe(id).completed()).isTrue();
            assertThat(fixture.count(json)).isEqualTo(1);
            for (String sql : List.of(RiskPublicationSqlCatalog.SQL_STATEMENT_INSERT,RiskPublicationSqlCatalog.SQL_STATEMENT_FIND_COMPLETED,
                    RiskPublicationSqlCatalog.SQL_STATEMENT_FIND_INCOMPLETE,RiskPublicationSqlCatalog.SQL_STATEMENT_FIND_INCOMPLETE_PUBLISHED_BEFORE,
                    RiskPublicationSqlCatalog.SQL_STATEMENT_FIND_BY_EVENT_AND_LISTENER_ID,RiskPublicationSqlCatalog.SQL_STATEMENT_UPDATE_BY_EVENT_AND_LISTENER_ID,
                    RiskPublicationSqlCatalog.SQL_STATEMENT_UPDATE_BY_ID)) assertThat(graph.source.sql).contains(sql.formatted("EVENT_PUBLICATION"));
            unbound();
        });
    }
    @Test void pgBaselineAttributesAreRestored() {
        for (boolean autoCommit : List.of(false,true)) for (boolean readOnly : List.of(false,true))
            for (int isolation : List.of(Connection.TRANSACTION_READ_COMMITTED,Connection.TRANSACTION_SERIALIZABLE)) {
                RecordingSource source = new RecordingSource(fixture.source(false),new Attributes(autoCommit,readOnly,isolation));
                graph(source, graph -> {
                    var event=event();
                    graph.registry.store(event,Stream.of(TARGET));
                    assertThat(graph.repository.findIncompletePublicationsByEventAndTargetIdentifier(event,TARGET)).isPresent();
                    assertThat(source.leases).hasSize(2).allSatisfy(lease -> {
                        assertThat(lease.beforeClose).isEqualTo(source.baseline);
                        assertThat(lease.controls).contains("commit","close").doesNotContain("rollback");
                    });
                    unbound();
                });
            }
    }
    @Test void ackCommitThenThrowKeepsDurableCompletion() {
        diagnosticGraphs(graph -> {
            var event=event(); UUID id=graph.registry.store(event,Stream.of(TARGET)).iterator().next().getIdentifier();
            graph.source.reset(Fault.COMMIT_AFTER);
            var failure=failure(()->graph.registry.markCompleted(event,TARGET));
            assertThat(failure.transactionOutcome()).isEqualTo("COMMIT_UNKNOWN");
            assertThat(failure.cleanupOutcome()).isEqualTo("NOT_ATTEMPTED");
            assertThat(fixture.observe(id).completed()).isTrue();
            assertThat(graph.source.leases).singleElement().satisfies(lease->assertThat(lease.controls).containsExactly("setAutoCommit:false","commit"));
            unbound();
        });
    }
    @Test void registrationCommitThenThrowKeepsOneIncompletePublication() {
        diagnosticGraphs(graph -> {
            var event=event(); String json=graph.serializer.serialize(event).toString(); graph.source.reset(Fault.COMMIT_AFTER);
            assertThat(failure(()->graph.registry.store(event,Stream.of(TARGET))).transactionOutcome()).isEqualTo("COMMIT_UNKNOWN");
            assertThat(fixture.count(json)).isEqualTo(1); assertThat(fixture.find(json).completed()).isFalse();
            assertThat(graph.source.leases).singleElement().satisfies(lease->assertThat(lease.controls).containsExactly("setAutoCommit:false","commit"));
            unbound();
        });
    }
    @Test void committedResetFailureStopsClose() {
        diagnosticGraphs(graph -> {
            var event=event(); UUID id=graph.registry.store(event,Stream.of(TARGET)).iterator().next().getIdentifier(); graph.source.reset(Fault.RESET_AFTER);
            var failure=failure(()->graph.registry.markCompleted(event,TARGET));
            assertThat(failure.transactionOutcome()).isEqualTo("COMMITTED"); assertThat(failure.cleanupOutcome()).isEqualTo("FAILED");
            assertThat(fixture.observe(id).completed()).isTrue();
            assertThat(graph.source.leases).singleElement().satisfies(lease->assertThat(lease.controls).containsExactly("setAutoCommit:false","commit","setAutoCommit:true"));
            unbound();
        });
    }
    @Test void committedCloseFailureIsNotRetried() {
        diagnosticGraphs(graph -> {
            var event=event(); UUID id=graph.registry.store(event,Stream.of(TARGET)).iterator().next().getIdentifier(); graph.source.reset(Fault.CLOSE_AFTER);
            var failure=failure(()->graph.registry.markCompleted(event,TARGET));
            assertThat(failure.transactionOutcome()).isEqualTo("COMMITTED"); assertThat(failure.cleanupOutcome()).isEqualTo("CLOSE_UNKNOWN");
            assertThat(fixture.observe(id).completed()).isTrue();
            assertThat(graph.source.leases).singleElement().satisfies(lease->assertThat(lease.controls).containsExactly("setAutoCommit:false","commit","setAutoCommit:true","close"));
            unbound();
        });
    }
    @Test void statementFailureRollsBackCompletion() {
        diagnosticGraphs(graph -> {
            var event=event(); UUID id=graph.registry.store(event,Stream.of(TARGET)).iterator().next().getIdentifier(); graph.source.reset(Fault.STATEMENT_AFTER);
            assertThatThrownBy(()->graph.registry.markCompleted(event,TARGET)).isInstanceOf(RuntimeException.class);
            assertThat(fixture.observe(id).completed()).isFalse();
            assertThat(graph.source.leases).singleElement().satisfies(lease->assertThat(lease.controls).containsExactly("setAutoCommit:false","rollback","setAutoCommit:true","close"));
            assertThat(graph.source.leases.getFirst().faultFired.get()).isTrue(); unbound();
        });
    }
    @Test void disposalSerializationFailureIsSafeAndReadOnly() {
        for (Level level : List.of(Level.INFO,Level.DEBUG)) {
            graph(new RecordingSource(fixture.source(false)), graph -> {
                UUID id=graph.registry.store(event(),Stream.of(TARGET)).iterator().next().getIdentifier();
                String original=fixture.observe(id).serialized();
                fixture.malformed(id,"{\"tenantId\":\""+SECRET+"\"");
                graph.source.reset(Fault.NONE);
                Logger root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                Logger nativeLogger=(Logger)LoggerFactory.getLogger("org.springframework.modulith.events.core.DefaultEventPublicationRegistry");
                Level old=root.getLevel(), nativeOld=nativeLogger.getLevel(); root.setLevel(level);nativeLogger.setLevel(level);
                var logs=new ListAppender<ILoggingEvent>();logs.start();root.addAppender(logs);
                var out=new java.io.ByteArrayOutputStream();var err=new java.io.ByteArrayOutputStream();
                java.io.PrintStream oldOut=System.out,oldErr=System.err;
                var captureOut=new java.io.PrintStream(out,true,java.nio.charset.StandardCharsets.UTF_8);
                var captureErr=new java.io.PrintStream(err,true,java.nio.charset.StandardCharsets.UTF_8);
                System.setOut(captureOut);System.setErr(captureErr);
                try {
                    graph.close.run();
                    StringBuilder text=new StringBuilder();
                    for(var log:logs.list){text.append(log.getFormattedMessage());if(log.getThrowableProxy()!=null)text.append(ThrowableProxyUtil.asString(log.getThrowableProxy()));}
                    assertThat(text.toString()).contains("Invocation of destroy method failed","Risk native publication unavailable").doesNotContain(SECRET);
                    assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(SECRET);
                    assertThat(err.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(SECRET);
                    assertThat(fixture.observe(id).completed()).isFalse();
                    assertThat(graph.source.leases).singleElement().satisfies(lease->assertThat(lease.controls).contains("commit","close").doesNotContain("rollback"));
                    assertThat(graph.source.sql).containsExactly(RiskPublicationSqlCatalog.SQL_STATEMENT_FIND_INCOMPLETE.formatted("EVENT_PUBLICATION"));
                    unbound();
                } finally {
                    System.setOut(oldOut);System.setErr(oldErr);captureOut.close();captureErr.close();
                    root.detachAppender(logs);logs.stop();root.setLevel(old);nativeLogger.setLevel(nativeOld);
                    // Restore only this case's original serialization; retain the row and completion state.
                    fixture.malformed(id,original);
                }
            });
        }
    }
    private void diagnosticGraphs(Consumer<Graph> assertions) {
        for (Level level : List.of(Level.INFO,Level.DEBUG)) {
            graph(new RecordingSource(fixture.source(false)), graph -> withDiagnostics(level, () -> assertions.accept(graph)));
        }
    }
    private static void withDiagnostics(Level level, Runnable action) {
        List<Logger> loggers=List.of((Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME),
                (Logger)LoggerFactory.getLogger("org.springframework.transaction.interceptor.TransactionInterceptor"),
                (Logger)LoggerFactory.getLogger("org.springframework.jdbc.core.JdbcTemplate"),
                (Logger)LoggerFactory.getLogger("org.springframework.jdbc.datasource.DataSourceUtils"),
                (Logger)LoggerFactory.getLogger("com.mightyoung.softwarefactory.support.RiskJdbcTransactionOwner$Manager"),
                (Logger)LoggerFactory.getLogger("org.springframework.modulith.events.core.DefaultEventPublicationRegistry"));
        List<Level> previous=loggers.stream().map(Logger::getLevel).toList();
        var appender=new BoundedAppender();appender.start();loggers.getFirst().addAppender(appender);
        for(Logger logger:loggers)logger.setLevel(level);
        var stdout=new BoundedOutput();var stderr=new BoundedOutput();
        java.io.PrintStream previousOut=System.out,previousErr=System.err;
        var out=new java.io.PrintStream(stdout,true,java.nio.charset.StandardCharsets.UTF_8);
        var err=new java.io.PrintStream(stderr,true,java.nio.charset.StandardCharsets.UTF_8);
        System.setOut(out);System.setErr(err);
        try { action.run(); }
        finally {
            System.setOut(previousOut);System.setErr(previousErr);out.close();err.close();
            loggers.getFirst().detachAppender(appender);appender.stop();
            for(int index=0;index<loggers.size();index++)loggers.get(index).setLevel(previous.get(index));
            assertThat(appender.overflow||stdout.overflow||stderr.overflow).as("Bounded fault diagnostics must be complete").isFalse();
            StringBuilder rendered=new StringBuilder();
            for(var event:appender.events){rendered.append(event.getFormattedMessage());if(event.getThrowableProxy()!=null)rendered.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));}
            assertThat(rendered.toString()).doesNotContain(SECRET);
            assertThat(stdout.text()).doesNotContain(SECRET);assertThat(stderr.text()).doesNotContain(SECRET);
        }
    }
    private static final class BoundedAppender extends ch.qos.logback.core.AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> events=new ArrayList<>();boolean overflow;
        @Override protected synchronized void append(ILoggingEvent event){if(events.size()<4096)events.add(event);else overflow=true;}
    }
    private static final class BoundedOutput extends java.io.OutputStream {
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();boolean overflow;
        @Override public synchronized void write(int value){if(bytes.size()<1048576)bytes.write(value);else overflow=true;}
        @Override public synchronized void write(byte[] values,int offset,int length){int available=1048576-bytes.size();bytes.write(values,offset,Math.min(length,available));if(length>available)overflow=true;}
        synchronized String text(){return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);}
    }

    private static RiskScenarioDraftChangedEvent event(){return new RiskScenarioDraftChangedEvent(UUID.randomUUID(),UUID.randomUUID(),"risk_scenario",UUID.randomUUID(),"risk_scenario.create",1,Instant.now().truncatedTo(ChronoUnit.MICROS),UUID.randomUUID(),"CREATE");}
    private static RiskNativePublicationTransactions.Failure failure(Runnable action){
        try{action.run();throw new AssertionError("Expected owned lifecycle failure");}
        catch(RiskNativePublicationTransactions.Failure failure){assertThat(failure.getCause()).isNull();assertThat(failure.getSuppressed()).isEmpty();return failure;}
    }
    private static void unbound(){assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
    private record Graph(RecordingSource source,EventPublicationRegistry registry,EventPublicationRepository repository,EventSerializer serializer,Runnable close){}
    private void graph(RecordingSource source,Consumer<Graph> assertions){
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,TransactionAutoConfiguration.class,
                    EventPublicationAutoConfiguration.class,Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"),
                    Class.forName("org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration")))
                .withUserConfiguration(NativeConfiguration.class)
                .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false","spring.modulith.events.jdbc.use-legacy-structure=true","spring.modulith.events.completion-mode=UPDATE")
                .withBean(DataSource.class,()->source)
                .withBean("ordinaryJdbc",JdbcTemplate.class,()->new JdbcTemplate(source),definition->definition.setPrimary(true))
                .run(context->{
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
                    assertThat(context.getBean(PlatformTransactionManager.class)).isInstanceOf(JdbcTransactionManager.class);
                    assertThat(context.getBeansOfType(EventPublicationRegistry.class)).hasSize(1);
                    assertThat(context.getBeansOfType(EventPublicationRepository.class)).hasSize(1);
                    assertThat(AopUtils.getTargetClass(context.getBean(EventPublicationRegistry.class)).getName()).isEqualTo("org.springframework.modulith.events.core.DefaultEventPublicationRegistry");
                    assertThat(AopUtils.getTargetClass(context.getBean(EventPublicationRepository.class)).getName()).isEqualTo("org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository");
                    assertThat(context.getBeansOfType(EventSerializer.class)).hasSize(1);
                    EventSerializer serializer=context.getBean(EventSerializer.class);
                    assertThat(serializer.getClass().getName()).isEqualTo("org.springframework.modulith.events.jackson.JacksonEventSerializer");
                    source.reset(Fault.NONE);
                    assertions.accept(new Graph(source,context.getBean(EventPublicationRegistry.class),context.getBean(EventPublicationRepository.class),serializer,context::close));
                });
        } catch(ReflectiveOperationException failure){throw CiPg18RegistryFixture.failed("native-graph",failure);}
    }
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement(order=Ordered.LOWEST_PRECEDENCE-100)
    static class NativeConfiguration {
        @Bean RiskNativePublicationTransactions nativeTransactions(DataSource source,ObjectProvider<PlatformTransactionManager> boot,ObjectProvider<EventPublicationRegistry> registry,ObjectProvider<EventPublicationRepository> repository,JdbcRepositorySettings settings,Environment environment,ApplicationContext context){return new RiskNativePublicationTransactions(source,boot::getObject,registry::getObject,repository::getObject,settings,environment,Set.of(RiskScenarioDraftChangedEvent.class),context.getClassLoader());}
        @Bean RiskPublicationJdbcTemplate riskPublicationJdbc(RiskNativePublicationTransactions transactions){return transactions.template();}
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) Advisor nativeOuter(RiskNativePublicationTransactions transactions){return transactions.outerAdvisor();}
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) Advisor nativeInner(RiskNativePublicationTransactions transactions){return transactions.sanitizingAdvisor();}
        @Bean TransactionManagementConfigurer transactionConfigurer(ObjectProvider<RiskNativePublicationTransactions> transactions){return ()->transactions.getObject().transactionConfigurer().annotationDrivenTransactionManager();}
        @Bean static BeanFactoryPostProcessor nativeArguments(){return factory->{
            var definition=factory.getBeanDefinition("jdbcEventPublicationRepository");
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0,new RuntimeBeanReference("riskPublicationJdbc"));
            var serializer=new RootBeanDefinition();serializer.setFactoryBeanName("nativeTransactions");serializer.setFactoryMethodName("serializer");serializer.getConstructorArgumentValues().addIndexedArgumentValue(0,new RuntimeBeanReference(EventSerializer.class));
            definition.getConstructorArgumentValues().addIndexedArgumentValue(1,serializer);
        };}
    }
    enum Fault{NONE,COMMIT_AFTER,RESET_AFTER,CLOSE_AFTER,STATEMENT_AFTER}
    private record Attributes(boolean autoCommit,boolean readOnly,int isolation){}
    private static final class Lease {
        final List<String> controls=new ArrayList<>();final AtomicBoolean faultFired=new AtomicBoolean();Attributes beforeClose;
    }
    private static final class RecordingSource extends AbstractDataSource {
        final DataSource delegate;final Attributes baseline;final List<Lease> leases=new ArrayList<>();final List<String> sql=new ArrayList<>();
        final AtomicReference<Fault> armed=new AtomicReference<>(Fault.NONE);
        RecordingSource(DataSource delegate){this(delegate,new Attributes(true,false,Connection.TRANSACTION_READ_COMMITTED));}
        RecordingSource(DataSource delegate,Attributes baseline){this.delegate=delegate;this.baseline=baseline;}
        void reset(Fault fault){leases.clear();sql.clear();armed.set(fault);}
        @Override public Connection getConnection()throws SQLException{
            Connection raw=delegate.getConnection();raw.setTransactionIsolation(baseline.isolation);raw.setReadOnly(baseline.readOnly);raw.setAutoCommit(baseline.autoCommit);
            Fault fault=armed.getAndSet(Fault.NONE);Lease lease=new Lease();leases.add(lease);AtomicBoolean committed=new AtomicBoolean();
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                String name=method.getName();if(name.equals("toString"))return SECRET;
                if(Set.of("commit","rollback","close").contains(name))lease.controls.add(name);
                if(name.equals("setAutoCommit"))lease.controls.add("setAutoCommit:"+args[0]);
                if(name.equals("close"))lease.beforeClose=new Attributes(raw.getAutoCommit(),raw.isReadOnly(),raw.getTransactionIsolation());
                try{
                    Object value=method.invoke(raw,args);
                    if(name.equals("prepareStatement")){
                        sql.add((String)args[0]);
                        PreparedStatement statement=(PreparedStatement)value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,call,values)->{
                            try{
                                Object result=call.invoke(statement,values);
                                if(call.getName().equals("executeUpdate")&&((String)args[0]).startsWith("UPDATE")&&fault==Fault.STATEMENT_AFTER&&lease.faultFired.compareAndSet(false,true))throw new SQLException(SECRET,"XX000");
                                return result;
                            }catch(InvocationTargetException failure){throw failure.getCause();}
                        });
                    }
                    if(name.equals("commit"))committed.set(true);
                    boolean fire=fault==Fault.COMMIT_AFTER&&name.equals("commit")||fault==Fault.CLOSE_AFTER&&name.equals("close")
                            ||fault==Fault.RESET_AFTER&&name.equals("setAutoCommit")&&Boolean.TRUE.equals(args[0])&&committed.get();
                    if(fire&&lease.faultFired.compareAndSet(false,true))throw new SQLException(SECRET,"XX000");
                    return value;
                }catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }
        @Override public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("Fixed CI connection identity","08004");}
    }
}
