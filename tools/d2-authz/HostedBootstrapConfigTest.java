/** Verifies configuration against the libraries from the same acceptance artifact, without JDBC. */
class HostedBootstrapConfigTest {
    public static void main(String[] args) {
        var flowable = HostedBootstrap.workflowConfiguration("not-a-secret");
        if (!"org.postgresql.Driver".equals(flowable.getJdbcDriver()))
            throw new AssertionError("Flowable deploy must explicitly select the PostgreSQL driver; observed "
                    + flowable.getJdbcDriver());
        if (!"false".equals(flowable.getDatabaseSchemaUpdate()))
            throw new AssertionError("Flowable deployment must not write the schema");
        System.out.println("AU08_FLOWABLE_CONFIG_OK");
    }
}
