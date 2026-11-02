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

/** Additional G3 gate precedes every fixture write; immutable history and all ancestors stay in the exclusive DB. */
final class PasswordChangePostgresFixture implements AutoCloseable {
    record CredentialSnapshot(long version,String encodedPassword) {
        @Override public String toString() { return "CredentialSnapshot[REDACTED]"; }
    }
    private final HikariDataSource owner;
    private final HikariDataSource runtime;
    private final JdbcTemplate jdbc;
    private final JdbcTemplate app;
    private final TransactionTemplate transaction;
    final BusinessWorkflowPostgresFixture business;
    final UUID tenantB = UUID.randomUUID(), memberB = UUID.randomUUID(), organizationB = UUID.randomUUID();
    final String tenantBCode = "pw-b-" + UUID.randomUUID();

    static LanPostgresCompatibilityIT.DatabaseTarget validateTarget() {
        var target = LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(System.getenv());
        if (!("I_CONFIRM_PASSWORD_CHANGE_FIXTURE_WRITES:" + target.databaseName())
                .equals(System.getenv("LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK"))) {
            throw new IllegalStateException("Password acceptance requires database-specific fixture-write confirmation");
        }
        return BusinessWorkflowPostgresFixture.validateTarget();
    }
    PasswordChangePostgresFixture(PasswordEncoder passwords) {
        var target = validateTarget();
        runtime = pool(target.jdbcUrl(),"LAN_PG_APP_USERNAME","LAN_PG_APP_PASSWORD","password-grant-gate");
        app = new JdbcTemplate(runtime);
        HikariDataSource createdOwner;
        try { createdOwner = pool(target.jdbcUrl(),"LAN_PG_USERNAME","LAN_PG_PASSWORD","password-fixture-owner"); }
        catch(RuntimeException | Error failure) { runtime.close(); throw failure; }
        owner = createdOwner;
        jdbc = new JdbcTemplate(owner); transaction = new TransactionTemplate(new DataSourceTransactionManager(owner));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try { schemaAndGrantGate(); migrationGate(); }
        catch(RuntimeException | Error failure) { owner.close(); runtime.close(); throw failure; }
        BusinessWorkflowPostgresFixture seeded = null;
        try {
            seeded = new BusinessWorkflowPostgresFixture(passwords);
            business = seeded;
            var guard = new PostgresAuthorizationWriteGuard(owner);
            transaction.executeWithoutResult(status -> {
                guard.forGlobalSecurityChange(); tenant(tenantB);
                jdbc.update("insert into public.tenant(id,code,name,status,auth_epoch) values (?,?,?,'ACTIVE',1)",tenantB,tenantBCode,"Password fixture second tenant");
                jdbc.update("insert into public.tenant_application(tenant_id,application_key,enabled,version) values (?,'safety',true,1)",tenantB);
                jdbc.update("insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values (?,?,?,'ACTIVE',1,'Password fixture second membership')",tenantB,memberB,business.reporter.accountId());
                jdbc.update("insert into public.org_unit(tenant_id,id,code,name,active) values (?,?,?,'Password fixture second organization',true)",tenantB,organizationB,"pw-org-" + UUID.randomUUID());
                jdbc.update("insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values (?,?,?,'PRIMARY',current_timestamp - interval '1 day')",tenantB,memberB,organizationB);
            });
        } catch(RuntimeException | Error failure) {
            if(seeded != null) seeded.close(); owner.close(); runtime.close(); throw failure;
        }
    }
    private static HikariDataSource pool(String url,String user,String password,String name) {
        HikariConfig c = new HikariConfig(); c.setJdbcUrl(url); c.setUsername(required(user)); c.setPassword(required(password));
        c.setMaximumPoolSize(2); c.setMinimumIdle(0); c.setConnectionTimeout(5000); c.setPoolName(name + "-" + UUID.randomUUID());
        return new HikariDataSource(c);
    }
    private static String required(String key) { String v = System.getenv(key); if(v == null || v.isBlank()) throw new IllegalStateException("Missing required acceptance input"); return v; }
    private static void demand(boolean condition,String criterion) { if(!condition) throw new IllegalStateException("Password acceptance gate: " + criterion); }
    private boolean yes(String sql,Object... args) { return Boolean.TRUE.equals(app.queryForObject(sql,Boolean.class,args)); }
    private void schemaAndGrantGate() {
        demand(yes("select current_user=session_user and current_user=?",required("LAN_PG_APP_USERNAME")),"runtime identity");
        demand(!yes("select rolsuper or rolbypassrls or rolinherit or rolcreatedb or rolcreaterole from pg_roles where rolname=current_user"),"nonprivileged role");
        demand(yes("select count(*)=0 from pg_auth_members where member=(select oid from pg_roles where rolname=current_user)"),"no membership");
        demand(!yes("select has_schema_privilege(current_user,'public','CREATE') or has_database_privilege(current_user,current_database(),'CREATE,TEMP')"),"no DDL/temp");
        demand(yes("select relrowsecurity and relforcerowsecurity and pg_get_userbyid(relowner)<>current_user from pg_class where oid='public.security_event'::regclass"),"V12 forced RLS and nonowner");
        demand(yes("select exists(select 1 from pg_trigger where tgrelid='public.security_event'::regclass and not tgisinternal and tgenabled='O')"),"append trigger enabled");
        demand(yes("select count(*)=10 and bool_and(attname in ('tenant_id','id','account_id','actor_id','type','result','reason_code','trace_id','policy_version','occurred_at')) from pg_attribute where attrelid='public.security_event'::regclass and attnum>0 and not attisdropped"),"V12 event shape");
        demand(yes("select has_table_privilege(current_user,'public.security_event','INSERT')"),"event insert");
        demand(!yes("select has_table_privilege(current_user,'public.security_event','SELECT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') or has_any_column_privilege(current_user,'public.security_event','SELECT,UPDATE,REFERENCES')"),"event insert only");
        demand(!yes("select has_table_privilege(current_user,'public.account','UPDATE') or has_table_privilege(current_user,'public.credential','UPDATE,INSERT,DELETE')"),"no broad credential DML");
        exactUpdateColumns("public.account",Set.of("credential_version"));
        exactUpdateColumns("public.credential",Set.of("secret_hash","changed_at"));
        demand(yes("select has_table_privilege(current_user,'public.audit_event','INSERT')"),"audit insertion");
    }
    private void exactUpdateColumns(String table,Set<String> allowed) {
        List<String> columns = app.queryForList("""
                select attname from pg_attribute
                where attrelid=cast(? as regclass) and attnum>0 and not attisdropped order by attnum
                """,String.class,table);
        demand(columns.containsAll(allowed),"required password update columns exist");
        for(String column:columns) {
            boolean granted = yes("select has_column_privilege(current_user,cast(? as regclass),?,'UPDATE')",table,column);
            demand(granted == allowed.contains(column),"exact password column update privileges");
        }
    }
    private void migrationGate() {
        // The runtime intentionally has no history SELECT grant. This separate migrator connection only reads.
        TransactionTemplate read = new TransactionTemplate(transaction.getTransactionManager());
        read.setReadOnly(true);
        read.executeWithoutResult(status -> demand(Boolean.TRUE.equals(jdbc.queryForObject("""
                select count(*)=1 from public.flyway_schema_history_app
                where version='12' and success
                """,Boolean.class)),"successful V12 migration before fixture writes"));
    }
    <T> T read(UUID tenant,Function<JdbcTemplate,T> work) {
        TransactionTemplate read = new TransactionTemplate(transaction.getTransactionManager()); read.setReadOnly(true);
        return read.execute(status -> { tenant(tenant); return work.apply(jdbc); });
    }
    CredentialSnapshot credentials() {
        return read(business.tenantId,db -> db.queryForObject("select a.credential_version,c.secret_hash from public.account a join public.credential c on c.account_id=a.id and c.kind='PASSWORD' where a.id=?",
                (row,index) -> new CredentialSnapshot(row.getLong(1),row.getString(2)),business.reporter.accountId()));
    }
    long audits() { return read(business.tenantId,db -> db.queryForObject("select count(*) from public.audit_event where tenant_id=? and resource_type='account' and resource_id=? and action='account.password.change'",Long.class,business.tenantId,business.reporter.accountId())); }
    long events(String result,String reason) {
        return read(business.tenantId,db -> db.queryForObject("select count(*) from public.security_event where tenant_id=? and account_id=? and type='PASSWORD_CHANGE' and result=? and reason_code is not distinct from cast(? as varchar)",Long.class,business.tenantId,business.reporter.accountId(),result,reason));
    }
    long totalEvents() {
        return read(business.tenantId,db -> db.queryForObject("select count(*) from public.security_event where tenant_id=? and account_id=? and type='PASSWORD_CHANGE'",Long.class,business.tenantId,business.reporter.accountId()));
    }
    Integer waitingPid(int blocker) {
        List<Integer> ids = jdbc.queryForList("select distinct pid from pg_locks where locktype='advisory' and not granted and ?=any(pg_blocking_pids(pid))",Integer.class,blocker);
        if(ids.size()>1) throw new IllegalStateException("Ambiguous password writer wait"); return ids.isEmpty()?null:ids.getFirst();
    }
    void runtimeTransaction(java.util.function.Consumer<JdbcTemplate> work) {
        new TransactionTemplate(new DataSourceTransactionManager(runtime)).executeWithoutResult(status -> {
            app.queryForObject("select set_config('app.tenant_id',?,true)",String.class,business.tenantId.toString());
            try { work.accept(app); } finally { status.setRollbackOnly(); }
        });
    }
    private void tenant(UUID id) { jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,id.toString()); }
    @Override public void close() { try { business.close(); } finally { owner.close(); runtime.close(); } }
    @Override public String toString() { return "PasswordChangePostgresFixture[REDACTED]"; }
}
