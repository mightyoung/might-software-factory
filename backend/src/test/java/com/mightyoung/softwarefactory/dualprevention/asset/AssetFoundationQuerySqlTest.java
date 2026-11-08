package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationQueryContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetFoundationQuerySqlTest {
    @Test void integerColumnComparisonsKeepFractionalAndTinyValuesExact(){
        var field=DSL.field("version",Long.class);
        assertThat(DSL.using(SQLDialect.POSTGRES).renderInlined(AssetFoundationQueryRepository.integerCondition(field,FilterOperator.EQ,new BigDecimal("1.5"),null))).isEqualTo("false");
        assertThat(DSL.using(SQLDialect.POSTGRES).renderInlined(AssetFoundationQueryRepository.integerCondition(field,FilterOperator.GT,new BigDecimal("1.5"),null))).contains(">= 2");
        assertThat(DSL.using(SQLDialect.POSTGRES).renderInlined(AssetFoundationQueryRepository.integerCondition(field,FilterOperator.LT,new BigDecimal("-0.1"),null))).contains("<= -1");
        assertThat(DSL.using(SQLDialect.POSTGRES).renderInlined(AssetFoundationQueryRepository.integerCondition(field,FilterOperator.GTE,new BigDecimal("1e-999999"),null))).contains(">= 1");
        assertThat(DSL.using(SQLDialect.POSTGRES).renderInlined(AssetFoundationQueryRepository.integerCondition(field,FilterOperator.BETWEEN,new BigDecimal("1.1"),new BigDecimal("2.9")))).contains(">= 2","<= 2");
    }
    @Test void fixedScopeAndEscapedContainsPrecedePagination(){
        var context=mock(CurrentTenantContext.class);when(context.tenantId()).thenReturn(new UUID(1,1));
        var scope=mock(ActionScope.class);when(scope.context()).thenReturn(context);when(scope.organizationIds()).thenReturn(Set.of(new UUID(2,2)));
        var plan=new Plan(List.of(new Filter("name",FilterOperator.CONTAINS,List.of("%_\\"))),List.of(new Sort("id",Direction.ASC)),List.of("name"),20,1,null);
        var query=DSL.using(SQLDialect.POSTGRES).select(DSL.field("id")).from(AssetFoundationQueryRepository.table(WORKSHOP))
                .where(AssetFoundationQueryRepository.where(WORKSHOP,plan,scope)).orderBy(AssetFoundationQueryRepository.order(WORKSHOP,plan.sort())).limit(21);
        assertThat(query.getSQL()).contains("tenant_id","organization_id","any(cast(? as uuid[]))","ilike","escape", "nulls last");
        assertThat(query.getSQL().indexOf("organization_id")).isLessThan(query.getSQL().indexOf("fetch next"));
        assertThat(query.getBindValues()).contains("%\\%\\_\\\\%");
    }
    @Test void nullLastSeekUsesNullSafePrefixAndNeverAdvancesInsideNullValue(){
        var row=DSL.using(SQLDialect.POSTGRES).newRecord(DSL.field("name",String.class),DSL.field("id",UUID.class));
        row.set(DSL.field("name",String.class),null);row.set(DSL.field("id",UUID.class),UUID.fromString("80000000-0000-0000-0000-000000000001"));
        String sql=DSL.using(SQLDialect.POSTGRES).renderInlined(AssetFoundationQueryRepository.seek(WORKSHOP,List.of(new Sort("name",Direction.DESC),new Sort("id",Direction.ASC)),row));
        assertThat(sql).contains("is not distinct from null",">", "80000000-0000-0000-0000-000000000001");
        assertThat(sql).doesNotContain("< null","> null");
    }
    @Test void sparseWorkUnitProjectionOmitsIrrelevantSourceButKeepsSelectedNullDescription(){
        var row=DSL.using(SQLDialect.POSTGRES).newRecord(DSL.field("kind",String.class),DSL.field("lineId",UUID.class),DSL.field("workshopId",UUID.class),DSL.field("boundaryDescription",String.class));
        UUID line=UUID.randomUUID();row.set(DSL.field("kind",String.class),"LINE");row.set(DSL.field("lineId",UUID.class),line);
        var wire=AssetFoundationQueryRepository.wire(WORK_UNIT,List.of("lineId","workshopId","boundaryDescription"),row);
        assertThat(wire).containsEntry("lineId",line).containsEntry("boundaryDescription",null).doesNotContainKey("workshopId");
    }
}
