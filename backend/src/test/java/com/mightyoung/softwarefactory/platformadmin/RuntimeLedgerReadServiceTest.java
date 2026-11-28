package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
import com.mightyoung.softwarefactory.authorization.OrdinaryLedgerQueryAuthorization;
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
    final OrdinaryLedgerQueryAuthorization queryAuthorization = mock(OrdinaryLedgerQueryAuthorization.class);
    final TenantApplicationReadRepository applications = mock(TenantApplicationReadRepository.class);
    final RuntimeLedgerReadRepository repository = mock(RuntimeLedgerReadRepository.class);
    boolean guarded;
    final IdentitySecurityTransaction transaction = new IdentitySecurityTransaction() {
        public <T> T execute(CurrentTenantContext c, Supplier<T> work) { guarded = true; return work.get(); }
        public void requireActive(CurrentTenantContext c) {}
    };
    final RuntimeLedgerReadService service = new RuntimeLedgerReadService(resolver,transaction,permissions,queryAuthorization,applications,repository);
    final Published published = new Published("purchase",1,LedgerConfigurationWorkflowTest.withWorkflow(LedgerConfigurationWorkflowTest.linear()));
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
    @Test void ordinaryQueryRoutePassesAnAuthorizedEmptyScopeThroughWithoutChangingOtherGrants() {
        var request = new Query(2, 3, List.of(), null);
        var empty = new Page(List.of(), 2, 3, 0, false);
        when(queryAuthorization.queryOrganizations(context, "purchase")).thenReturn(Optional.of(Set.of()));
        when(repository.query(context.tenantId(), published, Set.of(), request)).thenReturn(empty);

        var response = new RuntimeLedgerReadController(service).query("purchase", request);
        assertThat(response).isEqualTo(empty);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(3);
        assertThat(response.total()).isZero();
        assertThat(response.hasMore()).isFalse();
        verify(queryAuthorization).queryOrganizations(context, "purchase");
        verify(repository).query(context.tenantId(), published, Set.of(), request);
        verifyNoInteractions(permissions);

        when(repository.published(context.tenantId())).thenReturn(List.of(published));
        assertThat(service.catalog().items()).isEmpty();
        assertThatThrownBy(() -> service.definition("purchase")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.detail("purchase", UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        verifyNoMoreInteractions(queryAuthorization);
    }

    @Test void ordinaryQueryUsesTheSingleDecisionWithoutFallbackOnDeniedUnavailableOrDisabled() {
        var request = new Query(1, 20, List.of(), null);
        var existingOrg = UUID.randomUUID();
        when(queryAuthorization.queryOrganizations(context, "purchase"))
                .thenReturn(Optional.empty()).thenThrow(new IllegalStateException("authority unavailable"))
                .thenReturn(Optional.of(Set.of(existingOrg)));
        assertThatThrownBy(() -> service.query("purchase", request)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.query("purchase", request)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).query(any(), any(), any(), any());
        verifyNoInteractions(permissions);

        var page = new Page(List.of(), 1, 20, 0, false);
        when(repository.query(context.tenantId(), published, Set.of(existingOrg), request)).thenReturn(page);
        assertThat(service.query("purchase", request)).isEqualTo(page);
        verify(queryAuthorization, times(3)).queryOrganizations(context, "purchase");
        when(applications.findCurrentInCaller(context.tenantId(), "platform"))
                .thenReturn(Optional.of(new TenantApplicationState(false, 1)));
        assertThatThrownBy(() -> service.query("purchase", request)).isInstanceOf(AccessDeniedException.class);
        verifyNoMoreInteractions(queryAuthorization);
    }

    @Test void catalogAndItemHaveOnlyTheEstablishedOrdinaryDefinitionFields() {
        when(permissions.has(context,"ledger.record.purchase.read")).thenReturn(true);
        when(repository.published(context.tenantId())).thenReturn(List.of(published));
        assertOrdinaryDefinition(service.catalog().items().getFirst().definition());
        assertOrdinaryDefinition(service.definition("purchase").definition());
    }
    private static void assertOrdinaryDefinition(Object definition) {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(mapper.<tools.jackson.databind.JsonNode>valueToTree(definition)).isEqualTo(mapper.valueToTree(LedgerConfigurationServiceTest.complete()));
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
                .thenReturn(new Detail(record,PublicDefinition.from(published.definition()),new Capabilities(true,false,true,true)));
        Detail detail = service.detail("purchase",id);
        assertOrdinaryDefinition(detail.definition());
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
