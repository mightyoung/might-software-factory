package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.auditops.BusinessAuditAppend;
import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceReadPort;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;

/** Real Spring owner/seal pipeline with fixture authority/repository/driver, not HTTP or database acceptance. */
class AssetFoundationReceiptAuthorizationTest {
    private final UUID org=UUID.randomUUID(),target=UUID.randomUUID(),key=UUID.randomUUID(),trace=UUID.randomUUID();
    private final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1);
    private final AssetFoundationRepository repository=mock(AssetFoundationRepository.class);
    private final EntityActionAuthorization actions=mock(EntityActionAuthorization.class);
    private final HumanSessionAuthorityTransaction authority=mock(HumanSessionAuthorityTransaction.class);
    private final AuthorizationWriteGuard guard=mock(AuthorizationWriteGuard.class);
    private final DirectoryReferenceReadPort directory=mock(DirectoryReferenceReadPort.class);
    private final BusinessAuditAppend audit=mock(BusinessAuditAppend.class);
    private final AssetFoundationEventPublisher events=mock(AssetFoundationEventPublisher.class);
    private final CapturedHumanRequest request=mock(CapturedHumanRequest.class);
    private final Connection driver=mock(Connection.class);
    private final EntityActionAuthorization.ActionScope scope=mock(EntityActionAuthorization.ActionScope.class);
    private final EntityActionAuthorization.FinalActionScopes scopes=mock(EntityActionAuthorization.FinalActionScopes.class);

    private AssetFoundationService service(RegisteredEntityAction action)throws Exception {
        when(request.context()).thenReturn(context);
        when(scope.organizationIds()).thenReturn(Set.of(org));
        when(scope.context()).thenReturn(context);when(scope.action()).thenReturn(action);
        when(scopes.require(action)).thenReturn(scope);
        when(scopes.observedAt()).thenReturn(Instant.parse("2026-11-04T01:00:00Z"));
        when(actions.requireFinalCommand(any(),any(),any())).thenReturn(scopes);
        var pool=mock(DataSource.class);when(pool.getConnection()).thenReturn(driver);
        var jdbcAutoCommit=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(driver.getAutoCommit()).thenAnswer(call->jdbcAutoCommit.get());
        doAnswer(call->{jdbcAutoCommit.set(call.getArgument(0));return null;}).when(driver).setAutoCommit(anyBoolean());var jdbcReadOnly=new java.util.concurrent.atomic.AtomicBoolean(false);
        when(driver.isReadOnly()).thenAnswer(call->jdbcReadOnly.get());
        doAnswer(call->{jdbcReadOnly.set(call.getArgument(0));return null;}).when(driver).setReadOnly(anyBoolean());
        var jdbcIsolation=new java.util.concurrent.atomic.AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
        when(driver.getTransactionIsolation()).thenAnswer(call->jdbcIsolation.get());
        doAnswer(call->{jdbcIsolation.set(call.getArgument(0));return null;}).when(driver).setTransactionIsolation(anyInt());
        var metadata=mock(DatabaseMetaData.class);when(driver.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseMajorVersion()).thenReturn(18);when(metadata.getDatabaseMinorVersion()).thenReturn(0);
        when(metadata.getDatabaseProductVersion()).thenReturn("18.0");
        var statement=mock(PreparedStatement.class);when(driver.prepareStatement(AssetFoundationSql.FLUSH_CONSTRAINTS.sql)).thenReturn(statement);
        when(statement.execute()).thenReturn(false);when(statement.getLargeUpdateCount()).thenReturn(0L);
        var manager=new G014JdbcTransactionManager(pool,List.of(AssetFoundationSql.FLUSH_CONSTRAINTS.template()));
        when(authority.withGuarded(any(),any())).thenAnswer(invocation->{
            Function<HumanSessionAuthorityTransaction.GuardedBoundary,Object> callback=invocation.getArgument(1);
            Object result=callback.apply(mock(HumanSessionAuthorityTransaction.GuardedBoundary.class));
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(manager.commandDataSource());
            var connection=holder.getConnection();connection.getAutoCommit();connection.isReadOnly();connection.getTransactionIsolation();
            return result;
        });
        return new AssetFoundationService(manager,repository,actions,authority,guard,directory,audit,events,new ObjectMapper(),"fixed-listener");
    }

    @Test void nanosecondServerCutFailsUnavailableBeforeAnyBusinessEffectWithoutRounding()throws Exception {
        var service=service(WORKSHOP_CREATE);
        when(scopes.observedAt()).thenReturn(Instant.parse("2026-11-04T01:00:00.123456789Z"));
        assertEquals(503,assertThrows(AssetFoundationFailure.class,()->service.createWorkshop(request,key,trace,new AssetContracts.WorkshopCreate("W","Workshop",org,null))).status());
        verify(repository,never()).execute(any(),any());
        verifyNoInteractions(directory,audit,events);verify(driver).rollback();verify(driver,never()).commit();
    }
    @Test void newSourceCommandAcquiresFullCanonicalKeysBeforeParentLockAndFreshAuthority()throws Exception {
        var service=service(WORK_UNIT_CREATE);
        when(scopes.require(WORKSHOP_READ)).thenReturn(scope);
        when(repository.row(AssetFoundationSql.WORKSHOP_LOCK,context.tenantId(),target)).thenReturn(Optional.of(new AssetFoundationRepository.Row(target,org,1,"ACTIVE")));
        when(directory.findOrganizationAt(eq(context.tenantId()),eq(org),any())).thenAnswer(call->Optional.of(new com.mightyoung.softwarefactory.directory.DirectoryReferenceContracts.Organization(org,"O","Org",true,call.getArgument(2))));
        assertEquals(404,assertThrows(AssetFoundationFailure.class,()->service.createWorkUnit(request,key,trace,
                new AssetContracts.WorkUnitCreate("WU","Unit",org,AssetContracts.WorkUnitKind.WORKSHOP,target,null,null,null,null))).status());
        var ordered=inOrder(guard,repository,actions);
        ordered.verify(guard).forBusinessCommand(context.tenantId());
        ordered.verify(repository).lock(AssetFoundationSql.RECEIPT_LOCK,List.of(context.tenantId(),context.actorId(),"work_unit.create",key));
        ordered.verify(actions).requireFinalCommand(any(),any(),eq(Set.of(WORK_UNIT_CREATE)));
        ordered.verify(repository).receipt(scope,key);
        ordered.verify(repository).lock(AssetFoundationSql.WORK_UNIT_NATURAL,List.of(context.tenantId(),"WU"));
        ordered.verify(repository).lock(eq(AssetFoundationSql.MUTATION_LOCK),argThat(fields->fields.size()==5&&fields.get(0).equals("work_unit")
                &&fields.get(1).equals(context.tenantId())&&fields.get(2) instanceof UUID&&fields.get(3).equals(1L)&&fields.get(4) instanceof UUID));
        ordered.verify(repository).row(AssetFoundationSql.WORKSHOP_LOCK,context.tenantId(),target);
        ordered.verify(actions).requireFinalCommand(any(),any(),eq(Set.of(WORK_UNIT_CREATE,WORKSHOP_READ)));
        verify(repository,never()).execute(any(),any());verifyNoInteractions(audit,events);verify(driver).rollback();
    }
    @Test void unauthorizedCreateAnchorCannotReadAnyReceipt()throws Exception {
        var service=service(WORKSHOP_CREATE);
        when(scope.organizationIds()).thenReturn(Set.of(UUID.randomUUID()));
        assertThrows(AssetFoundationFailure.class,()->service.createWorkshop(request,key,trace,new AssetContracts.WorkshopCreate("W","Workshop",org,null)));
        verify(repository,never()).receipt(any(),any());verify(driver,never()).commit();verify(driver).rollback();
    }
    @Test void unauthorizedRouteTargetCannotReadAnyReceipt()throws Exception {
        var service=service(WORKSHOP_UPDATE);
        when(repository.current(AssetFoundationSql.WORKSHOP_CURRENT,scope,target)).thenReturn(Optional.empty());
        assertThrows(AssetFoundationFailure.class,()->service.updateWorkshop(request,key,trace,target,new AssetContracts.WorkshopUpdate(1,"New",null)));
        verify(repository,never()).receipt(any(),any());verify(driver,never()).commit();verify(driver).rollback();
    }
    @Test void authorizedReplayFlushesConstraintAckAndSkipsStaleCasActiveChecksAndAllWrites()throws Exception {
        var service=service(WORKSHOP_UPDATE);
        var current=new AssetFoundationRepository.Row(target,org,99,"ARCHIVED");
        when(repository.current(AssetFoundationSql.WORKSHOP_CURRENT,scope,target)).thenReturn(Optional.of(current));
        when(repository.row(AssetFoundationSql.WORKSHOP_LOCK,context.tenantId(),target)).thenReturn(Optional.of(current));
        String fingerprint=AssetFoundationFingerprint.command(context,WORKSHOP_UPDATE,target,Arrays.asList(1L,"New",null));
        var receipt=new AssetFoundationRepository.Receipt(UUID.randomUUID(),context.accountId(),context.memberId(),fingerprint,
                "workshop",2,Instant.parse("2026-11-03T00:00:00Z"),target);
        when(repository.receipt(scope,key)).thenReturn(Optional.of(receipt));
        assertEquals(receipt.result(),service.updateWorkshop(request,key,trace,target,new AssetContracts.WorkshopUpdate(1,"New",null)));
        var ordered=inOrder(actions,repository);
        ordered.verify(actions).requireFinalCommand(any(),any(),eq(Set.of(WORKSHOP_UPDATE)));
        ordered.verify(repository).current(AssetFoundationSql.WORKSHOP_CURRENT,scope,target);
        ordered.verify(repository).receipt(scope,key);
        verify(repository,never()).lock(eq(AssetFoundationSql.MUTATION_LOCK),anyList());
        verify(repository,never()).lock(eq(AssetFoundationSql.WORKSHOP_NATURAL),anyList());
        verify(repository,never()).execute(any(),any());
        verify(repository,never()).active(any(),any(),any());verifyNoInteractions(directory,audit,events);
        verify(actions,times(3)).requireFinalCommand(any(),any(),eq(Set.of(WORKSHOP_UPDATE)));
        verify(driver).commit();verify(driver,never()).rollback();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
