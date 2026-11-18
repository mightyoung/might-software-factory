package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationQueryContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetFoundationQueryRepositoryTest {
    private final ActionScope scope=scope();
    private static ActionScope scope(){var context=mock(CurrentTenantContext.class);when(context.tenantId()).thenReturn(new UUID(1,1));when(context.actorId()).thenReturn(new UUID(2,2));when(context.accountId()).thenReturn(new UUID(4,4));when(context.memberId()).thenReturn(new UUID(5,5));var value=mock(ActionScope.class);when(value.context()).thenReturn(context);when(value.action()).thenReturn(WORKSHOP.readAction());when(value.organizationIds()).thenReturn(Set.of(new UUID(3,3)));return value;}
    private static Plan plan(Integer page,String cursor){return new Plan(List.of(),List.of(new Sort("name",Direction.ASC),new Sort("id",Direction.ASC)),List.of("code"),20,page,cursor);}
    private static UUID id(int n){return new UUID(Long.MIN_VALUE,n+1);}
    private static final Field<?>[] FIELDS={DSL.field("code",String.class),DSL.field("name",String.class),DSL.field("id",UUID.class),DSL.field("version",Long.class)};
    private static org.jooq.Result<Record> rows(int first,int count){
        var sql=DSL.using(SQLDialect.POSTGRES);var result=sql.newResult(FIELDS);
        for(int i=first;i<first+count;i++){
            Record row=sql.newRecord(FIELDS);row.set(DSL.field("code",String.class),"C"+i);row.set(DSL.field("name",String.class),"N"+i);row.set(DSL.field("id",UUID.class),id(i));row.set(DSL.field("version",Long.class),3L);result.add(row);
        }
        return result;
    }
    @Test void typedQueryAndAnchorRecheckShareOwnerConnectionAndKeepHiddenSortOutOfWire()throws Exception {
        var calls=new AtomicInteger();var statements=new ArrayList<String>();var bindings=new ArrayList<List<Object>>();
        var connection=new MockConnection(context->{int call=calls.getAndIncrement();statements.add(context.sql());bindings.add(java.util.Arrays.asList(context.bindings()));return new MockResult[]{new MockResult(0,call==0?rows(0,21):call==1?rows(19,1):rows(20,1))};});
        var owner=mock(AssetFoundationRepository.class);when(owner.connection()).thenReturn(connection);var repository=new AssetFoundationQueryRepository(owner);
        var first=repository.query(WORKSHOP,plan(1,null),scope);
        assertThat(first.items()).hasSize(20).allSatisfy(item->assertThat(item).containsOnlyKeys("code"));
        assertThat(first.page().nextCursor()).hasSize(78);assertThat(first.page().total()).isNull();
        var second=repository.query(WORKSHOP,plan(null,first.page().nextCursor()),scope);
        assertThat(second.items()).containsExactly(java.util.Map.of("code","C20"));assertThat(second.page().nextCursor()).isNull();
        assertThat(statements).hasSize(3);assertThat(statements.get(1)).contains("\"a\".\"version\" = ?","\"a\".\"id\" = cast(? as uuid)");
        assertThat(bindings.get(1)).contains(id(19),3L);assertThat(connection.isClosed()).isFalse();
        verify(owner,times(4)).require(scope);
    }
    @Test void deletedOutOfScopeOrChangedBusinessVersionAnchorNeverFallsBackToFirstPage(){
        var calls=new AtomicInteger();var connection=new MockConnection(context->{calls.incrementAndGet();assertThat(java.util.Arrays.asList(context.bindings())).contains(3L);return new MockResult[]{new MockResult(0,rows(0,0))};});
        var owner=mock(AssetFoundationRepository.class);when(owner.connection()).thenReturn(connection);
        var source=plan(1,null);String cursor=AssetFoundationQueryCursor.encode(WORKSHOP,AssetFoundationQueryCursor.fingerprint(WORKSHOP,source,scope),id(7),3L);
        assertThatThrownBy(()->new AssetFoundationQueryRepository(owner).query(WORKSHOP,plan(null,cursor),scope)).isInstanceOfSatisfying(AssetFoundationFailure.class,failure->assertThat(failure.status()).isEqualTo(400));
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void unauthorizedScopeRefusesBeforeAnyJdbcQuery(){
        var calls=new AtomicInteger();var connection=new MockConnection(context->{calls.incrementAndGet();return new MockResult[0];});
        var owner=mock(AssetFoundationRepository.class);doThrow(new AssetFoundationFailure(403)).when(owner).require(scope);
        assertThatThrownBy(()->new AssetFoundationQueryRepository(owner).query(WORKSHOP,plan(1,null),scope)).isInstanceOf(AssetFoundationFailure.class);
        assertThat(calls.get()).isZero();verify(owner,never()).connection();
    }
}
