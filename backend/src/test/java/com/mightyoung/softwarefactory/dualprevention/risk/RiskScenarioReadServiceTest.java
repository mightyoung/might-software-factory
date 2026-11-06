package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts.ExistenceState;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring JDBC read owner with synthetic authority/repository; not PostgreSQL acceptance. */
class RiskScenarioReadServiceTest {
    private static final UUID ID=new UUID(0,1), HAZARD=new UUID(0,2), TRACE=new UUID(0,3);
    private static final Instant CUT=Instant.parse("2026-11-07T00:00:00.123456Z");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @Test void detailReturnsFullFrozenSummaryAndDefensivelyOwnedFinalBytes()throws Exception {
        var f=new Fixture();var response=f.service.detail(f.request,ID,TRACE);byte[] bytes=response.body();var data=JSON.readTree(bytes).get("data");
        assertEquals("UNASSESSED",data.get("assessmentStatus").stringValue());assertTrue(data.get("currentAssessment").isNull());
        assertEquals("COMPLETE",data.get("hazards").get("visibility").stringValue());assertEquals("frozen",data.get("hazards").get("items").get(0).get("descriptionPreview").stringValue());
        bytes[0]=0;assertEquals('{',response.body()[0]);assertEquals(response.size(),response.body().length);
        var output=new java.io.ByteArrayOutputStream();response.writeTo(output);assertArrayEquals(response.body(),output.toByteArray());
        verify(f.repository).summaries(any(),eq(f.read),eq(ID),eq(1L));verify(f.source).getConnection();
    }
    @Test void oneHiddenTargetRestrictsWholeSetAndNeverLoadsLabels()throws Exception {
        var f=new Fixture();when(f.repository.targetVisible(any(),eq(f.hazards),eq(HAZARD))).thenReturn(false);
        var union=JSON.readTree(f.service.detail(f.request,ID,TRACE).body()).get("data").get("hazards");assertEquals(1,union.size());assertEquals("RESTRICTED",union.get("visibility").stringValue());
        verify(f.repository,never()).summaries(any(),any(),any(),anyLong());
    }
    @Test void deniedTargetActionRestrictsButDependencyFailureRemains503()throws Exception {
        var denied=new Fixture();when(denied.actions.requireRead(denied.request,denied.boundary,HAZARD_SOURCE_READ)).thenThrow(new AccessDeniedException("hidden"));
        assertEquals("RESTRICTED",JSON.readTree(denied.service.detail(denied.request,ID,TRACE).body()).get("data").get("hazards").get("visibility").stringValue());
        var failed=new Fixture();when(failed.repository.targetVisible(any(),any(),any())).thenThrow(new Failure(503));assertEquals(503,assertThrows(Failure.class,()->failed.service.detail(failed.request,ID,TRACE)).status());
    }
    @Test void missingParentPreventsAnyRelationshipLookup()throws Exception {
        var f=new Fixture();when(f.repository.parent(any(),any(),eq(ID))).thenReturn(Optional.empty());assertEquals(404,assertThrows(Failure.class,()->f.service.detail(f.request,ID,TRACE)).status());
        verify(f.repository,never()).snapshotKeys(any(),any(),any(),anyLong());
    }
    @Test void historicalPointRequiresAllTargetsAndReturnsFullFrozenDescription()throws Exception {
        var f=new Fixture();when(f.repository.historicalSnapshot(any(),eq(f.read),eq(ID),eq(1L),eq(HAZARD))).thenReturn(Optional.of(new HistoricalHazardSnapshot(HAZARD,HAZARD,1,"H","𠀀".repeat(201),ExistenceState.UNKNOWN,2,3)));
        var point=JSON.readTree(f.service.historicalSnapshot(f.request,ID,1,HAZARD,TRACE).body()).get("data").get("hazardSnapshot");assertEquals("𠀀".repeat(201),point.get("fullDescription").stringValue());assertEquals(2,point.get("hazardVersionAtLink").longValue());assertEquals(3,point.get("hazardVersionAtScenarioRevision").longValue());
        when(f.repository.targetVisible(any(),any(),eq(HAZARD))).thenReturn(false);assertEquals(404,assertThrows(Failure.class,()->f.service.historicalSnapshot(f.request,ID,1,HAZARD,TRACE)).status());
        verify(f.repository,times(1)).historicalSnapshot(any(),any(),any(),anyLong(),any());
    }
    @Test void historicalMissingRevisionOrNonmemberNeverFetchesFullText()throws Exception {
        var f=new Fixture();when(f.repository.revisionExists(any(),any(),eq(ID),eq(2L))).thenReturn(false);
        assertEquals(404,assertThrows(Failure.class,()->f.service.historicalSnapshot(f.request,ID,2,HAZARD,TRACE)).status());
        assertEquals(404,assertThrows(Failure.class,()->f.service.historicalSnapshot(f.request,ID,1,new UUID(4,5),TRACE)).status());
        verify(f.repository,never()).historicalSnapshot(any(),any(),any(),anyLong(),any());
    }
    @Test void queryUtf8ByteShortPagesResumeAtLastActuallyReturnedWithoutOmissions()throws Exception {
        var f=new Fixture();var keys=java.util.stream.IntStream.rangeClosed(1,20).mapToObj(i->new RiskScenarioReadRepository.QueryKey(new UUID(1,i),i,List.of(new UUID(1,i)))).toList();
        when(f.repository.queryKeys(any(),eq(f.read),any(),nullable(RiskScenarioReadRepository.QueryKey.class))).thenAnswer(call->{var anchor=(RiskScenarioReadRepository.QueryKey)call.getArgument(3);return keys.stream().filter(k->anchor==null||k.version()>anchor.version()).toList();});
        when(f.repository.queryAnchor(any(),eq(f.read),any(),any())).thenAnswer(call->{var anchor=(RiskScenarioReadCursorCodec.Anchor)call.getArgument(3);return keys.stream().filter(k->k.id().equals(anchor.id())&&k.version()==anchor.version()).findFirst();});
        when(f.repository.count(any(),any(),any())).thenReturn(20L);
        when(f.repository.projection(any(),any(),any(),any())).thenAnswer(call->{var key=(RiskScenarioReadRepository.QueryKey)call.getArgument(3);return Map.of("id",key.id(),"description","\"".repeat(20_000),"triggeringConditions","\"".repeat(20_000),"exposureDescription","\"".repeat(20_000),"consequence","\"".repeat(20_000));});
        var all=new ArrayList<String>();String after=null;int pages=0;
        do {var query=new QueryRequest(List.of(),List.of(new Sort("id",Direction.ASC)),List.of("id","description","triggeringConditions","exposureDescription","consequence"),20,after==null?1:null,after);
            var result=f.service.query(f.request,query,TRACE);assertTrue(result.size()<=ResponseBudget.QUERY.bytes());var wire=JSON.readTree(result.body()).get("data");assertTrue(wire.get("items").size()>0);assertTrue(wire.get("items").size()<20);
            for(var row:wire.get("items"))all.add(row.get("id").stringValue());var page=wire.get("page");after=page.get("hasMore").booleanValue()?page.get("nextCursor").stringValue():null;pages++;assertTrue(pages<10);
        }while(after!=null);
        assertEquals(keys.stream().map(k->k.id().toString()).toList(),all);assertEquals(all.size(),new HashSet<>(all).size());
    }
    @Test void historyByteShortPageCursorUsesLastReturnedRootCommand()throws Exception {
        var f=new Fixture();when(f.repository.parent(any(),any(),eq(ID))).thenReturn(Optional.of(new RiskScenarioReadRepository.Root(ID,ID,"R",20,"d","t","e","c",CUT,CUT)));var keys=java.util.stream.IntStream.rangeClosed(1,20).mapToObj(i->new RiskScenarioReadRepository.RevisionKey(i,CUT.plusSeconds(i),new UUID(2,i))).toList();
        when(f.repository.revisionKeys(any(),eq(f.read),eq(ID),any())).thenAnswer(call->{var input=(HistoryRequest)call.getArgument(3);return keys.stream().filter(k->input.after()==null||k.occurredAt().isAfter(input.after().occurredAt())).toList();});
        when(f.repository.snapshotKeys(any(),eq(f.read),eq(ID),anyLong())).thenReturn(List.of(new RiskScenarioReadRepository.SnapshotKey(HAZARD)));
        when(f.repository.summaries(any(),eq(f.read),eq(ID),anyLong())).thenReturn(List.of(f.summary));
        when(f.repository.revision(any(),eq(f.read),eq(ID),anyLong(),any())).thenAnswer(call->{long v=call.getArgument(3);return Optional.of(new HistoryEntry(v,new UUID(2,v),CUT.plusSeconds(v),ID,"R","\"".repeat(20_000),"\"".repeat(20_000),"\"".repeat(20_000),"\"".repeat(20_000),AssessmentStatus.UNASSESSED,null,call.getArgument(4)));});
        var data=JSON.readTree(f.service.history(f.request,ID,new HistoryRequest(null,20),TRACE).body()).get("data");int count=data.get("items").size();assertTrue(count>0&&count<20);assertTrue(data.get("hasMore").booleanValue());assertEquals(new UUID(2,count).toString(),data.get("nextCursor").get("commandId").stringValue());
        verify(f.repository,times(count+1)).revision(any(),any(),any(),anyLong(),any());
        var observed=new ArrayList<Long>();for(var row:data.get("items"))observed.add(row.get("version").longValue());HistoryCursor after=new HistoryCursor(CUT.plusSeconds(count),new UUID(2,count));
        for(int page=0;page<10;page++){var next=JSON.readTree(f.service.history(f.request,ID,new HistoryRequest(after,20),TRACE).body()).get("data");for(var row:next.get("items"))observed.add(row.get("version").longValue());if(!next.get("hasMore").booleanValue())break;var cursor=next.get("nextCursor");after=new HistoryCursor(Instant.parse(cursor.get("occurredAt").stringValue()),UUID.fromString(cursor.get("commandId").stringValue()));}
        assertEquals(java.util.stream.LongStream.rangeClosed(1,20).boxed().toList(),observed);
    }
    @Test void selectorUsesFixedOwningActionAndCurrentOrganizationAndReferenceScope()throws Exception {
        var f=new Fixture();when(f.repository.eligibleKeys(any(),eq(f.hazards),any())).thenReturn(List.of(HAZARD));
        when(f.repository.candidate(any(),eq(f.hazards),eq(HAZARD))).thenReturn(Optional.of(new HazardCandidate(HAZARD,ID,"H",3,"preview",false,ExistenceState.UNKNOWN)));
        f.service.eligibleHazards(f.request,new SelectorRequest(SelectorUse.CREATE,ID,null,"",null,20),TRACE);
        f.service.eligibleHazards(f.request,new SelectorRequest(SelectorUse.UPDATE,null,ID,"",null,20),TRACE);
        verify(f.actions).requireRead(f.request,f.boundary,RISK_SCENARIO_CREATE);verify(f.actions).requireRead(f.request,f.boundary,RISK_SCENARIO_UPDATE);verify(f.actions,times(2)).requireRead(f.request,f.boundary,HAZARD_SOURCE_READ);
    }
    @Test void ambientTransactionsAndEscapedSnapshotAreRejected()throws Exception {
        var f=new Fixture();TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(Failure.class,()->f.service.detail(f.request,ID,TRACE));}finally{TransactionSynchronizationManager.clear();}verifyNoInteractions(f.source,f.authority);verify(f.repository,never()).setTenant(any());
        var escaped=new AtomicReference<RiskScenarioReadService.ReadSnapshot>();f.service.inReadSnapshot(f.request,snapshot->{escaped.set(snapshot);snapshot.authorize(RISK_SCENARIO_READ);return 1;});assertThrows(Failure.class,escaped.get()::requireActive);
    }
    @Test void caughtCrossThreadAndReplacedHolderPoisonTheReadCapsule()throws Exception {
        for(int mode=0;mode<2;mode++){var f=new Fixture();int fault=mode;assertThrows(RuntimeException.class,()->f.service.inReadSnapshot(f.request,snapshot->{
            if(fault==0){var thrown=new AtomicReference<Throwable>();var thread=new Thread(()->{try{snapshot.requireActive();}catch(Throwable e){thrown.set(e);}});thread.start();try{thread.join();}catch(InterruptedException e){throw new AssertionError(e);}assertInstanceOf(Failure.class,thrown.get());}
            else{Object original=TransactionSynchronizationManager.unbindResource(f.source);TransactionSynchronizationManager.bindResource(f.source,new ConnectionHolder(f.connection));try{assertThrows(Failure.class,snapshot::requireActive);}finally{TransactionSynchronizationManager.unbindResource(f.source);TransactionSynchronizationManager.bindResource(f.source,original);}}
            assertThrows(Failure.class,snapshot::requireActive);return 1;}));verify(f.connection).rollback();}
    }
    @Test void applicationBindingUsesSafetyAndForeignScopesAreRejected()throws Exception {
        var f=new Fixture();long version=f.service.inReadSnapshot(f.request,s->s.applicationVersion(s.authorize(RISK_SCENARIO_READ)));assertEquals(7,version);verify(f.applications).findCurrentInCaller(f.context.tenantId(),"safety");
        assertThrows(Failure.class,()->f.service.inReadSnapshot(f.request,s->s.connection(mock(EntityActionAuthorization.ActionScope.class))));
    }
    @Test void firstOversizedItemFails503WithoutAnEmptyLoopCursor()throws Exception {
        var f=new Fixture();var key=new RiskScenarioReadRepository.QueryKey(ID,1,List.of(ID));
        when(f.repository.queryKeys(any(),any(),any(),nullable(RiskScenarioReadRepository.QueryKey.class))).thenReturn(List.of(key));when(f.repository.count(any(),any(),any())).thenReturn(1L);
        when(f.repository.projection(any(),any(),any(),any())).thenReturn(Map.of("description","x".repeat(ResponseBudget.ITEM.bytes())));
        var q=new QueryRequest(List.of(),List.of(),List.of("description"),20,1,null);var refusal=assertThrows(Failure.class,()->f.service.query(f.request,q,TRACE));assertEquals(503,refusal.status());assertEquals("RESPONSE_BUDGET_EXCEEDED",refusal.code());
    }
    @Test void missingAndForeignPageAnchorsAreRejectedBeforeMaterialization()throws Exception {
        var f=new Fixture();when(f.repository.historyAnchor(any(),any(),any(),any())).thenReturn(false);assertEquals(400,assertThrows(Failure.class,()->f.service.history(f.request,ID,new HistoryRequest(new HistoryCursor(CUT,ID),20),TRACE)).status());
        verify(f.repository,never()).revisionKeys(any(),any(),any(),any());
        when(f.repository.selectorAnchor(any(),any(),any())).thenReturn(false);assertEquals(400,assertThrows(Failure.class,()->f.service.eligibleHazards(f.request,new SelectorRequest(SelectorUse.CREATE,ID,null,"",HAZARD,20),TRACE)).status());verify(f.repository,never()).eligibleKeys(any(),any(),any());
    }
    @Test void allLegalMaximumNarrativesAndHundredSummariesFitWithoutPartialCompleteSet()throws Exception {
        var f=new Fixture();String text="𠀀".repeat(20_000);when(f.repository.parent(any(),any(),eq(ID))).thenReturn(Optional.of(new RiskScenarioReadRepository.Root(ID,ID,"R",1,text,text,text,text,CUT,CUT)));
        var keys=java.util.stream.IntStream.rangeClosed(1,100).mapToObj(i->new RiskScenarioReadRepository.SnapshotKey(new UUID(3,i))).toList();
        var summaries=keys.stream().map(k->new HazardSummary(k.hazardId(),new UUID(4,k.hazardId().getLeastSignificantBits()),1,"H","𠀀".repeat(200),true,ExistenceState.UNKNOWN,2,3)).toList();
        when(f.repository.snapshotKeys(any(),any(),eq(ID),eq(1L))).thenReturn(keys);when(f.repository.targetVisible(any(),any(),any())).thenReturn(true);when(f.repository.summaries(any(),any(),eq(ID),eq(1L))).thenReturn(summaries);
        var response=f.service.detail(f.request,ID,TRACE);assertTrue(response.size()<=ResponseBudget.DETAIL.bytes());var union=JSON.readTree(response.body()).get("data").get("hazards");assertEquals("COMPLETE",union.get("visibility").stringValue());assertEquals(100,union.get("items").size());
    }
    @Test void changedPhysicalConnectionOnSameHolderInvalidatesCapsule()throws Exception {
        var f=new Fixture();var substituted=new AtomicReference<ConnectionHolder>();
        when(f.authority.withRead(eq(f.request),any())).thenAnswer(call->{Object original=TransactionSynchronizationManager.unbindResource(f.source);var holder=spy((ConnectionHolder)original);substituted.set(holder);TransactionSynchronizationManager.bindResource(f.source,holder);
            try{return ((Function<HumanSessionAuthorityTransaction.ReadBoundary,?>)call.getArgument(1)).apply(f.boundary);}finally{TransactionSynchronizationManager.unbindResource(f.source);TransactionSynchronizationManager.bindResource(f.source,original);}});
        assertThrows(RuntimeException.class,()->f.service.inReadSnapshot(f.request,s->{doReturn(mock(Connection.class)).when(substituted.get()).getConnection();assertThrows(Failure.class,s::requireActive);return 1;}));verify(f.connection).rollback();
    }
    @Test void bothSelectorUsesAllowReadableCrossOrganizationOutsideOwnerScope()throws Exception {
        UUID targetOrganization=new UUID(7,8);
        for(var use:SelectorUse.values()){
            var f=new Fixture();when(f.hazards.organizationIds()).thenReturn(Set.of(targetOrganization));
            when(f.repository.eligibleKeys(any(),eq(f.hazards),any())).thenReturn(List.of(HAZARD));
            when(f.repository.candidate(any(),eq(f.hazards),eq(HAZARD))).thenReturn(Optional.of(new HazardCandidate(HAZARD,targetOrganization,"H",3,"cross organization",false,ExistenceState.UNKNOWN)));
            var request=new SelectorRequest(use,use==SelectorUse.CREATE?ID:null,use==SelectorUse.UPDATE?ID:null,"",null,20);
            var items=JSON.readTree(f.service.eligibleHazards(f.request,request,TRACE).body()).get("data").get("items");
            assertEquals(1,items.size());assertEquals(targetOrganization.toString(),items.get(0).get("organizationId").stringValue());
            verify(f.repository).organization(any(),argThat(owner->owner.organizationIds().equals(Set.of(ID))),eq(ID));
        }
    }
    @Test void bothSelectorUsesRejectWhenTargetReadIsDeniedDespiteValidOwner()throws Exception {
        for(var use:SelectorUse.values()){
            var f=new Fixture();when(f.actions.requireRead(f.request,f.boundary,HAZARD_SOURCE_READ)).thenThrow(new AccessDeniedException("target not readable"));
            var input=new SelectorRequest(use,use==SelectorUse.CREATE?ID:null,use==SelectorUse.UPDATE?ID:null,"",null,20);
            assertThrows(AccessDeniedException.class,()->f.service.eligibleHazards(f.request,input,TRACE));
            verify(f.repository,never()).eligibleKeys(any(),any(),any());verify(f.repository,never()).candidate(any(),any(),any());
        }
    }
    @Test void bothSelectorUsesFailClosedIfRepositoryReturnsAnUnreadableOrganization()throws Exception {
        UUID hiddenOrganization=new UUID(7,8);
        for(var use:SelectorUse.values()){
            var f=new Fixture();when(f.repository.eligibleKeys(any(),eq(f.hazards),any())).thenReturn(List.of(HAZARD));
            when(f.repository.candidate(any(),eq(f.hazards),eq(HAZARD))).thenReturn(Optional.of(new HazardCandidate(HAZARD,hiddenOrganization,"H",3,"must not leak",false,ExistenceState.UNKNOWN)));
            var input=new SelectorRequest(use,use==SelectorUse.CREATE?ID:null,use==SelectorUse.UPDATE?ID:null,"",null,20);
            assertEquals(503,assertThrows(Failure.class,()->f.service.eligibleHazards(f.request,input,TRACE)).status());
        }
    }
    private static final class Fixture {
        final RiskScenarioReadRepository repository=mock(RiskScenarioReadRepository.class);final HumanSessionAuthorityTransaction authority=mock(HumanSessionAuthorityTransaction.class);final EntityActionAuthorization actions=mock(EntityActionAuthorization.class);
        final CapturedHumanRequest request=mock(CapturedHumanRequest.class);final CurrentTenantContext context=new CurrentTenantContext(ID,ID,ID,ID,1,1,1);final HumanSessionAuthorityTransaction.ReadBoundary boundary=mock(HumanSessionAuthorityTransaction.ReadBoundary.class);
        final TenantApplicationReadRepository applications=mock(TenantApplicationReadRepository.class);final DataSource source=mock(DataSource.class);final Connection connection=mock(Connection.class);
        final EntityActionAuthorization.ActionScope read,hazards;final RiskScenarioReadService service;
        final HazardSummary summary=new HazardSummary(HAZARD,HAZARD,1,"H","frozen",false,ExistenceState.UNKNOWN,2,3);
        Fixture()throws Exception {
            when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);when(request.context()).thenReturn(context);
            var scopes=new EnumMap<RegisteredEntityAction,EntityActionAuthorization.ActionScope>(RegisteredEntityAction.class);for(var action:List.of(RISK_SCENARIO_READ,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE,HAZARD_SOURCE_READ)){var scope=mock(EntityActionAuthorization.ActionScope.class);when(scope.context()).thenReturn(context);when(scope.action()).thenReturn(action);when(scope.organizationIds()).thenReturn(Set.of(ID));scopes.put(action,scope);}
            read=scopes.get(RISK_SCENARIO_READ);hazards=scopes.get(HAZARD_SOURCE_READ);when(actions.requireRead(eq(request),eq(boundary),any())).thenAnswer(call->scopes.get(call.getArgument(2)));
            when(authority.withRead(eq(request),any())).thenAnswer(call->((Function<HumanSessionAuthorityTransaction.ReadBoundary,?>)call.getArgument(1)).apply(boundary));
            when(applications.findCurrentInCaller(ID,"safety")).thenReturn(Optional.of(new TenantApplicationState(true,7)));
            when(repository.parent(any(),any(),eq(ID))).thenReturn(Optional.of(new RiskScenarioReadRepository.Root(ID,ID,"R",1,"d","t","e","c",CUT,CUT)));
            when(repository.organization(any(),any(),eq(ID))).thenReturn(true);when(repository.historyAnchor(any(),any(),eq(ID),nullable(HistoryCursor.class))).thenReturn(true);
            when(repository.selectorAnchor(any(),any(),any())).thenReturn(true);when(repository.revisionExists(any(),any(),eq(ID),eq(1L))).thenReturn(true);
            when(repository.snapshotKeys(any(),any(),eq(ID),eq(1L))).thenReturn(List.of(new RiskScenarioReadRepository.SnapshotKey(HAZARD)));when(repository.targetVisible(any(),eq(hazards),eq(HAZARD))).thenReturn(true);when(repository.summaries(any(),any(),eq(ID),eq(1L))).thenReturn(List.of(summary));
            service=new RiskScenarioReadService(repository,authority,actions,new JdbcTransactionManager(source),source,applications);
        }
    }
}
