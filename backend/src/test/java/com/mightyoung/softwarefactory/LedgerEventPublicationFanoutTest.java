package com.mightyoung.softwarefactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.support.PersistentApplicationEventMulticaster;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerEventPublicationFanoutTest {
    private static final String A = "fixture-ledger-a";
    private static final String B = "fixture-ledger-b";

    @Test
    void nativeRetryQueuesOnlyTheListenerWhosePublicationIsIncomplete() throws Exception {
        fixture(probe -> {
            var event = new ProbeEvent("urn:fixture:purchase", "evt-1");
            probe.transaction.executeWithoutResult(status -> {
                probe.events.publishEvent(event);
                assertThat(probe.rows()).extracting(Row::listener).containsExactly(A, B);
                assertThat(probe.tasks.queue).isEmpty();
            });

            var registered = probe.rows();
            assertThat(registered).extracting(Row::id).doesNotHaveDuplicates();
            assertThat(registered).allSatisfy(row -> assertThat(row.completed()).isFalse());
            assertThat(probe.tasks.queue).hasSize(2);
            assertThat(probe.tasks.calls).isEmpty();

            probe.tasks.runOne();
            probe.tasks.runOne();
            assertThat(probe.tasks.queue).isEmpty();
            assertThat(probe.tasks.calls).containsExactlyInAnyOrder(A, B);
            assertThat(probe.tasks.failures).singleElement().isInstanceOf(ProbeFailure.class);
            assertThat(probe.rows()).containsExactly(
                    new Row(registered.get(0).id(), A, true),
                    new Row(registered.get(1).id(), B, false));
            assertThat(probe.registry.findIncompletePublications()).singleElement().satisfies(publication -> {
                assertThat(publication.getIdentifier()).isEqualTo(registered.get(1).id());
                assertThat(publication.getTargetIdentifier().getValue()).isEqualTo(B);
                assertThat(publication.getEvent()).isEqualTo(event);
            });

            probe.retry.resubmitIncompletePublications(publication -> true);
            assertThat(probe.tasks.queue).hasSize(1);
            assertThat(probe.tasks.calls).containsExactlyInAnyOrder(A, B);
            probe.tasks.runOne();
            assertThat(probe.tasks.calls.subList(2, 3)).containsExactly(B);
            assertThat(probe.tasks.failures).hasSize(1);
            assertThat(probe.rows()).containsExactly(
                    new Row(registered.get(0).id(), A, true),
                    new Row(registered.get(1).id(), B, true));
            assertThat(probe.registry.findIncompletePublications()).isEmpty();
            probe.retry.resubmitIncompletePublications(publication -> true);
            assertThat(probe.tasks.queue).isEmpty();
            assertThat(probe.tasks.calls).hasSize(3);
        });
    }

    @Test
    void rollingBackTheProducerLeavesNoPublicationOrListenerWork() throws Exception {
        fixture(probe -> {
            probe.transaction.executeWithoutResult(status -> {
                probe.events.publishEvent(new ProbeEvent("urn:fixture:purchase", "rolled-back"));
                assertThat(probe.rows()).extracting(Row::listener).containsExactly(A, B);
                assertThat(probe.tasks.queue).isEmpty();
                status.setRollbackOnly();
            });
            assertThat(probe.rows()).isEmpty();
            assertThat(probe.registry.findIncompletePublications()).isEmpty();
            probe.retry.resubmitIncompletePublications(publication -> true);
            assertThat(probe.tasks.queue).isEmpty();
            assertThat(probe.tasks.calls).isEmpty();
        });
    }

    // JVM-local H2 only. This is not PostgreSQL, production routing, or a business-effect dedupe proof.
    private static void fixture(Consumer<Probe> assertions) throws Exception {
        var source = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    create table EVENT_PUBLICATION (
                        ID uuid primary key,
                        EVENT_TYPE varchar(512) not null,
                        LISTENER_ID varchar(512) not null,
                        PUBLICATION_DATE timestamp with time zone not null,
                        SERIALIZED_EVENT varchar(4096) not null,
                        COMPLETION_DATE timestamp with time zone
                    )
                    """);
            var tasks = new Tasks();
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            DataSourceTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                            EventPublicationAutoConfiguration.class,
                            Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"),
                            Class.forName("org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration")))
                    .withUserConfiguration(ListenerConfiguration.class)
                    .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false",
                            "spring.modulith.events.jdbc.use-legacy-structure=true",
                            "spring.modulith.events.completion-mode=UPDATE")
                    .withBean(DataSource.class, () -> source)
                    .withBean(JdbcTemplate.class, () -> jdbc)
                    .withBean(Tasks.class, () -> tasks)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var nativeMulticaster = context.getBean("applicationEventMulticaster");
                        assertThat(nativeMulticaster).isExactlyInstanceOf(PersistentApplicationEventMulticaster.class);
                        assertions.accept(new Probe(context, jdbc, tasks,
                                new TransactionTemplate(context.getBean(PlatformTransactionManager.class)),
                                context.getBean(EventPublicationRegistry.class),
                                (IncompleteEventPublications) nativeMulticaster));
                    });
        } finally {
            source.shutdown();
        }
    }

    public record ProbeEvent(String source, String id) {}
    private record Row(UUID id, String listener, boolean completed) {}
    private record Probe(ApplicationEventPublisher events, JdbcTemplate jdbc, Tasks tasks,
                         TransactionTemplate transaction, EventPublicationRegistry registry,
                         IncompleteEventPublications retry) {
        List<Row> rows() {
            return jdbc.query("select id, listener_id, completion_date from event_publication order by listener_id",
                    (rs, number) -> new Row(rs.getObject("id", UUID.class), rs.getString("listener_id"),
                            rs.getTimestamp("completion_date") != null));
        }
    }
    static class ProbeFailure extends RuntimeException {}
    static final class Tasks implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        @Override public void execute(Runnable task) { queue.addLast(task); }
        void runOne() { queue.removeFirst().run(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    @EnableTransactionManagement
    static class ListenerConfiguration implements AsyncConfigurer {
        private final Tasks tasks;
        ListenerConfiguration(Tasks tasks) { this.tasks = tasks; }
        @Override public Executor getAsyncExecutor() { return tasks; }
        @Override public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
            return (failure, method, arguments) -> tasks.failures.add(failure);
        }
        @Bean ProbeListeners listeners() { return new ProbeListeners(tasks); }
    }
    static class ProbeListeners {
        private final Tasks tasks;
        private boolean first = true;
        ProbeListeners(Tasks tasks) { this.tasks = tasks; }
        @ApplicationModuleListener(id = A)
        public void succeeded(ProbeEvent event) { tasks.calls.add(A); }
        @ApplicationModuleListener(id = B)
        public void failsOnce(ProbeEvent event) {
            tasks.calls.add(B);
            if (first) {
                first = false;
                throw new ProbeFailure();
            }
        }
    }
}
