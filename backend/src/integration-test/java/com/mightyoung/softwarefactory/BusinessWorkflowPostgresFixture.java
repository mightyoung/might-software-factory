package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Function;

/** Owned synthetic business history is retained, including all FK ancestors. Close only removes owned sessions. */
final class BusinessWorkflowPostgresFixture implements AutoCloseable {
    record Actor(UUID accountId, UUID principalId, UUID memberId, String loginKey, String password) { }
    final UUID tenantId = UUID.randomUUID();
    final UUID organizationId = UUID.randomUUID();
    final UUID legacyFindingId = UUID.randomUUID();
    final String run = "business-" + UUID.randomUUID();
    final String tenantCode = "b-" + UUID.randomUUID();
    final Actor reporter = actor("reporter");
    final Actor person = actor("person");
    final Actor safety = actor("safety");
    final Actor remediator = actor("remediator");
    final Actor alternatePerson = actor("alternate-person");
    private final HikariDataSource source;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PostgresAuthorizationWriteGuard guard;

    static LanPostgresCompatibilityIT.DatabaseTarget validateTarget() {
        var target = LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(System.getenv());
        if (!("I_CONFIRM_BUSINESS_WORKFLOW_FIXTURE_WRITES:" + target.databaseName())
                .equals(System.getenv("LAN_PG_BUSINESS_ACCEPTANCE_ACK"))) {
            throw new IllegalStateException("Business acceptance requires database-specific fixture-write confirmation");
        }
        return LanPostgresCompatibilityIT.DatabaseTarget.preflightFromEnvironment(System.getenv());
    }

    BusinessWorkflowPostgresFixture(PasswordEncoder passwords) {
        var target = validateTarget();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(target.jdbcUrl()); config.setUsername(required("LAN_PG_USERNAME"));
        config.setPassword(required("LAN_PG_PASSWORD")); config.setMaximumPoolSize(2);
        config.setMinimumIdle(0); config.setPoolName(run);
        source = new HikariDataSource(config); jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        guard = new PostgresAuthorizationWriteGuard(source);
        try { seed(passwords); } catch (RuntimeException | Error failure) { source.close(); throw failure; }
    }

    private static Actor actor(String kind) {
        return new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                "business-" + kind + "-" + UUID.randomUUID(), UUID.randomUUID().toString());
    }

    private void seed(PasswordEncoder passwords) {
        transaction.executeWithoutResult(status -> {
            guard.forGlobalSecurityChange(); tenant();
            // V5 seeds command actions only. Retain this canonical read permission with its owned role ancestors.
            jdbc.update("""
                    insert into public.permission(id,code,resource_type,action)
                    values (?,'finding.read','finding','read') on conflict(code) do nothing
                    """,UUID.randomUUID());
            var readPermission = jdbc.queryForMap("select resource_type,action from public.permission where code='finding.read'");
            if (!"finding".equals(readPermission.get("resource_type")) || !"read".equals(readPermission.get("action"))) {
                throw new IllegalStateException("Canonical finding.read permission has an incompatible definition");
            }
            jdbc.update("insert into public.tenant(id,code,name,status,auth_epoch) values (?,?,?,'ACTIVE',1)",tenantId,tenantCode,run);
            jdbc.update("insert into public.tenant_application(tenant_id,application_key,enabled,version) values (?,'safety',true,1)",tenantId);
            jdbc.update("insert into public.org_unit(tenant_id,id,code,name,active) values (?,?,?,?,true)",tenantId,organizationId,run,run);
            for (Actor actor : List.of(reporter,person,safety,remediator,alternatePerson)) {
                jdbc.update("insert into public.account(id,login_key,status,credential_version) values (?,?,'ACTIVE',1)",actor.accountId(),actor.loginKey());
                jdbc.update("insert into public.principal(id,kind,account_id,status) values (?,'HUMAN',?,'ACTIVE')",actor.principalId(),actor.accountId());
                jdbc.update("insert into public.credential(account_id,kind,secret_hash,changed_at) values (?,'PASSWORD',?,current_timestamp)",actor.accountId(),passwords.encode(actor.password()));
                jdbc.update("insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values (?,?,?,'ACTIVE',1,?)",tenantId,actor.memberId(),actor.accountId(),run);
                jdbc.update("insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values (?,?,?,'PRIMARY',current_timestamp - interval '1 day')",tenantId,actor.memberId(),organizationId);
                List<String> permissions = actor == reporter ? List.of("finding.create","finding.workflow.initialize","finding.read")
                        : actor == person || actor == alternatePerson ? List.of("finding.confirm.person")
                        : actor == safety ? List.of("finding.confirm.safety","finding.dispatch","finding.verify")
                        : List.of("finding.remediate");
                role(actor,permissions);
            }
            for (String stage : List.of("PERSON_CONFIRM","SAFETY_CONFIRM","SAFETY_DISPATCH","SAFETY_VERIFY")) {
                jdbc.update("""
                        insert into public.finding_stage_assignment(tenant_id,organization_id,stage,assignee_member_id,valid_from)
                        values (?,?,?,?,current_timestamp - interval '1 day')
                        """,tenantId,organizationId,stage,stage.equals("PERSON_CONFIRM") ? person.memberId() : safety.memberId());
            }
            jdbc.update("""
                    insert into public.finding(tenant_id,id,organization_id,code,description,discovered_at,grade,state,
                        round_no,version,created_by_member_id,updated_by_member_id)
                    values (?,?,?,?,?,current_timestamp,'UNKNOWN','DISCOVERED',1,1,?,?)
                    """,tenantId,legacyFindingId,organizationId,"LEGACY-" + UUID.randomUUID(),run,reporter.memberId(),reporter.memberId());
        });
    }

    private void role(Actor actor,List<String> permissions) {
        UUID role = UUID.randomUUID(), scope = UUID.randomUUID();
        jdbc.update("insert into public.\"role\"(tenant_id,id,code,name,active) values (?,?,?,?,true)",tenantId,role,actor.loginKey(),run);
        jdbc.update("insert into public.scope_rule(tenant_id,id,kind,version_no,definition) values (?,?,'EXPLICIT',1,'{}'::jsonb)",tenantId,scope);
        jdbc.update("insert into public.scope_org(tenant_id,scope_rule_id,org_id) values (?,?,?)",tenantId,scope,organizationId);
        for (String permission : permissions) {
            int added = jdbc.update("""
                    insert into public.role_permission(tenant_id,role_id,permission_id,scope_rule_id)
                    select ?,?,id,? from public.permission where code = ?
                    """,tenantId,role,scope,permission);
            if (added != 1) throw new IllegalStateException("Missing canonical fixture permission " + permission);
        }
        jdbc.update("""
                insert into public.role_grant(tenant_id,id,role_id,subject_kind,member_id,source_type,source_id,granted_by_member_id,valid_from)
                values (?,?,?,'MEMBER',?,'BUSINESS_ACCEPTANCE',?,?,current_timestamp - interval '1 day')
                """,tenantId,UUID.randomUUID(),role,actor.memberId(),UUID.randomUUID(),actor.memberId());
    }

    void expirePersonAssignments() {
        transaction.executeWithoutResult(status -> {
            guard.forGlobalSecurityChange(); tenant();
            int changed = jdbc.update("""
                    update public.finding_stage_assignment set valid_to=current_timestamp
                    where tenant_id=? and organization_id=? and stage='PERSON_CONFIRM'
                      and valid_from<=current_timestamp and (valid_to is null or valid_to>current_timestamp)
                    """,tenantId,organizationId);
            if (changed != 1) throw new IllegalStateException("Expected one owned current personal assignment");
        });
    }

    void addTwoCurrentPersonAssignments() {
        transaction.executeWithoutResult(status -> {
            guard.forGlobalSecurityChange(); tenant();
            jdbc.update("""
                    insert into public.finding_stage_assignment(tenant_id,organization_id,stage,assignee_member_id,valid_from)
                    values (?,?,'PERSON_CONFIRM',?,current_timestamp),
                           (?,?,'PERSON_CONFIRM',?,current_timestamp - interval '1 microsecond')
                    """,tenantId,organizationId,person.memberId(),tenantId,organizationId,alternatePerson.memberId());
            int current = jdbc.queryForObject("""
                    select count(*) from public.finding_stage_assignment
                    where tenant_id=? and organization_id=? and stage='PERSON_CONFIRM'
                      and valid_from<=current_timestamp and (valid_to is null or valid_to>current_timestamp)
                    """,Integer.class,tenantId,organizationId);
            if (current != 2) throw new IllegalStateException("Expected exactly two owned current personal assignments");
        });
    }

    void revokePersonGrant() {
        transaction.executeWithoutResult(status -> {
            guard.forGlobalSecurityChange(); tenant();
            int changed = jdbc.update("update public.role_grant set valid_to=current_timestamp where tenant_id=? and member_id=? and valid_to is null",tenantId,person.memberId());
            if (changed != 1) throw new IllegalStateException("Expected one owned personal confirmation grant");
        });
    }
    void disableSafetyApplication() {
        transaction.executeWithoutResult(status -> {
            guard.forGlobalSecurityChange(); tenant();
            int changed = jdbc.update("update public.tenant_application set enabled=false,version=version+1 where tenant_id=? and application_key='safety'",tenantId);
            if (changed != 1) throw new IllegalStateException("Expected one owned safety entitlement");
        });
    }

    /** Same datasource/physical transaction as the guard; callbacks only coordinate bounded test barriers. */
    void bumpReporterCredentialVersion(java.util.function.Consumer<Integer> beforeGuard, Runnable afterMutation) {
        transaction.executeWithoutResult(status -> {
            int pid = jdbc.queryForObject("select pg_backend_pid()",Integer.class);
            beforeGuard.accept(pid);
            guard.forGlobalSecurityChange();
            tenant();
            int changed = jdbc.update("update public.account set credential_version=credential_version+1 where id=?",reporter.accountId());
            if (changed != 1) throw new IllegalStateException("Expected one owned reporter account");
            afterMutation.run();
        });
    }

    Integer waitingAdvisoryPid(int blockerPid) {
        List<Integer> waiting = jdbc.queryForList("""
                select distinct pid from pg_locks where locktype='advisory' and not granted
                 and ?=any(pg_blocking_pids(pid))
                """,Integer.class,blockerPid);
        if (waiting.size() > 1) throw new IllegalStateException("Ambiguous advisory waiter in exclusive business target");
        return waiting.isEmpty() ? null : waiting.getFirst();
    }

    <T> T read(Function<JdbcTemplate,T> work) {
        TransactionTemplate read = new TransactionTemplate(transaction.getTransactionManager());
        read.setReadOnly(true);
        return read.execute(status -> { tenant(); return work.apply(jdbc); });
    }
    long count(String table) {
        if (!Set.of("finding","finding_transition","finding_confirmation","finding_command_receipt","workflow_binding","audit_event","remediation_notice","remediation_round").contains(table)) {
            throw new IllegalArgumentException("Unsupported acceptance table");
        }
        return read(db -> db.queryForObject("select count(*) from public." + table + " where tenant_id = ?",Long.class,tenantId));
    }
    long publications(UUID command) {
        return read(db -> db.queryForObject("select count(*) from public.event_publication where serialized_event like ?",Long.class,"%" + command + "%"));
    }
    private void tenant() { jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenantId.toString()); }
    private static String required(String key) {
        String value = System.getenv(key); if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key); return value;
    }
    @Override public void close() {
        try {
            // Normal owned session lifecycle only; immutable business, engine and ancestor rows stay for reinspection.
            for (Actor actor : List.of(reporter,person,safety,remediator,alternatePerson)) {
                jdbc.update("delete from public.spring_session where principal_name = ?",actor.accountId().toString());
            }
        } finally { source.close(); }
    }
    @Override public String toString() { return run; }
}
