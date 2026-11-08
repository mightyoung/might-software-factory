package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes repository JDBC mechanics against a real read transaction owner with synthetic rows. */
class RiskScenarioReadRepositoryTest {
    private static final UUID ID=new UUID(0,1),HAZARD=new UUID(0,2);private static final Instant CUT=Instant.parse("2026-11-07T00:00:00Z");
    private static QueryRequest query(){return new QueryRequest(List.of(),List.of(new Sort("id",Direction.ASC)),List.of("id"),20,1,null);}
    @Test void queriesOnlySmallKeysBeforeSeparateProjectionAndUsesScopeArrays()throws Exception {
        var f=new Fixture();f.answer=sql->sql.startsWith("select s.id,s.version,")?Collections.singletonList(new Object[]{ID,1L,ID}):Collections.singletonList(new Object[]{ID});
        f.service.inReadSnapshot(f.request,snapshot->{var scope=snapshot.authorize(RISK_SCENARIO_READ);var keys=f.repository.queryKeys(snapshot,scope,query(),null);assertEquals(1,keys.size());assertEquals(Map.of("id",ID),f.repository.projection(snapshot,scope,query(),keys.getFirst()));return null;});
        String keys=f.sql.stream().filter(s->s.startsWith("select s.id,s.version,")).findFirst().orElseThrow();assertFalse(keys.contains("description"));assertTrue(keys.contains("organization_id=any"));assertTrue(keys.contains("order by s.id asc limit ? offset ?"));
        verify(f.source).getConnection();assertFalse(f.arrays.isEmpty());for(var array:f.arrays)verify(array).free();
    }
    @Test void snapshotSummaryNeverLoadsFullDescriptionsOrCurrentLabels()throws Exception {
        var f=new Fixture();f.answer=sql->Collections.singletonList(new Object[]{HAZARD,HAZARD,1L,"FROZEN","preview",false,"UNKNOWN",2L,3L});
        f.service.inReadSnapshot(f.request,s->{var rows=f.repository.summaries(s,s.authorize(RISK_SCENARIO_READ),ID,1);assertEquals("FROZEN",rows.getFirst().code());assertEquals(3,rows.getFirst().hazardVersionAtScenarioRevision());return null;});
        String sql=f.sql.getLast();assertTrue(sql.contains("description_preview"));assertFalse(sql.contains("full_description"));assertFalse(sql.contains("public.hazard_source"));assertTrue(sql.contains("scenario_version=?"));
    }
    @Test void protectedPointReadsFullFrozenDescriptionAndBothAnchors()throws Exception {
        var f=new Fixture();f.answer=sql->Collections.singletonList(new Object[]{HAZARD,HAZARD,1L,"H","full historical", "PRESENT",2L,3L});
        f.service.inReadSnapshot(f.request,s->{var row=f.repository.historicalSnapshot(s,s.authorize(RISK_SCENARIO_READ),ID,1,HAZARD).orElseThrow();assertEquals("full historical",row.fullDescription());assertEquals(2,row.hazardVersionAtLink());return null;});
        assertTrue(f.sql.getLast().contains("full_description"));assertTrue(f.sql.getLast().contains("hazard_version_at_scenario_revision"));assertFalse(f.sql.getLast().contains("public.hazard_source"));
    }
    @Test void historyKeysAndAnchorAreBoundToCurrentParentAndExactRevisionCommand()throws Exception {
        var f=new Fixture();f.answer=sql->sql.startsWith("select r.version,r.occurred_at")?Collections.singletonList(new Object[]{1L,CUT.atOffset(ZoneOffset.UTC),ID}):Collections.singletonList(new Object[]{1L});
        f.service.inReadSnapshot(f.request,s->{var scope=s.authorize(RISK_SCENARIO_READ);assertTrue(f.repository.historyAnchor(s,scope,ID,new HistoryCursor(CUT,ID)));assertEquals(1,f.repository.revisionKeys(s,scope,ID,new HistoryRequest(new HistoryCursor(CUT,ID),20)).size());return null;});
        assertTrue(f.sql.getLast().contains("(r.occurred_at,r.command_id)>(?,?)"));assertTrue(f.sql.getLast().contains("order by r.occurred_at,r.command_id limit ?"));assertFalse(f.sql.getLast().contains("description"));
    }
    @Test void currentTargetAndSelectorUseHazardScopeAndActiveOrganization()throws Exception {
        var f=new Fixture();f.answer=sql->Collections.singletonList(new Object[]{HAZARD});
        f.service.inReadSnapshot(f.request,s->{var scope=s.authorize(HAZARD_SOURCE_READ);assertTrue(f.repository.targetVisible(s,scope,HAZARD));assertEquals(List.of(HAZARD),f.repository.eligibleKeys(s,scope,new SelectorRequest(SelectorUse.CREATE,ID,null,"%_",null,20)));return null;});
        assertTrue(f.sql.getLast().contains("o.active"));assertFalse(f.sql.getLast().contains("h.organization_id=?"));assertTrue(f.sql.getLast().contains("h.organization_id=any(cast(? as uuid[]))"));assertTrue(f.sql.getLast().contains("order by h.id limit ?"));assertFalse(f.sql.getLast().startsWith("select h.description"));
        assertTrue(f.values.stream().anyMatch(v->v.equals("%!%!_%")));
    }
    @Test void finiteTypesRejectRelationshipFieldsAndMalformedOperatorsBeforeSql() {
        assertThrows(Failure.class,()->RiskScenarioReadRepository.column("hazardIds"));
        var invalid=List.of(new QueryRequest(List.of(),List.of(),List.of("hazards"),20,1,null),
                new QueryRequest(List.of(new Filter("version",FilterOperator.EQ,List.of("1"))),List.of(),List.of("id"),20,1,null),
                new QueryRequest(List.of(new Filter("description",FilterOperator.GT,List.of("text"))),List.of(),List.of("id"),20,1,null),
                new QueryRequest(List.of(),List.of(new Sort("description",Direction.ASC)),List.of("id"),20,1,null));
        for(var request:invalid)assertThrows(Failure.class,()->RiskScenarioReadRepository.validate(request));
        assertDoesNotThrow(()->RiskScenarioReadRepository.validate(new QueryRequest(List.of(new Filter("description",FilterOperator.EQ,List.of(""))),List.of(),List.of(),20,1,null)));
    }
    @Test void tinyExactDecimalUsesIntegerBoundsWithoutExpandingScale()throws Exception {
        var f=new Fixture();var query=new QueryRequest(List.of(new Filter("version",FilterOperator.GT,List.of(new BigDecimal("1e-999999")))),List.of(),List.of("id"),20,1,null);
        f.service.inReadSnapshot(f.request,s->{f.repository.queryKeys(s,s.authorize(RISK_SCENARIO_READ),query,null);return null;});
        assertTrue(f.sql.getLast().contains("s.version>=?"));assertTrue(f.values.contains(1L));assertTrue(f.values.stream().noneMatch(BigDecimal.class::isInstance));
    }
    @Test void emptyProjectionIsLegalAndDoesNotSelectNarratives()throws Exception {
        var f=new Fixture();f.answer=sql->Collections.singletonList(new Object[]{ID});var q=new QueryRequest(List.of(),List.of(),List.of(),20,1,null);
        f.service.inReadSnapshot(f.request,s->{assertTrue(f.repository.projection(s,s.authorize(RISK_SCENARIO_READ),q,new RiskScenarioReadRepository.QueryKey(ID,1,List.of(ID))).isEmpty());return null;});
        assertTrue(f.sql.getLast().startsWith("select s.id from"));
    }
    @Test void rowOverflowPoisonsTransactionEvenWhenCallerCatchesFailure()throws Exception {
        var f=new Fixture();f.answer=sql->Collections.nCopies(102,new Object[]{ID,1L,ID});
        assertThrows(RuntimeException.class,()->f.service.inReadSnapshot(f.request,s->{assertThrows(Failure.class,()->f.repository.queryKeys(s,s.authorize(RISK_SCENARIO_READ),query(),null));assertThrows(Failure.class,s::requireActive);return null;}));verify(f.connection).rollback();
    }
    @Test void jdbcFailureCannotBeCaughtAndResumeTheCapsule()throws Exception {
        var f=new Fixture();f.failReads=true;
        assertThrows(RuntimeException.class,()->f.service.inReadSnapshot(f.request,s->{assertThrows(Failure.class,()->f.repository.queryKeys(s,s.authorize(RISK_SCENARIO_READ),query(),null));return null;}));verify(f.connection).rollback();
    }
    @Test void everyStatementIsBoundedClosedAndCannotUseForeignScope()throws Exception {
        var f=new Fixture();f.service.inReadSnapshot(f.request,s->{f.repository.queryKeys(s,s.authorize(RISK_SCENARIO_READ),query(),null);return null;});
        for(int i=1;i<f.statements.size();i++){verify(f.statements.get(i)).setQueryTimeout(5);verify(f.statements.get(i)).setMaxRows(102);verify(f.statements.get(i)).close();}
        int before=f.sql.size();assertThrows(Failure.class,()->f.service.inReadSnapshot(f.request,s->{f.repository.queryKeys(s,mock(EntityActionAuthorization.ActionScope.class),query(),null);return null;}));assertEquals(before+1,f.sql.size());
    }
    @Test void validHazardScopeCannotReadRiskRootOrImmutableHistory()throws Exception {
        var f=new Fixture();assertThrows(RuntimeException.class,()->f.service.inReadSnapshot(f.request,s->{var hazard=s.authorize(HAZARD_SOURCE_READ);assertThrows(Failure.class,()->f.repository.parent(s,hazard,ID));assertThrows(Failure.class,s::requireActive);return null;}));
        assertEquals(1,f.sql.size());verify(f.connection).rollback();
    }
    @Test void selectorKeysAnchorsAndMaterializationUseOnlyIndependentTargetOrganizations()throws Exception {
        UUID organizationB=new UUID(7,8);
        for(var use:SelectorUse.values()){
            var f=new Fixture();f.answer=sql->sql.startsWith("select h.id,h.organization_id,h.code")?Collections.singletonList(new Object[]{HAZARD,organizationB,"B_HAZARD",3L,"preview",false,"UNKNOWN"}):Collections.singletonList(new Object[]{HAZARD});
            f.service.inReadSnapshot(f.request,s->{var target=s.authorize(HAZARD_SOURCE_READ);when(target.organizationIds()).thenReturn(Set.of(organizationB));
                var request=new SelectorRequest(use,use==SelectorUse.CREATE?ID:null,use==SelectorUse.UPDATE?ID:null,"%_",null,20);
                assertEquals(List.of(HAZARD),f.repository.eligibleKeys(s,target,request));
                assertTrue(f.repository.selectorAnchor(s,target,new SelectorRequest(use,request.organizationId(),request.scenarioId(),request.query(),HAZARD,20)));
                assertEquals(organizationB,f.repository.candidate(s,target,HAZARD).orElseThrow().organizationId());return null;});
            for(String sql:f.sql.subList(1,f.sql.size())){assertFalse(sql.contains("h.organization_id=?"));assertTrue(sql.contains("h.tenant_id=?"));assertTrue(sql.contains("h.organization_id=any(cast(? as uuid[]))"));assertTrue(sql.contains("o.tenant_id=h.tenant_id"));assertTrue(sql.contains("o.active"));}
            assertEquals(3,f.arrayValues.size());for(UUID[] values:f.arrayValues)assertArrayEquals(new UUID[]{organizationB},values);
        }
    }
    @Test void foreignTenantAndUnreadableTargetAnchorsCannotBecomeCandidatesInCurrentScopedLookup()throws Exception {
        UUID foreignTenant=new UUID(8,9),organizationB=new UUID(7,8),foreignHazard=new UUID(9,10);
        for(var use:SelectorUse.values()){
            var f=new Fixture();f.answer=sql->List.of();
            f.service.inReadSnapshot(f.request,s->{var target=s.authorize(HAZARD_SOURCE_READ);when(target.organizationIds()).thenReturn(Set.of(organizationB));
                var request=new SelectorRequest(use,use==SelectorUse.CREATE?ID:null,use==SelectorUse.UPDATE?ID:null,"",foreignHazard,20);
                assertFalse(f.repository.selectorAnchor(s,target,request));assertTrue(f.repository.candidate(s,target,foreignHazard).isEmpty());return null;});
            assertTrue(f.values.contains(ID));assertFalse(f.values.contains(foreignTenant));
            for(String sql:f.sql.subList(1,f.sql.size()))assertTrue(sql.contains("h.tenant_id=?")&&sql.contains("h.organization_id=any(cast(? as uuid[]))"));
            for(UUID[] values:f.arrayValues)assertArrayEquals(new UUID[]{organizationB},values);
        }
    }
    private static final class Fixture {
        final DataSource source=mock(DataSource.class);final Connection connection=mock(Connection.class);final CapturedHumanRequest request=mock(CapturedHumanRequest.class);final CurrentTenantContext context=new CurrentTenantContext(ID,ID,ID,ID,1,1,1);
        final HumanSessionAuthorityTransaction authority=mock(HumanSessionAuthorityTransaction.class);final HumanSessionAuthorityTransaction.ReadBoundary boundary=mock(HumanSessionAuthorityTransaction.ReadBoundary.class);final EntityActionAuthorization actions=mock(EntityActionAuthorization.class);
        final RiskScenarioReadRepository repository;final RiskScenarioReadService service;final List<String> sql=new ArrayList<>();final List<Object> values=new ArrayList<>();final List<java.sql.Array> arrays=new ArrayList<>();final List<UUID[]> arrayValues=new ArrayList<>();final List<PreparedStatement> statements=new ArrayList<>();
        Function<String,List<Object[]>> answer=text->List.of();boolean failReads;
        Fixture()throws Exception {
            when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);when(request.context()).thenReturn(context);
            when(authority.withRead(eq(request),any())).thenAnswer(call->((Function<HumanSessionAuthorityTransaction.ReadBoundary,?>)call.getArgument(1)).apply(boundary));
            when(actions.requireRead(eq(request),eq(boundary),any())).thenAnswer(call->{var action=(RegisteredEntityAction)call.getArgument(2);var scope=mock(EntityActionAuthorization.ActionScope.class);when(scope.action()).thenReturn(action);when(scope.context()).thenReturn(context);when(scope.organizationIds()).thenReturn(Set.of(ID));return scope;});
            when(connection.createArrayOf(eq("uuid"),any(Object[].class))).thenAnswer(call->{var array=mock(java.sql.Array.class);arrays.add(array);arrayValues.add(((UUID[])call.getArgument(1)).clone());return array;});
            when(connection.prepareStatement(anyString())).thenAnswer(call->{String text=call.getArgument(0);sql.add(text);var statement=mock(PreparedStatement.class);statements.add(statement);
                doAnswer(set->{values.add(set.getArgument(1));return null;}).when(statement).setObject(anyInt(),any());
                when(statement.executeQuery()).thenAnswer(run->{if(failReads&&!text.contains("set_config"))throw new SQLException("synthetic unavailable");List<Object[]> data=text.contains("set_config")?Collections.singletonList(new Object[]{ID.toString()}):answer.apply(text);var rows=mock(ResultSet.class);var at=new AtomicInteger(-1);when(rows.next()).thenAnswer(i->at.incrementAndGet()<data.size());
                    when(rows.getObject(anyInt(),eq(UUID.class))).thenAnswer(i->data.get(at.get())[(int)i.getArgument(0)-1]);when(rows.getObject(anyInt(),eq(OffsetDateTime.class))).thenAnswer(i->data.get(at.get())[(int)i.getArgument(0)-1]);
                    when(rows.getString(anyInt())).thenAnswer(i->String.valueOf(data.get(at.get())[(int)i.getArgument(0)-1]));when(rows.getLong(anyInt())).thenAnswer(i->((Number)data.get(at.get())[(int)i.getArgument(0)-1]).longValue());when(rows.getBoolean(anyInt())).thenAnswer(i->data.get(at.get())[(int)i.getArgument(0)-1]);return rows;});return statement;});
            repository=new RiskScenarioReadRepository(source);service=new RiskScenarioReadService(repository,authority,actions,new JdbcTransactionManager(source),source,mock(TenantApplicationReadRepository.class));
        }
    }
}
