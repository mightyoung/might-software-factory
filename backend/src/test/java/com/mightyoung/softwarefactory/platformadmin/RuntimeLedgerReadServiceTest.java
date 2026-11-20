package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerReadModels.*;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerCommandModels.RecordView;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeLedgerReadServiceTest {
    final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),0,0,1);
    final SessionContextResolver resolver = mock(SessionContextResolver.class);
    final EffectivePermissions permissions = mock(EffectivePermissions.class);
    final TenantApplicationReadRepository applications = mock(TenantApplicationReadRepository.class);
    final RuntimeLedgerReadRepository repository = mock(RuntimeLedgerReadRepository.class);
    boolean guarded;
    final IdentitySecurityTransaction transaction = new IdentitySecurityTransaction() {
        public <T> T execute(CurrentTenantContext c, Supplier<T> work) { guarded = true; return work.get(); }
        public void requireActive(CurrentTenantContext c) {}
    };
    final RuntimeLedgerReadService service = new RuntimeLedgerReadService(resolver,transaction,permissions,applications,repository);
    final Published published = new Published("purchase",1,LedgerConfigurationServiceTest.complete());
    RuntimeLedgerReadServiceTest() {
        when(resolver.currentTenant()).thenReturn(context);
        when(applications.findCurrentInCaller(context.tenantId(),"platform")).thenAnswer(call -> {
            assertThat(guarded).isTrue();
            return Optional.of(new TenantApplicationState(true,1));
        });
        when(repository.published(context.tenantId(),"purchase")).thenReturn(published);
    }
    @Test void configurationManagementDoesNotGrantRuntimeReadAndDisabledApplicationDenies() {
        when(permissions.has(context,"ledger.configuration.manage")).thenReturn(true);
        assertThatThrownBy(() -> service.query("purchase",new Query(1,20,List.of(),null))).isInstanceOf(AccessDeniedException.class);
        verify(repository,never()).query(any(),any(),any(),any());
        when(applications.findCurrentInCaller(context.tenantId(),"platform")).thenReturn(Optional.of(new TenantApplicationState(false,1)));
        assertThatThrownBy(service::catalog).isInstanceOf(AccessDeniedException.class);
    }
    @Test void detailReadScopeDoesNotGrantOtherOrganizationsUpdateOrDelete() {
        UUID organization = UUID.randomUUID();
        when(permissions.has(context,"ledger.record.purchase.read")).thenReturn(true);
        when(permissions.has(context,"ledger.record.purchase.update")).thenReturn(true);
        when(permissions.readableOrganizations(context,"ledger.record.purchase.read")).thenReturn(Set.of(organization));
        when(permissions.readableOrganizations(context,"ledger.record.purchase.update")).thenReturn(Set.of(UUID.randomUUID()));
        UUID id = UUID.randomUUID();
        RecordView record = new RecordView(id,"purchase",1,organization,Map.of("quantity",1),1,Instant.now(),Instant.now());
        when(repository.detail(eq(context.tenantId()),eq("purchase"),eq(id),eq(Set.of(organization)),any()))
                .thenReturn(new Detail(record,published.definition(),new Capabilities(true,false,true,true)));
        Detail detail = service.detail("purchase",id);
        assertThat(detail.capabilities()).isEqualTo(new Capabilities(true,false,false,false));
    }
    @Test void filtersRejectNonQueryFieldsWrongTypesAndOperators() {
        var definition = new LedgerConfigurationModels.Definition("采购",
                List.of(new LedgerConfigurationModels.Field("amount","金额","number",false),
                        new LedgerConfigurationModels.Field("secret","秘密","text",false)),
                List.of("amount"),List.of("amount"));
        assertThatThrownBy(() -> JooqRuntimeLedgerReadRepository.filter(definition,new Filter("secret","eq","x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JooqRuntimeLedgerReadRepository.filter(definition,new Filter("amount","contains",1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JooqRuntimeLedgerReadRepository.filter(definition,new Filter("amount","eq","1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(JooqRuntimeLedgerReadRepository.filter(definition,new Filter("amount","gte",1)).toString())
                .contains("jsonb_typeof").contains("numeric");
    }
}
