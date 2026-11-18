package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.support.RiskPublicationJdbcTemplate;
import com.mightyoung.softwarefactory.support.RiskPublicationSqlCatalog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.Appender;
import com.mightyoung.softwarefactory.dualprevention.RiskListenerFailureBoundary;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskChangeCaptureService;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedListener;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.interceptor.AsyncExecutionInterceptor;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.modulith.events.support.CompletionRegisteringAdvisor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native proxy/outer-boundary proof with controlled JDBC; not secure lease or PostgreSQL acceptance. */
class RiskListenerFailureBoundaryTest {
    private static final String SECRET = "PRIVATE SQL password=outer-secret tenant-payload";
    private static final String TARGET = "risk-scenario-draft-change";
    enum Fault { NONE, PROCESSING, COMPLETING_BEFORE, COMPLETING_AFTER, FAILING, BODY_RUNTIME, BODY_ERROR }

    @Test
    void actualAfterCommitDispatchUsesAsyncBoundaryCompletionAndNoListenerTransaction() throws Exception {
        var f = fixture(Fault.NONE);
        try (var tasks = new Tasks()) {
            runner(f, tasks).run(context -> {
                assertThat(context).hasNotFailed();
                var listener = context.getBean(RiskScenarioDraftChangedListener.class);
                var advisors = Arrays.asList(((Advised) listener).getAdvisors());
                assertThat(advisors).hasSize(3);
                assertThat(advisors.get(0).getAdvice()).isInstanceOf(AsyncExecutionInterceptor.class);
                assertThat(advisors.get(1)).isInstanceOf(RiskListenerFailureBoundary.class);
                assertThat(advisors.get(2)).isInstanceOf(CompletionRegisteringAdvisor.class);
                assertThat(advisors).noneSatisfy(advisor -> assertThat(advisor.getAdvice()).isInstanceOf(TransactionInterceptor.class));
                assertThat(context.getBeansOfType(EventPublicationRegistry.class)).hasSize(1);
                assertThat(context.getBeansOfType(EventPublicationRepository.class)).hasSize(1);
                assertThat(AopUtils.getTargetClass(context.getBean(EventPublicationRegistry.class)).getName())
                        .isEqualTo("org.springframework.modulith.events.core.DefaultEventPublicationRegistry");
                assertThat(AopUtils.getTargetClass(context.getBean(EventPublicationRepository.class)).getName())
                        .isEqualTo("org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository");
                new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                    context.publishEvent(f.event());
                    verifyNoInteractions(f.capture());
                });
                tasks.await();
                verify(f.capture()).capture(same(f.event()));
                assertThat(f.bodyThreads()).singleElement().satisfies(thread -> assertThat(thread).isNotSameAs(Thread.currentThread()));
                assertThat(f.completions().get()).isEqualTo(1);
                assertThat(tasks.failures).isEmpty();
                assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            });
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("faults")
    void actualCompletionAndAsyncLogsNeverReceiveEscapingRegistryOrBodySecrets(Level level, Fault fault) throws Exception {
        var f = fixture(fault);
        try (var tasks = new Tasks()) {
            runner(f, tasks).run(context -> {
                assertThat(context).hasNotFailed();
                var registry = context.getBean(EventPublicationRegistry.class);
                registry.store(f.event(), Stream.of(PublicationTargetIdentifier.of(TARGET)));
                withLogs(level, events -> {
                    context.getBean(RiskScenarioDraftChangedListener.class).capture(f.event());
                    tasks.await();
                    String logs = render(events);
                    assertThat(logs).doesNotContain(SECRET, f.event().tenantId().toString(),
                            f.event().commandId().toString(), f.event().traceId().toString());
                    assertThat(logs).contains("Risk asynchronous listener failed; inspect durable publication state");
                    if (fault == Fault.BODY_RUNTIME || fault == Fault.BODY_ERROR) {
                        assertThat(logs).contains("Leaving event publication uncompleted");
                    }
                });
                assertThat(tasks.failures).singleElement().satisfies(failure -> {
                    assertThat(failure).isExactlyInstanceOf(IllegalStateException.class)
                            .hasMessage("Risk asynchronous listener is unavailable").hasNoCause();
                    assertThat(failure.getSuppressed()).isEmpty();
                });
                assertThat(f.completions().get()).isEqualTo(fault == Fault.COMPLETING_AFTER ? 1 : 0);
                if (fault == Fault.PROCESSING) verifyNoInteractions(f.capture());
                else verify(f.capture()).capture(same(f.event()));
                assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            });
        }
    }

    @Test
    void uncaughtHandlerDoesNotReadFailureMethodOrParameters() throws Exception {
        Throwable hostile = new RuntimeException() {
            @Override public String getMessage() { throw new AssertionError(SECRET); }
            @Override public String toString() { throw new AssertionError(SECRET); }
        };
        Object argument = new Object() {
            @Override public String toString() { throw new AssertionError(SECRET); }
        };
        withLogs(Level.DEBUG, events -> {
            assertThatCode(() -> new RiskAsyncUncaughtExceptionHandler().handleUncaughtException(hostile, null, argument))
                    .doesNotThrowAnyException();
            assertThat(render(events)).doesNotContain(SECRET).contains("inspect durable publication state");
        });
        assertThat(RiskListenerFailureBoundary.class.getDeclaredAnnotations()).isEmpty();
        assertThat(RiskAsyncUncaughtExceptionHandler.class.getDeclaredAnnotations()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void uncaughtHandlerContainsRuntimeAndErrorFromActualLogbackAppender(boolean error) {
        Logger logger = (Logger) LoggerFactory.getLogger(RiskAsyncUncaughtExceptionHandler.class);
        Level previous = logger.getLevel();
        Appender<ILoggingEvent> appender = mock(Appender.class);
        AtomicInteger reached = new AtomicInteger();
        doAnswer(call -> { reached.incrementAndGet(); throw sensitiveFailure(error); }).when(appender).doAppend(any());
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        try {
            assertThatCode(() -> new RiskAsyncUncaughtExceptionHandler().handleUncaughtException(null, null))
                    .doesNotThrowAnyException();
            assertThat(reached.get()).isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }

    @Test
    void boundaryCannotMatchUnrelatedMethodsOrSubclasses() throws Exception {
        var boundary = new RiskListenerFailureBoundary();
        Method capture = RiskScenarioDraftChangedListener.class.getMethod("capture", RiskScenarioDraftChangedEvent.class);
        assertThat(boundary.matches(capture, RiskScenarioDraftChangedListener.class)).isTrue();
        assertThat(boundary.matches(Object.class.getMethod("toString"), RiskScenarioDraftChangedListener.class)).isFalse();
        assertThat(boundary.matches(capture, Object.class)).isFalse();
        assertThat(boundary.matches(capture, OtherListener.class)).isFalse();
    }

    public static class OtherListener extends RiskScenarioDraftChangedListener {
        OtherListener(RiskChangeCaptureService service) { super(service); }
    }

    private static Stream<Arguments> faults() {
        return Stream.of(Level.INFO, Level.DEBUG).flatMap(level -> Stream.of(Fault.PROCESSING,
                Fault.COMPLETING_BEFORE, Fault.COMPLETING_AFTER, Fault.FAILING, Fault.BODY_RUNTIME, Fault.BODY_ERROR)
                .map(fault -> Arguments.of(level, fault)));
    }

    private record Fixture(DataSource source, RiskChangeCaptureService capture, RiskScenarioDraftChangedEvent event,
                           Fault fault, AtomicInteger completions, List<Thread> bodyThreads) {}
    private static Fixture fixture(Fault fault) throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        AtomicInteger completions = new AtomicInteger();
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet rows = mock(ResultSet.class);
            when(statement.executeQuery()).thenReturn(rows);
            when(statement.executeUpdate()).thenAnswer(ignored -> {
                if (sql.startsWith("UPDATE EVENT_PUBLICATION")) completions.incrementAndGet();
                return 1;
            });
            return statement;
        });
        var event = new RiskScenarioDraftChangedEvent(UUID.randomUUID(), UUID.randomUUID(), "risk_scenario",
                UUID.randomUUID(), "risk_scenario.create", 1, Instant.parse("2026-11-07T00:00:00Z"), UUID.randomUUID(), "CREATE");
        var capture = mock(RiskChangeCaptureService.class);
        List<Thread> threads = new CopyOnWriteArrayList<>();
        when(capture.capture(event)).thenAnswer(call -> {
            threads.add(Thread.currentThread());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            assertThat(TransactionSynchronizationManager.getResource(source)).isNull();
            if (fault == Fault.FAILING || fault == Fault.BODY_RUNTIME) throw sensitiveFailure(false);
            if (fault == Fault.BODY_ERROR) throw sensitiveFailure(true);
            return new RiskChangeCaptureService.Capture(event.tenantId(), event.commandId(), 1, event.occurredAt(), UUID.randomUUID(), false);
        });
        return new Fixture(source, capture, event, fault, completions, threads);
    }

    private static Throwable sensitiveFailure(boolean error) {
        Throwable failure = error ? new AssertionError(SECRET, new IllegalStateException(SECRET))
                : new IllegalStateException(SECRET, new IllegalArgumentException(SECRET));
        failure.addSuppressed(new IllegalStateException(SECRET));
        return failure;
    }

    private static ApplicationContextRunner runner(Fixture f, Tasks tasks) throws Exception {
        var serializer = mock(EventSerializer.class);
        when(serializer.serialize(f.event())).thenReturn("controlled-publication-json");
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"),
                EventPublicationAutoConfiguration.class))
                .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false",
                        "spring.modulith.events.jdbc.use-legacy-structure=true", "spring.modulith.events.completion-mode=UPDATE")
                .withUserConfiguration(Graph.class)
                .withBean(DataSource.class, f::source)
                .withBean(Fixture.class, () -> f)
                .withBean("riskScenarioExecutor", Tasks.class, () -> tasks)
                .withBean(RiskChangeCaptureService.class, f::capture)
                .withBean(EventSerializer.class, () -> serializer);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    @EnableTransactionManagement
    static class Graph implements AsyncConfigurer {
        private final Tasks tasks;
        Graph(Tasks tasks) { this.tasks = tasks; }
        @Override public java.util.concurrent.Executor getAsyncExecutor() { return tasks; }
        @Override public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
            var safe = new RiskAsyncUncaughtExceptionHandler();
            return (failure, method, arguments) -> { tasks.failures.add(failure); safe.handleUncaughtException(failure, method, arguments); };
        }
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) RiskListenerFailureBoundary riskListenerFailureBoundary() { return new RiskListenerFailureBoundary(); }
        @Bean RiskScenarioDraftChangedListener riskListener(RiskChangeCaptureService capture) { return new RiskScenarioDraftChangedListener(capture); }
        @Bean DataSourceTransactionManager manager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean RiskPublicationSqlCatalog catalog(JdbcRepositorySettings settings, Environment environment) {
            return new RiskPublicationSqlCatalog(settings, environment);
        }
        @Bean RiskPublicationJdbcTemplate riskPublicationJdbc(DataSource source, RiskPublicationSqlCatalog catalog, ApplicationContext context) {
            return new RiskPublicationJdbcTemplate(source, catalog, Set.of(RiskScenarioDraftChangedEvent.class), context.getClassLoader());
        }
        @Bean static BeanFactoryPostProcessor bindTemplate() {
            return factory -> factory.getBeanDefinition("jdbcEventPublicationRepository").getConstructorArgumentValues()
                    .addIndexedArgumentValue(0, new RuntimeBeanReference("riskPublicationJdbc"));
        }
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) StaticMethodMatcherPointcutAdvisor controlledRegistryFailure(Fixture f) {
            return new StaticMethodMatcherPointcutAdvisor((MethodInterceptor) call -> {
                String method = call.getMethod().getName();
                if (f.fault() == Fault.PROCESSING && method.equals("markProcessing")
                        || f.fault() == Fault.COMPLETING_BEFORE && method.equals("markCompleted")
                        || f.fault() == Fault.FAILING && method.equals("markFailed")) throw sensitiveFailure(false);
                Object result = call.proceed();
                if (f.fault() == Fault.COMPLETING_AFTER && method.equals("markCompleted")) throw sensitiveFailure(false);
                return result;
            }) {
                @Override public boolean matches(Method method, Class<?> type) {
                    return EventPublicationRegistry.class.isAssignableFrom(type) && method.getName().startsWith("mark");
                }
            };
        }
    }

    private static class Tasks implements java.util.concurrent.Executor, AutoCloseable {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final BlockingQueue<CompletableFuture<Void>> submitted = new LinkedBlockingQueue<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        @Override public void execute(Runnable command) {
            var completed = new CompletableFuture<Void>();
            submitted.add(completed);
            executor.execute(() -> {
                try { command.run(); completed.complete(null); }
                catch (Throwable failure) { completed.completeExceptionally(failure); }
            });
        }
        void await() throws Exception {
            CompletableFuture<Void> task = submitted.poll(5, TimeUnit.SECONDS);
            assertThat(task).isNotNull();
            task.get(5, TimeUnit.SECONDS);
        }
        @Override public void close() { executor.close(); }
    }

    @FunctionalInterface private interface LogCase { void run(ListAppender<ILoggingEvent> events) throws Exception; }
    private static void withLogs(Level level, LogCase body) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level old = logger.getLevel();
        var events = new ListAppender<ILoggingEvent>();
        events.start(); logger.addAppender(events); logger.setLevel(level);
        try { body.run(events); }
        finally { logger.setLevel(old); logger.detachAppender(events); events.stop(); }
    }
    private static String render(ListAppender<ILoggingEvent> events) {
        StringBuilder text = new StringBuilder();
        for (var event : events.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) text.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        return text.toString();
    }
}
