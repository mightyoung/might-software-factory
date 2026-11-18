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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.*;

/** Mock authority/repository with actual Spring transaction/seal and actual jOOQ zero-count flush, not DB acceptance. */
class AssetFoundationBindingCommandTest {
    private final UUID org=UUID.randomUUID(),parent=UUID.randomUUID(),id=UUID.randomUUID(),counterpart=UUID.randomUUID(),key=UUID.randomUUID(),trace=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-11-04T00:00:00Z"),from=Instant.parse("2026-11-02T00:00:00Z"),to=Instant.parse("2026-11-03T00:00:00Z");
    private final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1);
    private final AssetFoundationRepository repository=mock(AssetFoundationRepository.class);
    private final EntityActionAuthorization actions=mock(EntityActionAuthorization.class);
    private final HumanSessionAuthorityTransaction authority=mock(HumanSessionAuthorityTransaction.class);
    private final EntityActionAuthorization.ActionScope scope=mock(EntityActionAuthorization.ActionScope.class);
    private final EntityActionAuthorization.FinalActionScopes scopes=mock(EntityActionAuthorization.FinalActionScopes.class);
    private final CapturedHumanRequest request=mock(CapturedHumanRequest.class);
    private final DirectoryReferenceReadPort directory=mock(DirectoryReferenceReadPort.class);
    private final BusinessAuditAppend audit=mock(BusinessAuditAppend.class);
    private final AssetFoundationEventPublisher events=mock(AssetFoundationEventPublisher.class);
    private final Connection driver=mock(Connection.class);

    private AssetFoundationService service(AssetFoundationBindingCommands.Kind kind,boolean bind,long parentVersion)throws Exception {
        when(request.context()).thenReturn(context);when(scope.context()).thenReturn(context);when(scope.organizationIds()).thenReturn(Set.of(org));
        when(scope.action()).thenReturn(bind?kind.bind:kind.end);when(scopes.require(any())).thenReturn(scope);when(scopes.observedAt()).thenReturn(now);
        when(actions.requireFinalCommand(any(),any(),any())).thenReturn(scopes);
        when(repository.receipt(scope,key)).thenReturn(Optional.empty());
        when(repository.row(any(),eq(context.tenantId()),any())).thenAnswer(call->Optional.of(new AssetFoundationRepository.Row(call.getArgument(2),org,parentVersion,"ARCHIVED")));
        when(repository.current(kind.parentCurrent,scope,parent)).thenReturn(Optional.of(new AssetFoundationRepository.Row(parent,org,parentVersion,"ARCHIVED")));
        var pool=mock(DataSource.class);when(pool.getConnection()).thenReturn(driver);var jdbcAutoCommit=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(driver.getAutoCommit()).thenAnswer(call->jdbcAutoCommit.get());
        doAnswer(call->{jdbcAutoCommit.set(call.getArgument(0));return null;}).when(driver).setAutoCommit(anyBoolean());
        var jdbcReadOnly=new java.util.concurrent.atomic.AtomicBoolean(false);
        when(driver.isReadOnly()).thenAnswer(call->jdbcReadOnly.get());
        doAnswer(call->{jdbcReadOnly.set(call.getArgument(0));return null;}).when(driver).setReadOnly(anyBoolean());var jdbcIsolation=new java.util.concurrent.atomic.AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
        when(driver.getTransactionIsolation()).thenAnswer(call->jdbcIsolation.get());
        doAnswer(call->{jdbcIsolation.set(call.getArgument(0));return null;}).when(driver).setTransactionIsolation(anyInt());
        var metadata=mock(DatabaseMetaData.class);when(driver.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseMajorVersion()).thenReturn(18);when(metadata.getDatabaseProductVersion()).thenReturn("18.0");
        var flush=mock(PreparedStatement.class);when(driver.prepareStatement(AssetFoundationSql.FLUSH_CONSTRAINTS.sql)).thenReturn(flush);
        when(flush.execute()).thenReturn(false);when(flush.getLargeUpdateCount()).thenReturn(0L);
        var manager=new G014JdbcTransactionManager(pool,List.of(AssetFoundationSql.FLUSH_CONSTRAINTS.template()));
        when(authority.withGuarded(any(),any())).thenAnswer(call->{
            Function<HumanSessionAuthorityTransaction.GuardedBoundary,Object> work=call.getArgument(1);Object value=work.apply(mock(HumanSessionAuthorityTransaction.GuardedBoundary.class));
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(manager.commandDataSource());var connection=holder.getConnection();
            connection.getAutoCommit();connection.isReadOnly();connection.getTransactionIsolation();return value;
        });
        return new AssetFoundationService(manager,repository,actions,authority,mock(AuthorizationWriteGuard.class),directory,audit,events,new ObjectMapper(),"listener");
    }
    private AssetFoundationRepository.Link link(Instant start) { return new AssetFoundationRepository.Link(id,org,1,parent,counterpart,null,null,start,start.isBefore(to)?to:null,start.isBefore(to)?to:null,null,null,null,null,null); }
    private CommandResult bind(AssetFoundationService service,AssetFoundationBindingCommands.Kind kind) {
        return switch(kind) {
            case LINE_WORKSHOP->service.bindLineWorkshop(request,key,trace,parent,new LineWorkshopBind(1,counterpart,from,to));
            case STAGE_EQUIPMENT->service.bindStageEquipment(request,key,trace,parent,new StageEquipmentBind(1,counterpart,from,to));
            case RESPONSIBILITY->service.bindResponsibility(request,key,trace,parent,new ResponsibilityBind(1,counterpart,null,Duty.OPERATIONS,from,to));
        };
    }
    private CommandResult end(AssetFoundationService service,AssetFoundationBindingCommands.Kind kind) {
        return switch(kind) {
            case LINE_WORKSHOP->service.endLineWorkshop(request,key,trace,parent,id,new EndBinding(1,1,null));
            case STAGE_EQUIPMENT->service.endStageEquipment(request,key,trace,parent,id,new EndBinding(1,1,null));
            case RESPONSIBILITY->service.endResponsibility(request,key,trace,parent,id,new EndBinding(1,1,null));
        };
    }
    private AssetFoundationRepository.Receipt receipt(AssetFoundationBindingCommands.Kind kind,boolean bind) {
        List<Object> fields=bind?(kind==AssetFoundationBindingCommands.Kind.RESPONSIBILITY?Arrays.asList(1L,counterpart,null,Duty.OPERATIONS,from,to):Arrays.asList(1L,counterpart,from,to)):Arrays.asList(id,1L,1L,null);
        String fingerprint=AssetFoundationFingerprint.command(context,bind?kind.bind:kind.end,parent,fields);
        return new AssetFoundationRepository.Receipt(UUID.randomUUID(),context.accountId(),context.memberId(),fingerprint,kind.table,bind?1:2,from,id);
    }
    @ParameterizedTest @EnumSource(AssetFoundationBindingCommands.Kind.class)
    void nanosecondServerCutFailsUnavailableBeforeAnyBindingEffectWithoutRounding(AssetFoundationBindingCommands.Kind kind)throws Exception {
        var service=service(kind,true,1);when(scopes.observedAt()).thenReturn(now.plusNanos(123456789));
        assertEquals(503,assertThrows(AssetFoundationFailure.class,()->bind(service,kind)).status());
        verify(repository,never()).execute(any(),any());
        verifyNoInteractions(directory,audit,events);verify(driver).rollback();verify(driver,never()).commit();
    }
    @ParameterizedTest @EnumSource(AssetFoundationBindingCommands.Kind.class)
    void exactBindReplayIgnoresNowArchivedParentExpiredPeriodAndOldReferenceEligibility(AssetFoundationBindingCommands.Kind kind)throws Exception {
        var service=service(kind,true,99);var receipt=receipt(kind,true);when(repository.receipt(scope,key)).thenReturn(Optional.of(receipt));
        when(repository.link(kind.peek,context.tenantId(),id,null)).thenReturn(link(from));when(repository.link(kind.current,context.tenantId(),id,scope)).thenReturn(link(from));
        assertEquals(receipt.result(),bind(service,kind));
        verify(repository,never()).active(any(),any(),any());verify(repository,never()).execute(any(),any());
        verifyNoInteractions(directory,audit,events);verify(driver).commit();verify(driver,never()).rollback();
    }
    @ParameterizedTest @EnumSource(AssetFoundationBindingCommands.Kind.class)
    void exactEndReplayIgnoresOldParentAndBindingCasAndDoesNotReopenTheInterval(AssetFoundationBindingCommands.Kind kind)throws Exception {
        var service=service(kind,false,99);var receipt=receipt(kind,false);when(repository.receipt(scope,key)).thenReturn(Optional.of(receipt));
        when(repository.link(kind.peek,context.tenantId(),id,null)).thenReturn(link(from));when(repository.link(kind.current,context.tenantId(),id,scope)).thenReturn(link(from));
        assertEquals(receipt.result(),end(service,kind));
        verify(repository,never()).lock(eq(AssetFoundationSql.MUTATION_LOCK),anyList());verify(repository,never()).execute(any(),any());
        verifyNoInteractions(directory,audit,events);verify(driver).commit();
    }
    @ParameterizedTest @EnumSource(AssetFoundationBindingCommands.Kind.class)
    void newBindRequiresCurrentParentBusinessVersionBeforeAnyBusinessWrite(AssetFoundationBindingCommands.Kind kind)throws Exception {
        var service=service(kind,true,99);
        assertEquals(409,assertThrows(AssetFoundationFailure.class,()->bind(service,kind)).status());
        verify(repository,never()).execute(any(),any());verifyNoInteractions(directory,audit,events);verify(driver).rollback();verify(driver,never()).commit();
    }
    @ParameterizedTest @EnumSource(AssetFoundationBindingCommands.Kind.class)
    void newEndCannotEndAnIntervalBeforeItsStart(AssetFoundationBindingCommands.Kind kind)throws Exception {
        var service=service(kind,false,1);var existing=link(now.plusSeconds(60));
        when(repository.link(kind.peek,context.tenantId(),id,null)).thenReturn(existing);when(repository.link(kind.lock,context.tenantId(),id,null)).thenReturn(existing);
        assertEquals(409,assertThrows(AssetFoundationFailure.class,()->end(service,kind)).status());
        verify(repository,never()).execute(any(),any());verifyNoInteractions(directory,audit,events);verify(driver).rollback();verify(driver,never()).commit();
    }
}
