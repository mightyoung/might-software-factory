package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real JDBC transaction owner with synthetic mocked authority; not persisted HUMAN acceptance. */
class AssetFoundationReadServiceTest {
    @Test void allSixDetailsUseTheirOwnFixedReadActionAndPreserveRepositoryResults()throws Exception {
        var f=new Fixture();UUID id=UUID.randomUUID();
        var workshop=new AssetContracts.Workshop(id,f.context.tenantId(),1,"W","Workshop",null,AssetContracts.Lifecycle.ACTIVE,null,null);
        when(f.repository.workshop(f.scope,id)).thenReturn(workshop);
        assertSame(workshop,f.service.workshop(f.request,id));
        f.service.productionLine(f.request,id);f.service.processStage(f.request,id);
        f.service.equipment(f.request,id);f.service.workUnit(f.request,id);f.service.hazard(f.request,id);
        for(var action:new RegisteredEntityAction[]{RegisteredEntityAction.WORKSHOP_READ,RegisteredEntityAction.PRODUCTION_LINE_READ,RegisteredEntityAction.PROCESS_STAGE_READ,RegisteredEntityAction.EQUIPMENT_READ,RegisteredEntityAction.WORK_UNIT_READ,RegisteredEntityAction.HAZARD_SOURCE_READ})verify(f.actions).requireRead(f.request,f.boundary,action);
        verify(f.repository).productionLine(f.scope,id);verify(f.repository).processStage(f.scope,id);
        verify(f.repository).equipment(f.scope,id);verify(f.repository).workUnit(f.scope,id);verify(f.repository).hazard(f.scope,id);
        verify(f.authority,times(6)).withRead(eq(f.request),any());
        verify(f.source,times(6)).getConnection();
    }
    @Test void snapshotUsesOneBoundaryHolderAndCallerApplicationVersionAndClosesAtReturn()throws Exception {
        var f=new Fixture();var captured=new AtomicReference<AssetFoundationReadService.ReadSnapshot>();
        when(f.applications.findCurrentInCaller(f.context.tenantId(),"dual-prevention")).thenReturn(Optional.of(new TenantApplicationState(true,7)));
        long version=f.service.inReadSnapshot(f.request,snapshot->{
            captured.set(snapshot);
            assertSame(f.connection,((ConnectionHolder)TransactionSynchronizationManager.getResource(f.source)).getConnection());
            var read=snapshot.authorize(RegisteredEntityAction.WORKSHOP_READ);
            snapshot.authorize(RegisteredEntityAction.WORKSHOP_CREATE);
            snapshot.authorize(RegisteredEntityAction.LINE_WORKSHOP_BIND);
            return snapshot.applicationVersion(read);
        });
        assertEquals(7,version);assertEquals(503,assertThrows(AssetFoundationFailure.class,captured.get()::requireActive).status());
        verify(f.authority).withRead(eq(f.request),any());verify(f.source).getConnection();
        verify(f.applications,never()).findCurrent(any(),any());
    }
    @Test void snapshotUsesTheCanonicalSafetyApplicationEnabledByAuthorization()throws Exception {
        var f=new Fixture();
        when(f.applications.findCurrentInCaller(f.context.tenantId(),"safety"))
                .thenReturn(Optional.of(new TenantApplicationState(true,11)));
        long version=assertDoesNotThrow(()->f.service.inReadSnapshot(f.request,snapshot->{
            var scope=snapshot.authorize(RegisteredEntityAction.WORKSHOP_READ);
            return snapshot.applicationVersion(scope);
        }),"The same enabled safety application used by authorization must permit the read snapshot");
        assertEquals(11,version);
        verify(f.applications).findCurrentInCaller(f.context.tenantId(),"safety");
        verify(f.applications,never()).findCurrentInCaller(any(),eq("dual-prevention"));
    }
    @Test void rejectsAmbientTransactionBeforeRequestOrSql()throws Exception {
        var f=new Fixture();TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertEquals(503,assertThrows(AssetFoundationFailure.class,()->f.service.workshop(f.request,UUID.randomUUID())).status()); }
        finally { TransactionSynchronizationManager.clear(); }
        verifyNoInteractions(f.repository,f.source,f.authority);
    }
    @Test void caughtWrongThreadReplacedHolderAndForeignScopeCannotResumeTheSnapshot()throws Exception {
        for(int kind=0;kind<3;kind++) {
            var f=new Fixture();int fault=kind;
            assertThrows(RuntimeException.class,()->f.service.inReadSnapshot(f.request,snapshot->{
                if(fault==0) {
                    var error=new AtomicReference<Throwable>();
                    Thread thread=new Thread(()->{try {snapshot.requireActive();}catch(Throwable e){error.set(e);}});
                    thread.start();try {thread.join();}catch(InterruptedException e){throw new AssertionError(e);}
                    assertInstanceOf(AssetFoundationFailure.class,error.get());
                } else if(fault==1) {
                    Object original=TransactionSynchronizationManager.unbindResource(f.source);
                    TransactionSynchronizationManager.bindResource(f.source,new ConnectionHolder(f.connection));
                    try {assertThrows(AssetFoundationFailure.class,snapshot::requireActive);}
                    finally {TransactionSynchronizationManager.unbindResource(f.source);TransactionSynchronizationManager.bindResource(f.source,original);}
                } else assertThrows(AssetFoundationFailure.class,()->snapshot.applicationVersion(mock(EntityActionAuthorization.ActionScope.class)));
                assertThrows(AssetFoundationFailure.class,snapshot::requireActive);
                assertTrue(((ConnectionHolder)TransactionSynchronizationManager.getResource(f.source)).isRollbackOnly());
                return null;
            }));
            verifyNoInteractions(f.applications);
            verify(f.connection).rollback();
        }
    }
    @Test void exceptionClosesSnapshotAndPostValidationRejectsPoisonedHolder()throws Exception {
        var f=new Fixture();var captured=new AtomicReference<AssetFoundationReadService.ReadSnapshot>();
        var failure=new IllegalArgumentException("synthetic callback failure");
        assertSame(failure,assertThrows(IllegalArgumentException.class,()->f.service.inReadSnapshot(f.request,snapshot->{captured.set(snapshot);throw failure;})));
        assertThrows(AssetFoundationFailure.class,captured.get()::requireActive);
        assertThrows(AssetFoundationFailure.class,()->f.service.inReadSnapshot(f.request,snapshot->{
            ((ConnectionHolder)TransactionSynchronizationManager.getResource(f.source)).setRollbackOnly();return 1;
        }));
    }
    @Test void actualManagedBoundarySharesOneClockAndRejectsSavepointEvenWhenCaught()throws Exception {
        var f=new Fixture();
        var autoCommit=new java.util.concurrent.atomic.AtomicBoolean(true);
        var readOnly=new java.util.concurrent.atomic.AtomicBoolean(false);
        var isolation=new java.util.concurrent.atomic.AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
        when(f.connection.getAutoCommit()).thenAnswer(i->autoCommit.get());
        doAnswer(i->{autoCommit.set(i.getArgument(0));return null;}).when(f.connection).setAutoCommit(anyBoolean());
        when(f.connection.isReadOnly()).thenAnswer(i->readOnly.get());
        doAnswer(i->{readOnly.set(i.getArgument(0));return null;}).when(f.connection).setReadOnly(anyBoolean());
        when(f.connection.getTransactionIsolation()).thenAnswer(i->isolation.get());
        doAnswer(i->{isolation.set(i.getArgument(0));return null;}).when(f.connection).setTransactionIsolation(anyInt());
        when(f.connection.unwrap(Connection.class)).thenReturn(f.connection);
        var clock=java.time.Instant.parse("2026-11-04T01:02:03.123456Z");
        var clockReads=new java.util.concurrent.atomic.AtomicInteger();
        when(f.connection.prepareStatement(anyString())).thenAnswer(invocation->{
            String sql=invocation.getArgument(0);var statement=mock(java.sql.PreparedStatement.class);var rows=mock(java.sql.ResultSet.class);
            when(statement.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true,false);
            if(sql.equals("select transaction_timestamp()")) {
                clockReads.incrementAndGet();when(rows.getObject(1,java.time.OffsetDateTime.class)).thenReturn(clock.atOffset(java.time.ZoneOffset.UTC));
            } else when(rows.getString(1)).thenReturn(switch(sql) {
                case "select current_setting('transaction_isolation')" -> "repeatable read";
                case "select current_setting('transaction_read_only')" -> "on";
                case "select current_setting('app.tenant_id',true)" -> f.context.tenantId().toString();
                default -> throw new AssertionError("Unexpected synthetic JDBC statement: "+sql);
            });
            return statement;
        });
        var manager=new JdbcTransactionManager(f.source);
        var authority=new ManagedSessionSensitiveSecurityTransaction(f.source,manager,mock(PostgresAuthorizationWriteGuard.class));
        var observed=new ArrayList<java.time.Instant>();
        when(f.actions.requireRead(eq(f.request),any(),any())).thenAnswer(invocation->{
            observed.add(((HumanSessionAuthorityTransaction.ReadBoundary)invocation.getArgument(1)).observedAt());return f.scope;
        });
        var service=new AssetFoundationReadService(f.repository,authority,f.actions,manager,f.source,f.applications);
        service.inReadSnapshot(f.request,snapshot->{snapshot.authorize(RegisteredEntityAction.WORKSHOP_READ);snapshot.authorize(RegisteredEntityAction.WORKSHOP_CREATE);return null;});
        assertEquals(List.of(clock,clock),observed);assertEquals(1,clockReads.get());
        assertThrows(RuntimeException.class,()->service.inReadSnapshot(f.request,snapshot->{
            TransactionSynchronizationManager.getSynchronizations().forEach(sync->sync.savepoint(new Object()));
            assertThrows(RuntimeException.class,snapshot::requireActive);return null;
        }));
    }
    private static final class Fixture {
        final AssetFoundationRepository repository=mock(AssetFoundationRepository.class);
        final HumanSessionAuthorityTransaction authority=mock(HumanSessionAuthorityTransaction.class);
        final EntityActionAuthorization actions=mock(EntityActionAuthorization.class);
        final CapturedHumanRequest request=mock(CapturedHumanRequest.class);
        final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1);
        final HumanSessionAuthorityTransaction.ReadBoundary boundary=mock(HumanSessionAuthorityTransaction.ReadBoundary.class);
        final EntityActionAuthorization.ActionScope scope=mock(EntityActionAuthorization.ActionScope.class);
        final TenantApplicationReadRepository applications=mock(TenantApplicationReadRepository.class);
        final DataSource source=mock(DataSource.class);
        final Connection connection=mock(Connection.class);
        final AssetFoundationReadService service;
        Fixture()throws Exception {
            when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(request.context()).thenReturn(context);
            when(actions.requireRead(eq(request),eq(boundary),any())).thenReturn(scope);
            when(authority.withRead(eq(request),any())).thenAnswer(invocation->((Function<HumanSessionAuthorityTransaction.ReadBoundary,?>)invocation.getArgument(1)).apply(boundary));
            service=new AssetFoundationReadService(repository,authority,actions,new JdbcTransactionManager(source),source,applications);
        }
    }
}
