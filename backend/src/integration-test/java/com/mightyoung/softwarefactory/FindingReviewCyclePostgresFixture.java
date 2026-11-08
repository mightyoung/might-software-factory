package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.workflow.FindingReviewDefinitionProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flowable.engine.RepositoryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** Explicitly deployed owned V3 fixture, never evidence of old-source continuity or auto-deployment. */
final class FindingReviewCyclePostgresFixture implements AutoCloseable {
    static final List<String> HISTORIES = List.of("finding_review_cycle", "finding_review_decision",
            "finding_report_supplement", "finding_command_participant", "finding_confirmation", "finding_transition");
    final BusinessWorkflowPostgresFixture business;
    final String definitionId;
    final String digest;
    private final HikariDataSource runtime;
    private final HikariDataSource owner;
    private final TransactionTemplate runtimeTx;
    private final TransactionTemplate ownerTx;
    private final JdbcTemplate app;
    private final JdbcTemplate admin;
    private final PostgresAuthorizationWriteGuard guard;

    static LanPostgresCompatibilityIT.DatabaseTarget validateTarget() {
        var target = BusinessWorkflowPostgresFixture.validateTarget();
        if (!("I_CONFIRM_G006_FIXTURE_AND_EXPLICIT_DEPLOYMENT:" + target.databaseName())
                .equals(System.getenv("LAN_PG_REVIEW_CYCLE_ACCEPTANCE_ACK"))) {
            throw new IllegalStateException("G006 requires exact database-specific fixture/deployment authority");
        }
        return target;
    }

    FindingReviewCyclePostgresFixture(PasswordEncoder passwords, RepositoryService definitions) throws Exception {
        var target = validateTarget();
        runtime = pool(target.jdbcUrl(), "LAN_PG_APP_USERNAME", "LAN_PG_APP_PASSWORD");
        owner = pool(target.jdbcUrl(), "LAN_PG_USERNAME", "LAN_PG_PASSWORD");
        app = new JdbcTemplate(runtime); admin = new JdbcTemplate(owner);
        runtimeTx = new TransactionTemplate(new DataSourceTransactionManager(runtime));
        ownerTx = new TransactionTemplate(new DataSourceTransactionManager(owner));
        guard = new PostgresAuthorizationWriteGuard(owner);
        try {
            preflight(); // Before all fixture writes, require separately installed V17 and reviewed privileges.
            business = new BusinessWorkflowPostgresFixture(passwords);
            try (var resource = getClass().getClassLoader().getResourceAsStream("processes/finding-remediation-v3.bpmn20.xml")) {
                if (resource == null) throw new IllegalStateException("Packaged V3 resource missing");
                digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resource.readAllBytes()));
            }
            var deployment = definitions.createDeployment().name(business.run + "-g006-explicit-v3")
                    .tenantId(business.tenantId.toString())
                    .addClasspathResource("processes/finding-remediation-v3.bpmn20.xml").deploy();
            definitionId = definitions.createProcessDefinitionQuery().deploymentId(deployment.getId())
                    .processDefinitionKey("findingRemediationV3").singleResult().getId();
        } catch (Exception | Error failure) {
            runtime.close(); owner.close(); throw failure;
        }
    }

    FindingReviewDefinitionProperties properties() {
        return new FindingReviewDefinitionProperties(true, Map.of(business.tenantId,
                new FindingReviewDefinitionProperties.Pin(definitionId, digest)));
    }

    private void preflight() {
        if (!Boolean.FALSE.equals(app.queryForObject("select rolsuper or rolbypassrls or rolinherit from pg_roles where rolname=current_user", Boolean.class)))
            throw new IllegalStateException("Nonprivileged non-inheriting runtime required");
        if (!Boolean.FALSE.equals(app.queryForObject("select has_schema_privilege(current_user,'public','CREATE')", Boolean.class)))
            throw new IllegalStateException("Runtime DDL forbidden");
        if (!Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from public.flyway_schema_history_app where version='17' and success)", Boolean.class)))
            throw new IllegalStateException("Reviewed V17 is not installed; no fixture writes permitted");
        for (String column : List.of("review_cycle", "review_protocol")) {
            if (!Boolean.TRUE.equals(app.queryForObject("select has_column_privilege(current_user,'public.finding',?,'SELECT') and has_column_privilege(current_user,'public.finding',?,'INSERT') and has_column_privilege(current_user,'public.finding',?,'UPDATE')", Boolean.class, column, column, column)))
                throw new IllegalStateException("Missing separately reviewed finding column privileges: " + column);
        }
        if (!Boolean.TRUE.equals(app.queryForObject("select has_column_privilege(current_user,'public.finding_command_receipt','result_review_cycle','SELECT') and has_column_privilege(current_user,'public.finding_command_receipt','result_review_cycle','UPDATE')", Boolean.class)))
            throw new IllegalStateException("Missing separately reviewed receipt result-cycle privileges");
        for (String table : HISTORIES.subList(0, 4)) {
            if (!Boolean.TRUE.equals(app.queryForObject("select relrowsecurity and relforcerowsecurity and pg_get_userbyid(relowner)<>current_user from pg_class where oid=cast(? as regclass)", Boolean.class, "public." + table)))
                throw new IllegalStateException("FORCE RLS and nonowner runtime required");
            if (!Boolean.TRUE.equals(app.queryForObject("select has_table_privilege(current_user,cast(? as regclass),'SELECT') and has_table_privilege(current_user,cast(? as regclass),'INSERT')", Boolean.class, "public." + table, "public." + table)))
                throw new IllegalStateException("Missing separately reviewed V17 runtime privileges");
            for (String action : List.of("UPDATE", "DELETE", "TRUNCATE"))
                if (!Boolean.FALSE.equals(app.queryForObject("select has_table_privilege(current_user,cast(? as regclass),?)", Boolean.class, "public." + table, action)))
                    throw new IllegalStateException("Unexpected mutable history privilege");
        }
    }

    <T> T runtime(UUID tenant, Function<JdbcTemplate, T> work) {
        return runtimeTx.execute(status -> {
            app.queryForObject("select set_config('app.tenant_id',?,true)", String.class, tenant == null ? "" : tenant.toString());
            return work.apply(app);
        });
    }
    /** Negative owner probes always roll back, including probes whose deferred COMMIT checks succeed. */
    void rollbackProbe(Function<JdbcTemplate, ?> work) {
        ownerTx.executeWithoutResult(status -> {
            guard.forGlobalSecurityChange();
            admin.queryForObject("select set_config('app.tenant_id',?,true)", String.class, business.tenantId.toString());
            work.apply(admin);
            admin.execute("set constraints all immediate");
            status.setRollbackOnly();
        });
    }
    void securityWrite(Function<JdbcTemplate, ?> work) {
        ownerTx.executeWithoutResult(status -> {
            guard.forGlobalSecurityChange();
            admin.queryForObject("select set_config('app.tenant_id',?,true)", String.class, business.tenantId.toString());
            work.apply(admin);
        });
    }
    void assertPoolTenantCleared() {
        String setting = app.queryForObject("select nullif(current_setting('app.tenant_id',true),'')", String.class);
        if (setting != null) throw new AssertionError("Transaction-local tenant escaped into pooled connection");
    }
    record Snapshot(Map<String, List<Map<String, Object>>> rows) {
        @Override public String toString() {
            Map<String,Integer> sizes = new TreeMap<>(); rows.forEach((table, data) -> sizes.put(table, data.size()));
            return "OwnedFixtureSnapshot[rowCounts=" + sizes + "]";
        }
    }
    Snapshot snapshot() {
        return business.read(db -> {
            Map<String, List<Map<String, Object>>> rows = new TreeMap<>();
            var tables = new ArrayList<>(HISTORIES);
            tables.addAll(List.of("finding", "finding_command_receipt", "workflow_binding", "audit_event",
                    "remediation_round", "remediation_notice", "remediation_submission", "remediation_submission_evidence",
                    "verification", "evidence_set", "evidence_item", "file_object", "file_link", "finding_number_sequence"));
            for (String table : tables) rows.put(table, db.queryForList("select to_jsonb(t)::text row from public." + table
                    + " t where tenant_id=? order by to_jsonb(t)::text", business.tenantId));
            engineSnapshot(db, rows);
            rows.put("publication", db.queryForList("select (to_jsonb(t)-'completion_date')::text row from public.event_publication t where serialized_event like ? order by (to_jsonb(t)-'completion_date')::text", "%" + business.tenantId + "%"));
            return new Snapshot(Map.copyOf(rows));
        });
    }
    /** Column mapping checked against pinned Flowable 8.0.0 PostgreSQL common/engine/history DDL.
     * Variables and links have no tenant column: derive ownership through real process/task IDs.
     * No global engine dump, session rows, authentication cookies or credential material is captured.
     */
    private void engineSnapshot(JdbcTemplate db, Map<String,List<Map<String,Object>>> rows) {
        String owned = """
                with owned_process(id) as (
                    select process_instance_id from public.workflow_binding where tenant_id=? and business_type='FINDING'
                    union select proc_inst_id_ from public.act_hi_procinst where tenant_id_=?
                    union select proc_inst_id_ from public.act_ru_execution where tenant_id_=?
                ), owned_execution(id) as (
                    select id_ from public.act_ru_execution where proc_inst_id_ in (select id from owned_process)
                ), owned_task(id) as (
                    select id_ from public.act_ru_task where proc_inst_id_ in (select id from owned_process)
                    union select id_ from public.act_hi_taskinst where proc_inst_id_ in (select id from owned_process)
                )
                """;
        Map<String,String> filters = new LinkedHashMap<>();
        for (String table : List.of("act_ru_execution", "act_ru_task", "act_hi_procinst", "act_hi_taskinst",
                "act_ru_actinst", "act_hi_actinst"))
            filters.put(table, "t.proc_inst_id_ in (select id from owned_process)");
        for (String table : List.of("act_ru_variable", "act_hi_varinst", "act_hi_detail"))
            filters.put(table, "t.proc_inst_id_ in (select id from owned_process) or t.execution_id_ in (select id from owned_execution) or t.task_id_ in (select id from owned_task)");
        for (String table : List.of("act_ru_identitylink", "act_hi_identitylink"))
            filters.put(table, "t.proc_inst_id_ in (select id from owned_process) or t.task_id_ in (select id from owned_task)");
        filters.put("act_ge_bytearray", """
                t.id_ in (
                    select v.bytearray_id_ from public.act_ru_variable v where v.proc_inst_id_ in (select id from owned_process) or v.execution_id_ in (select id from owned_execution) or v.task_id_ in (select id from owned_task)
                    union select v.bytearray_id_ from public.act_hi_varinst v where v.proc_inst_id_ in (select id from owned_process) or v.execution_id_ in (select id from owned_execution) or v.task_id_ in (select id from owned_task)
                    union select v.bytearray_id_ from public.act_hi_detail v where v.proc_inst_id_ in (select id from owned_process) or v.execution_id_ in (select id from owned_execution) or v.task_id_ in (select id from owned_task)
                )
                """);
        filters.forEach((table, filter) -> rows.put(table, db.queryForList(owned
                + "select to_jsonb(t)::text row from public." + table + " t where " + filter + " order by to_jsonb(t)::text",
                business.tenantId, business.tenantId.toString(), business.tenantId.toString())));
    }
    private static HikariDataSource pool(String url, String user, String password) {
        var config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername(required(user));
        config.setPassword(required(password)); config.setMinimumIdle(0); config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing explicit acceptance input");
        return value;
    }
    @Override public void close() {
        try { business.close(); } finally { runtime.close(); owner.close(); }
    }
    @Override public String toString() { return "FindingReviewCyclePostgresFixture[REDACTED]"; }
}
