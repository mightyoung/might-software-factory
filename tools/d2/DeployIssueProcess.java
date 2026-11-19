import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.HexFormat;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;

/** Installs the packaged process only on the isolated D2 acceptance tenant. */
class DeployIssueProcess {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("D2_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/msf_d2_(?:issue|return|loss)_test")) {
            throw new IllegalArgumentException("Expected loopback D2 issue database");
        }
        String user = System.getenv("D2_USER");
        String password = System.getenv("D2_PASSWORD");
        String tenant = java.util.UUID.fromString(System.getenv("D2_TENANT_ID")).toString();
        try (var c = DriverManager.getConnection(url, user, password);
             var context = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            context.setString(1, tenant);
            context.execute();
            try (var q = c.createStatement();
                 var rows = q.executeQuery("""
                         select id from public.tenant where current_database() in ('msf_d2_issue_test','msf_d2_return_test','msf_d2_loss_test')
                           and code='d1seed' and name='D1 isolated tooling seed v1'
                         """)) {
                if (!rows.next()) throw new IllegalStateException("Missing acceptance tenant");
                if (!tenant.equals(rows.getString(1))) throw new IllegalStateException("Unexpected tenant");
            }
        }
        var cfg = new StandaloneProcessEngineConfiguration();
        cfg.setJdbcUrl(url).setJdbcUsername(user).setJdbcPassword(password)
                .setDatabaseSchemaUpdate("false").setAsyncExecutorActivate(false);
        cfg.setDisableIdmEngine(true);
        cfg.setDisableEventRegistry(true);
        var engine = cfg.buildProcessEngine();
        try {
            var repository = engine.getRepositoryService();
            String resource = "processes/tool-loan-v1.bpmn20.xml";
            byte[] expected = Files.readAllBytes(Path.of("backend/src/main/resources", resource));
            var query = repository.createProcessDefinitionQuery().processDefinitionTenantId(tenant)
                    .processDefinitionKey("toolLoanV1");
            if (query.count() == 0) {
                repository.createDeployment().tenantId(tenant).name("D2 tool loan v1")
                        .addBytes(resource, expected).deploy();
            }
            var definition = query.singleResult();
            if (definition.isSuspended()) throw new IllegalStateException("Suspended definition");
            try (var stream = repository.getResourceAsStream(definition.getDeploymentId(), resource)) {
                if (stream == null || !Arrays.equals(expected, stream.readAllBytes())) {
                    throw new IllegalStateException("Unexpected deployed BPMN");
                }
            }
            System.out.printf("D2_PIN %s %s %s%n", tenant, definition.getId(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)));
        } finally {
            engine.close();
        }
    }
}
