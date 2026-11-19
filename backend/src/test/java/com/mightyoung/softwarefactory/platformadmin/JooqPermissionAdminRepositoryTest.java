package com.mightyoung.softwarefactory.platformadmin;

import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JooqPermissionAdminRepositoryTest {
    private static final List<String> RESERVED=List.of("audit_event.read","identity.invitation.create","identity.invitation.revoke");
    @Test void catalogEditsPreserveEveryUncataloguedCodeAndItsExistingScope() {
        var fixture=new Fixture();Set<String> desired=new HashSet<>(fixture.held);desired.add("finding.read");desired.remove("finding.create");
        var result=fixture.replace(desired);
        assertThat(result.permissionCodes()).containsExactlyInAnyOrderElementsOf(desired);
        assertThat(fixture.writes).hasSize(3);
        assertThat(fixture.writes.get(0).bindings()).contains(fixture.ids.get("finding.create"));
        assertThat(fixture.writes.get(1).bindings()).contains(fixture.ids.get("finding.read"),fixture.scope);
        assertThat(fixture.writes.get(2).bindings()).contains("{\"finding.read\"}","{\"finding.create\"}");
        for(String code:RESERVED) assertThat(fixture.writes.subList(0,2)).allSatisfy(write->assertThat(write.bindings()).doesNotContain(fixture.ids.get(code)));
    }
    @Test void unchangedFullSetWithUncataloguedPermissionsIsValidAndDoesNotEmitChangeEvent() {
        var fixture=new Fixture();var result=fixture.replace(Set.copyOf(fixture.held));
        assertThat(result.permissionCodes()).containsAll(RESERVED);assertThat(fixture.writes).isEmpty();
    }
    @Test void uncataloguedPermissionsCannotBeRemovedOrAddedByTheCatalogEditor() {
        for(String reserved:RESERVED) {
            var removed=new Fixture();var desired=new HashSet<>(removed.held);desired.remove(reserved);
            assertReason(()->removed.replace(desired),PermissionAdminException.Reason.UNKNOWN_PERMISSION);
            assertThat(removed.writes).isEmpty();
            var added=new Fixture();added.held.remove(reserved);
            var requested=new HashSet<>(added.held);requested.add(reserved);
            assertReason(()->added.replace(requested),PermissionAdminException.Reason.UNKNOWN_PERMISSION);
            assertThat(added.writes).isEmpty();
        }
    }
    @Test void catalogAdditionsCannotInheritAnUncataloguedCapabilityScope() {
        var fixture=new Fixture();fixture.held.remove("finding.create");
        var desired=new HashSet<>(fixture.held);desired.add("finding.read");
        assertReason(()->fixture.replace(desired),PermissionAdminException.Reason.ROLE_HAS_NO_SCOPE);
        assertThat(fixture.writes).isEmpty();
    }
    @Test void concurrencyVersionStillCoversUncataloguedPermissions() {
        var fixture=new Fixture();String stale=JooqPermissionAdminRepository.version(Set.of("finding.create"));
        assertReason(()->fixture.repository.replaceRolePermissions(fixture.tenant,fixture.role,Set.copyOf(fixture.held),stale,UUID.randomUUID(),UUID.randomUUID()),PermissionAdminException.Reason.VERSION_CONFLICT);
        assertThat(fixture.writes).isEmpty();
    }
    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,PermissionAdminException.Reason reason) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PermissionAdminException.class,failure->assertThat(failure.reason()).isEqualTo(reason));
    }
    record Write(String sql,List<Object> bindings) { }
    private static final class Fixture implements MockDataProvider {
        final UUID tenant=UUID.randomUUID(),role=UUID.randomUUID(),scope=UUID.randomUUID();
        final Map<String,UUID> ids=new LinkedHashMap<>();final Set<String> held=new TreeSet<>();final List<Write> writes=new ArrayList<>();
        final DSLContext records=DSL.using(SQLDialect.POSTGRES);final JooqPermissionAdminRepository repository;
        Fixture() {
            for(String code:List.of("finding.create","finding.read","audit_event.read","identity.invitation.create","identity.invitation.revoke"))ids.put(code,UUID.randomUUID());
            held.add("finding.create");held.addAll(RESERVED);
            var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            repository=new JooqPermissionAdminRepository(DSL.using(new MockConnection(this),SQLDialect.POSTGRES),manager);
        }
        PermissionAdminModels.RoleView replace(Set<String> desired) {return repository.replaceRolePermissions(tenant,role,desired,JooqPermissionAdminRepository.version(held),UUID.randomUUID(),UUID.randomUUID());}
        @Override public MockResult[] execute(MockExecuteContext ctx) {
            String sql=ctx.sql();Object[] bindings=ctx.bindings();
            if(sql.startsWith("select set_config"))return result(new String[]{"set_config"},new Class<?>[]{String.class},List.<Object[]>of(new Object[]{tenant.toString()}));
            if(sql.startsWith("select 1 from public.\"role\""))return result(new String[]{"exists"},new Class<?>[]{Integer.class},List.<Object[]>of(new Object[]{1}));
            if(sql.startsWith("select p.code, rp.scope_rule_id"))return result(new String[]{"code","scope_rule_id"},new Class<?>[]{String.class,UUID.class},held.stream().map(code->new Object[]{code,code.startsWith("finding.")?scope:ids.get(code)}).toList());
            if(sql.equals("select code, id, application_key from public.permission"))return result(new String[]{"code","id","application_key"},new Class<?>[]{String.class,UUID.class,String.class},ids.entrySet().stream().map(e->new Object[]{e.getKey(),e.getValue(),e.getKey().startsWith("finding.")?"safety":null}).toList());
            if(sql.startsWith("select rp.role_id, p.code"))return result(new String[]{"role_id","code"},new Class<?>[]{UUID.class,String.class},held.stream().map(code->new Object[]{role,code}).toList());
            if(sql.startsWith("select role_id, count(*)"))return result(new String[]{"role_id","count"},new Class<?>[]{UUID.class,Long.class},List.of());
            if(sql.startsWith("select id, code, name, active"))return result(new String[]{"id","code","name","active"},new Class<?>[]{UUID.class,String.class,String.class,Boolean.class},List.<Object[]>of(new Object[]{role,"role","Role",true}));
            if(sql.startsWith("delete from public.role_permission"))held.remove(code(bindings[2]));
            else if(sql.startsWith("insert into public.role_permission"))held.add(code(bindings[2]));
            else if(!sql.startsWith("insert into public.permission_change_event"))throw new AssertionError("Unexpected repository SQL: "+sql);
            writes.add(new Write(sql,List.of(bindings)));return new MockResult[]{new MockResult(1)};
        }
        String code(Object id){return ids.entrySet().stream().filter(e->e.getValue().equals(id)).findFirst().orElseThrow().getKey();}
        MockResult[] result(String[] names,Class<?>[] types,List<Object[]> rows) {
            Field<?>[] fields=new Field<?>[names.length];for(int i=0;i<names.length;i++)fields[i]=DSL.field(names[i],types[i]);
            Result<org.jooq.Record> result=records.newResult(fields);
            for(Object[] values:rows){var row=records.newRecord(fields);row.fromArray(values);result.add(row);}
            return new MockResult[]{new MockResult(rows.size(),result)};
        }
    }
}
