import java.sql.DriverManager;
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

class ReceiptEventRegistryTest {
    @Test
    void startsInstancesAndOnlyWakesTheMatchingInstanceAndBatch() throws Exception {
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
                    engine.getRepositoryService().createDeployment().addClasspathResource("receipt.bpmn20.xml").deploy();
                    channel.send(Map.of("type", "receiptRequested", "batch", "A"));
                    channel.send(Map.of("type", "receiptRequested", "batch", "B"));
                    var processes = engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("receipt");
                    assertEquals(2, processes.count());
                    var instance = processes.variableValueEquals("batch", "A").singleResult().getId();
                    var tasks = engine.getTaskService().createTaskQuery().taskDefinitionKey("received");
                    channel.send(Map.of("type", "batchApproved", "instance", instance, "batch", "B"));
                    assertEquals(0, tasks.count());
                    channel.send(Map.of("type", "batchApproved", "instance", instance, "batch", "A"));
                    assertEquals(instance, tasks.singleResult().getProcessInstanceId());
                    assertEquals(1, engine.getRuntimeService().createEventSubscriptionQuery().activityId("waitForBatch").count());
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
        void send(Map<String, String> fields) { registry.eventReceived(channel, mapper.writeValueAsString(fields)); }
    }
}
