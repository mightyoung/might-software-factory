package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.AssetFoundationSqlCatalog;
import com.mightyoung.softwarefactory.auditops.JooqBusinessAuditAppend;
import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.directory.*;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.modulith.events.core.*;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Managed authority/guard/manager and default Modulith JDBC repository. Identity/scope and driver remain fixtures. */
class AssetFoundationManagedCommandTest {
    private static final Instant TIME=Instant.parse("2026-11-04T00:00:00.123456Z");
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions {}

    @Test void replayClosesRealManagedStagingBeforeGateFlushAndFinalRevalidation()throws Exception {
        run(f->{
            UUID id=UUID.randomUUID(),key=UUID.randomUUID();
            var command=new AssetContracts.WorkshopUpdate(1,"Retained",null);
            var retained=new AssetFoundationRepository.Receipt(UUID.randomUUID(),f.context.accountId(),f.context.memberId(),
                    AssetFoundationFingerprint.command(f.context,RegisteredEntityAction.WORKSHOP_UPDATE,id,Arrays.asList(1L,"Retained",null)),"workshop",2,TIME.minusSeconds(60),id);
            when(f.repository.receipt(any(),eq(key))).thenReturn(Optional.of(retained));
            when(f.repository.row(eq(AssetFoundationSql.WORKSHOP_LOCK),any(),eq(id))).thenReturn(Optional.of(new AssetFoundationRepository.Row(id,f.org,99,"ARCHIVED")));
            when(f.repository.current(eq(AssetFoundationSql.WORKSHOP_CURRENT),any(),eq(id))).thenReturn(Optional.of(new AssetFoundationRepository.Row(id,f.org,99,"ARCHIVED")));
            assertEquals(retained.result(),f.service.updateWorkshop(f.request,key,UUID.randomUUID(),id,command));
            assertEquals(3,f.clocks.get());assertEquals(1,Collections.frequency(f.sql,AssetFoundationSql.FLUSH_CONSTRAINTS.sql));
            verify(f.repository,never()).execute(any(),any());
            verify(f.driver).commit();verify(f.driver,never()).rollback();f.assertFlushWindow();
        });
    }
    @Test void newWorkshopStagesActualWritesAuditAndDefaultRepositoryBeforeClosedCallbackFlush()throws Exception {
        run(f->{
            var result=f.service.createWorkshop(f.request,UUID.randomUUID(),UUID.randomUUID(),new AssetContracts.WorkshopCreate("W","Workshop",f.org,null));
            assertEquals(1,result.version());assertEquals(TIME.plusSeconds(1),result.occurredAt());
            assertTrue(f.sql.contains(AssetFoundationSql.WORKSHOP_INSERT.sql));assertTrue(f.sql.contains(AssetFoundationSql.WORKSHOP_REVISION.sql));
            assertTrue(f.sql.contains(AssetFoundationSql.WORKSHOP_RECEIPT.sql));f.assertTypedTemporalWrites(AssetFoundationSql.WORKSHOP_INSERT.sql,AssetFoundationSql.WORKSHOP_RECEIPT.sql);
            assertEquals(1,f.sql.stream().filter(sql->sql.startsWith("INSERT INTO EVENT_PUBLICATION ")).count());
            assertEquals(3,f.clocks.get());verify(f.driver).commit();verify(f.driver,never()).rollback();f.assertFlushWindow();
        });
    }
    @ParameterizedTest @EnumSource(AssetFoundationBindingCommands.Kind.class)
    void newBindingStagesActualEffectsAndDefaultPublicationBeforeClosedManagedFlush(AssetFoundationBindingCommands.Kind kind)throws Exception {
        run(f->{
            f.bindingKind=kind;
            var result=switch(kind) {
                case LINE_WORKSHOP->f.service.bindLineWorkshop(f.request,UUID.randomUUID(),UUID.randomUUID(),f.parent,new AssetContracts.LineWorkshopBind(1,f.counterpart,TIME.minusSeconds(60),null));
                case STAGE_EQUIPMENT->f.service.bindStageEquipment(f.request,UUID.randomUUID(),UUID.randomUUID(),f.parent,new AssetContracts.StageEquipmentBind(1,f.counterpart,TIME.minusSeconds(60),null));
                case RESPONSIBILITY->f.service.bindResponsibility(f.request,UUID.randomUUID(),UUID.randomUUID(),f.parent,new AssetContracts.ResponsibilityBind(1,f.org,null,AssetContracts.Duty.OPERATIONS,TIME.minusSeconds(60),null));
            };
            assertEquals(1,result.version());assertTrue(f.sql.contains(kind.insert.sql));assertTrue(f.sql.contains(kind.revision.sql));assertTrue(f.sql.contains(kind.receipt.sql));f.assertTypedTemporalWrites(kind.insert.sql,kind.receipt.sql);
            verify(f.driver).commit();verify(f.driver,never()).rollback();assertEquals(3,f.clocks.get());f.assertFlushWindow();
        });
    }
    @ParameterizedTest @EnumSource(AssetFoundationBindingCommands.Kind.class)
    void bindingReplayClosesRealManagedScopeBeforeControlFlush(AssetFoundationBindingCommands.Kind kind)throws Exception {
        run(f->{
            f.bindingKind=kind;UUID id=UUID.randomUUID(),key=UUID.randomUUID();
            var fields=Arrays.asList(id,1L,1L,null);
            var receipt=new AssetFoundationRepository.Receipt(UUID.randomUUID(),f.context.accountId(),f.context.memberId(),
                    AssetFoundationFingerprint.command(f.context,kind.end,f.parent,fields),kind.table,2,TIME.minusSeconds(10),id);
            when(f.repository.receipt(any(),eq(key))).thenReturn(Optional.of(receipt));
            var command=new AssetContracts.EndBinding(1,1,null);
            var result=switch(kind) {
                case LINE_WORKSHOP->f.service.endLineWorkshop(f.request,key,UUID.randomUUID(),f.parent,id,command);
                case STAGE_EQUIPMENT->f.service.endStageEquipment(f.request,key,UUID.randomUUID(),f.parent,id,command);
                case RESPONSIBILITY->f.service.endResponsibility(f.request,key,UUID.randomUUID(),f.parent,id,command);
            };
            assertEquals(receipt.result(),result);verify(f.repository,never()).execute(any(),any());verify(f.driver).commit();assertEquals(3,f.clocks.get());f.assertFlushWindow();
        });
    }
    private enum ArchiveRoot {
        WORKSHOP(AssetFoundationSql.WORKSHOP_CURRENT,AssetFoundationSql.WORKSHOP_ARCHIVE_CLEAR),
        LINE(AssetFoundationSql.PRODUCTION_LINE_CURRENT,AssetFoundationSql.PRODUCTION_LINE_ARCHIVE_CLEAR),
        STAGE(AssetFoundationSql.PROCESS_STAGE_CURRENT,AssetFoundationSql.PROCESS_STAGE_ARCHIVE_CLEAR),
        EQUIPMENT(AssetFoundationSql.EQUIPMENT_CURRENT,AssetFoundationSql.EQUIPMENT_ARCHIVE_CLEAR),
        WORK_UNIT(AssetFoundationSql.WORK_UNIT_CURRENT,AssetFoundationSql.WORK_UNIT_ARCHIVE_CLEAR);
        final AssetFoundationSql current,clear;
        ArchiveRoot(AssetFoundationSql current,AssetFoundationSql clear){this.current=current;this.clear=clear;}
    }
    @ParameterizedTest @EnumSource(ArchiveRoot.class)
    void archiveRechecksActualArchivedVersionAndAbsenceAtSoleFinalCut(ArchiveRoot root)throws Exception {
        run(f->{
            f.archiveState(root,"ARCHIVED",true);var result=f.archive(root);
            assertEquals(2,result.version());verify(f.repository).archiveClear(eq(root.clear),any(),eq(f.parent),eq(TIME.plusSeconds(2)));
            assertEquals(3,f.clocks.get());verify(f.driver).commit();verify(f.driver,never()).rollback();f.assertFlushWindow();
        });
    }
    @ParameterizedTest @EnumSource(ArchiveRoot.class)
    void archiveRollsBackWhenFinalDownstreamAbsenceCheckFails(ArchiveRoot root)throws Exception {
        run(f->{
            f.archiveState(root,"ARCHIVED",false);assertEquals(409,assertThrows(AssetFoundationFailure.class,()->f.archive(root)).status());
            verify(f.repository).archiveClear(eq(root.clear),any(),eq(f.parent),eq(TIME.plusSeconds(2)));
            assertEquals(3,f.clocks.get());verify(f.driver).rollback();verify(f.driver,never()).commit();f.assertFlushWindow();
        });
    }
    @ParameterizedTest @EnumSource(ArchiveRoot.class)
    void archiveRejectsActualFinalNonArchivedStateBeforeAbsenceQuery(ArchiveRoot root)throws Exception {
        run(f->{
            f.archiveState(root,"ACTIVE",true);assertEquals(409,assertThrows(AssetFoundationFailure.class,()->f.archive(root)).status());
            verify(f.repository,never()).archiveClear(any(),any(),any(),any());
            assertEquals(3,f.clocks.get());verify(f.driver).rollback();verify(f.driver,never()).commit();f.assertFlushWindow();
        });
    }
    private static void run(ThrowingConsumer<Fixture> body)throws Exception {
        var fixture=new Fixture();var mapper=new ObjectMapper();var serializer=mock(EventSerializer.class);
        when(serializer.serialize(any())).thenAnswer(call->mapper.writeValueAsString(call.getArgument(0)));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration")))
                .withUserConfiguration(Transactions.class)
                .withPropertyValues("spring.modulith.events.jdbc.use-legacy-structure=true","spring.modulith.events.jdbc.schema-initialization.enabled=false")
                .withBean(DataSource.class,()->fixture.pool).withBean(JdbcTemplate.class,()->new JdbcTemplate(fixture.pool))
                .withBean(PlatformTransactionManager.class,()->new JdbcTransactionManager(fixture.pool))
                .withBean(EventSerializer.class,()->serializer).run(context->{
                    assertNull(context.getStartupFailure());
                    var publications=context.getBean(EventPublicationRepository.class);
                    var catalog=AssetFoundationSqlCatalog.commandTemplates(DSL.using(SQLDialect.POSTGRES),publications,context.getBean(JdbcRepositorySettings.class));
                    fixture.compose(catalog,publications,mapper);
                    try {body.accept(fixture);}catch(Exception failure){throw new AssertionError(failure);}
                });
    }
    @FunctionalInterface private interface ThrowingConsumer<T> { void accept(T value)throws Exception; }
    private static final class Fixture {
        final UUID org=UUID.randomUUID(),parent=UUID.randomUUID(),counterpart=UUID.randomUUID();AssetFoundationBindingCommands.Kind bindingKind;final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1);
        final DataSource pool=mock(DataSource.class);final Connection driver=mock(Connection.class);final List<String> sql=new ArrayList<>();final Set<String> typedTemporalWrites=new HashSet<>();final AtomicInteger clocks=new AtomicInteger();
        final CapturedHumanRequest request=mock(CapturedHumanRequest.class);final AssetFoundationRepository repository=mock(AssetFoundationRepository.class);
        final EntityActionAuthorization actions=mock(EntityActionAuthorization.class);final DirectoryReferenceReadPort directory=mock(DirectoryReferenceReadPort.class);
        HumanSessionAuthorityTransaction.WriteScope currentScope;HumanSessionAuthorityTransaction.GuardedBoundary currentBoundary;
        ManagedSessionSensitiveSecurityTransaction authority;DataSource source;AssetFoundationService service;
        Fixture()throws Exception {
            when(pool.getConnection()).thenReturn(driver);var jdbcAutoCommit=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(driver.getAutoCommit()).thenAnswer(call->jdbcAutoCommit.get());
        doAnswer(call->{jdbcAutoCommit.set(call.getArgument(0));return null;}).when(driver).setAutoCommit(anyBoolean());var jdbcReadOnly=new java.util.concurrent.atomic.AtomicBoolean(false);
        when(driver.isReadOnly()).thenAnswer(call->jdbcReadOnly.get());
        doAnswer(call->{jdbcReadOnly.set(call.getArgument(0));return null;}).when(driver).setReadOnly(anyBoolean());var jdbcIsolation=new java.util.concurrent.atomic.AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
        when(driver.getTransactionIsolation()).thenAnswer(call->jdbcIsolation.get());
        doAnswer(call->{jdbcIsolation.set(call.getArgument(0));return null;}).when(driver).setTransactionIsolation(anyInt());
            var metadata=mock(DatabaseMetaData.class);when(driver.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");when(metadata.getDatabaseMajorVersion()).thenReturn(18);when(metadata.getDatabaseProductVersion()).thenReturn("18.0");
            when(driver.prepareStatement(anyString())).thenAnswer(call->{
                String query=call.getArgument(0);sql.add(query);var statement=mock(PreparedStatement.class);
                doAnswer(binding->{if(binding.getArgument(1) instanceof OffsetDateTime && (query.stripLeading().startsWith("insert")||query.stripLeading().startsWith("update")))typedTemporalWrites.add(query);return null;}).when(statement).setObject(anyInt(),any());
                boolean select=query.stripLeading().startsWith("select")||query.stripLeading().startsWith("with");
                when(statement.execute()).thenReturn(select);when(statement.executeUpdate()).thenReturn(1);
                when(statement.getLargeUpdateCount()).thenReturn(query.equals(AssetFoundationSql.FLUSH_CONSTRAINTS.sql)?0L:1L);when(statement.getUpdateCount()).thenReturn(query.equals(AssetFoundationSql.FLUSH_CONSTRAINTS.sql)?0:1);
                var rows=mock(ResultSet.class);when(rows.next()).thenReturn(true,false);
                String value=query.contains("transaction_isolation")?"read committed":query.contains("transaction_read_only")?"off":
                        query.contains("app.tenant_id")?context.tenantId().toString():query.contains("select case")?"5000ms":"0";
                when(rows.getString(1)).thenReturn(value);
                if(query.equals("select statement_timestamp()"))when(rows.getObject(1,OffsetDateTime.class)).thenReturn(TIME.plusSeconds(clocks.getAndIncrement()).atOffset(ZoneOffset.UTC));
                when(statement.executeQuery()).thenReturn(rows);when(statement.getResultSet()).thenReturn(select?rows:null);return statement;
            });
        }
        void compose(Collection<com.mightyoung.softwarefactory.support.TransactionSqlSealGate.SqlTemplate> catalog,EventPublicationRepository publications,ObjectMapper mapper) {
            var manager=new G014JdbcTransactionManager(pool,catalog);source=manager.commandDataSource();var guard=new PostgresAuthorizationWriteGuard(source);
            authority=new ManagedSessionSensitiveSecurityTransaction(source,manager,guard);when(request.context()).thenReturn(context);
            var scope=mock(EntityActionAuthorization.ActionScope.class);var scopes=mock(EntityActionAuthorization.FinalActionScopes.class);
            when(actions.requireFinalCommand(any(),any(),any())).thenAnswer(call->{currentScope=call.getArgument(0);currentBoundary=call.getArgument(1);Set<RegisteredEntityAction> selected=call.getArgument(2);currentAction=selected.stream().filter(action->!action.isRead()).findFirst().orElseThrow();require();return scopes;});
            when(scopes.require(any())).thenAnswer(call->{require();return scope;});when(scopes.observedAt()).thenAnswer(call->{require();return currentBoundary.observedAt();});
            when(scope.context()).thenAnswer(call->{require();return context;});when(scope.organizationIds()).thenAnswer(call->{require();return Set.of(org);});
            when(scope.action()).thenAnswer(call->{require();return currentAction;});when(scope.observedAt()).thenAnswer(call->{require();return currentBoundary.observedAt();});
            doAnswer(call->{require();return null;}).when(actions).requireCurrent(any());doAnswer(call->{require();return null;}).when(actions).requireSqlConnection(any(),any(),any());
            when(repository.receipt(any(),any())).thenReturn(Optional.empty());
            when(repository.current(eq(AssetFoundationSql.WORKSHOP_CURRENT),any(),any())).thenAnswer(call->Optional.of(new AssetFoundationRepository.Row(call.getArgument(2),org,1,"ACTIVE")));
            when(repository.row(any(),any(),any())).thenAnswer(call->Optional.of(new AssetFoundationRepository.Row(call.getArgument(2),org,1,"ACTIVE")));
            when(repository.current(any(),any(),any())).thenAnswer(call->Optional.of(new AssetFoundationRepository.Row(call.getArgument(2),org,1,"ACTIVE")));
            when(repository.active(any(),any(),any())).thenReturn(true);
            when(repository.link(any(),any(),any(),any())).thenAnswer(call->new AssetFoundationRepository.Link(call.getArgument(2),org,1,parent,
                    bindingKind==AssetFoundationBindingCommands.Kind.RESPONSIBILITY?org:counterpart,null,"OPERATIONS",TIME.minusSeconds(60),null,null,"O","Org",null,null,TIME.plusSeconds(1)));
            var actual=new AssetFoundationRepository(source,actions);
            doAnswer(call->{actual.setTenant(call.getArgument(0));return null;}).when(repository).setTenant(any());
            doAnswer(call->{actual.lock(call.getArgument(0),call.getArgument(1));return null;}).when(repository).lock(any(),anyList());
            doAnswer(call->{actual.execute(call.getArgument(0),call.getArgument(1));return null;}).when(repository).execute(any(),any());
            when(directory.findOrganizationAt(any(),eq(org),any())).thenAnswer(call->Optional.of(new DirectoryReferenceContracts.Organization(org,"O","Org",true,call.getArgument(2))));
            var publisher=new ModulithAssetFoundationEventPublisher(manager,pool,event->publications.create(TargetEventPublication.of(event,PublicationTargetIdentifier.of("listener"),TIME)));
            service=new AssetFoundationService(manager,repository,actions,authority,guard,directory,new JooqBusinessAuditAppend(source,actions),publisher,mapper,"listener");
        }
        void archiveState(ArchiveRoot root,String lifecycle,boolean clear) {
            when(repository.current(eq(root.current),any(),eq(parent))).thenAnswer(call->Optional.of(new AssetFoundationRepository.Row(parent,org,clocks.get()>=3?2:1,clocks.get()>=3?lifecycle:"ACTIVE")));
            when(repository.archiveClear(eq(root.clear),any(),eq(parent),any())).thenReturn(clear);
            when(repository.workshop(any(),eq(parent))).thenReturn(new AssetContracts.Workshop(parent,org,1,"W","Workshop",null,AssetContracts.Lifecycle.ACTIVE,TIME,TIME));
            when(repository.productionLine(any(),eq(parent))).thenReturn(new AssetContracts.ProductionLine(parent,org,1,"L","Line",counterpart,null,AssetContracts.Lifecycle.ACTIVE,TIME,TIME));
            when(repository.processStage(any(),eq(parent))).thenReturn(new AssetContracts.ProcessStage(parent,org,1,"S","Stage",counterpart,1,null,AssetContracts.Lifecycle.ACTIVE,TIME,TIME));
            when(repository.equipment(any(),eq(parent))).thenReturn(new AssetContracts.Equipment(parent,org,1,"E","Equipment","Type",null,AssetContracts.OperationalState.UNKNOWN,AssetContracts.Lifecycle.ACTIVE,TIME,TIME));
            when(repository.workUnit(any(),eq(parent))).thenReturn(new AssetContracts.WorkUnit(parent,org,1,"U","Unit",AssetContracts.WorkUnitKind.STANDALONE,null,null,null,null,null,AssetContracts.Lifecycle.ACTIVE,TIME,TIME));
        }
        AssetContracts.CommandResult archive(ArchiveRoot root) {
            var command=new AssetContracts.Archive(1);var key=UUID.randomUUID();var trace=UUID.randomUUID();
            return switch(root) {
                case WORKSHOP->service.archiveWorkshop(request,key,trace,parent,command);
                case LINE->service.archiveProductionLine(request,key,trace,parent,command);
                case STAGE->service.archiveProcessStage(request,key,trace,parent,command);
                case EQUIPMENT->service.archiveEquipment(request,key,trace,parent,command);
                case WORK_UNIT->service.archiveWorkUnit(request,key,trace,parent,command);
            };
        }
        RegisteredEntityAction currentAction=RegisteredEntityAction.WORKSHOP_CREATE;
        void require() {var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);authority.requireGuarded(currentScope,currentBoundary,source,holder.getConnection());}
        void assertTypedTemporalWrites(String row,String receipt) {
            assertTrue(typedTemporalWrites.contains(row));assertTrue(typedTemporalWrites.contains(receipt));
            assertTrue(typedTemporalWrites.stream().anyMatch(query->query.stripLeading().startsWith("insert into public.audit_event")));
        }
        void assertFlushWindow() {int flush=sql.indexOf(AssetFoundationSql.FLUSH_CONSTRAINTS.sql);int finalClock=sql.lastIndexOf("select statement_timestamp()");assertTrue(flush>=0&&finalClock>flush);}
    }
}
