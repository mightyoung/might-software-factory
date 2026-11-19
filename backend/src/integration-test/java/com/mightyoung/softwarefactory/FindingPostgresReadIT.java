package com.mightyoung.softwarefactory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator;
import com.mightyoung.softwarefactory.authorization.EntityReadAccessPolicy;
import com.mightyoung.softwarefactory.authorization.EntityReadGrant;
import com.mightyoung.softwarefactory.dualprevention.FindingEntitySchema;
import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.dualprevention.FindingReadAuthorizer;
import com.mightyoung.softwarefactory.dualprevention.FindingReadService;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import com.mightyoung.softwarefactory.dualprevention.finding.JooqFindingRepository;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipal;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL read/RLS probe. The session principal and permission policy are test fixtures;
 * this does not exercise production login or JDBC session persistence.
 */
class FindingPostgresReadIT {

    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Pattern SAFE_ROLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private static HikariDataSource applicationDataSource;
    private static FindingReadService findingReadService;
    private static String appRole;

    @AfterAll
    static void closeApplicationPoolAndClearSecurityContext() {
        SecurityContextHolder.clearContext();
        if (applicationDataSource != null) {
            applicationDataSource.close();
        }
    }

    @Test
    void lowPrivilegeConnectionReadsOnlyItsTenantAndRlsHidesOtherTenantRows() throws Exception {
        FixturePostgresTarget.Target target = FixturePostgresTarget.preflightRepository(System.getenv());
        appRole = required("LAN_PG_APP_USERNAME");
        assertThat(SAFE_ROLE_NAME.matcher(appRole).matches()).isTrue();

        verifyFindingMigrationAndReadGrant(target.jdbcUrl(), target.databaseName(), appRole);
        applicationDataSource = createApplicationDataSource(target.jdbcUrl(), appRole,
                required("LAN_PG_APP_PASSWORD"));
        verifyApplicationRoleBoundary(applicationDataSource, target.databaseName(), appRole);
        verifyFindingRlsDefinition(target.jdbcUrl());

        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID organizationA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        UUID findingA = UUID.randomUUID();
        UUID findingB = UUID.randomUUID();

        try {
            setupService(applicationDataSource, tenantA, organizationA);
            seedSyntheticFindings(target.jdbcUrl(), tenantA, tenantB, organizationA, organizationB,
                    findingA, findingB);
            int originalBackendPid = backendPid(applicationDataSource);
            runAsTenant(tenantA);

            var page = findingReadService.query("{\"page\":{\"number\":1},\"size\":20}");
            assertThat(page.data().page().total()).isEqualTo(1);
            assertThat(page.data().items()).hasSize(1);
            assertThat(page.data().items().getFirst()).containsEntry("id", findingA);
            assertThat(page.data().items().getFirst()).doesNotContainKeys("tenant_id", "organization_id");
            assertThat(page.data().items().getFirst())
                    .containsEntry("sourceCategory", "FIELD_OBSERVATION")
                    .containsEntry("urgency", "NORMAL")
                    .containsEntry("location", "Tenant A loading dock")
                    .containsEntry("interimMeasure", "Tenant A temporary barrier")
                    .containsEntry("backfill", false);

            var detail = findingReadService.detail(findingA);
            assertThat(detail.data()).containsEntry("id", findingA);
            assertThat(detail.data())
                    .containsEntry("sourceCategory", "FIELD_OBSERVATION")
                    .containsEntry("urgency", "NORMAL")
                    .containsEntry("location", "Tenant A loading dock")
                    .containsEntry("interimMeasure", "Tenant A temporary barrier")
                    .containsEntry("backfill", false);
            assertThatThrownBy(() -> findingReadService.detail(findingB))
                    .isInstanceOf(FindingNotVisibleException.class);

            assertDirectRlsCannotReadOtherTenant(applicationDataSource, tenantA, tenantB, findingB);
            assertConnectionLocalTenantIsCleared(applicationDataSource, originalBackendPid);
        } finally {
            SecurityContextHolder.clearContext();
            cleanupSyntheticFindings(target.jdbcUrl(), tenantA, tenantB, findingA, findingB);
        }

        assertSyntheticFindingsRemoved(target.jdbcUrl(), tenantA, tenantB, findingA, findingB);
    }

    private static void verifyFindingMigrationAndReadGrant(String jdbcUrl, String expectedDatabase, String applicationRole) throws Exception {
        String migrationUser = required("LAN_PG_USERNAME");
        String migrationPassword = required("LAN_PG_PASSWORD");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, migrationUser, migrationPassword);
                PreparedStatement query = connection.prepareStatement("""
                        select current_database(), current_user,
                               (select rolsuper from pg_roles where rolname = current_user),
                               to_regclass('public.finding') is not null,
                               to_regclass('public.flyway_schema_history_app') is not null,
                               exists(select 1 from public.flyway_schema_history_app
                                      where version = '2' and success),
                               exists(select 1 from public.flyway_schema_history_app
                                      where version = '8' and success),
                               has_table_privilege(?, 'public.finding', 'SELECT'),
                               has_table_privilege(current_user, 'public.finding', 'INSERT'),
                               has_table_privilege(current_user, 'public.finding', 'DELETE')
                        """)) {
            query.setString(1, applicationRole);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(expectedDatabase);
                assertThat(result.getString(2)).isEqualTo(migrationUser);
                assertThat(result.getBoolean(3)).isFalse();
                assertThat(result.getBoolean(4)).isTrue();
                assertThat(result.getBoolean(5)).isTrue();
                assertThat(result.getBoolean(6)).isTrue();
                assertThat(result.getBoolean(7)).as("V8 must be applied before metadata read acceptance").isTrue();
                assertThat(result.getBoolean(8)).isTrue();
                assertThat(result.getBoolean(9)).isTrue();
                assertThat(result.getBoolean(10)).isTrue();
            }
        }
    }

    private static HikariDataSource createApplicationDataSource(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setPoolName("finding-read-it");
        return new HikariDataSource(config);
    }

    private static void verifyApplicationRoleBoundary(DataSource dataSource, String expectedDatabase, String expectedRole) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement query = connection.createStatement();
                ResultSet result = query.executeQuery("""
                        select current_database(), current_user, session_user,
                               pg_get_userbyid(d.datdba), pg_get_userbyid(n.nspowner),
                               r.rolsuper, r.rolbypassrls, r.rolcreatedb, r.rolcreaterole,
                               r.rolreplication, r.rolinherit,
                               has_database_privilege(current_user, current_database(), 'CONNECT'),
                               has_schema_privilege(current_user, 'public', 'USAGE'),
                               has_schema_privilege(current_user, 'public', 'CREATE'),
                               exists(select 1 from pg_auth_members where member = r.oid),
                               has_table_privilege(current_user, 'public.finding', 'SELECT'),
                               pg_get_userbyid(c.relowner)
                          from pg_roles r
                          join pg_database d on d.datname = current_database()
                          join pg_namespace n on n.nspname = 'public'
                          join pg_class c on c.oid = to_regclass('public.finding')
                         where r.rolname = current_user
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo(expectedDatabase);
            assertThat(result.getString(2)).isEqualTo(expectedRole);
            assertThat(result.getString(3)).isEqualTo(expectedRole);
            assertThat(result.getString(4)).isNotEqualTo(expectedRole);
            assertThat(result.getString(5)).isNotEqualTo(expectedRole);
            for (int privilege : List.of(6, 7, 8, 9, 10, 11)) assertThat(result.getBoolean(privilege)).isFalse();
            assertThat(result.getBoolean(12)).isTrue();
            assertThat(result.getBoolean(13)).isTrue();
            assertThat(result.getBoolean(14)).isFalse();
            assertThat(result.getBoolean(15)).isFalse();
            assertThat(result.getBoolean(16)).isTrue();
            assertThat(result.getString(17)).isNotEqualTo(expectedRole);
        }
    }

    private static void verifyFindingRlsDefinition(String jdbcUrl) throws Exception {
        try (Connection connection = managementConnection(jdbcUrl);
                PreparedStatement query = connection.prepareStatement("""
                        select c.relrowsecurity, c.relforcerowsecurity,
                               exists(select 1 from pg_policies p
                                       where p.schemaname = 'public' and p.tablename = 'finding'
                                         and p.policyname = 'finding_tenant_isolation'
                                         and p.qual like '%app.tenant_id%'
                                         and p.with_check like '%app.tenant_id%')
                          from pg_class c where c.oid = to_regclass('public.finding')
                        """)) {
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isTrue();
                assertThat(result.getBoolean(2)).isTrue();
                assertThat(result.getBoolean(3)).isTrue();
            }
        }
    }

    private static void seedSyntheticFindings(String jdbcUrl, UUID tenantA, UUID tenantB,
            UUID organizationA, UUID organizationB, UUID findingA, UUID findingB) throws Exception {
        try (Connection connection = managementConnection(jdbcUrl);
                PreparedStatement insert = connection.prepareStatement("""
                        insert into public.finding (
                            tenant_id, id, organization_id, code, description, discovered_at,
                            grade, state, round_no, version, created_by_member_id, updated_by_member_id,
                            source_category, urgency, location, interim_measure, backfill
                        ) values (?, ?, ?, ?, ?, ?, 'GENERAL', 'DISCOVERED', 1, 1, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            connection.setAutoCommit(false);
            try {
                setTenantContext(connection, tenantA);
                insertSyntheticFinding(insert, tenantA, findingA, organizationA, "FINDING-IT-A",
                        "FIELD_OBSERVATION", "NORMAL", "Tenant A loading dock",
                        "Tenant A temporary barrier", false);
                assertThat(insert.executeUpdate()).isEqualTo(1);
                setTenantContext(connection, tenantB);
                insertSyntheticFinding(insert, tenantB, findingB, organizationB, "FINDING-IT-B",
                        "PERSON_REPORT", "URGENT", "Tenant B west entrance",
                        "Tenant B area closed", true);
                assertThat(insert.executeUpdate()).isEqualTo(1);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void setTenantContext(Connection connection, UUID tenantId) throws Exception {
        try (PreparedStatement setTenant = connection.prepareStatement(
                "select set_config('app.tenant_id', ?, true)")) {
            setTenant.setString(1, tenantId.toString());
            try (ResultSet result = setTenant.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(tenantId.toString());
            }
        }
    }

    private static void insertSyntheticFinding(PreparedStatement insert, UUID tenantId, UUID findingId,
            UUID organizationId, String code, String sourceCategory, String urgency, String location,
            String interimMeasure, boolean backfill) throws Exception {
        UUID fixtureMember = UUID.randomUUID();
        insert.setObject(1, tenantId);
        insert.setObject(2, findingId);
        insert.setObject(3, organizationId);
        insert.setString(4, code + "-" + findingId.toString().substring(0, 8));
        insert.setString(5, "Synthetic integration test finding");
        insert.setObject(6, OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
        insert.setObject(7, fixtureMember);
        insert.setObject(8, fixtureMember);
        insert.setString(9, sourceCategory);
        insert.setString(10, urgency);
        insert.setString(11, location);
        insert.setString(12, interimMeasure);
        insert.setBoolean(13, backfill);
    }

    private static void runAsTenant(UUID tenantId) {
        VerifiedSessionPrincipal principal = VerifiedSessionPrincipalTestFixture.create(ACTOR_ID, tenantId, MEMBER_ID);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"))));
    }

    private static void assertDirectRlsCannotReadOtherTenant(
            DataSource dataSource, UUID currentTenant, UUID otherTenant, UUID otherFinding) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int backendPid = queryBackendPid(connection);
            setTenantContext(connection, currentTenant);
            try (PreparedStatement query = connection.prepareStatement(
                    "select count(*) from public.finding where tenant_id = ? and id = ?")) {
                query.setObject(1, otherTenant);
                query.setObject(2, otherFinding);
                try (ResultSet result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                }
            } finally {
                connection.rollback();
            }
            assertThat(backendPid).isGreaterThan(0);
        }
    }

    private static void assertConnectionLocalTenantIsCleared(DataSource dataSource, int expectedBackendPid)
            throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(queryBackendPid(connection)).isEqualTo(expectedBackendPid);
            try (Statement query = connection.createStatement();
                    ResultSet result = query.executeQuery("select current_setting('app.tenant_id', true)")) {
                assertThat(result.next()).isTrue();
                assertThat(Optional.ofNullable(result.getString(1)).orElse("")).isEmpty();
            }
        }
    }

    private static int backendPid(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return queryBackendPid(connection);
        }
    }

    private static int queryBackendPid(Connection connection) throws Exception {
        try (Statement query = connection.createStatement(); ResultSet result = query.executeQuery("select pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static void cleanupSyntheticFindings(String jdbcUrl, UUID tenantA, UUID tenantB,
            UUID findingA, UUID findingB) throws Exception {
        try (Connection connection = managementConnection(jdbcUrl);
                PreparedStatement delete = connection.prepareStatement(
                        "delete from public.finding where tenant_id = ? and id = ?")) {
            connection.setAutoCommit(false);
            try {
                setTenantContext(connection, tenantA);
                delete.setObject(1, tenantA);
                delete.setObject(2, findingA);
                assertThat(delete.executeUpdate()).isBetween(0, 1);
                setTenantContext(connection, tenantB);
                delete.setObject(1, tenantB);
                delete.setObject(2, findingB);
                assertThat(delete.executeUpdate()).isBetween(0, 1);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void assertSyntheticFindingsRemoved(String jdbcUrl, UUID tenantA, UUID tenantB,
            UUID findingA, UUID findingB) throws Exception {
        try (Connection connection = managementConnection(jdbcUrl);
                PreparedStatement query = connection.prepareStatement(
                        "select count(*) from public.finding where tenant_id = ? and id = ?")) {
            connection.setAutoCommit(false);
            setTenantContext(connection, tenantA);
            query.setObject(1, tenantA);
            query.setObject(2, findingA);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
            setTenantContext(connection, tenantB);
            query.setObject(1, tenantB);
            query.setObject(2, findingB);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
            connection.rollback();
        }
    }

    private static Connection managementConnection(String jdbcUrl) throws Exception {
        return DriverManager.getConnection(jdbcUrl, required("LAN_PG_USERNAME"), required("LAN_PG_PASSWORD"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test input is missing: " + name);
        }
        return value;
    }

    private static FindingReadService createFindingReadService(
            DataSource dataSource, UUID allowedTenant, UUID allowedOrganization) {
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var connectionProvider = new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource));
        DSLContext dsl = DSL.using(new DefaultConfiguration()
                .set(SQLDialect.POSTGRES)
                .set(connectionProvider));
        var repository = new JooqFindingRepository(dsl, transactionManager);
        var accessPolicy = (EntityReadAccessPolicy) (context, entityKey) -> {
            if (!"finding".equals(entityKey) || !allowedTenant.equals(context.tenantId())) {
                return Optional.empty();
            }
            return Optional.of(new EntityReadGrant(allowedTenant, Set.of(allowedOrganization)));
        };
        return new FindingReadService(
                new SessionContextResolver(), new FindingReadAuthorizer(accessPolicy),
                new FindingEntitySchema(), new EntityQueryValidator(), repository,
                new StaticListableBeanFactory().getBeanProvider(PlatformTransactionManager.class));
    }

    private static void setupService(DataSource dataSource, UUID tenantA, UUID organizationA) {
        findingReadService = createFindingReadService(dataSource, tenantA, organizationA);
    }
}
