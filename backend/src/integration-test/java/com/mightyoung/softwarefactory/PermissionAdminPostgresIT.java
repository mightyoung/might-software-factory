package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.platformadmin.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real D0 schema/FORCE RLS with the non-elevated migration role; every synthetic write rolls back. */
class PermissionAdminPostgresIT {
    private static final String MANAGE="platform.permission.manage";
    private static final List<String> HIDDEN=List.of("audit_event.read","identity.invitation.create","identity.invitation.revoke");

    @Test void catalogReplacementPreservesUncataloguedCapabilityRowsAndScopes() throws Exception {
        run(f->{
            f.addHidden();f.verifyRls();
            var before=f.role(f.target);var scopes=f.capabilityScopes();
            var desired=new HashSet<>(before.permissionCodes());desired.remove("finding.create");desired.add("finding.read");
            var after=f.replace(desired,before.version());
            assertThat(after.permissionCodes()).containsExactlyInAnyOrderElementsOf(desired);
            assertThat(f.capabilityScopes()).isEqualTo(scopes);
            assertThat(f.db.fetchOne("select added_permission_codes,removed_permission_codes from public.permission_change_event where tenant_id=?",f.tenant).get(0,String[].class)).containsExactly("finding.read");
            assertThat(f.db.fetchValue("select count(*) from public.permission_change_event where tenant_id=?",f.tenant)).isEqualTo(1L);
            assertThat(f.replace(desired,after.version())).isEqualTo(after);
            assertThat(f.db.fetchValue("select count(*) from public.permission_change_event where tenant_id=?",f.tenant)).isEqualTo(1L);
        });
    }
    @ParameterizedTest @ValueSource(strings={"audit_event.read","identity.invitation.create","identity.invitation.revoke"})
    void cannotAddOrRemoveUncataloguedCapabilities(String code) throws Exception {
        run(f->{f.addHidden();f.verifyRls();var before=f.role(f.target);var desired=new HashSet<>(before.permissionCodes());desired.remove(code);
            denied(()->f.replace(desired,before.version()),PermissionAdminException.Reason.UNKNOWN_PERMISSION);
            assertThat(f.role(f.target)).isEqualTo(before);
            assertThat(f.db.fetchValue("select count(*) from public.permission_change_event where tenant_id=?",f.tenant)).isEqualTo(0L);
            denied(()->f.repository.replaceRolePermissions(f.tenant,f.other,Set.of(MANAGE,code),f.role(f.other).version(),f.member,UUID.randomUUID()),PermissionAdminException.Reason.UNKNOWN_PERMISSION);
        });
    }
    @ParameterizedTest @ValueSource(strings={"MEMBER","ORG","POSITION","GROUP"})
    void everyEffectiveSubjectKindCanRetainManagement(String subject) throws Exception {
        run(f->{f.subject(subject,true);f.verifyRls();assertThat(f.effectiveManager()).isTrue();var before=f.role(f.target);
            var desired=new HashSet<>(before.permissionCodes());desired.remove(MANAGE);
            assertThat(f.replace(desired,before.version()).permissionCodes()).doesNotContain(MANAGE);
        });
    }
    @ParameterizedTest @ValueSource(strings={"disabled_member","disabled_account","locked_account","disabled_principal","inactive_role",
            "future_grant","expired_grant","empty_org","empty_group","empty_position","inactive_org","inactive_group","inactive_position",
            "expired_org_membership","expired_group_membership","expired_position_membership","future_group_membership",
            "empty_explicit_scope","inactive_explicit_scope","null_scope","empty_department_scope","empty_department_tree_scope","self_scope","relation_scope"})
    void inertGrantsCannotUnlockTheLastManager(String condition) throws Exception {
        run(f->{f.inert(condition);f.verifyRls();assertThat(f.effectiveManager()).isFalse();var before=f.role(f.target);var desired=new HashSet<>(before.permissionCodes());desired.remove(MANAGE);
            denied(()->f.replace(desired,before.version()),PermissionAdminException.Reason.LAST_MANAGER);
            assertThat(f.role(f.target)).isEqualTo(before);
            assertThat(f.db.fetchValue("select count(*) from public.permission_change_event where tenant_id=?",f.tenant)).isEqualTo(0L);
        });
    }
    @ParameterizedTest @ValueSource(strings={"DEPT","DEPT_TREE"})
    void currentActiveDepartmentGivesResolvableNonemptyScope(String scope) throws Exception {
        run(f->{f.db.execute("update public.scope_rule set kind=? where tenant_id=? and id=?",scope,f.tenant,f.otherScope);
            f.verifyRls();assertThat(f.effectiveManager()).isTrue();var before=f.role(f.target);var desired=new HashSet<>(before.permissionCodes());desired.remove(MANAGE);
            assertThat(f.replace(desired,before.version()).permissionCodes()).doesNotContain(MANAGE);
        });
    }
    private static void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,PermissionAdminException.Reason reason) {
        assertThatThrownBy(work).isInstanceOfSatisfying(PermissionAdminException.class,error->assertThat(error.reason()).isEqualTo(reason));
    }
    private static void run(Consumer<Fixture> test) throws Exception {
        var environment=System.getenv();var target=IdentityAcceptanceTarget.preflightFromEnvironment(environment);
        assertThat(IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment,target.databaseName())).isIn(IdentityLoginPostgresIT.RuntimePrivilegeProfile.D0_APPLICATION, IdentityLoginPostgresIT.RuntimePrivilegeProfile.D1_APPLICATION);
        try(var connection=DriverManager.getConnection(target.jdbcUrl(),required("LAN_PG_USERNAME"),required("LAN_PG_PASSWORD"))) {
            var source=new SingleConnectionDataSource(connection,true);var manager=new DataSourceTransactionManager(source);
            new TransactionTemplate(manager).executeWithoutResult(status->{
                try {new PostgresAuthorizationWriteGuard(source).forGlobalSecurityChange();test.accept(new Fixture(connection,manager));}
                finally {status.setRollbackOnly();}
            });
        }
    }
    private static String required(String key){return Objects.requireNonNull(System.getenv(key),key);}
    private static final class Fixture {
        final UUID tenant=UUID.randomUUID(),member=UUID.randomUUID(),account=UUID.randomUUID(),actor=UUID.randomUUID(),
                target=UUID.randomUUID(),other=UUID.randomUUID(),scope=UUID.randomUUID(),otherScope=UUID.randomUUID(),org=UUID.randomUUID(),
                group=UUID.randomUUID(),position=UUID.randomUUID(),grant=UUID.randomUUID();
        final DSLContext db;final JooqPermissionAdminRepository repository;final DataSourceTransactionManager manager;
        Fixture(java.sql.Connection connection,DataSourceTransactionManager manager) {
            this.manager=manager;
            db=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false));
            repository=new JooqPermissionAdminRepository(db,manager);
            db.fetchValue("select set_config('app.tenant_id',?,true)",tenant.toString());
            db.execute("insert into public.tenant(id,code,name,status,auth_epoch) values(?,?,'Permission test','ACTIVE',1)",tenant,"pa-"+tenant);
            db.execute("insert into public.account(id,login_key,status,credential_version) values(?,?,'ACTIVE',1)",account,"pa-"+account);
            db.execute("insert into public.principal(id,account_id,kind,status) values(?,?,'HUMAN','ACTIVE')",actor,account);
            db.execute("insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values(?,?,?,'ACTIVE',1,'Permission test')",tenant,member,account);
            db.execute("insert into public.org_unit(tenant_id,id,code,name,active) values(?,?,'ORG','Org',true)",tenant,org);
            db.execute("insert into public.user_group(tenant_id,id,code,name,active) values(?,?,'GROUP','Group',true)",tenant,group);
            db.execute("insert into public.position(tenant_id,id,code,name,active) values(?,?,'POSITION','Position',true)",tenant,position);
            db.execute("insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values(?,?,?,'PRIMARY',current_timestamp-interval '1 day')",tenant,member,org);
            for(UUID role:List.of(target,other))db.execute("insert into public.role(tenant_id,id,code,name,active) values(?,?,?,'Permission role',true)",tenant,role,role.toString());
            for(UUID rule:List.of(scope,otherScope)) {
                db.execute("insert into public.scope_rule(tenant_id,id,kind,version_no,definition) values(?,?,'EXPLICIT',1,'{}'::jsonb)",tenant,rule);
                db.execute("insert into public.scope_org(tenant_id,scope_rule_id,org_id) values(?,?,?)",tenant,rule,org);
            }
            for(String code:List.of("finding.create","finding.read"))db.execute("insert into public.permission(id,code,resource_type,action,application_key,label) values(?,?,'finding',?,'safety',?) on conflict(code) do nothing",UUID.randomUUID(),code,code.substring(code.indexOf('.')+1),code);
            for(UUID role:List.of(target,other))permission(role,MANAGE,role.equals(target)?scope:otherScope);
            permission(target,"finding.create",scope);
            db.execute("insert into public.role_grant(tenant_id,id,role_id,subject_kind,member_id,source_type,source_id,granted_by_member_id,valid_from) values(?,?,?,'MEMBER',?,'PR55_PERMISSION',?,?,current_timestamp-interval '1 day')",tenant,grant,other,member,UUID.randomUUID(),member);
        }
        void verifyRls() {
            assertThat(db.fetchOne("select current_user").get(0,String.class)).isEqualTo(required("LAN_PG_USERNAME"));
            assertThat(db.fetchOne("select rolsuper or rolbypassrls from pg_roles where rolname=current_user").get(0,Boolean.class)).isFalse();
            for(String table:List.of("tenant_member","role","role_permission","scope_rule","role_grant","org_unit"))
                assertThat(db.fetchOne("select row_security_active(cast(? as regclass))","public."+table).get(0,Boolean.class)).isTrue();
        }
        boolean effectiveManager() {
            var repository=new com.mightyoung.softwarefactory.authorization.PersistentFindingReadAuthorizationRepository(db,
                    new com.mightyoung.softwarefactory.identity.JooqAccountStatusReadRepository(db),
                    new com.mightyoung.softwarefactory.identity.JooqPrincipalStatusReadRepository(db),
                    new com.mightyoung.softwarefactory.tenancy.JooqTenantMembershipReadRepository(db),
                    new com.mightyoung.softwarefactory.directory.JooqMemberDirectoryReadRepository(db),
                    new com.mightyoung.softwarefactory.authorization.JooqRoleScopeReadRepository(db),manager);
            return new com.mightyoung.softwarefactory.authorization.PersistentEffectivePermissions(repository).has(
                    new com.mightyoung.softwarefactory.identity.CurrentTenantContext(actor,account,tenant,member,1,1,1),MANAGE);
        }
        void permission(UUID role,String code,UUID rule) {assertThat(db.execute("insert into public.role_permission(tenant_id,role_id,permission_id,scope_rule_id) select ?,?,id,? from public.permission where code=?",tenant,role,rule,code)).isEqualTo(1);}
        PermissionAdminModels.RoleView role(UUID id) {return repository.roles(tenant).stream().filter(role->role.id().equals(id.toString())).findFirst().orElseThrow();}
        PermissionAdminModels.RoleView replace(Set<String> desired,String version) {return repository.replaceRolePermissions(tenant,target,desired,version,member,UUID.randomUUID());}
        void addHidden() {
            for(String kind:List.of("AUDIT_TENANT","IDENTITY_TENANT")) {
                UUID rule=UUID.randomUUID();db.execute("insert into public.scope_rule(tenant_id,id,kind,version_no,definition) values(?,?,?,1,'{}'::jsonb)",tenant,rule,kind);
                for(String code:HIDDEN)if(kind.equals("AUDIT_TENANT")==code.equals("audit_event.read")) {
                    if(code.startsWith("identity."))db.execute("insert into public.permission(id,code,resource_type,action) values(?,?,'account-invitation',?) on conflict(code) do nothing",UUID.randomUUID(),code,code.substring(code.lastIndexOf('.')+1));
                    permission(target,code,rule);
                }
            }
        }
        Map<String,UUID> capabilityScopes() {
            return db.fetch("select p.code,rp.scope_rule_id from public.role_permission rp join public.permission p on p.id=rp.permission_id where rp.tenant_id=? and rp.role_id=? and p.application_key is null",tenant,target).intoMap(row->row.get(0,String.class),row->row.get(1,UUID.class));
        }
        void subject(String kind,boolean membership) {
            db.execute("update public.role_grant set subject_kind=?,member_id=?,org_id=?,position_id=?,group_id=? where tenant_id=? and id=?",kind,
                    kind.equals("MEMBER")?member:null,kind.equals("ORG")?org:null,kind.equals("POSITION")?position:null,kind.equals("GROUP")?group:null,tenant,grant);
            if(membership && kind.equals("GROUP"))db.execute("insert into public.group_member(tenant_id,member_id,group_id,valid_from) values(?,?,?,current_timestamp-interval '1 day')",tenant,member,group);
            if(membership && kind.equals("POSITION"))db.execute("insert into public.member_position(tenant_id,member_id,position_id,valid_from) values(?,?,?,current_timestamp-interval '1 day')",tenant,member,position);
        }
        void inert(String condition) {
            switch(condition) {
                case "disabled_member" -> db.execute("update public.tenant_member set status='DISABLED' where tenant_id=? and id=?",tenant,member);
                case "disabled_account","locked_account" -> db.execute("update public.account set status=? where id=?",condition.equals("locked_account")?"LOCKED":"DISABLED",account);
                case "disabled_principal" -> db.execute("update public.principal set status='DISABLED' where id=?",actor);
                case "inactive_role" -> db.execute("update public.role set active=false where tenant_id=? and id=?",tenant,other);
                case "future_grant" -> db.execute("update public.role_grant set valid_from=current_timestamp+interval '1 day' where tenant_id=? and id=?",tenant,grant);
                case "expired_grant" -> db.execute("update public.role_grant set valid_to=current_timestamp-interval '1 hour' where tenant_id=? and id=?",tenant,grant);
                case "empty_org" -> {subject("ORG",false);db.execute("delete from public.member_org where tenant_id=?",tenant);}
                case "empty_group" -> subject("GROUP",false);
                case "empty_position" -> subject("POSITION",false);
                case "inactive_org" -> {subject("ORG",true);db.execute("update public.org_unit set active=false where tenant_id=? and id=?",tenant,org);}
                case "inactive_group" -> {subject("GROUP",true);db.execute("update public.user_group set active=false where tenant_id=? and id=?",tenant,group);}
                case "inactive_position" -> {subject("POSITION",true);db.execute("update public.position set active=false where tenant_id=? and id=?",tenant,position);}
                case "expired_org_membership" -> {subject("ORG",true);db.execute("update public.member_org set valid_to=current_timestamp-interval '1 hour' where tenant_id=?",tenant);}
                case "expired_group_membership","future_group_membership" -> {subject("GROUP",true);db.execute("update public.group_member set "+(condition.startsWith("future")?"valid_from=current_timestamp+interval '1 day'":"valid_to=current_timestamp-interval '1 hour'")+" where tenant_id=?",tenant);}
                case "expired_position_membership" -> {subject("POSITION",true);db.execute("update public.member_position set valid_to=current_timestamp-interval '1 hour' where tenant_id=?",tenant);}
                case "empty_explicit_scope" -> db.execute("delete from public.scope_org where tenant_id=? and scope_rule_id=?",tenant,otherScope);
                case "inactive_explicit_scope" -> db.execute("update public.org_unit set active=false where tenant_id=? and id=?",tenant,org);
                case "null_scope" -> db.execute("update public.role_permission set scope_rule_id=null where tenant_id=? and role_id=?",tenant,other);
                case "empty_department_scope","empty_department_tree_scope","self_scope","relation_scope" -> {
                    db.execute("update public.scope_rule set kind=? where tenant_id=? and id=?",switch(condition){case "empty_department_scope"->"DEPT";case "empty_department_tree_scope"->"DEPT_TREE";case "self_scope"->"SELF";default->"RELATION";},tenant,otherScope);
                    if(condition.startsWith("empty_"))db.execute("delete from public.member_org where tenant_id=?",tenant);
                }
                default -> throw new AssertionError(condition);
            }
        }
    }
}
