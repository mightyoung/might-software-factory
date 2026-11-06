package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationHistoryContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetFoundationHistoryRepositoryTest {
    private static final OffsetDateTime TIME=OffsetDateTime.parse("2026-11-04T00:00:00.123456Z");
    private static final Field<?>[] FIELDS={DSL.field("id",UUID.class),DSL.field("version",Long.class),DSL.field("command_id",UUID.class),DSL.field("change_kind",String.class),DSL.field("occurred_at",OffsetDateTime.class),
            DSL.field("valid_from",OffsetDateTime.class),DSL.field("valid_to",OffsetDateTime.class),DSL.field("end_observed_at",OffsetDateTime.class),DSL.field("parent_id",UUID.class),DSL.field("counterpart_id",UUID.class),
            DSL.field("parent_version",Long.class),DSL.field("counterpart_version",Long.class),DSL.field("parent_code",String.class),DSL.field("parent_name",String.class),DSL.field("counterpart_code",String.class),DSL.field("counterpart_name",String.class)};
    @Test void parentScopeCheckedBeforePagingAndLabelsStayAtBindNotCurrent()throws Exception {
        UUID parent=UUID.randomUUID(),counterpart=UUID.randomUUID(),firstCommand=UUID.randomUUID();var authorized=new AtomicBoolean();
        var owner=mock(AssetFoundationRepository.class);var scope=scope();
        when(owner.productionLine(scope,parent)).thenAnswer(call->{authorized.set(true);return mock(AssetContracts.ProductionLine.class);});
        var connection=new MockConnection(context->{
            assertThat(authorized).isTrue();assertThat(context.sql()).contains("snapshot_line_version_at_bind","snapshot_workshop_version_at_bind");
            var sql=DSL.using(SQLDialect.POSTGRES);var result=sql.newResult(FIELDS);
            for(int i=0;i<2;i++){
                Record row=sql.newRecord(FIELDS);set(row,"id",UUID.randomUUID());set(row,"version",1L);set(row,"command_id",i==0?firstCommand:UUID.randomUUID());set(row,"change_kind","BIND");
                set(row,"occurred_at",TIME);set(row,"valid_from",TIME);set(row,"parent_id",parent);set(row,"counterpart_id",counterpart);set(row,"parent_version",2L);set(row,"counterpart_version",3L);
                set(row,"parent_code","OLD-LINE");set(row,"parent_name","Line at bind");set(row,"counterpart_code","OLD-WORKSHOP");set(row,"counterpart_name","Workshop at bind");result.add(row);
            }
            return new MockResult[]{new MockResult(2,result)};
        });
        when(owner.connection()).thenReturn(connection);
        Page page=new AssetFoundationHistoryRepository(owner).page(Kind.LINE_WORKSHOP,parent,new Request(null,1),scope);
        assertThat(page.items()).hasSize(1);assertThat(page.nextAfter()).isEqualTo(new Cursor(TIME.toInstant(),firstCommand));
        assertThat((Endpoint)page.items().getFirst().get("workshopAtBind")).isEqualTo(new Endpoint(counterpart,3,"OLD-WORKSHOP","Workshop at bind"));
        assertThat(page.items().getFirst()).containsEntry("validTo",null).containsEntry("endObservedAt",null);
        assertThat(connection.isClosed()).isFalse();verify(owner,never()).workshop(any(),any());
    }
    @Test void missingOrOutOfScopeParentCannotProbeHistory(){
        var owner=mock(AssetFoundationRepository.class);var scope=scope();UUID parent=UUID.randomUUID();
        when(owner.productionLine(scope,parent)).thenThrow(new AssetFoundationFailure(404));
        assertThatThrownBy(()->new AssetFoundationHistoryRepository(owner).page(Kind.LINE_WORKSHOP,parent,new Request(null,20),scope)).isInstanceOfSatisfying(AssetFoundationFailure.class,error->assertThat(error.status()).isEqualTo(404));
        verify(owner,never()).connection();
    }
    private static ActionScope scope(){var context=mock(CurrentTenantContext.class);when(context.tenantId()).thenReturn(UUID.randomUUID());var scope=mock(ActionScope.class);when(scope.context()).thenReturn(context);when(scope.action()).thenReturn(Kind.LINE_WORKSHOP.read);when(scope.organizationIds()).thenReturn(Set.of(UUID.randomUUID()));return scope;}
    @SuppressWarnings({"rawtypes","unchecked"}) private static void set(Record row,String name,Object value){row.set((Field)row.field(name),value);}
}
