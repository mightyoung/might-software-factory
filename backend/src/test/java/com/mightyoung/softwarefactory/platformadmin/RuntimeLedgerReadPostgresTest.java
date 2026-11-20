package com.mightyoung.softwarefactory.platformadmin;

import java.time.Instant;
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
            List.of("amount"),List.of("amount"));

    @Test void scopedPagesCountsAndProjectionUseTheSameSqlAndHistoricalDetailKeepsItsDefinition() {
        tx.execute(status -> {
            configurations.save(tenant,member,key,new SaveRequest(UUID.randomUUID(),0L,initial));
            configurations.publish(tenant,member,key,new PublishRequest(1L,1L,null));
            var one = commands.create(tenant,member,key,new CreateRequest(UUID.randomUUID(),1L,organization,Map.of("amount",2,"secret","hidden")));
            commands.create(tenant,member,key,new CreateRequest(UUID.randomUUID(),1L,organization,Map.of("amount",3)));
            commands.create(tenant,member,key,new CreateRequest(UUID.randomUUID(),1L,other,Map.of("amount",1)));
            var published = reads.published(tenant,key);
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
            assertThat(reads.detail(tenant,key,one.id(),Set.of(organization),new Capabilities(true,false,false,false)).definition()).isEqualTo(initial);
            assertThat(reads.query(tenant,reads.published(tenant,key),Set.of(organization),
                    new Query(1,20,List.of(new Filter("amount","eq","2")),new Sort("amount","asc"))).total()).isZero();
            status.setRollbackOnly();
            return null;
        });
    }
}
