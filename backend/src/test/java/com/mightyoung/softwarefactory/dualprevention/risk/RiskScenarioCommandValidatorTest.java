package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.auditops.BusinessAuditAppend;
import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceReadPort;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.support.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioServiceTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Issuer/lifetime and actual G014 refusal tests with synthetic scopes. Not persisted authority or PostgreSQL proof. */
class RiskScenarioCommandValidatorTest {
    @Test void fingerprintCanonicalizesHazardsButBindsAllFourNarrativesAndSubjectIds(){
        var c=new CurrentTenantContext(ACTOR,ACCOUNT,TENANT,MEMBER,0,0,1);
        var one=new RiskScenarioCommandValidator.Intent(RISK_SCENARIO_CREATE,null,input(List.of(id(21),id(20))),null);
        var ordered=new RiskScenarioCommandValidator.Intent(RISK_SCENARIO_CREATE,null,input(List.of(id(20),id(21))),null);
        assertThat(RiskScenarioCommandValidator.fingerprint(c,one)).isEqualTo(RiskScenarioCommandValidator.fingerprint(c,ordered));
        var changed=new RiskScenarioCommandValidator.Intent(RISK_SCENARIO_CREATE,null,new Create("R-1",ORG,"description","trigger","exposure","changed",List.of(id(20),id(21))),null);
        assertThat(RiskScenarioCommandValidator.fingerprint(c,one)).isNotEqualTo(RiskScenarioCommandValidator.fingerprint(c,changed));
        var other=new CurrentTenantContext(ACTOR,id(99),TENANT,MEMBER,0,0,1);
        assertThat(RiskScenarioCommandValidator.fingerprint(c,one)).isNotEqualTo(RiskScenarioCommandValidator.fingerprint(other,one));
        var generation=new CurrentTenantContext(ACTOR,ACCOUNT,TENANT,MEMBER,5,6,7);
        assertThat(RiskScenarioCommandValidator.fingerprint(c,one)).isEqualTo(RiskScenarioCommandValidator.fingerprint(generation,one));
    }
    @Test void typedFramingSeparatesAdjacentNarrativesAndUpdateTargetAndExpectedVersion(){
        var c=new CurrentTenantContext(ACTOR,ACCOUNT,TENANT,MEMBER,0,0,1);
        var left=new RiskScenarioCommandValidator.Intent(RISK_SCENARIO_CREATE,null,new Create("R-1",ORG,"ab","c","e","f",List.of(id(20))),null);
        var right=new RiskScenarioCommandValidator.Intent(RISK_SCENARIO_CREATE,null,new Create("R-1",ORG,"a","bc","e","f",List.of(id(20))),null);
        assertThat(RiskScenarioCommandValidator.fingerprint(c,left)).isNotEqualTo(RiskScenarioCommandValidator.fingerprint(c,right));
        var first=new RiskScenarioCommandValidator.Intent(RISK_SCENARIO_UPDATE,SCENARIO,null,new Update(1,"d","t","e","c",List.of(id(20))));
        var version=new RiskScenarioCommandValidator.Intent(RISK_SCENARIO_UPDATE,SCENARIO,null,new Update(2,"d","t","e","c",List.of(id(20))));
        var target=new RiskScenarioCommandValidator.Intent(RISK_SCENARIO_UPDATE,id(99),null,new Update(1,"d","t","e","c",List.of(id(20))));
        assertThat(RiskScenarioCommandValidator.fingerprint(c,first)).isNotEqualTo(RiskScenarioCommandValidator.fingerprint(c,version)).isNotEqualTo(RiskScenarioCommandValidator.fingerprint(c,target));
    }
    @Test void pinnedJacksonLongAndIntegerNodesRequireWireNormalization()throws Exception {
        var mapper=JsonMapper.builder().disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        var event=new RiskScenarioDraftChangedEvent(TENANT,id(11),ENTITY_KEY,SCENARIO,RISK_SCENARIO_CREATE.permissionCode(),1,CUT,TRACE,"CREATE");
        var rawExpected=mapper.valueToTree(event);String serialized=mapper.writeValueAsString(event);var decoded=mapper.readTree(serialized);
        assertThat(rawExpected.get("version").longValue()).isEqualTo(decoded.get("version").longValue());
        assertThat(rawExpected).as("Pinned Jackson keeps LongNode in valueToTree and IntNode in parsed JSON").isNotEqualTo(decoded);
        assertThat(mapper.readTree(mapper.writeValueAsString(event))).isEqualTo(decoded);
        assertThat(mapper.readValue(serialized,RiskScenarioDraftChangedEvent.class)).isEqualTo(event);
    }
    @Test void aListenerAliasIsRejectedBeforeAnyConnectionBorrowing()throws Exception {
        var f=new Fixture();
        assertThatThrownBy(()->new RiskScenarioCommandValidator(f.manager.commandDataSource(),f.repository,f.directory,f.audit,f.publisher,f.json,"asset-foundation-change-trace"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(f.pool,never()).getConnection();
    }
    @Test void wrongThreadInvalidatesTheInvocationForItsOriginalOwnerPermanently()throws Exception {
        var f=new Fixture();try(var invocation=f.validator.begin()){
            var error=new AtomicReference<Throwable>();var thread=new Thread(()->{try{invocation.call(()->1);}catch(Throwable failure){error.set(failure);}});
            thread.start();thread.join();assertThat(error.get()).isInstanceOf(Failure.class);
            assertThatThrownBy(()->invocation.call(()->1)).isInstanceOf(Failure.class);
        }
        verify(f.pool,never()).getConnection();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"requiredActions","handleCommandPlan"})
    void crossThreadIssuedPlanEntrypointsPermanentlyInvalidateTheOriginalTransaction(String entry)throws Exception {
        var f=new Fixture();try(var invocation=f.validator.begin()){
            assertThatThrownBy(()->f.transaction.execute(status->{
                var handle=f.replay(invocation);var plan=handle.commandPlan();f.gate.bindCommand(plan,f.write);
                var refusal=new AtomicReference<Throwable>();
                var thread=new Thread(()->{try{
                    if(entry.equals("requiredActions"))f.validator.requiredActions(plan);else handle.commandPlan();
                }catch(Throwable failure){refusal.set(failure);}});
                thread.start();try{thread.join();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
                assertThat(refusal.get()).isInstanceOf(Failure.class);
                f.holder().resetRollbackOnly();
                assertThatThrownBy(handle::commandPlan).isInstanceOf(Failure.class);
                assertThatThrownBy(()->f.validator.requiredActions(plan)).isInstanceOf(Failure.class);
                assertThatThrownBy(f.gate::finalizeCommand).isInstanceOf(RuntimeException.class);
                f.holder().resetRollbackOnly();return null;
            })).isInstanceOf(UnexpectedRollbackException.class);
        }
        verify(f.driver,never()).commit();verify(f.driver).rollback();
    }
    @Test void caughtStagingRefusalAndResetRollbackOnlyCannotResealOrCommit()throws Exception {
        var f=new Fixture();try(var invocation=f.validator.begin()){
            assertThatThrownBy(()->f.transaction.execute(status->{
                var handle=f.replay(invocation);var plan=handle.commandPlan();f.gate.bindCommand(plan,f.write);
                assertThatThrownBy(()->f.validator.requireStaged(plan,List.of())).isInstanceOf(Failure.class);
                f.holder().resetRollbackOnly();
                assertThatThrownBy(()->f.validator.requireOwnedPlan(plan)).isInstanceOf(Failure.class);
                assertThatThrownBy(f.gate::finalizeCommand).isInstanceOf(RuntimeException.class);
                f.holder().resetRollbackOnly();return null;
            })).isInstanceOf(UnexpectedRollbackException.class);
        }
        verify(f.driver,never()).commit();verify(f.driver).rollback();
    }
    @Test void foreignPlanAttemptInvalidatesTheIssuedPlanEvenWhenTheAttemptIsCaught()throws Exception {
        var f=new Fixture();try(var invocation=f.validator.begin()){
            assertThatThrownBy(()->f.transaction.execute(status->{
                var own=f.replay(invocation);f.gate.bindCommand(own.commandPlan(),f.write);
                var foreign=mock(TransactionSqlSealGate.CommandPlan.class);
                assertThatThrownBy(()->f.validator.requireOwnedPlan(foreign)).isInstanceOf(Failure.class);
                f.holder().resetRollbackOnly();assertThatThrownBy(own::commandPlan).isInstanceOf(Failure.class);
                assertThatThrownBy(f.gate::finalizeCommand).isInstanceOf(RuntimeException.class);f.holder().resetRollbackOnly();return null;
            })).isInstanceOf(UnexpectedRollbackException.class);
        }
        verify(f.driver,never()).commit();verify(f.driver).rollback();
    }
    @Test void closedPlanCannotBeReusedInAnotherInvocationOrTransaction()throws Exception {
        var f=new Fixture();var old=new AtomicReference<TransactionSqlSealGate.CommandPlan>();
        try(var invocation=f.validator.begin()){
            f.transaction.execute(status->{old.set(f.replay(invocation).commandPlan());status.setRollbackOnly();return null;});
        }
        try(var invocation=f.validator.begin()){
            f.transaction.execute(status->{assertThatThrownBy(()->f.validator.requireOwnedPlan(old.get())).isInstanceOf(Failure.class);
                f.holder().resetRollbackOnly();assertThatThrownBy(()->invocation.call(()->1)).isInstanceOf(Failure.class);status.setRollbackOnly();return null;});
        }
        verify(f.driver,never()).commit();verify(f.driver,times(2)).rollback();
    }
    @Test void replayRequiresOnlyCurrentParentActionWithoutObsoleteHazardRead()throws Exception {
        var f=new Fixture();try(var invocation=f.validator.begin()){
            f.transaction.execute(status->{var plan=f.replay(invocation).commandPlan();assertThat(f.validator.requiredActions(plan)).containsExactly(RISK_SCENARIO_CREATE);
                status.setRollbackOnly();return null;});
        }
    }
    private static final class Fixture {
        final DataSource pool=mock(DataSource.class);final Connection driver=mock(Connection.class);
        final RiskScenarioRepository repository=mock(RiskScenarioRepository.class);
        final DirectoryReferenceReadPort directory=mock(DirectoryReferenceReadPort.class);
        final BusinessAuditAppend audit=mock(BusinessAuditAppend.class);final RiskScenarioEventPublisher publisher=mock(RiskScenarioEventPublisher.class);
        final EntityActionAuthorization actions=mock(EntityActionAuthorization.class);final HumanSessionAuthorityTransaction authority=mock(HumanSessionAuthorityTransaction.class);
        final JsonMapper json=JsonMapper.builder().disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        final G014JdbcTransactionManager manager;final RiskScenarioCommandValidator validator;final TransactionSqlSealGate gate;final TransactionTemplate transaction;
        final CurrentTenantContext context=new CurrentTenantContext(ACTOR,ACCOUNT,TENANT,MEMBER,0,0,1);
        final CapturedHumanRequest request=mock(CapturedHumanRequest.class);
        final HumanSessionAuthorityTransaction.WriteScope write;
        Fixture()throws Exception {
            when(pool.getConnection()).thenReturn(driver);when(driver.getAutoCommit()).thenReturn(true);when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            manager=new G014JdbcTransactionManager(G014SqlProfile.RISK_SCENARIO,pool,RiskScenarioService.businessSqlTemplates());
            validator=new RiskScenarioCommandValidator(manager.commandDataSource(),repository,directory,audit,publisher,json,RiskScenarioService.REQUIRED_LISTENER_ID);
            gate=new TransactionSqlSealGate(G014SqlProfile.RISK_SCENARIO,manager,authority,actions,Map.of(RISK_SCENARIO_CREATE,validator,RISK_SCENARIO_UPDATE,validator));
            transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            when(request.context()).thenReturn(context);write=new HumanSessionAuthorityTransaction.WriteScope(request,HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND,List.of(TENANT));
        }
        ConnectionHolder holder(){return (ConnectionHolder)TransactionSynchronizationManager.getResource(manager.commandDataSource());}
        RiskScenarioCommandValidator.Handle replay(RiskScenarioCommandValidator.Invocation invocation){
            var intent=new RiskScenarioCommandValidator.Intent(RISK_SCENARIO_CREATE,null,input(List.of(id(20))),null);
            String fingerprint=RiskScenarioCommandValidator.fingerprint(context,intent);
            var receipt=new RiskScenarioRepository.Receipt(id(11),ACCOUNT,MEMBER,ACTOR,RISK_SCENARIO_CREATE.permissionCode(),fingerprint,SCENARIO,1,"R-1",CUT,id(12),TRACE);
            var root=new RiskScenarioRepository.Root(TENANT,SCENARIO,ORG,"R-1",5,"d","t","e","c",ACTOR,MEMBER,CUT,ACTOR,MEMBER,CUT,id(13));
            var scope=mock(ActionScope.class);when(scope.action()).thenReturn(RISK_SCENARIO_CREATE);when(scope.context()).thenReturn(context);when(scope.organizationIds()).thenReturn(Set.of(ORG));
            return validator.issueReplay(invocation,intent,context,KEY,fingerprint,REQUEST,TRACE,receipt,root,scope);
        }
    }
}
