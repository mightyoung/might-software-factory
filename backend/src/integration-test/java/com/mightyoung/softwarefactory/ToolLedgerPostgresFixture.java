package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.zaxxer.hikari.*;
import java.util.*;
import java.util.function.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns real identity/qualification fixtures; immutable ledger/audit rows and FK ancestors are retained. */
final class ToolLedgerPostgresFixture implements AutoCloseable {
    record Actor(UUID account,UUID principal,UUID member,UUID role,UUID scope,UUID grant,String login,String password) { }
    final UUID tenant=UUID.randomUUID(), otherTenant=UUID.randomUUID();
    final UUID org=UUID.randomUUID(), outsideOrg=UUID.randomUUID(), foreignOrg=UUID.randomUUID();
    final UUID custody=UUID.randomUUID(), outsideCustody=UUID.randomUUID(), foreignCustody=UUID.randomUUID(), unassignedCustody=UUID.randomUUID();
    final String tenantCode="d1it-"+UUID.randomUUID(), foreignCode="d1foreign-"+UUID.randomUUID();
    final Map<String,Actor> actors=new LinkedHashMap<>();
    final HikariDataSource pool;
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    final PostgresAuthorizationWriteGuard guard;
    ToolLedgerPostgresFixture(PasswordEncoder passwords) {
        var target=IdentityAcceptanceTarget.preflightFromEnvironment(System.getenv());
        if(IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(System.getenv(),target.databaseName())!=IdentityLoginPostgresIT.RuntimePrivilegeProfile.D1_APPLICATION)throw new IllegalStateException("Explicit D1 profile required");
        HikariConfig config=new HikariConfig();config.setJdbcUrl(target.jdbcUrl());config.setUsername(required("LAN_PG_USERNAME"));config.setPassword(required("LAN_PG_PASSWORD"));config.setMaximumPoolSize(3);config.setMinimumIdle(0);
        pool=new HikariDataSource(config);jdbc=new JdbcTemplate(pool);tx=new TransactionTemplate(new DataSourceTransactionManager(pool));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);guard=new PostgresAuthorizationWriteGuard(pool);
        for(String name:List.of("keeper","reader","nonkeeper","createonly","updateonly","outside","foreign"))actors.put(name,new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"d1-"+name+"-"+UUID.randomUUID(),UUID.randomUUID().toString()));
        try {security(db->{
            seedTenant(db,tenant,tenantCode);seedTenant(db,otherTenant,foreignCode);
            organization(db,tenant,org,"A");organization(db,tenant,outsideOrg,"B");organization(db,otherTenant,foreignOrg,"F");
            custody(db,tenant,custody,org,"A");custody(db,tenant,outsideCustody,outsideOrg,"B");custody(db,otherTenant,foreignCustody,foreignOrg,"F");custody(db,tenant,unassignedCustody,org,"SAME-ORG-NO-KEEPER");
            for(var e:actors.entrySet())seedActor(db,e.getKey(),e.getValue(),passwords);
        });}catch(RuntimeException|Error failure){pool.close();throw failure;}
    }
    void security(Consumer<JdbcTemplate> work){tx.executeWithoutResult(s->{guard.forGlobalSecurityChange();context(jdbc,tenant);work.accept(jdbc);});}
    <T>T read(Function<JdbcTemplate,T> work){return tx.execute(s->{context(jdbc,tenant);return work.apply(jdbc);});}
    static void context(JdbcTemplate db,UUID tenant){db.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    private void seedTenant(JdbcTemplate db,UUID t,String code){context(db,t);db.update("insert into public.tenant(id,code,name,status,auth_epoch) values (?,?,?,'ACTIVE',1)",t,code,"Owned D1 tooling fixture");db.update("insert into public.tenant_application(tenant_id,application_key,enabled,version) values (?,'tooling',true,1)",t);}
    private void organization(JdbcTemplate db,UUID t,UUID id,String code){context(db,t);db.update("insert into public.org_unit(tenant_id,id,code,name,active) values (?,?,?,?,true)",t,id,"D1-"+code,"D1 "+code);}
    private void custody(JdbcTemplate db,UUID t,UUID id,UUID organization,String code){context(db,t);db.update("insert into public.tool_custody_scope(tenant_id,id,organization_id,code,name,active) values (?,?,?,?,?,true)",t,id,organization,"D1-"+code,"D1 custody "+code);}
    private void seedActor(JdbcTemplate db,String name,Actor a,PasswordEncoder passwords){
        UUID t=name.equals("foreign")?otherTenant:tenant;UUID o=name.equals("foreign")?foreignOrg:name.equals("outside")?outsideOrg:org;UUID c=name.equals("foreign")?foreignCustody:name.equals("outside")?outsideCustody:custody;context(db,t);
        db.update("insert into public.account(id,login_key,status,credential_version) values (?,?,'ACTIVE',1)",a.account(),a.login());
        db.update("insert into public.principal(id,kind,account_id,status) values (?,'HUMAN',?,'ACTIVE')",a.principal(),a.account());
        db.update("insert into public.credential(account_id,kind,secret_hash,changed_at) values (?,'PASSWORD',?,current_timestamp)",a.account(),passwords.encode(a.password()));
        db.update("insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values (?,?,?,'ACTIVE',1,?)",t,a.member(),a.account(),"D1 "+name);
        db.update("insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values (?,?,?,'PRIMARY',current_timestamp-interval '1 day')",t,a.member(),o);
        db.update("insert into public.\"role\"(tenant_id,id,code,name,active) values (?,?,?,?,true)",t,a.role(),"D1-"+name,"D1 "+name);
        db.update("insert into public.scope_rule(tenant_id,id,kind,version_no,definition) values (?,?,'EXPLICIT',1,'{}'::jsonb)",t,a.scope());
        db.update("insert into public.scope_org(tenant_id,scope_rule_id,org_id) values (?,?,?)",t,a.scope(),o);
        for(String permission:permissions(name)){int added=db.update("insert into public.role_permission(tenant_id,role_id,permission_id,scope_rule_id) select ?,?,id,? from public.permission where code=?",t,a.role(),a.scope(),permission);if(added!=1)throw new IllegalStateException("Missing tool permission");}
        db.update("insert into public.role_grant(tenant_id,id,role_id,subject_kind,member_id,source_type,source_id,granted_by_member_id,valid_from) values (?,?,?,'MEMBER',?,'D1_IT',?,?,current_timestamp-interval '1 day')",t,a.grant(),a.role(),a.member(),UUID.randomUUID(),a.member());
        if(!Set.of("reader","nonkeeper").contains(name))db.update("insert into public.tool_custodian(tenant_id,custody_scope_id,member_id,valid_from) values (?,?,?,current_timestamp-interval '1 day')",t,c,a.member());
    }
    static List<String> permissions(String name){return switch(name){case "reader"->List.of("tool.read");case "createonly"->List.of("tool.create");case "updateonly"->List.of("tool.update");default->List.of("tool.read","tool.create","tool.update");};}
    Map<String,Long> counts(){return read(db->{Map<String,Long> counts=new LinkedHashMap<>();for(String table:List.of("tool","ledger_record_revision","ledger_command_receipt"))counts.put(table,db.queryForObject("select count(*) from public."+table+" where tenant_id=?",Long.class,tenant));counts.put("audit",db.queryForObject("select count(*) from public.audit_event where tenant_id=? and policy_version='ledger-v1'",Long.class,tenant));return counts;});}
    void expireKeeper(String actor,boolean expire){security(db->db.update("update public.tool_custodian set valid_to="+(expire?"current_timestamp":"null")+" where tenant_id=? and custody_scope_id=? and member_id=?",tenant,custody,actors.get(actor).member()));}
    void enableScope(boolean active){security(db->db.update("update public.tool_custody_scope set active=? where tenant_id=? and id=?",active,tenant,custody));}
    void enableMember(String actor,boolean active){security(db->db.update("update public.tenant_member set status=? where tenant_id=? and id=?",active?"ACTIVE":"DISABLED",tenant,actors.get(actor).member()));}
    void enableGrant(String actor,boolean active){security(db->db.update("update public.role_grant set valid_to="+(active?"null":"current_timestamp")+" where tenant_id=? and id=?",tenant,actors.get(actor).grant()));}
    @Override public void close(){try{for(var a:actors.values())jdbc.update("delete from public.spring_session where principal_name=?",a.account().toString());}finally{pool.close();}}
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing explicit fixture input "+key);return value;}
}
