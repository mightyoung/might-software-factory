package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Spring risk read owner and encoding, synthetic authority/JDBC/provider faults; no real HUMAN/PG proof. */
class RiskScenarioMetadataServiceTest {
    private static final UUID ACTOR=new UUID(0,1),ACCOUNT=new UUID(0,2),TENANT=new UUID(0,3),MEMBER=new UUID(0,4),ORG=new UUID(0,5),REQUEST=new UUID(0,6),TRACE=new UUID(0,7);
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @Test void schemaAndBothDefinitionUsesResolveOnlyTheirFixedCurrentOwnerActions()throws Exception {
        var f=new Fixture();f.service.schema(f.request,REQUEST,TRACE);f.service.definition(f.request,Use.READ,REQUEST,TRACE);f.service.definition(f.request,Use.CREATE,REQUEST,TRACE);
        verify(f.actions,times(2)).requireRead(f.request,f.boundary,RISK_SCENARIO_READ);verify(f.actions).requireRead(f.request,f.boundary,RISK_SCENARIO_CREATE);verify(f.actions,never()).requireRead(f.request,f.boundary,RISK_SCENARIO_UPDATE);verify(f.actions,never()).requireRead(f.request,f.boundary,HAZARD_SOURCE_READ);
        verify(f.applications,times(3)).findCurrentInCaller(TENANT,"safety");verify(f.applications,never()).findCurrent(any(),any());verify(f.provider).publication(Use.READ);verify(f.provider).publication(Use.CREATE);verify(f.source,times(3)).getConnection();
    }
    @Test void authorizationApplicationLookupAndProviderRunInSameRiskReadSnapshot()throws Exception {
        var f=new Fixture();var original=f.provider.publication(Use.CREATE);clearInvocations(f.provider);
        doAnswer(call->{assertReadOwner(f);return original;}).when(f.provider).publication(Use.CREATE);
        f.service.definition(f.request,Use.CREATE,REQUEST,TRACE);
        var order=inOrder(f.actions,f.applications,f.provider);order.verify(f.actions).requireRead(f.request,f.boundary,RISK_SCENARIO_CREATE);order.verify(f.applications).findCurrentInCaller(TENANT,"safety");order.verify(f.provider).publication(Use.CREATE);
        verify(f.authority).withRead(eq(f.request),any());verify(f.authority,atLeastOnce()).requireRead(f.request,f.boundary,f.source,f.connection);verify(f.actions,atLeastOnce()).requireSqlConnection(f.create,f.source,f.connection);verify(f.repository).setTenant(TENANT);
    }
    @Test void deniedReadCannotPublishSchemaOrDefinitionEvenAfterEarlierMatchingValidator()throws Exception {
        var f=new Fixture();String prior=f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag();assertTrue(prior.startsWith("W/\""));clearInvocations(f.applications,f.provider);
        when(f.actions.requireRead(f.request,f.boundary,RISK_SCENARIO_READ)).thenThrow(new AccessDeniedException("revoked"));
        assertThrows(AccessDeniedException.class,()->f.service.schema(f.request,REQUEST,TRACE));assertThrows(AccessDeniedException.class,()->f.service.definition(f.request,Use.READ,REQUEST,TRACE));verifyNoInteractions(f.applications,f.provider);
    }
    @Test void deniedCreateNeverFallsBackToReadDefinitionOrProvider()throws Exception {
        var f=new Fixture();when(f.actions.requireRead(f.request,f.boundary,RISK_SCENARIO_CREATE)).thenThrow(new AccessDeniedException("create denied"));
        assertThrows(AccessDeniedException.class,()->f.service.definition(f.request,Use.CREATE,REQUEST,TRACE));verify(f.actions,never()).requireRead(f.request,f.boundary,RISK_SCENARIO_READ);verifyNoInteractions(f.applications,f.provider);
    }
    @Test void everyIdentityAndAuthorizationEpochChangesBothContextualValidators()throws Exception {
        var f=new Fixture();var original=f.context.get();String schema=f.service.schema(f.request,REQUEST,TRACE).etag(),definition=f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag();UUID other=new UUID(3,9);
        var contexts=List.of(new CurrentTenantContext(other,ACCOUNT,TENANT,MEMBER,1,2,3),new CurrentTenantContext(ACTOR,other,TENANT,MEMBER,1,2,3),new CurrentTenantContext(ACTOR,ACCOUNT,other,MEMBER,1,2,3),new CurrentTenantContext(ACTOR,ACCOUNT,TENANT,other,1,2,3),new CurrentTenantContext(ACTOR,ACCOUNT,TENANT,MEMBER,4,2,3),new CurrentTenantContext(ACTOR,ACCOUNT,TENANT,MEMBER,1,4,3),new CurrentTenantContext(ACTOR,ACCOUNT,TENANT,MEMBER,1,2,4));
        for(var context:contexts){f.context.set(context);assertNotEquals(schema,f.service.schema(f.request,REQUEST,TRACE).etag());assertNotEquals(definition,f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag());}f.context.set(original);
    }
    @Test void scopeOrderIsStableButScopeMembershipChangesBothValidators()throws Exception {
        var f=new Fixture();UUID other=new UUID(3,9);f.organizations.set(new LinkedHashSet<>(List.of(ORG,other)));String schema=f.service.schema(f.request,REQUEST,TRACE).etag(),definition=f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag();
        f.organizations.set(new LinkedHashSet<>(List.of(other,ORG)));assertEquals(schema,f.service.schema(f.request,REQUEST,TRACE).etag());assertEquals(definition,f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag());
        f.organizations.set(Set.of(ORG));assertNotEquals(schema,f.service.schema(f.request,REQUEST,TRACE).etag());assertNotEquals(definition,f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag());
    }
    @Test void applicationVersionChangesValidatorAndDisabledApplicationCannotReturnMetadata()throws Exception {
        var f=new Fixture();String schema=f.service.schema(f.request,REQUEST,TRACE).etag(),definition=f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag();f.applicationVersion.set(8);
        assertNotEquals(schema,f.service.schema(f.request,REQUEST,TRACE).etag());assertNotEquals(definition,f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag());f.enabled.set(false);
        assertEquals(503,assertThrows(Failure.class,()->f.service.schema(f.request,REQUEST,TRACE)).status());assertEquals(503,assertThrows(Failure.class,()->f.service.definition(f.request,Use.READ,REQUEST,TRACE)).status());
    }
    @Test void randomRequestAndTraceIdsChangeFinalBytesWithoutChangingSemanticValidators()throws Exception {
        var f=new Fixture();UUID nextRequest=new UUID(4,8),nextTrace=new UUID(4,9);
        for(int kind=0;kind<3;kind++){
            var one=metadata(f,kind,REQUEST,TRACE);var two=metadata(f,kind,nextRequest,nextTrace);assertEquals(one.etag(),two.etag());assertFalse(Arrays.equals(one.body(),two.body()));assertIds(one,REQUEST,TRACE);assertIds(two,nextRequest,nextTrace);
            assertTrue(one.etag().matches("W/\"[0-9a-f]{64}\""));assertTrue(one.etag().length()<=256);assertTrue(one.size()<=ResponseBudget.METADATA.bytes());
        }
    }
    @Test void schemaReadAndDefinitionReadAndCreateHaveDistinctSemanticValidators()throws Exception {
        var f=new Fixture();var tags=Set.of(f.service.schema(f.request,REQUEST,TRACE).etag(),f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag(),f.service.definition(f.request,Use.CREATE,REQUEST,TRACE).etag());assertEquals(3,tags.size());
    }
    @Test void finalWirePreservesRootOnlyQuerySchemaAndSeparateCreateMembershipInputs()throws Exception {
        var f=new Fixture();var schema=JSON.readTree(f.service.schema(f.request,REQUEST,TRACE).body()).get("data");assertEquals(11,schema.get("fields").size());assertFalse(schema.get("fields").has("hazardIds"));assertEquals("risk_scenario",schema.get("key").stringValue());
        var read=JSON.readTree(f.service.definition(f.request,Use.READ,REQUEST,TRACE).body()).get("data");assertEquals(0,read.get("commandInputs").size());assertEquals(0,read.get("actions").size());
        var create=JSON.readTree(f.service.definition(f.request,Use.CREATE,REQUEST,TRACE).body()).get("data");assertTrue(create.get("commandInputs").has("hazardIds"));assertFalse(create.get("querySchema").get("fields").has("hazardIds"));assertEquals("CREATE",create.get("use").stringValue());
    }
    @Test void publicationChecksumAndDefinitionVersionEachParticipateInValidator()throws Exception {
        var f=new Fixture();var original=f.provider.publication(Use.READ);String baseline=f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag();
        doReturn(new RiskScenarioDefinitionProvider.Publication(original.definition(),"0".repeat(64))).when(f.provider).publication(Use.READ);assertNotEquals(baseline,f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag());
        var d=original.definition();var versioned=new RiskScenarioDefinitionProvider.Definition(d.appKey(),d.entityKey(),"2",d.schemaVersion(),d.use(),d.title(),d.querySchema(),d.fields(),d.commandInputs(),d.layout(),d.actions(),d.references());
        doReturn(new RiskScenarioDefinitionProvider.Publication(versioned,original.checksum())).when(f.provider).publication(Use.READ);assertNotEquals(baseline,f.service.definition(f.request,Use.READ,REQUEST,TRACE).etag());
    }
    @Test void wrongUseOrMalformedChecksumCannotExposeCreatePublicationToReadOwner()throws Exception {
        var f=new Fixture();var create=f.provider.publication(Use.CREATE);doReturn(create).when(f.provider).publication(Use.READ);assertEquals(503,assertThrows(Failure.class,()->f.service.definition(f.request,Use.READ,REQUEST,TRACE)).status());
        doReturn(new RiskScenarioDefinitionProvider.Publication(create.definition(),"not-a-checksum")).when(f.provider).publication(Use.CREATE);assertEquals(503,assertThrows(Failure.class,()->f.service.definition(f.request,Use.CREATE,REQUEST,TRACE)).status());
    }
    @Test void actualUtf8MetadataOverflowFailsBeforeAnyOutcomeEscapes()throws Exception {
        var f=new Fixture();var publication=f.provider.publication(Use.READ);var d=publication.definition();var oversized=new RiskScenarioDefinitionProvider.Definition(d.appKey(),d.entityKey(),d.definitionVersion(),d.schemaVersion(),d.use(),"𠀀".repeat(70_000),d.querySchema(),d.fields(),d.commandInputs(),d.layout(),d.actions(),d.references());
        doReturn(new RiskScenarioDefinitionProvider.Publication(oversized,publication.checksum())).when(f.provider).publication(Use.READ);var failure=assertThrows(Failure.class,()->f.service.definition(f.request,Use.READ,REQUEST,TRACE));assertEquals(503,failure.status());assertEquals("RESPONSE_BUDGET_EXCEEDED",failure.code());verify(f.connection).rollback();
    }
    @Test void postEncodingScopeValidationRefusesPoisonedHolder()throws Exception {
        var f=new Fixture();var publication=f.provider.publication(Use.READ);doAnswer(call->{((ConnectionHolder)TransactionSynchronizationManager.getResource(f.source)).setRollbackOnly();return publication;}).when(f.provider).publication(Use.READ);
        assertEquals(503,assertThrows(Failure.class,()->f.service.definition(f.request,Use.READ,REQUEST,TRACE)).status());verify(f.connection).rollback();
    }
    @Test void outcomeBytesRemainPrivateAndWriteToDoesNotOwnCallerStream()throws Exception {
        var f=new Fixture();for(var outcome:List.of(f.service.schema(f.request,REQUEST,TRACE),f.service.definition(f.request,Use.CREATE,REQUEST,TRACE))){byte[] original=outcome.body();var count=new AtomicInteger();var flushed=new AtomicBoolean();var closed=new AtomicBoolean();var retained=new AtomicReference<byte[]>();
            outcome.writeTo(new java.io.OutputStream(){@Override public void write(int value){fail("expected bounded bulk output");}@Override public void write(byte[] bytes,int offset,int length){count.accumulateAndGet(length,Math::max);retained.set(bytes);Arrays.fill(bytes,(byte)0);}@Override public void flush(){flushed.set(true);}@Override public void close(){closed.set(true);}});
            assertTrue(count.get()<=8192);assertFalse(flushed.get());assertFalse(closed.get());Arrays.fill(retained.get(),(byte)1);assertArrayEquals(original,outcome.body());original[0]=0;assertEquals('{',outcome.body()[0]);
        }
    }
    @Test void nullUseAndNullResponseIdsAreRejectedBeforeBorrowingConnection()throws Exception {
        var f=new Fixture();assertThrows(NullPointerException.class,()->f.service.definition(f.request,null,REQUEST,TRACE));assertThrows(NullPointerException.class,()->f.service.schema(f.request,null,TRACE));assertThrows(NullPointerException.class,()->f.service.schema(f.request,REQUEST,null));verifyNoInteractions(f.source,f.authority,f.applications);verify(f.repository,never()).setTenant(any());
    }
    private static RiskScenarioMetadataService.MetadataOutcome metadata(Fixture f,int kind,UUID requestId,UUID trace){return kind==0?f.service.schema(f.request,requestId,trace):f.service.definition(f.request,kind==1?Use.READ:Use.CREATE,requestId,trace);}
    private static void assertIds(RiskScenarioMetadataService.MetadataOutcome result,UUID requestId,UUID trace)throws Exception{var output=new java.io.ByteArrayOutputStream();result.writeTo(output);byte[] bytes=output.toByteArray();assertEquals(result.size(),bytes.length);assertTrue(bytes.length<=ResponseBudget.METADATA.bytes());var meta=JSON.readTree(bytes).get("meta");assertNotEquals(requestId,trace);assertEquals(requestId.toString(),meta.get("requestId").stringValue());assertEquals(trace.toString(),meta.get("traceId").stringValue());assertArrayEquals(bytes,result.body());}
    private static void assertReadOwner(Fixture f){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isSynchronizationActive());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertTrue(Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()));assertSame(f.connection,((ConnectionHolder)TransactionSynchronizationManager.getResource(f.source)).getConnection());}
    private static final class Fixture {
        final AtomicReference<CurrentTenantContext> context=new AtomicReference<>(new CurrentTenantContext(ACTOR,ACCOUNT,TENANT,MEMBER,1,2,3));final AtomicReference<Set<UUID>> organizations=new AtomicReference<>(Set.of(ORG));final AtomicLong applicationVersion=new AtomicLong(7);final AtomicBoolean enabled=new AtomicBoolean(true);
        final RiskScenarioReadRepository repository=mock(RiskScenarioReadRepository.class);final CapturedHumanRequest request=mock(CapturedHumanRequest.class);final HumanSessionAuthorityTransaction authority=mock(HumanSessionAuthorityTransaction.class);final HumanSessionAuthorityTransaction.ReadBoundary boundary=mock(HumanSessionAuthorityTransaction.ReadBoundary.class);final EntityActionAuthorization actions=mock(EntityActionAuthorization.class);
        final TenantApplicationReadRepository applications=mock(TenantApplicationReadRepository.class);final DataSource source=mock(DataSource.class);final Connection connection=mock(Connection.class);final RiskScenarioDefinitionProvider provider=spy(new RiskScenarioDefinitionProvider());final EntityActionAuthorization.ActionScope read=scope(RISK_SCENARIO_READ),create=scope(RISK_SCENARIO_CREATE);
        final RiskScenarioReadService reads;final RiskScenarioMetadataService service;
        Fixture()throws Exception{
            when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);when(request.context()).thenAnswer(call->context.get());
            when(authority.withRead(eq(request),any())).thenAnswer(call->{assertReadOwner(this);return ((Function<HumanSessionAuthorityTransaction.ReadBoundary,?>)call.getArgument(1)).apply(boundary);});
            when(actions.requireRead(eq(request),eq(boundary),any())).thenAnswer(call->{assertReadOwner(this);return call.getArgument(2)==RISK_SCENARIO_READ?read:create;});
            when(applications.findCurrentInCaller(any(UUID.class),eq("safety"))).thenAnswer(call->{assertReadOwner(this);return Optional.of(new TenantApplicationState(enabled.get(),applicationVersion.get()));});
            reads=new RiskScenarioReadService(repository,authority,actions,new JdbcTransactionManager(source),source,applications);service=new RiskScenarioMetadataService(reads,provider);
        }
        private EntityActionAuthorization.ActionScope scope(RegisteredEntityAction action){var scope=mock(EntityActionAuthorization.ActionScope.class);when(scope.action()).thenReturn(action);when(scope.context()).thenAnswer(call->context.get());when(scope.organizationIds()).thenAnswer(call->organizations.get());return scope;}
    }
}
