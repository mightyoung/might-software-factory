package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
import com.mightyoung.softwarefactory.authorization.ManagedIdentitySecurityTransaction;
import com.mightyoung.softwarefactory.authorization.OrdinaryLedgerQueryAuthorization;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationState;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.*;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerCommandModels.*;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerReadModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="AUTHZ_CONFIG_PG_URL", matches=".+")
class RuntimeLedgerReadPostgresTest {
    final UUID tenant = UUID.fromString(System.getenv("AUTHZ_CONFIG_TENANT"));
    final UUID member = UUID.fromString(System.getenv("AUTHZ_CONFIG_MEMBER"));
    final UUID organization = UUID.fromString(System.getenv("AUTHZ_CONFIG_ORG"));
    final UUID other = UUID.fromString(System.getenv("AUTHZ_CONFIG_OTHER_ORG"));
    final DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("AUTHZ_CONFIG_PG_URL"),System.getenv("AUTHZ_CONFIG_PG_USER"),System.getenv("AUTHZ_CONFIG_PG_PASSWORD"));
    final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
    final TransactionTemplate tx = new TransactionTemplate(manager);
    final DSLContext db = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    final JsonMapper json = JsonMapper.builder().build();
    final JooqLedgerConfigurationRepository configurations = new JooqLedgerConfigurationRepository(db,json,manager);
    final JooqRuntimeLedgerCommandRepository commands = new JooqRuntimeLedgerCommandRepository(db,json);
    final JooqRuntimeLedgerReadRepository reads = new JooqRuntimeLedgerReadRepository(db,json);
    final String key = "read_" + UUID.randomUUID().toString().replace("-","");
    final Definition initial = new Definition("采购",
            List.of(new Field("amount","金额","number",true),new Field("secret","备注","text",false)),
            List.of("amount"),List.of("amount"),LedgerConfigurationWorkflowTest.linear());

    @Test void scopedPagesCountsAndProjectionUseTheSameSqlAndHistoricalDetailKeepsItsDefinition() {
        tx.execute(status -> {
            configurations.save(tenant,member,key,new SaveRequest(UUID.randomUUID(),0L,initial));
            configurations.publish(tenant,member,key,new PublishRequest(1L,1L,null));
            var one = commands.create(tenant,member,key,new CreateRequest(UUID.randomUUID(),1L,organization,Map.of("amount",2,"secret","hidden")));
            commands.create(tenant,member,key,new CreateRequest(UUID.randomUUID(),1L,organization,Map.of("amount",3)));
            commands.create(tenant,member,key,new CreateRequest(UUID.randomUUID(),1L,other,Map.of("amount",1)));
            var published = reads.published(tenant,key);
            assertThat(published.definition().workflow()).isEqualTo(initial.workflow());
            Query request = new Query(1,1,List.of(new Filter("amount","gte",2)),new Sort("amount","asc"));
            var first = reads.query(tenant,published,Set.of(organization),request);
            assertThat(first.total()).isEqualTo(2);
            assertThat(first.hasMore()).isTrue();
            assertThat(first.records()).extracting(RecordView::id).containsExactly(one.id());
            assertThat(first.records().getFirst().values()).containsOnlyKeys("amount");
            var second = reads.query(tenant,published,Set.of(organization),new Query(2,1,request.filters(),request.sort()));
            assertThat(second.total()).isEqualTo(2);
            assertThat(second.hasMore()).isFalse();
            assertThat(second.records().getFirst().id()).isNotEqualTo(one.id());
            assertThat(reads.query(tenant,published,Set.of(),request).total()).isZero();
            assertThatThrownBy(() -> reads.detail(tenant,key,one.id(),Set.of(other),new Capabilities(true,false,false,false)))
                    .isInstanceOf(LedgerConfigurationException.class);
            Definition newType = new Definition("采购新版",List.of(new Field("amount","数量说明","text",true)),List.of("amount"),List.of("amount"));
            configurations.save(tenant,member,key,new SaveRequest(UUID.randomUUID(),1L,newType));
            configurations.publish(tenant,member,key,new PublishRequest(2L,2L,1L));
            var detail = reads.detail(tenant,key,one.id(),Set.of(organization),new Capabilities(true,false,false,false));
            assertThat(detail.definition()).isEqualTo(PublicDefinition.from(initial));
            assertThat(json.<tools.jackson.databind.JsonNode>valueToTree(detail.definition()).has("workflow")).isFalse();
            assertThat(reads.query(tenant,reads.published(tenant,key),Set.of(organization),
                    new Query(1,20,List.of(new Filter("amount","eq","2")),new Sort("amount","asc"))).total()).isZero();
            status.setRollbackOnly();
            return null;
        });
    }

    @Test void authorizedEmptyQueryValidatesNormallyAndCannotRevealAnyExistingRecord() {
        tx.executeWithoutResult(status -> {
            configurations.save(tenant, member, key, new SaveRequest(UUID.randomUUID(), 0L, initial));
            configurations.publish(tenant, member, key, new PublishRequest(1L, 1L, null));
            commands.create(tenant, member, key, new CreateRequest(UUID.randomUUID(), 1L, organization,
                    Map.of("amount", 2, "secret", "first org")));
            commands.create(tenant, member, key, new CreateRequest(UUID.randomUUID(), 1L, other,
                    Map.of("amount", 3, "secret", "second org")));
        });

        // Real ordinary query/data transactions; this fixture supplies the authorization decision, not persisted RBAC.
        var context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(), tenant, member, 0, 0, 1);
        var resolver = mock(SessionContextResolver.class);
        when(resolver.currentTenant()).thenReturn(context);
        var applications = mock(TenantApplicationReadRepository.class);
        when(applications.findCurrentInCaller(tenant, "platform")).thenReturn(Optional.of(new TenantApplicationState(true, 1)));
        var scopes = mock(OrdinaryLedgerQueryAuthorization.class);
        when(scopes.queryOrganizations(context, key)).thenReturn(Optional.of(Set.of()));
        var service = new RuntimeLedgerReadService(resolver,
                new ManagedIdentitySecurityTransaction(source, manager, mock(AuthorizationWriteGuard.class)),
                mock(EffectivePermissions.class), scopes, applications, reads);
        var endpoint = new RuntimeLedgerReadController(service);

        var filter = List.of(new Filter("amount", "gte", 1));
        var sort = new Sort("amount", "asc");
        var page = endpoint.query(key, new Query(2, 1, filter, sort));
        assertThat(json.<tools.jackson.databind.JsonNode>valueToTree(page)).isEqualTo(
                json.readTree("""
                        {"records":[],"total":0,"page":2,"size":1,"hasMore":false}
                        """));
        assertThatThrownBy(() -> endpoint.query(key, new Query(1, 1,
                List.of(new Filter("secret", "eq", "ignored")), sort))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> endpoint.query(key, new Query(1, 0, filter, sort))).isInstanceOf(ValidationException.class);

        when(scopes.queryOrganizations(context, key)).thenReturn(Optional.of(Set.of(organization)));
        var existing = endpoint.query(key, new Query(1, 1, filter, sort));
        assertThat(existing.total()).isEqualTo(1);
        assertThat(existing.records()).singleElement().satisfies(row -> {
            assertThat(row.organizationId()).isEqualTo(organization);
            assertThat(row.values()).containsExactlyEntriesOf(Map.of("amount", 2));
        });
    }

}
