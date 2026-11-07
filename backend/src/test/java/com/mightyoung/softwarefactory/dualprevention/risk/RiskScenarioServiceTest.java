package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.auditops.*;
import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceReadPort;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceContracts.Organization;
import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts.ExistenceState;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.support.*;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioLockPlan.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real G014 manager/gate/guard, recording JDBC and synthetic authority/repository/audit/publication owners.
 * This is not SQL, RLS, durable listener, actual opaque audit-owner, HUMAN or PostgreSQL acceptance. */
public class RiskScenarioServiceTest {
    static final UUID TENANT=id(1),ACTOR=id(2),ACCOUNT=id(3),MEMBER=id(4),ORG=id(5),SCENARIO=id(6),KEY=id(7),TRACE=id(8),REQUEST=id(9);
    static final Instant CUT=Instant.parse("2026-11-07T00:00:00.123456Z");
    static final String PUB_SQL="""
            INSERT INTO public.EVENT_PUBLICATION (ID, EVENT_TYPE, LISTENER_ID, PUBLICATION_DATE, SERIALIZED_EVENT)
            VALUES (?, ?, ?, ?, ?)
            """;
    static UUID id(long value){return new UUID(0,value);}
    static Create input(List<UUID> hazards){return new Create("R-1",ORG,"description","trigger","exposure","consequence",hazards);}
    @Test void createsOneClosedDraftAndExactlyOneActualAuditPublicationAndFlush()throws Exception {
        var f=new Fixture();var outcome=f.service.create(f.request,KEY,REQUEST,TRACE,input(List.of(id(20))));
        assertThat(outcome.replay()).isFalse();assertThat(outcome.result().version()).isOne();assertThat(outcome.result().occurredAt()).isEqualTo(CUT);
        var wire=f.json.readTree(new String(outcome.responseBody(),java.nio.charset.StandardCharsets.UTF_8));
        assertThat(wire.get("meta").get("requestId").stringValue()).isEqualTo(REQUEST.toString());
        assertThat(wire.get("meta").get("traceId").stringValue()).isEqualTo(TRACE.toString());
        assertThat(wire.get("data").get("id").stringValue()).isEqualTo(outcome.result().id().toString());
        assertThat(outcome.responseBody().length).isLessThanOrEqualTo(ResponseBudget.COMMAND.bytes());
        byte[] copy=outcome.responseBody();copy[0]=0;assertThat(outcome.responseBody()[0]).isEqualTo((byte)'{');
        assertThat(f.applied).extracting(s->s.sql()).containsExactly(RiskScenarioSql.ROOT_INSERT,RiskScenarioSql.ROOT_REVISION,
                RiskScenarioSql.LINK_NEW,RiskScenarioSql.LINK_REVISION,RiskScenarioSql.SNAPSHOT_INSERT,RiskScenarioSql.RECEIPT_INSERT);
        assertThat(f.auditWrites.get()).isOne();assertThat(f.publicationWrites.get()).isOne();assertThat(f.flushes.get()).isOne();
        assertThat(f.commits.get()).isOne();assertThat(f.rollbacks.get()).isZero();assertThat(f.links).hasSize(1);assertThat(f.snapshots).hasSize(1);
        assertThat(f.events).singleElement().satisfies(event->{assertThat(event.commandId()).isEqualTo(f.receipt.commandId());assertThat(event.resourceId()).isEqualTo(outcome.result().id());});
    }
    @Test void updateAuthorizesRemovedUnionMembersTwiceAndPreservesRetainedAtLink()throws Exception {
        var f=new Fixture();f.existing();var retained=f.links.getFirst();var removed=f.links.getLast();
        var outcome=f.service.update(f.request,KEY,REQUEST,TRACE,SCENARIO,new Update(2,"new d","new t","new e","new c",List.of(retained.hazardId(),id(22))));
        assertThat(outcome.result().version()).isEqualTo(3);assertThat(f.lockPlan.unionHazards).containsExactly(id(20),id(21),id(22));
        assertThat(f.links).anySatisfy(link->{assertThat(link.id()).isEqualTo(retained.id());assertThat(link.hazardVersionAtLink()).isEqualTo(1);assertThat(link.lastCommandId()).isEqualTo(retained.lastCommandId());});
        assertThat(f.links).noneMatch(l->l.id().equals(removed.id()));
        assertThat(f.snapshots).anySatisfy(s->{assertThat(s.hazardId()).isEqualTo(retained.hazardId());assertThat(s.hazardVersionAtLink()).isEqualTo(1);assertThat(s.hazardVersionAtScenarioRevision()).isEqualTo(4);});
        verify(f.repository,times(2)).hazardReference(any(),eq(removed.hazardId()));assertThat(f.commits.get()).isOne();
    }
    @Test void exactReplayIgnoresObsoleteReferenceEligibilityAndCurrentRootVersion()throws Exception {
        var f=new Fixture();f.replay(input(List.of(id(20))));f.hidden.add(id(20));
        var outcome=f.service.create(f.request,KEY,REQUEST,TRACE,input(List.of(id(20))));
        assertThat(outcome.replay()).isTrue();assertThat(outcome.result().version()).isOne();assertThat(f.root.version()).isEqualTo(5);
        verify(f.repository,never()).hazardReference(any(),any());verify(f.repository,never()).lockAndVerify(any(),any(),any(),any());
        assertThat(f.applied).isEmpty();assertThat(f.auditWrites.get()).isZero();assertThat(f.publicationWrites.get()).isZero();assertThat(f.flushes.get()).isOne();assertThat(f.commits.get()).isOne();
    }
    @Test void replayFingerprintConflictDoesNotRequalifyOrWriteReferences()throws Exception {
        var f=new Fixture();f.replay(input(List.of(id(20))));
        assertThatThrownBy(()->f.service.create(f.request,KEY,REQUEST,TRACE,input(List.of(id(21)))))
                .isInstanceOfSatisfying(Failure.class,error->assertThat(error.status()).isEqualTo(409));
        verify(f.repository,never()).hazardReference(any(),any());assertThat(f.applied).isEmpty();assertThat(f.commits.get()).isZero();assertThat(f.rollbacks.get()).isOne();
    }
    @Test void losingReadOfARemovedOldMemberRejectsBeforeAnyBusinessAuditOrPublication()throws Exception {
        var f=new Fixture();f.existing();f.hidden.add(id(21));
        assertThatThrownBy(()->f.service.update(f.request,KEY,REQUEST,TRACE,SCENARIO,new Update(2,"d","t","e","c",List.of(id(20)))))
                .isInstanceOfSatisfying(Failure.class,error->assertThat(error.status()).isEqualTo(404));
        assertThat(f.lockPlan.unionHazards).containsExactly(id(20),id(21));assertThat(f.applied).isEmpty();assertThat(f.auditWrites.get()).isZero();assertThat(f.publicationWrites.get()).isZero();assertThat(f.rollbacks.get()).isOne();
    }
    @Test void publisherCannotSwallowReentrantFailureResetHolderAndRecoverTheInvocation()throws Exception {
        var f=new Fixture();f.publicationHook=event->{
            assertThatThrownBy(()->f.service.create(f.request,id(99),REQUEST,TRACE,input(List.of(id(20))))).isInstanceOf(Failure.class);
            ((ConnectionHolder)TransactionSynchronizationManager.getResource(f.manager.commandDataSource())).resetRollbackOnly();
        };
        assertThatThrownBy(()->f.service.create(f.request,KEY,REQUEST,TRACE,input(List.of(id(20))))).isInstanceOf(RuntimeException.class);
        assertThat(f.commits.get()).isZero();assertThat(f.rollbacks.get()).isOne();assertThat(f.flushes.get()).isZero();
    }
    @Test void caughtFinalCallbackFailureAndHolderResetStillPreventSealAndCommit()throws Exception {
        var f=new Fixture();f.finalHook=()->{
            assertThatThrownBy(()->f.service.create(f.request,id(99),REQUEST,TRACE,input(List.of(id(20))))).isInstanceOf(Failure.class);
            ((ConnectionHolder)TransactionSynchronizationManager.getResource(f.manager.commandDataSource())).resetRollbackOnly();
        };
        assertThatThrownBy(()->f.service.create(f.request,KEY,REQUEST,TRACE,input(List.of(id(20))))).isInstanceOf(RuntimeException.class);
        assertThat(f.flushes.get()).isOne();assertThat(f.commits.get()).isZero();assertThat(f.rollbacks.get()).isOne();
    }
    @Test void extraSuccessfulEffectFromPublisherIsRejectedRatherThanSilentlyCommitted()throws Exception {
        var f=new Fixture();f.duplicatePublication=true;
        assertThatThrownBy(()->f.service.create(f.request,KEY,REQUEST,TRACE,input(List.of(id(20))))).isInstanceOf(RuntimeException.class);
        assertThat(f.publicationWrites.get()).isEqualTo(2);assertThat(f.commits.get()).isZero();assertThat(f.rollbacks.get()).isOne();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"duplicateVersion","unknownField","floatVersion","stringVersion","wrongResourceId"})
    void actualPublicationWithTamperedJsonCannotSealOrCommit(String variant)throws Exception {
        var f=new Fixture();f.publicationJsonTransform=wire->switch(variant){
            case "duplicateVersion"->wire.replace("\"version\":1","\"version\":1,\"version\":1");
            case "unknownField"->wire.substring(0,wire.length()-1)+",\"unexpected\":\"x\"}";
            case "floatVersion"->wire.replace("\"version\":1","\"version\":1.0");
            case "stringVersion"->wire.replace("\"version\":1","\"version\":\"1\"");
            case "wrongResourceId"->wire.replace("\"resourceId\":\""+f.root.id()+"\"","\"resourceId\":\""+id(999)+"\"");
            default->throw new AssertionError("Unknown synthetic mutation");
        };
        assertThatThrownBy(()->f.service.create(f.request,KEY,REQUEST,TRACE,input(List.of(id(20))))).isInstanceOf(RuntimeException.class);
        assertThat(f.auditWrites.get()).isOne();assertThat(f.publicationWrites.get()).isOne();assertThat(f.flushes.get()).isOne();
        assertThat(f.commits.get()).isZero();assertThat(f.rollbacks.get()).isOne();
    }
    @Test void configurationBeforeFixedGateIsRejectedWithoutBorrowing() {
        var pool=mock(DataSource.class);var templates=new ArrayList<>(RiskScenarioService.businessSqlTemplates());
        templates.add(RiskScenarioService.auditSqlTemplate());
        templates.add(new TransactionSqlSealGate.SqlTemplate(PUB_SQL,5,false,RiskScenarioService.PUBLICATION_EFFECT,null,G014SqlProfile.RISK_SCENARIO));
        var manager=new G014JdbcTransactionManager(G014SqlProfile.RISK_SCENARIO,pool,templates);
        assertThatThrownBy(()->manager.configureRiskPublication(pool,event->{throw new AssertionError("must not publish");}))
                .hasMessage("G014 publication configuration is unavailable");
        verifyNoInteractions(pool);
    }
    public static final class Fixture {
        final DataSource pool=mock(DataSource.class);final Connection driver=mock(Connection.class);
        final CurrentTenantContext context=new CurrentTenantContext(ACTOR,ACCOUNT,TENANT,MEMBER,0,0,1);
        final CapturedHumanRequest request=mock(CapturedHumanRequest.class);
        final HumanSessionAuthorityTransaction authority=mock(HumanSessionAuthorityTransaction.class);
        final EntityActionAuthorization actions=mock(EntityActionAuthorization.class);
        final AuthorizationWriteGuard writeGuard=mock(AuthorizationWriteGuard.class);
        final DirectoryReferenceReadPort directory=mock(DirectoryReferenceReadPort.class);
        final BusinessAuditAppend audit=mock(BusinessAuditAppend.class);
        final JsonMapper json=JsonMapper.builder().disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        final AtomicInteger commits=new AtomicInteger(),rollbacks=new AtomicInteger(),flushes=new AtomicInteger(),auditWrites=new AtomicInteger(),publicationWrites=new AtomicInteger(),boundaries=new AtomicInteger();
        final List<RiskScenarioRepository.Statement> applied=new ArrayList<>();final List<RiskScenarioDraftChangedEvent> events=new ArrayList<>();
        final List<ActiveLink> links=new ArrayList<>();final List<RiskScenarioRepository.SnapshotIndex> snapshots=new ArrayList<>();
        final Set<UUID> hidden=new HashSet<>();final Map<UUID,RiskScenarioRepository.HazardReference> hazards=new HashMap<>();
        final G014JdbcTransactionManager manager;final RiskScenarioRepository repository;final RiskScenarioService service;
        RiskScenarioRepository.Root root;RiskScenarioRepository.Receipt receipt;RiskScenarioLockPlan lockPlan;
        Consumer<RiskScenarioDraftChangedEvent> publicationHook;Consumer<Object> bridgeEntryHook;Runnable bridgeExitHook;Runnable finalHook;boolean duplicatePublication,publicationExecutedByHook;
        Function<String,String> publicationJsonTransform=Function.identity();
        private SQLWarning publicationWarning;
        private Throwable publicationWarningFailure;
        private final AtomicInteger publicationWarningReads=new AtomicInteger();
        private final String publicationSql;
        public Fixture()throws Exception { this(true,true); }
        public Fixture(boolean configureBridge)throws Exception { this(configureBridge,true); }
        public Fixture(boolean configureBridge,boolean qualified)throws Exception {
            publicationSql=qualified?PUB_SQL:PUB_SQL.replace("public.EVENT_PUBLICATION","EVENT_PUBLICATION");
            var templates=new ArrayList<>(RiskScenarioService.businessSqlTemplates());templates.add(RiskScenarioService.auditSqlTemplate());
            templates.add(new TransactionSqlSealGate.SqlTemplate(publicationSql,5,false,RiskScenarioService.PUBLICATION_EFFECT,null,G014SqlProfile.RISK_SCENARIO));
            manager=new G014JdbcTransactionManager(G014SqlProfile.RISK_SCENARIO,pool,templates);
            when(pool.getConnection()).thenReturn(driver);var auto=new AtomicBoolean(true);when(driver.getAutoCommit()).thenAnswer(i->auto.get());
            doAnswer(i->{auto.set(i.getArgument(0));return null;}).when(driver).setAutoCommit(anyBoolean());
            when(driver.isReadOnly()).thenReturn(false);when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            doAnswer(i->{commits.incrementAndGet();return null;}).when(driver).commit();doAnswer(i->{rollbacks.incrementAndGet();return null;}).when(driver).rollback();
            when(driver.prepareStatement(anyString())).thenAnswer(invocation->{String sql=invocation.getArgument(0);var statement=mock(PreparedStatement.class);
                when(statement.executeUpdate()).thenAnswer(i->{if(sql.equals(JooqBusinessAuditAppend.commandInsertSql()))auditWrites.incrementAndGet();if(sql.equals(publicationSql))publicationWrites.incrementAndGet();return 1;});
                when(statement.getWarnings()).thenAnswer(i->{
                    if(!sql.equals(publicationSql))return null;
                    publicationWarningReads.incrementAndGet();if(publicationWarningFailure!=null)throw publicationWarningFailure;return publicationWarning;
                });
                when(statement.execute()).thenAnswer(i->{flushes.incrementAndGet();return false;});when(statement.getLargeUpdateCount()).thenReturn(0L);return statement;});
            when(request.context()).thenReturn(context);
            when(authority.withGuarded(any(),any())).thenAnswer(invocation->{
                int boundaryNumber=boundaries.incrementAndGet();var active=new AtomicBoolean(true);var boundary=mock(HumanSessionAuthorityTransaction.GuardedBoundary.class);
                var scopes=mock(EntityActionAuthorization.FinalActionScopes.class);
                when(actions.requireFinalCommand(eq(invocation.getArgument(0)),eq(boundary),any())).thenAnswer(call->{
                    Set<RegisteredEntityAction> required=call.getArgument(2);var issued=new EnumMap<RegisteredEntityAction,ActionScope>(RegisteredEntityAction.class);
                    for(var action:required){var scope=mock(ActionScope.class);
                        when(scope.context()).thenAnswer(i->{if(!active.get())throw new Failure(503);return context;});
                        when(scope.action()).thenAnswer(i->{if(!active.get())throw new Failure(503);return action;});
                        when(scope.observedAt()).thenAnswer(i->{if(!active.get())throw new Failure(503);return CUT;});
                        when(scope.organizationIds()).thenAnswer(i->{if(!active.get())throw new Failure(503);return Set.of(ORG);});issued.put(action,scope);}
                    when(scopes.require(any())).thenAnswer(i->{var value=issued.get(i.getArgument(0));if(value==null||!active.get())throw new Failure(503);return value;});when(scopes.observedAt()).thenReturn(CUT);return scopes;
                });
                try{Function<HumanSessionAuthorityTransaction.GuardedBoundary,Object> work=invocation.getArgument(1);Object result=work.apply(boundary);
                    var connection=boundConnection();connection.getAutoCommit();connection.isReadOnly();connection.getTransactionIsolation();return result;
                }finally{active.set(false);}
            });
            var real=new RiskScenarioRepository(manager.commandDataSource(),actions);
            repository=mock(RiskScenarioRepository.class,invocation->{String name=invocation.getMethod().getName();
                switch(name){
                    case "setTenant","lockReceipt":return null;
                    case "receipt":return Optional.ofNullable(receipt);
                    case "currentParent":if(boundaries.get()==4&&finalHook!=null){var work=finalHook;finalHook=null;work.run();}return Optional.ofNullable(root);
                    case "discover":return new RiskScenarioRepository.Discovery(root,List.copyOf(links));
                    case "lockAndVerify":lockPlan=invocation.getArgument(1);var versions=new HashMap<UUID,Long>();for(UUID id:lockPlan.unionHazards)versions.put(id,hazards.get(id).version());return new RiskScenarioRepository.Locked(lockPlan.create?null:root,versions);
                    case "hazardReference":UUID hazard=invocation.getArgument(1);return hidden.contains(hazard)?Optional.empty():Optional.ofNullable(hazards.get(hazard));
                    case "activeLinks":return canonicalOld(TENANT,root.id(),links);
                    case "snapshotIndex":return snapshots.stream().sorted(Comparator.comparing(RiskScenarioRepository.SnapshotIndex::hazardId,RiskScenarioContracts::compareUuid)).toList();
                    case "apply":var statement=(RiskScenarioRepository.Statement)invocation.getArgument(1);var result=real.apply(invocation.getArgument(0),statement);applied.add(statement);applyState(statement);return result;
                    default:try{return invocation.getMethod().invoke(real,invocation.getArguments());}catch(InvocationTargetException error){throw error.getCause();}
                }
            });
            when(directory.findOrganizationAt(any(),any(),any())).thenReturn(Optional.of(mock(Organization.class)));
            var receipts=new IdentityHashMap<BusinessAuditAppend.AuditAppendReceipt,Map.Entry<ActionScope,BusinessAuditRecord>>();
            when(audit.appendSuccess(any(),any())).thenAnswer(invocation->{ActionScope scope=invocation.getArgument(0);BusinessAuditRecord record=invocation.getArgument(1);
                String categories=record.changedFields().stream().sorted().map(x->"\""+x.name()+"\"").collect(java.util.stream.Collectors.joining(",","[","]"));
                jdbc(JooqBusinessAuditAppend.commandInsertSql(),List.of(TENANT,record.auditId(),ACTOR,scope.action().permissionCode(),ENTITY_KEY,record.resourceId(),record.commandId().toString(),record.changeKind().name(),categories,record.traceId().toString(),scope.observedAt().atOffset(ZoneOffset.UTC)));
                var receipt=mock(BusinessAuditAppend.AuditAppendReceipt.class);receipts.put(receipt,Map.entry(scope,record));return receipt;});
            when(audit.requireMatches(any(),any(),any())).thenAnswer(invocation->{var pair=receipts.get(invocation.getArgument(0));if(pair==null||pair.getKey()!=invocation.getArgument(1)||!pair.getValue().equals(invocation.getArgument(2)))throw new Failure(503);return pair.getValue().auditId();});
            var fixedPublisher=new ModulithRiskScenarioEventPublisher(manager);
            RiskScenarioEventPublisher publisher=event->{if(bridgeEntryHook!=null)bridgeEntryHook.accept(event);fixedPublisher.publish(event);if(bridgeExitHook!=null)bridgeExitHook.run();};
            service=new RiskScenarioService(manager,repository,actions,authority,writeGuard,directory,audit,publisher,json,RiskScenarioService.REQUIRED_LISTENER_ID);
            if(configureBridge)configurePublication(pool);
            for(long h=20;h<=22;h++)hazards.put(id(h),new RiskScenarioRepository.HazardReference(id(h),ORG,4,"H-"+h,"a".repeat(64),ExistenceState.UNKNOWN));
        }
        /** Test-only harness over the actual service/validator/gate; not a native/PG acceptance fixture. */
        public G014JdbcTransactionManager manager(){return manager;}
        public DataSource publicationAlias(){return configuredPublicationAlias;}
        private DataSource configuredPublicationAlias;
        public void configurePublication(DataSource alias){
            manager.configureRiskPublication(alias,published->{
                var event=(RiskScenarioDraftChangedEvent)published;events.add(event);
                if(publicationHook!=null)publicationHook.accept(event);
                if(!publicationExecutedByHook)publish(event);if(duplicatePublication)publish(event);
            });
            configuredPublicationAlias=alias;
        }
        public Connection guardedConnection(){return boundConnection();}
        public void onPublication(Consumer<Object> hook){publicationHook=hook::accept;}
        public void beforePublication(Consumer<Object> hook){bridgeEntryHook=hook;}
        public void afterPublication(Runnable hook){bridgeExitHook=hook;}
        public void executePublicationWith(Consumer<Object> executor){publicationHook=executor::accept;publicationExecutedByHook=true;}
        public void executeCreate(){service.create(request,KEY,REQUEST,TRACE,input(List.of(id(20))));}
        public int commits(){return commits.get();}
        public int rollbacks(){return rollbacks.get();}
        public int publicationWrites(){return publicationWrites.get();}
        public String serializeEvent(Object event){return json.writeValueAsString(event);}
        public long physicalCalls(String method){return mockingDetails(driver).getInvocations().stream().filter(call->call.getMethod().getName().equals(method)).count();}
        public void warning(SQLWarning warning){publicationWarning=warning;}
        public void warningFailure(Throwable failure){publicationWarningFailure=failure;}
        public int warningReads(){return publicationWarningReads.get();}
        public long poolBorrows(){return mockingDetails(pool).getInvocations().stream().filter(call->call.getMethod().getName().equals("getConnection")).count();}
        Connection boundConnection(){return ((ConnectionHolder)TransactionSynchronizationManager.getResource(manager.commandDataSource())).getConnection();}
        void jdbc(String sql,List<?> values)throws Exception {try(var query=boundConnection().prepareStatement(sql)){for(int i=0;i<values.size();i++)query.setObject(i+1,values.get(i));query.executeUpdate();}}
        void publish(RiskScenarioDraftChangedEvent event){try(var query=boundConnection().prepareStatement(publicationSql)){
            query.setObject(1,UUID.randomUUID());query.setString(2,RiskScenarioDraftChangedEvent.class.getName());query.setString(3,RiskScenarioService.REQUIRED_LISTENER_ID);
            query.setTimestamp(4,Timestamp.from(CUT));query.setString(5,publicationJsonTransform.apply(json.writeValueAsString(event)));query.executeUpdate();
        }catch(Exception failure){throw new IllegalStateException(failure);}}
        void existing(){root=new RiskScenarioRepository.Root(TENANT,SCENARIO,ORG,"R-1",2,"old d","old t","old e","old c",ACTOR,MEMBER,CUT.minusSeconds(120),ACTOR,MEMBER,CUT.minusSeconds(30),id(10));
            for(long h=20;h<=21;h++)links.add(new ActiveLink(TENANT,id(h+10),SCENARIO,id(h),1,1,CUT.minusSeconds(60),ACTOR,MEMBER,1,id(11),ACTOR,MEMBER,CUT.minusSeconds(60)));}
        void replay(Create input){root=new RiskScenarioRepository.Root(TENANT,SCENARIO,ORG,"R-1",5,"new","t","e","c",ACTOR,MEMBER,CUT.minusSeconds(120),ACTOR,MEMBER,CUT,id(10));
            var intent=new RiskScenarioCommandValidator.Intent(RISK_SCENARIO_CREATE,null,input,null);receipt=new RiskScenarioRepository.Receipt(id(11),ACCOUNT,MEMBER,ACTOR,RISK_SCENARIO_CREATE.permissionCode(),RiskScenarioCommandValidator.fingerprint(context,intent),SCENARIO,1,"R-1",CUT.minusSeconds(100),id(12),id(13));}
        void applyState(RiskScenarioRepository.Statement statement){var b=statement.bindings();switch(statement.sql()){
            case ROOT_INSERT-> {root=new RiskScenarioRepository.Root((UUID)b.get(0),(UUID)b.get(1),(UUID)b.get(2),(String)b.get(3),1,(String)b.get(4),(String)b.get(5),(String)b.get(6),(String)b.get(7),(UUID)b.get(8),(UUID)b.get(9),(Instant)b.get(10),(UUID)b.get(8),(UUID)b.get(9),(Instant)b.get(10),(UUID)b.get(11));snapshots.clear();}
            case ROOT_UPDATE-> {root=new RiskScenarioRepository.Root(root.tenantId(),root.id(),root.organizationId(),root.code(),(Long)b.get(4),(String)b.get(0),(String)b.get(1),(String)b.get(2),(String)b.get(3),root.createdByActorId(),root.createdByMemberId(),root.createdAt(),(UUID)b.get(5),(UUID)b.get(6),(Instant)b.get(7),(UUID)b.get(8));snapshots.clear();}
            case LINK_NEW->links.add(new ActiveLink((UUID)b.get(0),(UUID)b.get(8),(UUID)b.get(1),(UUID)b.get(9),(Long)b.get(10),1,(Instant)b.get(6),(UUID)b.get(4),(UUID)b.get(5),(Long)b.get(2),(UUID)b.get(3),(UUID)b.get(4),(UUID)b.get(5),(Instant)b.get(6)));
            case LINK_END->links.removeIf(link->link.id().equals(b.get(8)));
            case SNAPSHOT_INSERT->{var ref=hazards.get((UUID)b.get(5));snapshots.add(new RiskScenarioRepository.SnapshotIndex((UUID)b.get(5),(UUID)b.get(4),1,(Long)b.get(6),(Long)b.get(7),ref.code(),ref.descriptionSha256(),ref.existence(),(UUID)b.get(3),root.updatedByActorId(),root.updatedByMemberId(),root.updatedAt()));}
            case RECEIPT_INSERT->receipt=new RiskScenarioRepository.Receipt((UUID)b.get(1),(UUID)b.get(3),(UUID)b.get(4),(UUID)b.get(2),(String)b.get(5),(String)b.get(7),(UUID)b.get(8),(Long)b.get(9),(String)b.get(10),(Instant)b.get(11),(UUID)b.get(12),(UUID)b.get(13));
            default->{ }
        }}
    }
}
