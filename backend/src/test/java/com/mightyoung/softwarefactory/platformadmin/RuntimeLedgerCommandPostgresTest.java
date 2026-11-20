package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
import com.mightyoung.softwarefactory.authorization.ManagedIdentitySecurityTransaction;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationState;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.*;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerCommandModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="AUTHZ_CONFIG_PG_URL",matches=".+")
class RuntimeLedgerCommandPostgresTest {
    final UUID tenant=UUID.fromString(System.getenv("AUTHZ_CONFIG_TENANT"));
    final UUID member=UUID.fromString(System.getenv("AUTHZ_CONFIG_MEMBER"));
    final UUID organization=UUID.fromString(System.getenv("AUTHZ_CONFIG_ORG"));
    final DriverManagerDataSource source=new DriverManagerDataSource(System.getenv("AUTHZ_CONFIG_PG_URL"),System.getenv("AUTHZ_CONFIG_PG_USER"),System.getenv("AUTHZ_CONFIG_PG_PASSWORD"));
    final DataSourceTransactionManager manager=new DataSourceTransactionManager(source);
    final TransactionTemplate tx=new TransactionTemplate(manager);
    final org.jooq.DSLContext dsl=DSL.using(new TransactionAwareDataSourceProxy(source),SQLDialect.POSTGRES);
    final JsonMapper json=JsonMapper.builder().build();
    final JooqLedgerConfigurationRepository configurations=new JooqLedgerConfigurationRepository(dsl,json,manager);
    final JooqRuntimeLedgerCommandRepository records=new JooqRuntimeLedgerCommandRepository(dsl,json);
    final String key="records_"+UUID.randomUUID().toString().replace("-","");

    @Test void publishesActionsAndStoresPinnedDraftsWithDurableReceiptsAndTenantIsolation() {
        var definition=LedgerConfigurationServiceTest.complete();
        tx.execute(status->configurations.save(tenant,member,key,new SaveRequest(UUID.randomUUID(),0L,definition)));
        tx.execute(status->configurations.publish(tenant,member,key,new PublishRequest(1L,1L,null)));
        assertThat(dsl.fetchCount(DSL.table("public.permission"),DSL.field("code",String.class).like("ledger.record."+key+".%"))).isEqualTo(4);
        var request=new CreateRequest(UUID.randomUUID(),1L,organization,Map.of("quantity",3));
        RecordView created=tx.execute(status->records.create(tenant,member,key,request));
        assertThat(created.definitionVersion()).isEqualTo(1);
        assertThat(tx.<RecordView>execute(status->records.replay(tenant,member,key,request.requestId(),"create",request))).isEqualTo(created);
        assertThatThrownBy(()->tx.execute(status->records.replay(tenant,member,key,request.requestId(),"create",new CreateRequest(request.requestId(),1L,organization,Map.of("quantity",4))))).isInstanceOf(LedgerConfigurationException.class);
        var changed=new Definition("新版",definition.fields(),definition.listFields(),definition.queryFields());
        tx.execute(status->configurations.save(tenant,member,key,new SaveRequest(UUID.randomUUID(),1L,changed)));
        tx.execute(status->configurations.publish(tenant,member,key,new PublishRequest(2L,2L,1L)));
        var update=new UpdateRequest(UUID.randomUUID(),1L,Map.of("quantity",7));
        RecordView edited=tx.execute(status->records.update(tenant,member,key,created.id(),update));
        assertThat(edited.definitionVersion()).isEqualTo(1);
        assertThat(edited.version()).isEqualTo(2);
        assertThatThrownBy(()->tx.execute(status->records.update(tenant,member,key,created.id(),new UpdateRequest(UUID.randomUUID(),1L,Map.of("quantity",9))))).isInstanceOf(LedgerConfigurationException.class);
        var deletion=new DeleteRequest(UUID.randomUUID(),2L);
        RecordView deleted=tx.execute(status->records.delete(tenant,member,key,created.id(),deletion));
        assertThat(deleted.version()).isEqualTo(3);
        assertThatThrownBy(()->tx.execute(status->records.lock(tenant,key,created.id()))).isInstanceOf(LedgerConfigurationException.class);
        assertThat(tx.<RecordView>execute(status->records.replay(tenant,member,key,deletion.requestId(),"delete:"+created.id(),deletion))).isEqualTo(deleted);
        assertThatThrownBy(()->tx.execute(status->records.lock(UUID.randomUUID(),key,created.id()))).isInstanceOf(LedgerConfigurationException.class);
        assertThatThrownBy(()->tx.execute(status->{
            dsl.fetchValue("select set_config('app.tenant_id',?,true)",UUID.randomUUID().toString());
            return dsl.execute("insert into public.ordinary_ledger_record(tenant_id,configuration_key,id,definition_version,organization_id,values_json,version,created_by_member_id,updated_by_member_id) values(?,?,?,?,?,'{}',1,?,?)",tenant,key,UUID.randomUUID(),1,organization,member,member);
        })).isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThatThrownBy(()->tx.execute(status->{
            dsl.fetchValue("select set_config('app.tenant_id',?,true)",tenant.toString());
            return dsl.execute("update public.ordinary_ledger_command set response_payload='{}' where tenant_id=? and configuration_key=?",tenant,key);
        })).isInstanceOf(org.jooq.exception.DataAccessException.class);
    }

    @Test void recentlyChangedOrganizationScopeBlocksAnOldReceiptAndAnyNewWrite() {
        UUID otherOrganization = UUID.fromString(System.getenv("AUTHZ_CONFIG_OTHER_ORG"));
        assertThat(otherOrganization).isNotEqualTo(organization);
        var definition = LedgerConfigurationServiceTest.complete();
        tx.execute(status -> configurations.save(tenant, member, key, new SaveRequest(UUID.randomUUID(), 0L, definition)));
        tx.execute(status -> configurations.publish(tenant, member, key, new PublishRequest(1L, 1L, null)));
        var seeded = tx.execute(status -> records.create(tenant, member, key,
                new CreateRequest(UUID.randomUUID(), 1L, organization, Map.of("quantity", 3))));

        // This fixture supplies identities and current scopes, not a production policy engine or lock proof.
        var context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(), tenant, member, 0, 0, 1);
        var resolver = mock(SessionContextResolver.class);
        when(resolver.currentTenant()).thenReturn(context);
        var applications = mock(TenantApplicationReadRepository.class);
        when(applications.findCurrentInCaller(tenant, "platform")).thenReturn(Optional.of(new TenantApplicationState(true, 1)));
        var permissions = mock(EffectivePermissions.class);
        var scope = new AtomicReference<>(Set.of(organization));
        when(permissions.readableOrganizations(context, RuntimeLedgerReadService.code(key, "update")))
                .thenAnswer(invocation -> scope.get());
        var guard = mock(AuthorizationWriteGuard.class);
        var service = new RuntimeLedgerCommandService(resolver,
                new ManagedIdentitySecurityTransaction(source, manager, guard), permissions, applications, records);

        var original = new UpdateRequest(UUID.randomUUID(), 1L, Map.of("quantity", 7));
        RecordView updated = service.update(key, seeded.id(), original);
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.values()).containsEntry("quantity", 7);
        assertStoredUpdateUnchanged(updated, original.requestId());

        scope.set(Set.of(otherOrganization));
        assertThatThrownBy(() -> service.update(key, seeded.id(), original)).isInstanceOf(AccessDeniedException.class);
        assertStoredUpdateUnchanged(updated, original.requestId());

        var newWrite = new UpdateRequest(UUID.randomUUID(), updated.version(), Map.of("quantity", 9));
        assertThatThrownBy(() -> service.update(key, seeded.id(), newWrite)).isInstanceOf(AccessDeniedException.class);
        assertStoredUpdateUnchanged(updated, original.requestId());

        scope.set(Set.of(organization));
        assertThat(service.update(key, seeded.id(), original)).isEqualTo(updated);
        assertStoredUpdateUnchanged(updated, original.requestId());
    }

    private void assertStoredUpdateUnchanged(RecordView expected, UUID receiptId) {
        tx.executeWithoutResult(status -> {
            assertThat(records.lock(tenant, key, expected.id())).isEqualTo(expected);
            var updateReceipts = dsl.fetch("select request_id from public.ordinary_ledger_command "
                    + "where tenant_id=? and configuration_key=? and record_id=? and operation=?",
                    tenant, key, expected.id(), "update:" + expected.id()).getValues(0, UUID.class);
            assertThat(updateReceipts).containsExactly(receiptId);
            assertThat(dsl.fetchCount(DSL.table("public.ordinary_ledger_command"),
                    DSL.field("tenant_id", UUID.class).eq(tenant)
                            .and(DSL.field("configuration_key", String.class).eq(key)))).isEqualTo(2);
        });
    }

}
