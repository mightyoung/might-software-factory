package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerCommandModels.*;

class RuntimeLedgerCommandServiceTest {
    final UUID organization=UUID.randomUUID();
    final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),0,0,1);
    final SessionContextResolver resolver=mock(SessionContextResolver.class);
    final EffectivePermissions permissions=mock(EffectivePermissions.class);
    final TenantApplicationReadRepository applications=mock(TenantApplicationReadRepository.class);
    final RuntimeLedgerCommandRepository repository=mock(RuntimeLedgerCommandRepository.class);
    boolean guarded;
    final IdentitySecurityTransaction transaction=new IdentitySecurityTransaction() {
        public <T> T execute(CurrentTenantContext context,Supplier<T> work) { guarded=true; return work.get(); }
        public void requireActive(CurrentTenantContext context) { assertThat(guarded).isTrue(); }
    };
    final RuntimeLedgerCommandService service=new RuntimeLedgerCommandService(resolver,transaction,permissions,applications,repository);
    RuntimeLedgerCommandServiceTest() {
        when(resolver.currentTenant()).thenReturn(context);
        when(applications.findCurrentInCaller(context.tenantId(),"platform")).thenAnswer(call -> {
            assertThat(guarded).isTrue(); return Optional.of(new TenantApplicationState(true,1));
        });
    }
    @Test void malformedValuesCannotPassPublishedTypesOrInjectProtectedFields() {
        var definition=new LedgerConfigurationModels.Definition("检验",List.of(
                new LedgerConfigurationModels.Field("count","数量","number",true),
                new LedgerConfigurationModels.Field("date","日期","date",false),
                new LedgerConfigurationModels.Field("passed","通过","boolean",false)),List.of("count"),List.of("count"));
        assertThatThrownBy(()->RuntimeLedgerCommandService.validate(definition,Map.of())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(()->RuntimeLedgerCommandService.validate(definition,Map.of("count","3"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(()->RuntimeLedgerCommandService.validate(definition,Map.of("count",Double.NaN))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(()->RuntimeLedgerCommandService.validate(definition,Map.of("count",3,"date","2026-02-30"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(()->RuntimeLedgerCommandService.validate(definition,Map.of("count",3,"passed","true"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(()->RuntimeLedgerCommandService.validate(definition,Map.of("count",3,"tenant_id","injected"))).isInstanceOf(ValidationException.class);
        RuntimeLedgerCommandService.validate(definition,Map.of("count",3,"date","2026-11-20","passed",true));
    }
    @Test void currentPermissionIsRequiredBeforeReplayingOldRequests() {
        var request=new CreateRequest(UUID.randomUUID(),1L,organization,Map.of());
        assertThatThrownBy(()->service.create("purchase",request)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }
    @Test void createRequiresRequestedOrganizationAndCurrentPublishedDefinition() {
        when(permissions.readableOrganizations(context,"ledger.record.purchase.create")).thenReturn(Set.of(organization));
        var request=new CreateRequest(UUID.randomUUID(),1L,organization,Map.of());
        when(repository.published(context.tenantId(),"purchase")).thenReturn(new LedgerConfigurationModels.Snapshot("purchase",2,2L,LedgerConfigurationServiceTest.complete(),Instant.now(),null,context.memberId()));
        assertThatThrownBy(()->service.create("purchase",request)).isInstanceOf(LedgerConfigurationException.class);
        assertThatThrownBy(()->service.create("purchase",new CreateRequest(UUID.randomUUID(),2L,UUID.randomUUID(),Map.of()))).isInstanceOf(AccessDeniedException.class);
        verify(repository,never()).create(any(),any(),any(),any());
    }
    @Test void editUsesPinnedDefinitionAndChecksRecordOrganization() {
        when(permissions.readableOrganizations(context,"ledger.record.purchase.update")).thenReturn(Set.of(organization));
        UUID id=UUID.randomUUID();
        var record=new RecordView(id,"purchase",1,organization,Map.of("quantity",3),1,Instant.now(),Instant.now());
        when(repository.lock(context.tenantId(),"purchase",id)).thenReturn(record);
        when(repository.definition(context.tenantId(),"purchase",1)).thenReturn(LedgerConfigurationServiceTest.complete());
        var request=new UpdateRequest(UUID.randomUUID(),1L,Map.of("quantity",4));
        service.update("purchase",id,request);
        verify(repository).definition(context.tenantId(),"purchase",1);
        verify(repository).update(context.tenantId(),context.memberId(),"purchase",id,request);
        verify(repository,never()).published(any(),any());
    }
}
