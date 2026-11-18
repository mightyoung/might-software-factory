package com.mightyoung.softwarefactory.dualprevention.risk;

import java.sql.*;
import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskChangeCaptureServiceTest {
    @AfterEach void clear(){TransactionSynchronizationManager.clear();}
    @Test void realFactoryUsesOnePhysicalTransactionAndCurrentReadersAgainBeforeCommit()throws Exception {
        var f=new RiskChangeCaptureTestFixture();var service=f.service();verify(f.source,never()).getConnection();
        var result=service.capture(f.event());assertThat(result.tenantId()).isEqualTo(f.tenant);assertThat(result.commandId()).isEqualTo(f.command);
        assertThat(result.consumerVersion()).isEqualTo(1);assertThat(result.recordedAt()).isEqualTo(f.recorded);assertThat(result.recordedByServiceActorId()).isEqualTo(f.actor);assertThat(result.replay()).isFalse();
        assertThat(f.principalReads.get()).isEqualTo(2);assertThat(f.tenantReads.get()).isEqualTo(2);assertThat(f.appReads.get()).isEqualTo(2);
        assertThat(f.appBindings).allSatisfy(bindings->assertThat(bindings).containsExactly(f.tenant,"safety"));
        verify(f.source,times(1)).getConnection();assertThat(f.events).containsExactly("commit","close");assertThat(f.existing).isNotNull();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(f.sql).noneMatch(sql->sql.contains("g014r_validate_receipt")||sql.contains("from public.risk_scenario ")||sql.toLowerCase(Locale.ROOT).contains("description"));
    }
    @Test void duplicateDeliveryAndWorkerRotationKeepTheOriginalAttributionAndTime()throws Exception {
        var f=new RiskChangeCaptureTestFixture();var first=f.service().capture(f.event());f.currentActor=new UUID(88,88);
        var second=f.service().capture(f.event());assertThat(second.replay()).isTrue();assertThat(second.recordedByServiceActorId()).isEqualTo(first.recordedByServiceActorId());
        assertThat(second.recordedAt()).isEqualTo(first.recordedAt());assertThat(second.commandId()).isEqualTo(first.commandId());
        assertThat(f.sql.stream().filter(sql->sql.equals(RiskChangeCauseRepository.FIND_SQL.strip()))).hasSize(1);
        assertThat(f.sql).noneMatch(sql->sql.startsWith("update ")||sql.startsWith("delete "));assertThat(f.events).containsExactly("commit","close","commit","close");
    }
    @Test void factoryCopiesTheExactTenantDomainAndUnknownTenantsNeverBorrow()throws Exception {
        var f=new RiskChangeCaptureTestFixture();var domain=new HashSet<>(Set.of(f.tenant));var service=RiskChangeCaptureService.create(f.source,f.actor,domain);domain.clear();
        assertThat(service.acceptsTenant(f.tenant)).isTrue();assertThat(service.acceptsTenant(null)).isFalse();assertThat(service.acceptsTenant(new UUID(77,77))).isFalse();
        var event=new RiskScenarioDraftChangedEvent(new UUID(77,77),f.command,"risk_scenario",f.scenario,"risk_scenario.create",1,f.occurred,f.trace,"CREATE");
        refused(()->service.capture(event));verify(f.source,never()).getConnection();assertThat(service.capture(f.event()).replay()).isFalse();
    }
    @Test void missingFactoryInputsAndInvalidEventShapesCannotBecomeCaptureAuthority()throws Exception {
        var f=new RiskChangeCaptureTestFixture();refused(()->RiskChangeCaptureService.create(null,f.actor,Set.of(f.tenant)));
        refused(()->RiskChangeCaptureService.create(f.source,null,Set.of(f.tenant)));refused(()->RiskChangeCaptureService.create(f.source,f.actor,Set.of()));
        refused(()->RiskChangeCaptureService.create(f.source,f.actor,null));refused(()->f.service().capture(null));verify(f.source,never()).getConnection();
        assertThatThrownBy(()->new RiskScenarioDraftChangedEvent(f.tenant,f.command,"other",f.scenario,"risk_scenario.create",1,f.occurred,f.trace,"CREATE")).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"SERVICE_MISSING","SERVICE_DISABLED","ACTOR_MISMATCH","ACCOUNT_BOUND","NOT_SERVICE","TENANT_MISSING","TENANT_SUSPENDED","APP_MISSING","APP_DISABLED","APP_VERSION","NOT_PG"})
    void currentServiceTenantAndSafetyEligibilityDenyBeforeSourceOrInsertion(String fault)throws Exception {
        var f=new RiskChangeCaptureTestFixture();f.fault=fault;refused(()->f.service().capture(f.event()));
        assertThat(f.sql).doesNotContain(RiskChangeCauseRepository.SOURCE_SQL.strip(),RiskChangeCauseRepository.INSERT_SQL.strip());assertThat(f.existing).isNull();assertThat(f.events).containsExactly("rollback","close");
    }
    @ParameterizedTest @ValueSource(strings={"REVOKE_BEFORE_COMMIT","TENANT_EPOCH_DRIFT"})
    void beforeCommitRechecksCurrentEligibilityAfterTheBodyHasFinished(String fault)throws Exception {
        var f=new RiskChangeCaptureTestFixture();f.fault=fault;refused(()->f.service().capture(f.event()));
        assertThat(f.sql).contains(RiskChangeCauseRepository.INSERT_SQL.strip());assertThat(f.existing).isNull();assertThat(f.events).containsExactly("rollback","close");
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7,8,9,10,11,12,13})
    void everyPersistedEventFactMustMatchBothImmutableSourceRecords(int column)throws Exception {
        var f=new RiskChangeCaptureTestFixture();f.badSourceColumn=column-1;f.badSourceValue=switch(column){
            case 4,11->2L;case 5,12->f.occurred.plusSeconds(1).atOffset(ZoneOffset.UTC);case 6->"UPDATE";case 9->"risk_scenario.update";default->new UUID(99,99);};
        refused(()->f.service().capture(f.event()));assertThat(f.sql).doesNotContain(RiskChangeCauseRepository.INSERT_SQL.strip());assertThat(f.existing).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","DUPLICATE","SQL","NULL","TYPE","MICRO"})
    void missingDuplicateMalformedOrUnavailableSourceNeverProducesACause(String fault)throws Exception {
        var f=new RiskChangeCaptureTestFixture();switch(fault){case "MISSING"->f.sourceMissing=true;case "DUPLICATE"->f.sourceDuplicated=true;case "SQL"->f.fault="SOURCE_SQL";
            case "NULL"->{f.badSourceColumn=0;f.badSourceValue=null;}case "TYPE"->{f.badSourceColumn=3;f.badSourceValue=1;}case "MICRO"->{f.badSourceColumn=4;f.badSourceValue=f.occurred.plusNanos(1).atOffset(ZoneOffset.UTC);}}
        refused(()->f.service().capture(f.event()));assertThat(f.sql).doesNotContain(RiskChangeCauseRepository.INSERT_SQL.strip());assertThat(f.events).containsExactly("rollback","close");
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","DUPLICATE","VERSION","SHORT","LONG","VERSION_NULL","TIME_NULL","TIME_MICRO","ACTOR_NULL","TENANT","COMMAND","FIND_SQL"})
    void conflictReplayRequiresOneWellTypedImmutableCause(String fault)throws Exception {
        var f=new RiskChangeCaptureTestFixture();f.replay=true;f.existing=f.cause(new UUID(55,55),f.recorded.minusSeconds(10));
        switch(fault){case "MISSING"->f.existing=null;case "DUPLICATE"->f.fault="DUPLICATE_CAUSE";case "VERSION"->f.existing[2]=2;case "SHORT"->f.existing[2]=(short)1;
            case "LONG"->f.existing[2]=1L;case "VERSION_NULL"->f.existing[2]=null;case "TIME_NULL"->f.existing[3]=null;case "TIME_MICRO"->f.existing[3]=f.recorded.plusNanos(1).atOffset(ZoneOffset.UTC);
            case "ACTOR_NULL"->f.existing[4]=null;case "TENANT"->f.existing[0]=new UUID(66,66);case "COMMAND"->f.existing[1]=new UUID(67,67);case "FIND_SQL"->f.fault="FIND_SQL";}
        refused(()->f.service().capture(f.event()));assertThat(f.events).containsExactly("rollback","close");
    }
    @ParameterizedTest @ValueSource(strings={"INSERT_SQL","INSERT_ACTOR","INSERT_VERSION","COMMIT","ROLLBACK"})
    void storageAndCommitFailuresExposeOnlyTheFixedSafeFailure(String fault)throws Exception {
        var f=new RiskChangeCaptureTestFixture();f.fault=fault;refused(()->f.service().capture(f.event()));assertThat(f.events).contains("close");
        // A failed commit has an unknown durable outcome. This controlled fixture does not prove absence.
        if(!fault.equals("COMMIT"))assertThat(f.existing).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"SET_HOLDER","SCOPE_HOLDER_EXECUTE","SCOPE_HOLDER_NEXT","SCOPE_HOLDER_CLOSE"})
    void initializationOrGucDriftRejectBeforeSourceOrCauseSql(String fault)throws Exception {
        var f=new RiskChangeCaptureTestFixture();f.fault=fault;refused(()->f.service().capture(f.event()));
        assertThat(f.sql).doesNotContain(RiskChangeCauseRepository.SOURCE_SQL.strip(),RiskChangeCauseRepository.INSERT_SQL.strip());assertThat(f.existing).isNull();assertThat(f.events).containsExactly("rollback","close");
    }
    @ParameterizedTest @ValueSource(strings={"PRINCIPAL_INITIAL","TENANT_INITIAL","PRINCIPAL_COMMIT"})
    void eachEligibilityReadIsPinnedBeforeTheFollowingReaderCanExecute(String fault)throws Exception {
        var f=new RiskChangeCaptureTestFixture();var principalReads=new AtomicInteger();
        PrincipalStatusReadRepository principals=id->{int call=principalReads.incrementAndGet();if(fault.equals("PRINCIPAL_INITIAL")||fault.equals("PRINCIPAL_COMMIT")&&call==2)f.replaceHolder();return Optional.of(new PrincipalStatus(f.actor,null,"SERVICE","ACTIVE"));};
        var lifecycle=mock(TenantLifecycleReadRepository.class);when(lifecycle.findCurrentInCaller(f.tenant)).thenAnswer(call->{if(fault.equals("TENANT_INITIAL"))f.replaceHolder();return Optional.of(new TenantLifecycleState(f.tenant,"ACTIVE",0));});
        var app=mock(TenantApplicationReadRepository.class);when(app.findCurrentInCaller(f.tenant,"safety")).thenReturn(Optional.of(new TenantApplicationState(true,1)));
        var service=new RiskChangeCaptureService(f.source,f.actor,Set.of(f.tenant),new JdbcTransactionManager(f.source),mock(AuthorizationWriteGuard.class),principals,lifecycle,app);
        refused(()->service.capture(f.event()));assertThat(f.existing).isNull();assertThat(f.events).containsExactly("rollback","close");
        if(fault.equals("PRINCIPAL_INITIAL")){verifyNoInteractions(lifecycle,app);assertThat(f.sql).doesNotContain(RiskChangeCauseRepository.SOURCE_SQL.strip());}
        if(fault.equals("TENANT_INITIAL")){verify(lifecycle).findCurrentInCaller(f.tenant);verifyNoInteractions(app);}
        if(fault.equals("PRINCIPAL_COMMIT")){verify(lifecycle,times(1)).findCurrentInCaller(f.tenant);verify(app,times(1)).findCurrentInCaller(f.tenant,"safety");assertThat(f.sql).contains(RiskChangeCauseRepository.INSERT_SQL.strip());}
    }
    @Test void ambientTransactionSynchronizationAndForeignResourcesRejectWithoutBorrowing()throws Exception {
        for(String mode:List.of("actual","sync","foreign")){
            var f=new RiskChangeCaptureTestFixture();var service=f.service();try{
                if(mode.equals("actual"))TransactionSynchronizationManager.setActualTransactionActive(true);
                if(mode.equals("sync"))TransactionSynchronizationManager.initSynchronization();
                if(mode.equals("foreign"))TransactionSynchronizationManager.bindResource(new Object(),new ConnectionHolder(mock(Connection.class)));
                refused(()->service.capture(f.event()));verify(f.source,never()).getConnection();
            }finally{TransactionSynchronizationManager.clear();for(var key:new ArrayList<>(TransactionSynchronizationManager.getResourceMap().keySet()))TransactionSynchronizationManager.unbindResource(key);}
        }
    }
    static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Risk change capture is unavailable").hasNoCause().satisfies(error->assertThat(error.getSuppressed()).isEmpty());}
}
