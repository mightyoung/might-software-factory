import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.InboundEventChannelAdapter;
import org.flowable.eventregistry.api.model.EventPayloadTypes;
import org.flowable.eventregistry.model.InboundChannelModel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReceiptEventRegistryTest {
    @Test
    void startsInstancesAndOnlyWakesTheMatchingInstanceAndBatch() throws Exception {
        withEngine((engine, channel, storage, schema) -> {
            engine.getRepositoryService().createDeployment().addClasspathResource("receipt.bpmn20.xml").deploy();
            var requested = StructuredReceiptEventTest.REQUESTED;
            assertEquals(new StructuredReceiptEvent.Identity("urn:fixture:purchase", "req-a"), channel.send(requested));
            channel.send(requested.replace("req-a", "req-b").replace("\"batch\":\"A\"", "\"batch\":\"B\""));
            var processes = engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("receipt");
            assertEquals(2, processes.count());
            var instance = processes.variableValueEquals("batch", "A").singleResult().getId();
            var otherInstance = engine.getRuntimeService().createProcessInstanceQuery()
                    .processDefinitionKey("receipt").variableValueEquals("batch", "B").singleResult().getId();
            var tasks = engine.getTaskService().createTaskQuery().taskDefinitionKey("received");
            var waiting = engine.getRuntimeService().createEventSubscriptionQuery().activityId("waitForBatch");
            var approved = StructuredReceiptEventTest.APPROVED.replace("instance-a", instance);
            String invalid = approved.replace("\"1.0\"", "\"0.3\"");
            assertThrows(IllegalArgumentException.class, () -> channel.send(invalid));
            assertEquals(0, tasks.count());
            assertEquals(2, waiting.count());

            channel.send(approved.replace("approval-1", "wrong-batch").replace("\"batch\":\"A\"", "\"batch\":\"B\""));
            assertEquals(0, tasks.count());
            assertEquals(2, waiting.count());

            assertEquals(new StructuredReceiptEvent.Identity("urn:fixture:inspection", "approval-1"), channel.send(approved));
            assertEquals(instance, tasks.singleResult().getProcessInstanceId());
            assertEquals(otherInstance, waiting.singleResult().getProcessInstanceId());
        });
    }

    @Test
    void earlyStructuredApprovalDoesNotAdvanceUntilExactResendAfterSubscription() throws Exception {
        withEngine((engine, channel, storage, schema) -> {
            engine.getRepositoryService().createDeployment().addClasspathResource("receipt-early.bpmn20.xml").deploy();
            var runtime = engine.getRuntimeService();
            var tasks = engine.getTaskService();
            String instance = runtime.startProcessInstanceByKey("receiptWithIntake", Map.of("batch", "A")).getId();
            var intake = tasks.createTaskQuery().processInstanceId(instance).taskDefinitionKey("intake");
            var received = tasks.createTaskQuery().processInstanceId(instance).taskDefinitionKey("received");
            var waiting = runtime.createEventSubscriptionQuery().processInstanceId(instance).activityId("waitForBatch");
            String intakeId = intake.singleResult().getId();
            assertEquals(0L, waiting.count());
            assertEquals(0L, received.count());

            String approval = StructuredReceiptEventTest.APPROVED.replace("instance-a", instance);
            var identity = new StructuredReceiptEvent.Identity("urn:fixture:inspection", "approval-1");
            assertEquals(identity, channel.send(approval));
            assertEquals(intakeId, intake.singleResult().getId());
            assertEquals(0L, waiting.count());
            assertEquals(0L, received.count());

            tasks.complete(intakeId);
            assertEquals(0L, intake.count());
            assertEquals(1L, waiting.count());
            assertEquals(0L, received.count());

            // Identical source, ID, and payload; this fixture has no inbox or deduplication.
            assertEquals(identity, channel.send(approval));
            assertEquals(1L, received.count());
            assertEquals(0L, waiting.count());
        });
    }

    @Test
    void relaysStoredApprovalOnlyForAnUnexpiredReadyInstance() throws Exception {
        withEngine((engine, channel, storage, schema) -> {
            engine.getRepositoryService().createDeployment().addClasspathResource("receipt-early.bpmn20.xml").deploy();
            var runtime = engine.getRuntimeService();
            var tasks = engine.getTaskService();
            String first = runtime.startProcessInstanceByKey("receiptWithIntake", Map.of("batch", "A")).getId();
            String second = runtime.startProcessInstanceByKey("receiptWithIntake", Map.of("batch", "B")).getId();
            var inbox = new EarlyReceiptStorageFixture(schema);
            inbox.create(storage);
            Instant now = Instant.parse("2026-11-25T00:00:00Z");
            Duration ttl = Duration.ofSeconds(30);
            String body = StructuredReceiptEventTest.APPROVED;
            var accepted = EarlyReceiptContract.accept("tenant-a", "sub-v1",
                    body.replace("instance-a", first), now, ttl);
            var expiring = EarlyReceiptContract.accept("tenant-a", "sub-v1",
                    body.replace("instance-a", second).replace("approval-1", "approval-2")
                            .replace("\"batch\":\"A\"", "\"batch\":\"B\""),
                    now.minus(ttl), ttl);
            storage.setAutoCommit(false);
            try {
                inbox.accept(storage, accepted);
                inbox.accept(storage, expiring);
                storage.commit();
            } finally {
                storage.rollback(); // Also discard any writes if the fixture did not reach commit.
                storage.setAutoCommit(true);
            }

            assertEquals(EarlyReceiptContract.Decision.WAIT,
                    relay(engine, channel, inbox.find(storage, accepted.key()), now));
            var received = tasks.createTaskQuery().taskDefinitionKey("received");
            assertEquals(0L, received.count());

            tasks.complete(tasks.createTaskQuery().processInstanceId(first).taskDefinitionKey("intake").singleResult().getId());
            tasks.complete(tasks.createTaskQuery().processInstanceId(second).taskDefinitionKey("intake").singleResult().getId());
            assertEquals(2L, runtime.createEventSubscriptionQuery().activityId("waitForBatch").count());
            assertEquals(EarlyReceiptContract.Decision.EXPIRE,
                    relay(engine, channel, inbox.find(storage, expiring.key()), now));
            assertEquals(0L, received.count());
            assertEquals(EarlyReceiptContract.Decision.DELIVER,
                    relay(engine, channel, inbox.find(storage, accepted.key()), now));
            assertEquals(first, received.singleResult().getProcessInstanceId());
            assertEquals(second, runtime.createEventSubscriptionQuery()
                    .activityId("waitForBatch").singleResult().getProcessInstanceId());
            assertEquals(EarlyReceiptContract.Decision.WAIT,
                    relay(engine, channel, inbox.find(storage, accepted.key()), now));
            assertEquals(1L, received.count());
        });
    }

    // An explicit test relay, not a production scheduler, transaction, or durable receipt.
    private static EarlyReceiptContract.Decision relay(ProcessEngine engine, TestChannel channel,
            EarlyReceiptContract stored, Instant decisionTime) {
        assertNotNull(stored, "expected saved delivery");
        var runtime = engine.getRuntimeService();
        boolean ready = runtime.createEventSubscriptionQuery()
                .processInstanceId(stored.target().instance()).activityId("waitForBatch").count() == 1
                && stored.target().batch().equals(runtime.getVariable(stored.target().instance(), "batch"));
        var decision = stored.evaluate(decisionTime, ready);
        if (decision == EarlyReceiptContract.Decision.DELIVER) channel.send(stored.originalBody());
        return decision;
    }

    @FunctionalInterface
    private interface EngineScenario {
        void run(ProcessEngine engine, TestChannel channel, Connection storage, String schema) throws Exception;
    }

    private static void withEngine(EngineScenario test) throws Exception {
        String url = System.getenv("EVENT_PG_URL");
        assertNotNull(url, "Set EVENT_PG_URL to a dedicated PostgreSQL verification database");
        String user = System.getenv("EVENT_PG_USER");
        String password = System.getenv("EVENT_PG_PASSWORD");
        String schema = "event_probe_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password);
             var sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                var config = new StandaloneProcessEngineConfiguration();
                config.setJdbcUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
                config.setJdbcDriver("org.postgresql.Driver");

                config.setJdbcUsername(user);
                config.setJdbcPassword(password);
                config.setDatabaseSchema(schema);
                config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
                config.setAsyncExecutorActivate(false);
                config.setEventRegistryStartProcessInstanceAsync(false);
                var channel = new TestChannel();
                ProcessEngine engine = config.buildProcessEngine();
                try {
                    var eventConfig = engine.getManagementService().executeCommand(CommandContextUtil::getEventRegistryEngineConfiguration);
                    eventConfig.getExpressionManager().getBeans().put("receiptChannel", channel);
                    var repository = eventConfig.getEventRepositoryService();
                    repository.createEventModelBuilder().key("receiptRequested").resourceName("request.event")
                            .payload("batch", EventPayloadTypes.STRING).deploy();
                    repository.createEventModelBuilder().key("batchApproved").resourceName("approval.event")
                            .correlationParameter("instance", EventPayloadTypes.STRING)
                            .correlationParameter("batch", EventPayloadTypes.STRING).deploy();
                    repository.createInboundChannelModelBuilder().key("receipt-channel").resourceName("receipt.channel")
                            .channelAdapter("${receiptChannel}").jsonDeserializer().detectEventKeyUsingJsonField("type")
                            .jsonFieldsMapDirectlyToPayload().deploy();
                    test.run(engine, channel, connection, schema);
                } finally {
                    engine.close();
                }
            } finally {
                sql.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private static class TestChannel implements InboundEventChannelAdapter {
        private final ObjectMapper mapper = new ObjectMapper();
        private EventRegistry registry;
        private InboundChannelModel channel;

        @Override public void setEventRegistry(EventRegistry registry) { this.registry = registry; }
        @Override public void setInboundChannelModel(InboundChannelModel channel) { this.channel = channel; }
        StructuredReceiptEvent.Identity send(String json) {
            var parsed = StructuredReceiptEvent.parse(json);
            registry.eventReceived(channel, mapper.writeValueAsString(parsed.flowableFields()));
            return parsed.identity();
        }
    }
}
