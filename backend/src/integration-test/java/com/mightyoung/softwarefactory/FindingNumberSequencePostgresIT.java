package com.mightyoung.softwarefactory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowRepository;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.JooqFindingWorkflowRepository;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG18 repository/RLS acceptance for V7. This deliberately does not start Spring or Flowable:
 * the test exercises the real jOOQ number allocator under the gated runtime database role.
 * Two synthetic tenant parents are created and removed by the non-superuser migration owner,
 * with a transaction-local tenant context on every RLS-protected statement.
 */
class FindingNumberSequencePostgresIT {

    private static final String EXPECTED_DATABASE = "might_software_factory_pg18_test";
    private static final int CONCURRENT_ALLOCATIONS = 12;
    // Keep these exact allowlists: the second expression is the observed PG18.6 deparse;
    // no lossy normalization may erase an added predicate such as OR TRUE.
    private static final Set<String> APPROVED_TENANT_ISOLATION_EXPRESSIONS = Set.of(
            "(id = nullif(current_setting('app.tenant_id', true), '')::uuid)",
            "(id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)");
    private static final Set<String> APPROVED_TENANT_LOGIN_EXPRESSIONS = Set.of(
            "(code = nullif(current_setting('app.login_tenant_code', true), ''))",
            "((code)::text = NULLIF(current_setting('app.login_tenant_code'::text, true), ''::text))");

    @Test
    void lowPrivilegeAllocatorIsTenantScopedConcurrentAndTransactional() throws Exception {
        // Reuse the compatibility suite's pre-connect target guard. It requires an explicit
        // disposable-database acknowledgement and an exact URL match for the app-role target.
        LanPostgresCompatibilityIT.DatabaseTarget target =
                LanPostgresCompatibilityIT.DatabaseTarget.preflightFromEnvironment(System.getenv());
        assertThat(target.databaseName()).isEqualTo(EXPECTED_DATABASE);

        String migrationUser = required("LAN_PG_USERNAME");
        String migrationPassword = required("LAN_PG_PASSWORD");
        String appUser = required("LAN_PG_APP_USERNAME");
        String appPassword = required("LAN_PG_APP_PASSWORD");
        assertThat(appUser).isNotEqualTo(migrationUser);

        TenantFixtures tenants = newTenantFixtures();
        LocalDate firstDay = randomBusinessDay();
        List<LocalDate> testDays = IntStream.range(0, 5).mapToObj(firstDay::plusDays).toList();
        try (Connection management = managementConnection(target.jdbcUrl(), migrationUser, migrationPassword)) {
            assertMigrationTargetAndV7State(management, target.databaseName());
        }

        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(target.jdbcUrl());
        poolConfig.setUsername(appUser);
        poolConfig.setPassword(appPassword);
        poolConfig.setMaximumPoolSize(CONCURRENT_ALLOCATIONS + 2);
        poolConfig.setMinimumIdle(0);
        poolConfig.setPoolName("v7-sequence-acceptance");

        try {
            createTenantFixtures(target.jdbcUrl(), migrationUser, migrationPassword, tenants);
            try (HikariDataSource applicationDataSource = new HikariDataSource(poolConfig)) {
                assertApplicationRoleBoundary(applicationDataSource, appUser);
                assertFindingSelectPrivilege(applicationDataSource, appUser);
                assertApplicationCanSeeFixtureTenant(applicationDataSource, tenants.tenantA());
                assertApplicationCanSeeFixtureTenant(applicationDataSource, tenants.tenantB());
                JooqFindingWorkflowRepository repository = repository(applicationDataSource);

                assertTenantCountersStartAtOneAndConcurrentCallsStayUnique(
                        repository, applicationDataSource, tenants.tenantA().id(),
                        tenants.tenantB().id(), testDays.getFirst());
                assertRlsHidesOtherTenantAndRejectsUnscopedWrites(
                        applicationDataSource, tenants.tenantA().id(), tenants.tenantB().id(),
                        testDays.getFirst(), testDays.get(4));
                assertTransactionLocalTenantDoesNotLeak(applicationDataSource, tenants.tenantA().id());
                assertFailedTransactionRollsBackSequenceAdvance(repository, applicationDataSource,
                        tenants.tenantA().id(), testDays.get(3));
                assertOverflowIsNotTruncated(repository, applicationDataSource,
                        tenants.tenantA().id(), testDays.get(2));
            }
        } finally {
            cleanupTenantFixtures(target.jdbcUrl(), migrationUser, migrationPassword, tenants, testDays);
        }
    }

    private static JooqFindingWorkflowRepository repository(DataSource dataSource) {
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var connectionProvider = new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource));
        DSLContext dsl = DSL.using(new DefaultConfiguration().set(SQLDialect.POSTGRES).set(connectionProvider));
        return new JooqFindingWorkflowRepository(dsl, transactionManager);
    }

    private static void assertMigrationTargetAndV7State(Connection connection, String expectedDatabase)
            throws Exception {
        try (Statement query = connection.createStatement();
                ResultSet row = query.executeQuery("""
                        select current_database(), current_user,
                               (select rolsuper from pg_roles where rolname = current_user),
                               current_setting('server_version_num')::int,
                               to_regclass('public.finding_number_sequence') is not null,
                               to_regclass('public.flyway_schema_history_app') is not null,
                               to_regclass('public.tenant') is not null
                        """)) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo(expectedDatabase);
            assertThat(row.getString(2)).isEqualTo(required("LAN_PG_USERNAME"));
            assertThat(row.getBoolean(3)).as("migration identity is not superuser").isFalse();
            assertThat(row.getInt(4) / 10_000).isEqualTo(18);
            assertThat(row.getBoolean(5)).as("V7 table must already exist; this test never applies DDL").isTrue();
            assertThat(row.getBoolean(6)).as("application Flyway history must already exist").isTrue();
            assertThat(row.getBoolean(7)).as("V3 tenant table must already exist").isTrue();
        }
        try (Statement query = connection.createStatement();
                ResultSet row = query.executeQuery("""
                        select exists (select 1 from public.flyway_schema_history_app
                                       where version = '7' and success),
                               (select pg_get_constraintdef(oid)
                                  from pg_constraint
                                 where conrelid = 'public.finding_number_sequence'::regclass
                                   and contype = 'p'),
                               (select pg_get_userbyid(c.relowner) = current_user
                                  from pg_class c where c.oid = 'public.finding_number_sequence'::regclass),
                               (select pg_get_userbyid(c.relowner) = current_user
                                  from pg_class c where c.oid = 'public.tenant'::regclass),
                               (select c.relrowsecurity and c.relforcerowsecurity
                                  from pg_class c where c.oid = 'public.tenant'::regclass),
                               has_table_privilege(current_user, 'public.tenant', 'SELECT'),
                               has_table_privilege(current_user, 'public.tenant', 'INSERT'),
                               has_table_privilege(current_user, 'public.tenant', 'DELETE')
                        """)) {
            assertThat(row.next()).isTrue();
            assertThat(row.getBoolean(1)).as("V7 must be separately applied and recorded before this test").isTrue();
            assertThat(row.getString(2)).contains("tenant_id, business_day");
            assertThat(row.getBoolean(3)).as("migration identity owns V7 sequence table").isTrue();
            assertThat(row.getBoolean(4)).as("migration identity owns V3 tenant table").isTrue();
            assertThat(row.getBoolean(5)).as("tenant RLS is enabled and forced").isTrue();
            assertThat(row.getBoolean(6)).isTrue();
            assertThat(row.getBoolean(7)).isTrue();
            assertThat(row.getBoolean(8)).isTrue();
        }
        assertTenantPolicySet(connection);
    }

    private static void assertTenantPolicySet(Connection connection) throws SQLException {
        List<TenantPolicy> policies = new ArrayList<>();
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("""
                        select policyname, permissive, cmd, roles::text, qual, with_check
                          from pg_policies
                         where schemaname = 'public' and tablename = 'tenant'
                         order by policyname
                        """)) {
            while (rows.next()) {
                policies.add(new TenantPolicy(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6)));
            }
        }
        assertThat(hasExpectedTenantPolicies(policies))
                .as("public.tenant has exactly the V3 tenant isolation policy and V4 SELECT login lookup policy")
                .isTrue();
    }

    static boolean hasExpectedTenantPolicies(List<TenantPolicy> policies) {
        if (policies == null || policies.size() != 2) return false;
        Map<String, TenantPolicy> byName = new HashMap<>();
        for (TenantPolicy policy : policies) {
            if (policy == null || policy.name() == null || byName.putIfAbsent(policy.name(), policy) != null) {
                return false;
            }
        }
        if (!byName.keySet().equals(Set.of("tenant_isolation", "tenant_login_lookup"))) return false;

        TenantPolicy isolation = byName.get("tenant_isolation");
        if (!"PERMISSIVE".equals(isolation.permissive())
                || !"ALL".equals(isolation.command())
                || !"{public}".equals(isolation.roles())
                || !APPROVED_TENANT_ISOLATION_EXPRESSIONS.contains(isolation.usingExpression())
                || !APPROVED_TENANT_ISOLATION_EXPRESSIONS.contains(isolation.checkExpression())) {
            return false;
        }

        TenantPolicy loginLookup = byName.get("tenant_login_lookup");
        return "PERMISSIVE".equals(loginLookup.permissive())
                && "SELECT".equals(loginLookup.command())
                && "{public}".equals(loginLookup.roles())
                && APPROVED_TENANT_LOGIN_EXPRESSIONS.contains(loginLookup.usingExpression())
                && loginLookup.checkExpression() == null;
    }

    private static LocalDate randomBusinessDay() {
        int offset = ThreadLocalRandom.current().nextInt(36_500);
        return LocalDate.of(9000, 1, 1).plusDays(offset);
    }

    private static TenantFixtures newTenantFixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return new TenantFixtures(
                new TenantFixture(UUID.randomUUID(), "V7IT-A-" + suffix, "V7 sequence test tenant A " + suffix),
                new TenantFixture(UUID.randomUUID(), "V7IT-B-" + suffix, "V7 sequence test tenant B " + suffix));
    }

    private static void createTenantFixtures(
            String jdbcUrl, String migrationUser, String migrationPassword, TenantFixtures fixtures)
            throws Exception {
        for (TenantFixture fixture : fixtures.all()) {
            try (Connection connection = managementConnection(jdbcUrl, migrationUser, migrationPassword);
                    PreparedStatement insert = connection.prepareStatement("""
                            insert into public.tenant (id, code, name, status)
                            values (?, ?, ?, 'ACTIVE')
                            """)) {
                connection.setAutoCommit(false);
                try {
                    setLocalTenant(connection, fixture.id());
                    insert.setObject(1, fixture.id());
                    insert.setString(2, fixture.code());
                    insert.setString(3, fixture.name());
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                    connection.commit();
                } catch (Throwable failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                    if (failure instanceof Exception exception) throw exception;
                    throw (Error) failure;
                }
            }
        }
    }

    private static void assertApplicationCanSeeFixtureTenant(DataSource dataSource, TenantFixture fixture)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement query = connection.prepareStatement("""
                        select count(*) from public.tenant where id = ? and code = ?
                        """)) {
            connection.setAutoCommit(false);
            setLocalTenant(connection, fixture.id());
            query.setObject(1, fixture.id());
            query.setString(2, fixture.code());
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isEqualTo(1);
            }
            connection.rollback();
        }
    }

    private static void assertFindingSelectPrivilege(DataSource dataSource, String expectedRole)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement query = connection.createStatement();
                ResultSet row = query.executeQuery("""
                        select current_database(), current_user,
                               has_table_privilege(current_user, 'public.finding', 'SELECT')
                        """)) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo(EXPECTED_DATABASE);
            assertThat(row.getString(2)).isEqualTo(expectedRole);
            assertThat(row.getBoolean(3)).as("production allocator reads same-day existing finding codes").isTrue();
        }
    }

    private static void assertApplicationRoleBoundary(DataSource dataSource, String expectedRole)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement query = connection.prepareStatement("""
                        select current_database(), current_user, session_user,
                               current_setting('server_version_num')::int,
                               app_role.rolsuper, app_role.rolbypassrls, app_role.rolcreatedb,
                               app_role.rolcreaterole, app_role.rolreplication,
                               c.relrowsecurity, c.relforcerowsecurity,
                               pg_get_userbyid(c.relowner),
                               has_database_privilege(current_user, current_database(), 'CONNECT'),
                               has_schema_privilege(current_user, 'public', 'USAGE'),
                               has_schema_privilege(current_user, 'public', 'CREATE'),
                               has_column_privilege(current_user, 'public.finding_number_sequence', 'tenant_id', 'SELECT'),
                               has_column_privilege(current_user, 'public.finding_number_sequence', 'business_day', 'SELECT'),
                               has_column_privilege(current_user, 'public.finding_number_sequence', 'last_sequence', 'SELECT'),
                               has_column_privilege(current_user, 'public.finding_number_sequence', 'tenant_id', 'INSERT'),
                               has_column_privilege(current_user, 'public.finding_number_sequence', 'business_day', 'INSERT'),
                               has_column_privilege(current_user, 'public.finding_number_sequence', 'last_sequence', 'INSERT'),
                               has_column_privilege(current_user, 'public.finding_number_sequence', 'tenant_id', 'UPDATE'),
                               has_column_privilege(current_user, 'public.finding_number_sequence', 'business_day', 'UPDATE'),
                               has_column_privilege(current_user, 'public.finding_number_sequence', 'last_sequence', 'UPDATE'),
                               has_table_privilege(current_user, 'public.finding_number_sequence', 'DELETE'),
                               has_table_privilege(current_user, 'public.finding_number_sequence', 'TRUNCATE'),
                               has_table_privilege(current_user, 'public.finding_number_sequence', 'REFERENCES'),
                               has_table_privilege(current_user, 'public.finding_number_sequence', 'TRIGGER'),
                               exists (select 1 from pg_auth_members
                                        where member = (select oid from pg_roles where rolname = current_user)),
                               (select count(*) = 1
                                          and bool_and(policyname = 'finding_number_sequence_tenant_isolation'
                                                       and cmd = 'ALL'
                                                       and 'public' = any(roles)
                                                       and position('tenant_id' in coalesce(qual, '')) > 0
                                                       and position('app.tenant_id' in coalesce(qual, '')) > 0
                                                       and position('tenant_id' in coalesce(with_check, '')) > 0
                                                       and position('app.tenant_id' in coalesce(with_check, '')) > 0)
                                  from pg_policies
                                 where schemaname = 'public'
                                   and tablename = 'finding_number_sequence'
                               )
                          from pg_roles app_role
                          join pg_class c on c.oid = to_regclass('public.finding_number_sequence')
                         where app_role.rolname = current_user
                        """)) {
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo(EXPECTED_DATABASE);
                assertThat(row.getString(2)).isEqualTo(expectedRole);
                assertThat(row.getString(3)).isEqualTo(expectedRole);
                assertThat(row.getInt(4) / 10_000).isEqualTo(18);
                for (int attributeColumn : List.of(5, 6, 7, 8, 9)) assertThat(row.getBoolean(attributeColumn)).isFalse();
                assertThat(row.getBoolean(10)).isTrue();
                assertThat(row.getBoolean(11)).isTrue();
                assertThat(row.getString(12)).isNotEqualTo(expectedRole);
                assertThat(row.getBoolean(13)).isTrue();
                assertThat(row.getBoolean(14)).isTrue();
                assertThat(row.getBoolean(15)).isFalse();
                for (int selectColumn : List.of(16, 17, 18)) assertThat(row.getBoolean(selectColumn)).isTrue();
                for (int insertColumn : List.of(19, 20, 21)) assertThat(row.getBoolean(insertColumn)).isTrue();
                assertThat(row.getBoolean(22)).isFalse();
                assertThat(row.getBoolean(23)).isFalse();
                assertThat(row.getBoolean(24)).isTrue();
                for (int forbiddenPrivilege : List.of(25, 26, 27, 28)) {
                    assertThat(row.getBoolean(forbiddenPrivilege)).isFalse();
                }
                assertThat(row.getBoolean(29)).as("runtime role has no inherited role memberships").isFalse();
                assertThat(row.getBoolean(30)).as("exact forced tenant policy is present").isTrue();
            }
        }
    }

    private static void assertTenantCountersStartAtOneAndConcurrentCallsStayUnique(
            FindingWorkflowRepository repository, DataSource dataSource,
            UUID tenantA, UUID tenantB, LocalDate businessDay) throws Exception {
        int workers = CONCURRENT_ALLOCATIONS;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> pending = IntStream.range(0, workers)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting for the concurrent allocation barrier");
                        }
                        return allocate(repository, tenantA, businessDay);
                    }))
                    .toList();
            assertThat(ready.await(20, TimeUnit.SECONDS)).as("all PG sessions are ready").isTrue();
            start.countDown();

            List<String> codes = new ArrayList<>();
            for (Future<String> result : pending) codes.add(result.get(60, TimeUnit.SECONDS));
            List<String> expected = IntStream.rangeClosed(1, workers)
                    .mapToObj(sequence -> code(businessDay, sequence)).toList();
            assertThat(codes).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(codes).doesNotHaveDuplicates();

            assertThat(allocate(repository, tenantB, businessDay)).isEqualTo(code(businessDay, 1));
            assertThat(rowCount(dataSource, tenantA, businessDay)).isEqualTo(1);
            assertThat(rowCount(dataSource, tenantB, businessDay)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void assertRlsHidesOtherTenantAndRejectsUnscopedWrites(
            DataSource dataSource, UUID tenantA, UUID tenantB, LocalDate populatedDay, LocalDate unusedDay)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalTenant(connection, tenantB);
            assertThat(rowCount(connection, tenantA, populatedDay)).isZero();
            assertRlsDenied(() -> insertSequence(connection, tenantA, unusedDay));
            connection.rollback();
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalTenant(connection, "");
            assertThat(rowCount(connection, tenantA, populatedDay)).isZero();
            assertRlsDenied(() -> insertSequence(connection, tenantA, unusedDay));
            connection.rollback();
        }
    }

    private static void assertTransactionLocalTenantDoesNotLeak(DataSource dataSource, UUID tenant)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            int backendPid = backendPid(connection);
            connection.setAutoCommit(false);
            setLocalTenant(connection, tenant);
            connection.commit();
            assertThat(backendPid(connection)).isEqualTo(backendPid);
            try (Statement query = connection.createStatement();
                    ResultSet row = query.executeQuery("select current_setting('app.tenant_id', true)")) {
                assertThat(row.next()).isTrue();
                assertThat(java.util.Optional.ofNullable(row.getString(1)).orElse("")).isEmpty();
            }
        }
    }

    private static void assertFailedTransactionRollsBackSequenceAdvance(
            FindingWorkflowRepository repository, DataSource dataSource, UUID tenant, LocalDate businessDay) {
        assertThat(rowCount(dataSource, tenant, businessDay)).isZero();
        assertThatThrownBy(() -> repository.inTenantTransaction(tenant, tx -> {
            tx.nextFindingCode(businessDay);
            throw new DeliberateRollback();
        })).isInstanceOf(DeliberateRollback.class);
        assertThat(rowCount(dataSource, tenant, businessDay))
                .as("sequence row/update rolls back with the enclosing repository transaction")
                .isZero();
    }

    private static void assertOverflowIsNotTruncated(
            FindingWorkflowRepository repository, DataSource dataSource, UUID tenant, LocalDate businessDay)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalTenant(connection, tenant);
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into public.finding_number_sequence (tenant_id, business_day, last_sequence)
                    values (?, ?, 9998)
                    """)) {
                insert.setObject(1, tenant);
                insert.setObject(2, businessDay);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            connection.commit();
        }

        assertThat(allocate(repository, tenant, businessDay)).isEqualTo(code(businessDay, 9_999));
        assertThat(allocate(repository, tenant, businessDay)).isEqualTo(code(businessDay, 10_000));
        assertThat(rowCount(dataSource, tenant, businessDay)).isEqualTo(1);
    }

    private static String allocate(FindingWorkflowRepository repository, UUID tenant, LocalDate day) {
        return repository.inTenantTransaction(tenant, tx -> tx.nextFindingCode(day));
    }

    private static long rowCount(DataSource dataSource, UUID tenant, LocalDate day) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalTenant(connection, tenant);
            long count = rowCount(connection, tenant, day);
            connection.rollback();
            return count;
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect the sequence row as the app role", failure);
        }
    }

    private static long rowCount(Connection connection, UUID tenant, LocalDate day) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select count(*) from public.finding_number_sequence
                 where tenant_id = ? and business_day = ?
                """)) {
            query.setObject(1, tenant);
            query.setObject(2, day);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static void setLocalTenant(Connection connection, UUID tenant) throws SQLException {
        setLocalTenant(connection, tenant.toString());
    }

    private static void setLocalTenant(Connection connection, String tenant) throws SQLException {
        try (PreparedStatement set = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            set.setString(1, tenant);
            try (ResultSet row = set.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo(tenant);
            }
        }
    }

    private static void insertSequence(Connection connection, UUID tenant, LocalDate day) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into public.finding_number_sequence (tenant_id, business_day, last_sequence)
                values (?, ?, 1)
                """)) {
            insert.setObject(1, tenant);
            insert.setObject(2, day);
            insert.executeUpdate();
        }
    }

    private static void assertRlsDenied(SqlOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement query = connection.createStatement(); ResultSet row = query.executeQuery("select pg_backend_pid()")) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    private static String code(LocalDate businessDay, int sequence) {
        return "YH-" + businessDay.toString().replace("-", "")
                + "-" + String.format(Locale.ROOT, "%04d", sequence);
    }

    private static void cleanupTenantFixtures(String jdbcUrl, String migrationUser, String migrationPassword,
            TenantFixtures fixtures, List<LocalDate> days) throws SQLException {
        for (TenantFixture fixture : fixtures.all()) {
            try (Connection connection = managementConnection(jdbcUrl, migrationUser, migrationPassword);
                    PreparedStatement deleteSequence = connection.prepareStatement("""
                            delete from public.finding_number_sequence
                             where tenant_id = ? and business_day = ?
                            """);
                    PreparedStatement deleteTenant = connection.prepareStatement("""
                            delete from public.tenant where id = ? and code = ?
                            """)) {
                connection.setAutoCommit(false);
                try {
                    setLocalTenant(connection, fixture.id());
                    for (LocalDate day : days) {
                        deleteSequence.setObject(1, fixture.id());
                        deleteSequence.setObject(2, day);
                        assertThat(deleteSequence.executeUpdate()).isBetween(0, 1);
                    }

                    assertThat(sequenceRowCountForTenant(connection, fixture.id()))
                            .as("all child rows are visible in the matching tenant context before parent cleanup")
                            .isZero();
                    deleteTenant.setObject(1, fixture.id());
                    deleteTenant.setString(2, fixture.code());
                    assertThat(deleteTenant.executeUpdate()).isBetween(0, 1);
                    assertThat(tenantRowCount(connection, fixture.id(), fixture.code())).isZero();
                    assertThat(sequenceRowCountForTenant(connection, fixture.id())).isZero();
                    connection.commit();

                    // Check committed cleanup in a new transaction with the same explicit tenant context.
                    setLocalTenant(connection, fixture.id());
                    assertThat(sequenceRowCountForTenant(connection, fixture.id())).isZero();
                    assertThat(tenantRowCount(connection, fixture.id(), fixture.code())).isZero();
                    connection.rollback();
                } catch (Throwable failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                    if (failure instanceof SQLException exception) throw exception;
                    if (failure instanceof RuntimeException exception) throw exception;
                    if (failure instanceof Error error) throw error;
                    throw new SQLException("Could not clean V7 tenant fixture", failure);
                }
            } catch (SQLException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new SQLException("Could not clean V7 synthetic tenant fixture " + fixture.id(), failure);
            }
        }
    }

    private static long sequenceRowCountForTenant(Connection connection, UUID tenant) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select count(*) from public.finding_number_sequence where tenant_id = ?
                """)) {
            query.setObject(1, tenant);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static long tenantRowCount(Connection connection, UUID tenant, String code) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select count(*) from public.tenant where id = ? and code = ?
                """)) {
            query.setObject(1, tenant);
            query.setString(2, code);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static Connection managementConnection(String jdbcUrl, String username, String password)
            throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test input is missing: " + name);
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }

    private static final class DeliberateRollback extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private record TenantFixture(UUID id, String code, String name) { }

    record TenantPolicy(String name, String permissive, String command, String roles,
            String usingExpression, String checkExpression) { }

    private record TenantFixtures(TenantFixture tenantA, TenantFixture tenantB) {
        private List<TenantFixture> all() {
            return List.of(tenantA, tenantB);
        }
    }
}
