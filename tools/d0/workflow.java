import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;

/** Explicit, repeatable deployment of the checked-in v1/v2 processes for the owned D0 seed tenant. */
class workflow {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No positional arguments accepted");
        String url = bootstrap.required("D0_JDBC_URL");
        bootstrap.database = bootstrap.required("D0_DATABASE");
        bootstrap.tenantCode = System.getenv().getOrDefault("D0_TENANT_CODE", "d0seed");
        bootstrap.checkTarget(url, bootstrap.database, bootstrap.required("D0_BOOTSTRAP_ACK"));
        String user = bootstrap.required("D0_MIGRATOR_USERNAME"), password = bootstrap.required("D0_MIGRATOR_PASSWORD");
        String tenant = bootstrap.id("tenant").toString();
        try (var c = DriverManager.getConnection(url, user, password)) {
            bootstrap.verifyDatabase(c);
            c.setAutoCommit(false);
            bootstrap.execute(c, "select set_config('app.tenant_id', ?, true)", tenant);
            try (var q = c.prepareStatement("select count(*) from public.tenant where id=?::uuid and code=? and name=?")) {
                q.setString(1, tenant); q.setString(2, bootstrap.tenantCode); q.setString(3, bootstrap.SEED_NAME);
                try (var r = q.executeQuery()) { r.next(); if (r.getInt(1) != 1) throw new IllegalStateException("Owned D0 seed required"); }
            }
        }
        var cfg = new StandaloneProcessEngineConfiguration();
        cfg.setJdbcUrl(url).setJdbcUsername(user).setJdbcPassword(password).setJdbcDriver("org.postgresql.Driver")
            .setDatabaseSchemaUpdate("false").setAsyncExecutorActivate(false);
        cfg.setDisableIdmEngine(true); cfg.setDisableEventRegistry(true);
        var engine = cfg.buildProcessEngine();
        try {
            var repository = engine.getRepositoryService();
            var deployments = repository.createDeploymentQuery().deploymentTenantId(tenant).list();
            List<String> resources = List.of("processes/finding-remediation-v1.bpmn20.xml", "processes/finding-remediation-v2.bpmn20.xml");
            if (deployments.isEmpty()) {
                var builder = repository.createDeployment().name("D0 reviewed finding v1-v2").tenantId(tenant);
                for (String resource : resources) builder.addBytes(resource, Files.readAllBytes(Path.of("backend/src/main/resources", resource)));
                builder.deploy();
            } else {
                if (deployments.size() != 1) throw new IllegalStateException("Unexpected D0 deployment set");
                String id = deployments.getFirst().getId();
                if (!new java.util.HashSet<>(repository.getDeploymentResourceNames(id)).equals(new java.util.HashSet<>(resources)))
                    throw new IllegalStateException("Unexpected D0 deployment resources");
                for (String resource : resources) try (var deployed = repository.getResourceAsStream(id, resource)) {
                    if (!Arrays.equals(deployed.readAllBytes(), Files.readAllBytes(Path.of("backend/src/main/resources", resource))))
                        throw new IllegalStateException("D0 deployed process differs from checked-in resource");
                }
            }
            System.out.println("D0_WORKFLOW_VERIFIED");
        } finally { engine.close(); }
    }
}
