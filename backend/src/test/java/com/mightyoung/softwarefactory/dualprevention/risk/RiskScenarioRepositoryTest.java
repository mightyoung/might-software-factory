package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.lang.reflect.Modifier;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioLockPlan.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Recording JDBC/authority fixtures only. No real SQL, authorization, concurrent PG, audit or publication proof. */
class RiskScenarioRepositoryTest {
    private static final UUID TENANT=id(1),SCENARIO=id(2),COMMAND=id(3),ACTOR=id(4),MEMBER=id(5),ACCOUNT=id(6),ORG=id(7);
    private static final Instant TIME=Instant.parse("2026-11-07T00:00:00Z");
    private final DataSource pool=mock(DataSource.class);
    private final Connection driver=mock(Connection.class);
    private final EntityActionAuthorization authority=mock(EntityActionAuthorization.class);
    private final RiskScenarioRepository repository=new RiskScenarioRepository(pool,authority);
    private final Map<RiskScenarioSql,Deque<List<Map<String,Object>>>> scripted=new EnumMap<>(RiskScenarioSql.class);
    private final Map<RiskScenarioSql,Integer> counts=new EnumMap<>(RiskScenarioSql.class);
    private final Map<RiskScenarioSql,SQLException> errors=new EnumMap<>(RiskScenarioSql.class);
    private final List<Call> calls=new ArrayList<>();
    private ConnectionHolder holder;
    private record Call(RiskScenarioSql sql,List<Object> bindings) {}
    @BeforeEach void bind()throws Exception {
        holder=new ConnectionHolder(driver);holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(pool,holder);TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(driver.prepareStatement(anyString())).thenAnswer(invocation->{
            String text=invocation.getArgument(0);var sql=Arrays.stream(RiskScenarioSql.values()).filter(s->s.sql.equals(text)).findFirst().orElseThrow();
            var statement=mock(PreparedStatement.class);var parameters=new TreeMap<Integer,Object>();
            doAnswer(i->{parameters.put(i.getArgument(0),i.getArgument(1));return null;}).when(statement).setObject(anyInt(),any());
            when(statement.executeUpdate()).thenAnswer(i->{calls.add(new Call(sql,List.copyOf(parameters.values())));
                if(errors.containsKey(sql))throw errors.get(sql);return counts.getOrDefault(sql,1);});
            when(statement.executeQuery()).thenAnswer(i->{calls.add(new Call(sql,List.copyOf(parameters.values())));
                if(errors.containsKey(sql))throw errors.get(sql);
                var queue=scripted.get(sql);return result(queue==null||queue.isEmpty()?List.of():queue.removeFirst());});
            return statement;
        });
    }
    @AfterEach void clear()throws Exception {
        TransactionSynchronizationManager.unbindResourceIfPossible(pool);TransactionSynchronizationManager.clear();verify(pool,never()).getConnection();
    }
    @Test void unboundExecutionNeverAcquiresAFallbackConnection(){
        TransactionSynchronizationManager.unbindResource(pool);
        assertThatThrownBy(()->repository.currentParent(scope(RISK_SCENARIO_UPDATE),SCENARIO)).isInstanceOf(RiskScenarioContracts.Failure.class);
        assertThat(calls).isEmpty();
    }
    @Test void aSameOrganizationScopeForAnotherEntityCannotReadRiskRowsOrHazards(){
        assertThatThrownBy(()->repository.currentParent(scope(WORKSHOP_UPDATE),SCENARIO)).isInstanceOf(RiskScenarioContracts.Failure.class);
        assertThat(holder.isRollbackOnly()).isTrue();assertThat(calls).isEmpty();
    }
    @Test void hazardReferencesRequireTheirOwnReadActionAndSqlScope()throws Exception {
        var scope=scope(HAZARD_SOURCE_READ);enqueue(RiskScenarioSql.HAZARD_REFERENCE,row("id",id(9),"organization_id",ORG,"version",3L,
                "code","H-1","description_sha256","a".repeat(64),"existence_state","UNKNOWN"));
        var value=repository.hazardReference(scope,id(9)).orElseThrow();assertThat(value.version()).isEqualTo(3);
        assertThat(calls).containsExactly(new Call(RiskScenarioSql.HAZARD_REFERENCE,List.of(TENANT,id(9),"{"+ORG+"}")));
        verify(authority,atLeast(2)).requireSqlConnection(scope,pool,driver);
    }
    @Test void riskParentActionCannotSubstituteForHazardRead(){
        assertThatThrownBy(()->repository.hazardReference(scope(RISK_SCENARIO_UPDATE),id(9))).isInstanceOf(RiskScenarioContracts.Failure.class);
        assertThat(holder.isRollbackOnly()).isTrue();assertThat(calls).isEmpty();
    }
    @Test void casZeroRowsPoisonsTheHolderAndCannotBeRetriedInTheSameCallback(){
        var scope=scope(RISK_SCENARIO_UPDATE);var input=new RiskScenarioContracts.Update(1,"d","t","e","c",List.of(id(9)));
        var statement=repository.updateRoot(scope,SCENARIO,COMMAND,input);counts.put(RiskScenarioSql.ROOT_UPDATE,0);
        assertThatThrownBy(()->repository.apply(scope,statement)).isInstanceOfSatisfying(RiskScenarioContracts.Failure.class,f->assertThat(f.status()).isEqualTo(409));
        assertThat(holder.isRollbackOnly()).isTrue();assertThat(calls).hasSize(1);
        assertThatThrownBy(()->repository.apply(scope,statement)).isInstanceOf(RiskScenarioContracts.Failure.class);
        assertThat(calls).hasSize(1);
    }
    @Test void actualSingleRowResultCarriesOnlyItsOwnFixedStatementObservation(){
        var scope=scope(RISK_SCENARIO_CREATE);var statement=repository.createRoot(scope,SCENARIO,COMMAND,
                new RiskScenarioContracts.Create("R-1",ORG,"d","t","e","c",List.of(id(9))));
        var result=repository.apply(scope,statement);assertThat(result.statement()).isSameAs(statement);assertThat(result.actualRows()).isOne();
        assertThat(calls).hasSize(1);assertThat(calls.getFirst().sql()).isEqualTo(RiskScenarioSql.ROOT_INSERT);
        assertThat(calls.getFirst().bindings()).contains(TIME.atOffset(ZoneOffset.UTC),ACTOR,MEMBER,TENANT);
        assertThat(holder.isRollbackOnly()).isFalse();verify(authority,atLeast(2)).requireSqlConnection(scope,pool,driver);
    }
    @Test void aStatementCannotBeTransferredToADifferentActorScope(){
        var first=scope(RISK_SCENARIO_UPDATE);var statement=repository.updateRoot(first,SCENARIO,COMMAND,new RiskScenarioContracts.Update(1,"d","t","e","c",List.of(id(9))));
        var other=scope(RISK_SCENARIO_UPDATE);when(other.context()).thenReturn(new CurrentTenantContext(id(99),ACCOUNT,TENANT,MEMBER,0,0,1));
        assertThatThrownBy(()->repository.apply(other,statement)).isInstanceOf(RiskScenarioContracts.Failure.class);
        assertThat(holder.isRollbackOnly()).isTrue();assertThat(calls).isEmpty();
    }
    @Test void databaseCanonicalMismatchRefusesBeforeMutationOrPhysicalRowLocks(){
        var scope=scope(RISK_SCENARIO_CREATE);var plan=new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,true,0,List.of(),List.of(id(9)),Map.of(id(9),id(10)));
        enqueue(RiskScenarioSql.NATURAL_LOCK,row());enqueue(RiskScenarioSql.MUTATION_PLAN,row("family",20,"canonical_bytes",new byte[]{1}));
        assertThatThrownBy(()->repository.lockAndVerify(scope,plan,null,"R-1")).isInstanceOf(RiskScenarioContracts.Failure.class);
        assertThat(calls).extracting(Call::sql).containsExactly(RiskScenarioSql.NATURAL_LOCK,RiskScenarioSql.MUTATION_PLAN);
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void createLocksOnlyExistingHazardsAfterOneCompleteMutationAcquisition(){
        var scope=scope(RISK_SCENARIO_CREATE);var plan=new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,true,0,List.of(),List.of(id(9)),Map.of(id(9),id(10)));
        scriptPlan(plan);enqueue(RiskScenarioSql.NATURAL_LOCK,row());enqueue(RiskScenarioSql.ROW_LOCK,row("held_version",3L));
        var locked=repository.lockAndVerify(scope,plan,null,"R-1");assertThat(locked.root()).isNull();assertThat(locked.hazardVersions()).containsExactlyEntriesOf(Map.of(id(9),3L));
        assertThat(calls).extracting(Call::sql).containsExactly(RiskScenarioSql.NATURAL_LOCK,RiskScenarioSql.MUTATION_PLAN,RiskScenarioSql.MUTATION_LOCK,RiskScenarioSql.ROW_LOCK);
        assertThat(calls.getLast().bindings()).containsExactly(60,TENANT,id(9));
    }
    @Test void sameCountChangedOldLinkIsRejectedAfterAllOrderedRowsWithoutAddingAnyLock(){
        var scope=scope(RISK_SCENARIO_UPDATE);var old=old(id(20),id(9));
        var plan=new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,false,1,List.of(old),List.of(id(9)),Map.of());
        scriptPlan(plan);enqueue(RiskScenarioSql.ROW_LOCK,row("held_version",3L));enqueue(RiskScenarioSql.ROW_LOCK,row("held_version",1L));enqueue(RiskScenarioSql.ROW_LOCK,row("held_version",1L));
        enqueue(RiskScenarioSql.ROOT_CURRENT,rootRow());enqueue(RiskScenarioSql.ACTIVE_LINKS,linkRow(old(id(21),id(9))));
        var discovery=new RiskScenarioRepository.Discovery(rootValue(),List.of(old));
        assertThatThrownBy(()->repository.lockAndVerify(scope,plan,discovery,null)).isInstanceOfSatisfying(RiskScenarioContracts.Failure.class,f->assertThat(f.status()).isEqualTo(409));
        assertThat(calls).extracting(Call::sql).containsExactly(RiskScenarioSql.MUTATION_PLAN,RiskScenarioSql.MUTATION_LOCK,
                RiskScenarioSql.ROW_LOCK,RiskScenarioSql.ROW_LOCK,RiskScenarioSql.ROW_LOCK,RiskScenarioSql.ROOT_CURRENT,RiskScenarioSql.ACTIVE_LINKS);
        assertThat(calls.stream().filter(c->c.sql()==RiskScenarioSql.ROW_LOCK).map(Call::bindings)).containsExactly(
                List.of(60,TENANT,id(9)),List.of(100,TENANT,SCENARIO),List.of(110,TENANT,id(20)));
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void changedParentVersionRefusesBeforeAnyLinkRowsAndDoesNotExpandThePlan(){
        var scope=scope(RISK_SCENARIO_UPDATE);var old=old(id(20),id(9));var plan=new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,false,1,List.of(old),List.of(id(9)),Map.of());
        scriptPlan(plan);enqueue(RiskScenarioSql.ROW_LOCK,row("held_version",3L));enqueue(RiskScenarioSql.ROW_LOCK,row("held_version",2L));
        assertThatThrownBy(()->repository.lockAndVerify(scope,plan,new RiskScenarioRepository.Discovery(rootValue(),List.of(old)),null)).isInstanceOf(RiskScenarioContracts.Failure.class);
        assertThat(calls).extracting(Call::sql).containsExactly(RiskScenarioSql.MUTATION_PLAN,RiskScenarioSql.MUTATION_LOCK,RiskScenarioSql.ROW_LOCK,RiskScenarioSql.ROW_LOCK);
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"23514","08006"})
    void sqlFailureIsMappedAndPoisonsWithoutAutomaticRetry(String state){
        var scope=scope(RISK_SCENARIO_UPDATE);var statement=repository.updateRoot(scope,SCENARIO,COMMAND,new RiskScenarioContracts.Update(1,"d","t","e","c",List.of(id(9))));
        errors.put(RiskScenarioSql.ROOT_UPDATE,new SQLException("private driver detail",state));
        assertThatThrownBy(()->repository.apply(scope,statement)).isInstanceOfSatisfying(RiskScenarioContracts.Failure.class,f->{
            assertThat(f.status()).isEqualTo(state.equals("23514")?409:503);assertThat(f.getMessage()).doesNotContain("private driver detail");});
        assertThat(calls).hasSize(1);assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void repositoryHasNoPublicConnectionOrTransactionSurface(){
        assertThat(Modifier.isPublic(RiskScenarioRepository.class.getModifiers())).isFalse();
        for(var method:RiskScenarioRepository.class.getDeclaredMethods())if(method.getReturnType()==Connection.class)
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
    }
    private void scriptPlan(RiskScenarioLockPlan plan){
        List<Map<String,Object>> rows=plan.mutationKeys.stream().map(k->row("family",k.family(),"canonical_bytes",k.canonicalBytes())).toList();
        scripted.computeIfAbsent(RiskScenarioSql.MUTATION_PLAN,k->new ArrayDeque<>()).add(rows);enqueue(RiskScenarioSql.MUTATION_LOCK,row());
    }
    private ActionScope scope(RegisteredEntityAction action){
        var scope=mock(ActionScope.class);when(scope.action()).thenReturn(action);when(scope.context()).thenReturn(new CurrentTenantContext(ACTOR,ACCOUNT,TENANT,MEMBER,0,0,1));
        when(scope.observedAt()).thenReturn(TIME);when(scope.organizationIds()).thenReturn(Set.of(ORG));return scope;
    }
    private static UUID id(long n){return new UUID(0,n);}
    private static ActiveLink old(UUID link,UUID hazard){return new ActiveLink(TENANT,link,SCENARIO,hazard,3,1,TIME,ACTOR,MEMBER,1,id(8),ACTOR,MEMBER,TIME);}
    private static RiskScenarioRepository.Root rootValue(){return new RiskScenarioRepository.Root(TENANT,SCENARIO,ORG,"R-1",1,"d","t","e","c",ACTOR,MEMBER,TIME,ACTOR,MEMBER,TIME,id(8));}
    private static Map<String,Object> rootRow(){return row("tenant_id",TENANT,"id",SCENARIO,"organization_id",ORG,"code","R-1","version",1L,
            "description","d","triggering_conditions","t","exposure_description","e","consequence","c",
            "created_by_actor_id",ACTOR,"created_by_member_id",MEMBER,"created_at",TIME.atOffset(ZoneOffset.UTC),
            "updated_by_actor_id",ACTOR,"updated_by_member_id",MEMBER,"updated_at",TIME.atOffset(ZoneOffset.UTC),"last_command_id",id(8));}
    private static Map<String,Object> linkRow(ActiveLink link){return row("tenant_id",link.tenantId(),"id",link.id(),"scenario_id",link.scenarioId(),"hazard_id",link.hazardId(),
            "hazard_version_at_link",link.hazardVersionAtLink(),"version",link.version(),"linked_at",link.linkedAt().atOffset(ZoneOffset.UTC),"created_by_actor_id",link.createdByActorId(),
            "created_by_member_id",link.createdByMemberId(),"last_scenario_version",link.lastScenarioVersion(),"last_command_id",link.lastCommandId(),"last_actor_id",link.lastActorId(),
            "last_member_id",link.lastMemberId(),"last_occurred_at",link.lastOccurredAt().atOffset(ZoneOffset.UTC));}
    @SafeVarargs private final void enqueue(RiskScenarioSql sql,Map<String,Object>... rows){scripted.computeIfAbsent(sql,k->new ArrayDeque<>()).add(List.of(rows));}
    private static Map<String,Object> row(Object... values){var result=new HashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static ResultSet result(List<Map<String,Object>> data)throws Exception {
        var result=mock(ResultSet.class);var index=new AtomicInteger(-1);when(result.next()).thenAnswer(i->index.incrementAndGet()<data.size());
        doAnswer(i->data.get(index.get()).get((String)i.getArgument(0))).when(result).getObject(anyString(),any(Class.class));
        when(result.getString(anyString())).thenAnswer(i->data.get(index.get()).get((String)i.getArgument(0)));
        when(result.getBytes(anyString())).thenAnswer(i->data.get(index.get()).get((String)i.getArgument(0)));
        return result;
    }
}
