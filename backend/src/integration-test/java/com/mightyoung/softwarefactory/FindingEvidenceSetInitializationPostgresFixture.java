package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flowable.engine.RepositoryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.Function;

/** Owned G024 metadata and an explicit retained V2 deployment; no producer or legacy-upgrade proof.
 * All catalog checks precede business fixture writes and deployment. This file deliberately uses
 * only PRE17 production interfaces so it can overlay a fresh copy of the frozen V16 source.
 */
final class FindingEvidenceSetInitializationPostgresFixture implements AutoCloseable {
    static final String RECEIPTS = "finding_evidence_set_initialization_receipt";
    record Case(UUID findingId, UUID noticeId, int roundNo, BusinessWorkflowPostgresFixture.Actor owner) { }
    final BusinessWorkflowPostgresFixture business;
    final String definitionId;
    private final HikariDataSource runtime;
    private final HikariDataSource owner;
    private final JdbcTemplate app;
    private final JdbcTemplate admin;
    private final TransactionTemplate runtimeTx;
    private final TransactionTemplate ownerTx;
    private final PostgresAuthorizationWriteGuard ownerGuard;

    static LanPostgresCompatibilityIT.DatabaseTarget validateTarget() {
        var target = BusinessWorkflowPostgresFixture.validateTarget();
        if (!("I_CONFIRM_EVIDENCE_SET_INITIALIZATION_FIXTURE_AND_EXPLICIT_DEPLOYMENT:" + target.databaseName())
                .equals(System.getenv("LAN_PG_EVIDENCE_SET_INITIALIZATION_ACCEPTANCE_ACK"))) {
            throw new IllegalStateException("G024 requires database-specific fixture and explicit-deployment confirmation");
        }
        // DynamicPropertySource invokes this before application startup as well as before seeding.
        try (var app = DriverManager.getConnection(target.jdbcUrl(),required("LAN_PG_APP_USERNAME"),required("LAN_PG_APP_PASSWORD"));
             var admin = DriverManager.getConnection(target.jdbcUrl(),required("LAN_PG_USERNAME"),required("LAN_PG_PASSWORD"))) {
            app.setReadOnly(true); admin.setReadOnly(true);
            preflight(new JdbcTemplate(new SingleConnectionDataSource(app,true)),
                    new JdbcTemplate(new SingleConnectionDataSource(admin,true)));
        } catch (SQLException failure) {
            throw new IllegalStateException("Read-only G024 prerequisite inspection failed",failure);
        }
        return target;
    }

    FindingEvidenceSetInitializationPostgresFixture(PasswordEncoder passwords, RepositoryService definitions) {
        var target = validateTarget();
        runtime = pool(target.jdbcUrl(), "LAN_PG_APP_USERNAME", "LAN_PG_APP_PASSWORD");
        owner = pool(target.jdbcUrl(), "LAN_PG_USERNAME", "LAN_PG_PASSWORD");
        app = new JdbcTemplate(runtime); admin = new JdbcTemplate(owner);
        runtimeTx = transaction(runtime); ownerTx = transaction(owner);
        ownerGuard = new PostgresAuthorizationWriteGuard(owner);
        BusinessWorkflowPostgresFixture seeded = null;
        try {
            preflight(app,admin);
            seeded = new BusinessWorkflowPostgresFixture(passwords);
            business = seeded;
            // Give the second owned actor a real scoped remediation grant, not a mocked session.
            securityWrite(db -> {
                int inserted = db.update("""
                        insert into public.role_permission(tenant_id,role_id,permission_id,scope_rule_id)
                        select rp.tenant_id,rp.role_id,p.id,rp.scope_rule_id
                          from public.role_permission rp join public.role_grant g
                            on g.tenant_id=rp.tenant_id and g.role_id=rp.role_id
                          cross join public.permission p
                         where g.tenant_id=? and g.member_id=? and p.code='finding.remediate'
                        """, business.tenantId, business.alternatePerson.memberId());
                if (inserted != 1) throw new IllegalStateException("Expected one owned scoped remediation permission");
                return null;
            });
            var deployment = definitions.createDeployment().name(business.run + "-g024-explicit-v2")
                    .tenantId(business.tenantId.toString())
                    .addClasspathResource("processes/finding-remediation-v2.bpmn20.xml").deploy();
            definitionId = definitions.createProcessDefinitionQuery().deploymentId(deployment.getId())
                    .processDefinitionKey("findingRemediationV2").singleResult().getId();
        } catch (RuntimeException | Error failure) {
            if (seeded != null) seeded.close();
            runtime.close(); owner.close(); throw failure;
        }
    }

    private static void preflight(JdbcTemplate app,JdbcTemplate admin) {
        if (!Boolean.FALSE.equals(app.queryForObject("select rolsuper or rolbypassrls or rolinherit from pg_roles where rolname=current_user", Boolean.class)))
            throw new IllegalStateException("G024 requires a nonprivileged non-inheriting runtime");
        if (!Boolean.FALSE.equals(app.queryForObject("select has_schema_privilege(current_user,'public','CREATE') or has_database_privilege(current_user,current_database(),'CREATE') or has_database_privilege(current_user,current_database(),'TEMP')", Boolean.class)))
            throw new IllegalStateException("G024 runtime must not have DDL or temporary-object authority");
        // Class presence describes the source era only. Target/fixture authority comes from the
        // explicit acknowledgements above; it never comes from this compatibility detection.
        int requiredVersion = FindingEvidenceSetInitializationPostgresFixture.class.getClassLoader().getResource(
                "com/mightyoung/softwarefactory/dualprevention/finding/workflow/FindingReviewProtocol.class") == null ? 16 : 17;
        if (!Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from public.flyway_schema_history_app where version=? and success)", Boolean.class, Integer.toString(requiredVersion))))
            throw new IllegalStateException("Required separately installed source-era migration is missing");
        for (String table : List.of("evidence_set", RECEIPTS)) {
            if (!Boolean.TRUE.equals(app.queryForObject("select relrowsecurity and relforcerowsecurity and pg_get_userbyid(relowner)<>current_user from pg_class where oid=cast(? as regclass)", Boolean.class, "public." + table)))
                throw new IllegalStateException("G024 requires FORCE RLS and a nonowner runtime");
        }
        if (!Boolean.TRUE.equals(app.queryForObject("select has_table_privilege(current_user,'public.finding_evidence_set_initialization_receipt','SELECT') and has_table_privilege(current_user,'public.finding_evidence_set_initialization_receipt','INSERT')", Boolean.class)))
            throw new IllegalStateException("Missing separately reviewed G024 receipt privileges");
        for (String action : List.of("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "MAINTAIN"))
            if (!Boolean.FALSE.equals(app.queryForObject("select has_table_privilege(current_user,'public.finding_evidence_set_initialization_receipt',?)", Boolean.class, action)))
                throw new IllegalStateException("Unexpected mutable or administrative G024 receipt privilege");
        if (!Boolean.FALSE.equals(app.queryForObject("select has_any_column_privilege(current_user,'public.finding_evidence_set_initialization_receipt','UPDATE')", Boolean.class)))
            throw new IllegalStateException("Unexpected G024 receipt column UPDATE privilege");
        for (String column : List.of("tenant_id", "id", "finding_id", "round_no", "owner_member_id", "state", "created_at", "evidence_revision"))
            if (!Boolean.TRUE.equals(app.queryForObject("select has_column_privilege(current_user,'public.evidence_set',?,'INSERT')", Boolean.class, column)))
                throw new IllegalStateException("Missing separately reviewed evidence-set INSERT column: " + column);
    }

    Case createCase(BusinessWorkflowPostgresFixture.Actor actor) {
        return seedCase(actor, 1, 1, "OPEN");
    }

    /** Initial owned metadata only. No transition, historical seal or scanner result is fabricated. */
    Case seedCase(BusinessWorkflowPostgresFixture.Actor actor, int currentRound, int targetRound, String roundState) {
        if (currentRound < targetRound || targetRound < 1 || !Set.of("OPEN", "CLOSED").contains(roundState))
            throw new IllegalArgumentException("Invalid owned quota fixture");
        UUID finding = UUID.randomUUID(), notice = UUID.randomUUID();
        securityWrite(db -> {
            db.update("""
                    insert into public.finding(tenant_id,id,organization_id,code,description,discovered_at,grade,state,round_no,version,created_by_member_id,updated_by_member_id)
                    values (?,?,?,?,?,current_timestamp,'UNKNOWN','RECTIFYING',?,1,?,?)
                    """, business.tenantId, finding, business.organizationId, "G024-" + finding, business.run,
                    currentRound, business.reporter.memberId(), business.reporter.memberId());
            db.update("""
                    insert into public.remediation_round(tenant_id,finding_id,round_no,state,opened_at,opened_by_command_id,closed_at,closed_by_command_id)
                    values (?,?,?,?,current_timestamp,?,case when ?='CLOSED' then current_timestamp else null end,case when ?='CLOSED' then cast(? as uuid) else null end)
                    """, business.tenantId, finding, targetRound, roundState, UUID.randomUUID(), roundState, roundState, UUID.randomUUID());
            db.update("""
                    insert into public.remediation_notice(tenant_id,id,finding_id,revision,round_no,assignee_id,due_at,original_due_at,requirements,state,issued_at,command_id)
                    values (?,?,?,1,?,?,current_timestamp+interval '1 day',current_timestamp+interval '1 day','Owned G024 initializer fixture','ISSUED',current_timestamp,?)
                    """, business.tenantId, notice, finding, targetRound, actor.memberId(), UUID.randomUUID());
            if (currentRound != targetRound) {
                db.update("insert into public.remediation_round(tenant_id,finding_id,round_no,state,opened_at,opened_by_command_id) values (?,?,?,'OPEN',current_timestamp,?)",
                        business.tenantId, finding, currentRound, UUID.randomUUID());
                db.update("""
                        insert into public.remediation_notice(tenant_id,id,finding_id,revision,round_no,assignee_id,due_at,original_due_at,requirements,state,issued_at,command_id)
                        values (?,?,?,1,?,?,current_timestamp+interval '1 day',current_timestamp+interval '1 day','Owned G024 current-round metadata','ISSUED',current_timestamp,?)
                        """, business.tenantId, UUID.randomUUID(), finding, currentRound, actor.memberId(), UUID.randomUUID());
            }
            return null;
        });
        return new Case(finding, notice, targetRound, actor);
    }

    UUID seedSet(Case candidate, String state, boolean nonempty) {
        if (!Set.of("UPLOADING", "QUARANTINED", "FAILED").contains(state)) throw new IllegalArgumentException();
        UUID id = UUID.randomUUID();
        securityWrite(db -> {
            db.update("insert into public.evidence_set(tenant_id,id,finding_id,round_no,owner_member_id,state,created_at,evidence_revision) values (?,?,?,?,?,?,current_timestamp,0)",
                    business.tenantId, id, candidate.findingId(), candidate.roundNo(), candidate.owner().memberId(), state);
            if (nonempty) db.update("""
                    insert into public.evidence_item(tenant_id,id,evidence_set_id,source_kind,source_url,state,verification_level)
                    values (?,?,?,'URL','https://invalid.example/g024-unverified','QUARANTINED','UNVERIFIED')
                    """, business.tenantId, UUID.randomUUID(), id);
            return null;
        });
        return id;
    }

    <T> T securityWrite(Function<JdbcTemplate,T> work) {
        return ownerTx.execute(status -> {
            ownerGuard.forGlobalSecurityChange(); tenant(admin, business.tenantId);
            return work.apply(admin);
        });
    }
    <T> T read(Function<JdbcTemplate,T> work) { return business.read(work); }
    <T> T runtime(UUID tenant, Function<JdbcTemplate,T> work) {
        return runtimeTx.execute(status -> { tenant(app, tenant); return work.apply(app); });
    }
    void rollbackProbe(Function<JdbcTemplate,?> work) {
        ownerTx.executeWithoutResult(status -> {
            ownerGuard.forGlobalSecurityChange(); tenant(admin, business.tenantId);
            work.apply(admin); admin.execute("set constraints all immediate"); status.setRollbackOnly();
        });
    }
    void assertPoolTenantCleared() {
        if (app.queryForObject("select nullif(current_setting('app.tenant_id',true),'')", String.class) != null)
            throw new AssertionError("Transaction-local tenant escaped the runtime pool");
    }
    record Snapshot(Map<String,List<Map<String,Object>>> rows) {
        @Override public String toString() {
            Map<String,Integer> counts = new TreeMap<>(); rows.forEach((table,data) -> counts.put(table,data.size()));
            return "G024Snapshot[rowCounts=" + counts + "]";
        }
    }
    Snapshot snapshot() {
        return read(db -> {
            Map<String,List<Map<String,Object>>> rows = new TreeMap<>();
            for (String table : List.of("finding", "finding_confirmation", "finding_transition", "finding_command_receipt",
                    "workflow_binding", "audit_event", "remediation_round", "remediation_notice", "remediation_submission",
                    "remediation_submission_evidence", "verification", "evidence_set", "evidence_item", "file_object", "file_link", RECEIPTS))
                rows.put(table, db.queryForList("select to_jsonb(t)::text row from public." + table + " t where tenant_id=? order by to_jsonb(t)::text", business.tenantId));
            engineSnapshot(db, rows);
            rows.put("publication", db.queryForList("select (to_jsonb(t)-'completion_date')::text row from public.event_publication t where serialized_event like ? order by (to_jsonb(t)-'completion_date')::text", "%" + business.tenantId + "%"));
            return new Snapshot(Map.copyOf(rows));
        });
    }
    /** Same pinned Flowable 8 PostgreSQL mappings used by the reviewed G006 snapshot.
     * Referenced owned values are covered; unreferenced orphan bytearray absence is not proved.
     */
    private void engineSnapshot(JdbcTemplate db, Map<String,List<Map<String,Object>>> rows) {
        String owned = """
                with owned_process(id) as (select process_instance_id from public.workflow_binding where tenant_id=? and business_type='FINDING'
                    union select proc_inst_id_ from public.act_hi_procinst where tenant_id_=?
                    union select proc_inst_id_ from public.act_ru_execution where tenant_id_=?),
                owned_execution(id) as (select id_ from public.act_ru_execution where proc_inst_id_ in (select id from owned_process)),
                owned_task(id) as (select id_ from public.act_ru_task where proc_inst_id_ in (select id from owned_process)
                    union select id_ from public.act_hi_taskinst where proc_inst_id_ in (select id from owned_process))
                """;
        Map<String,String> filters = new LinkedHashMap<>();
        for (String table : List.of("act_ru_execution", "act_ru_task", "act_hi_procinst", "act_hi_taskinst", "act_ru_actinst", "act_hi_actinst"))
            filters.put(table, "t.proc_inst_id_ in (select id from owned_process)");
        for (String table : List.of("act_ru_variable", "act_hi_varinst", "act_hi_detail"))
            filters.put(table, "t.proc_inst_id_ in (select id from owned_process) or t.execution_id_ in (select id from owned_execution) or t.task_id_ in (select id from owned_task)");
        for (String table : List.of("act_ru_identitylink", "act_hi_identitylink"))
            filters.put(table, "t.proc_inst_id_ in (select id from owned_process) or t.task_id_ in (select id from owned_task)");
        filters.put("act_ge_bytearray", """
                t.id_ in (select bytearray_id_ from public.act_ru_variable where proc_inst_id_ in (select id from owned_process) or execution_id_ in (select id from owned_execution) or task_id_ in (select id from owned_task)
                    union select bytearray_id_ from public.act_hi_varinst where proc_inst_id_ in (select id from owned_process) or execution_id_ in (select id from owned_execution) or task_id_ in (select id from owned_task)
                    union select bytearray_id_ from public.act_hi_detail where proc_inst_id_ in (select id from owned_process) or execution_id_ in (select id from owned_execution) or task_id_ in (select id from owned_task))
                """);
        filters.forEach((table,filter) -> rows.put(table, db.queryForList(owned + "select to_jsonb(t)::text row from public." + table + " t where " + filter + " order by to_jsonb(t)::text", business.tenantId, business.tenantId.toString(), business.tenantId.toString())));
    }
    private static void tenant(JdbcTemplate db, UUID tenant) { db.queryForObject("select set_config('app.tenant_id',?,true)", String.class, tenant == null ? "" : tenant.toString()); }
    private static TransactionTemplate transaction(HikariDataSource source) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(10); return tx;
    }
    private static HikariDataSource pool(String url,String user,String password) {
        var config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername(required(user)); config.setPassword(required(password));
        config.setMinimumIdle(0); config.setMaximumPoolSize(2); return new HikariDataSource(config);
    }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException("Missing explicit G024 acceptance input"); return value;
    }
    @Override public void close() { try { business.close(); } finally { runtime.close(); owner.close(); } }
    @Override public String toString() { return "FindingEvidenceSetInitializationPostgresFixture[REDACTED]"; }
}
