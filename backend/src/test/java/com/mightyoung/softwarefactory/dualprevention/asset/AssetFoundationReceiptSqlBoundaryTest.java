package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.WORKSHOP_UPDATE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual jOOQ query/bind pipeline over an empty fixture; PostgreSQL predicate semantics remain unverified. */
class AssetFoundationReceiptSqlBoundaryTest {
    private final DataSource source=mock(DataSource.class);
    private final EntityActionAuthorization actions=mock(EntityActionAuthorization.class);
    private final EntityActionAuthorization.ActionScope scope=mock(EntityActionAuthorization.ActionScope.class);
    private final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1);
    private final UUID key=UUID.randomUUID(),org=UUID.randomUUID();

    @BeforeEach void begin() { TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true); }
    @AfterEach void clear() { TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear(); }
    private void bind(MockConnection connection) {
        var holder=new ConnectionHolder(connection);holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source,holder);
        when(scope.context()).thenReturn(context);when(scope.action()).thenReturn(WORKSHOP_UPDATE);
        when(scope.organizationIds()).thenReturn(Set.of(org));
    }
    @Test void receiptQueryBindsCurrentAuthorizedOrganizationBeforeActorActionAndKey() {
        var authorized=new AtomicBoolean();var queries=new AtomicInteger();
        var connection=new MockConnection(execution->{
            assertTrue(authorized.get(),"SQL must follow actual scope/connection validation");queries.incrementAndGet();
            assertEquals(AssetFoundationSql.RECEIPT_FIND.sql,execution.sql());
            assertArrayEquals(new Object[]{"{"+org+"}",context.tenantId(),context.actorId(),"workshop.update",key},execution.bindings());
            return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES).newResult(AssetFoundationRepository.fields(AssetFoundationSql.RECEIPT_FIND)))};
        });
        bind(connection);
        doAnswer(invocation->{authorized.set(true);return null;}).when(actions).requireSqlConnection(scope,source,connection);
        assertTrue(new AssetFoundationRepository(source,actions).receipt(scope,key).isEmpty());
        assertEquals(1,queries.get());verifyNoInteractions(source);
    }
    @Test void canonicalLockHelpersSendCompleteTypedTuplesThroughActualJooq() {
        UUID entity=UUID.fromString("80000000-0000-0000-8000-000000000000"),command=UUID.randomUUID();
        var expected=new LinkedHashMap<String,Object[]>();
        expected.put(AssetFoundationSql.RECEIPT_LOCK.sql,new Object[]{context.tenantId(),context.actorId(),"workshop.update",key});
        expected.put(AssetFoundationSql.MUTATION_LOCK.sql,new Object[]{"workshop",context.tenantId(),entity,2L,command});
        var observed=new ArrayList<String>();
        var connection=new MockConnection(execution->{
            assertTrue(expected.containsKey(execution.sql()));assertArrayEquals(expected.get(execution.sql()),execution.bindings());observed.add(execution.sql());
            var sql=execution.sql().equals(AssetFoundationSql.RECEIPT_LOCK.sql)?AssetFoundationSql.RECEIPT_LOCK:AssetFoundationSql.MUTATION_LOCK;
            var result=DSL.using(SQLDialect.POSTGRES).newResult(AssetFoundationRepository.fields(sql));
            var row=DSL.using(SQLDialect.POSTGRES).newRecord(AssetFoundationRepository.fields(sql));
            row.set(DSL.field(AssetFoundationRepository.fields(sql)[0].getName(),String.class),"");result.add(row);
            return new MockResult[]{new MockResult(1,result)};
        });
        bind(connection);var repository=new AssetFoundationRepository(source,actions);
        repository.lock(AssetFoundationSql.RECEIPT_LOCK,List.of(context.tenantId(),context.actorId(),"workshop.update",key));
        repository.lock(AssetFoundationSql.MUTATION_LOCK,List.of("workshop",context.tenantId(),entity,2L,command));
        assertEquals(new ArrayList<>(expected.keySet()),observed);verifyNoInteractions(source);
    }
    @Test void databaseFailureRetainsCauseStateAndCategoryWithoutChangingPublicMessage() {
        var original=new java.sql.SQLException("private fixture detail","23505");
        var connection=new MockConnection(execution->{throw original;});bind(connection);
        var failure=assertThrows(AssetFoundationFailure.class,()->new AssetFoundationRepository(source,actions).receipt(scope,key));
        assertEquals(409,failure.status());assertEquals("23505",failure.sqlState());assertEquals("DATABASE_CONFLICT",failure.category());
        assertInstanceOf(org.jooq.exception.DataAccessException.class,failure.getCause());
        assertSame(original,failure.getCause().getCause());assertFalse(failure.getMessage().contains("private fixture detail"));
        assertTrue(((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).isRollbackOnly());
    }
    @ParameterizedTest @EnumSource(value=AssetFoundationSql.class,names={"WORKSHOP_ARCHIVE_CLEAR","PRODUCTION_LINE_ARCHIVE_CLEAR","PROCESS_STAGE_ARCHIVE_CLEAR","EQUIPMENT_ARCHIVE_CLEAR","WORK_UNIT_ARCHIVE_CLEAR"})
    void archiveAbsenceQueriesBindTheSuppliedSingleFinalCutAndKeepFutureUnexpiredLinks(AssetFoundationSql sql) {
        UUID id=UUID.randomUUID();var cut=java.time.Instant.parse("2026-11-04T00:00:01.123456Z");var queries=new AtomicInteger();
        var connection=new MockConnection(execution->{
            queries.incrementAndGet();assertEquals(sql.sql,execution.sql());
            assertArrayEquals(new Object[]{context.tenantId(),id,cut.atOffset(java.time.ZoneOffset.UTC)},execution.bindings());
            assertFalse(execution.sql().contains("clock_timestamp"));assertFalse(execution.sql().contains("valid_from<="));
            if(sql==AssetFoundationSql.WORK_UNIT_ARCHIVE_CLEAR)assertFalse(execution.sql().contains("existence_state"));
            var result=DSL.using(SQLDialect.POSTGRES).newResult(AssetFoundationRepository.fields(sql));
            var row=DSL.using(SQLDialect.POSTGRES).newRecord(AssetFoundationRepository.fields(sql));row.set(DSL.field("archive_clear",Boolean.class),true);result.add(row);
            return new MockResult[]{new MockResult(1,result)};
        });bind(connection);
        assertTrue(new AssetFoundationRepository(source,actions).archiveClear(sql,scope,id,cut));assertEquals(1,queries.get());
    }
    @Test void rejectedScopeNeverExecutesReceiptSql() {
        var queries=new AtomicInteger();
        var connection=new MockConnection(execution->{queries.incrementAndGet();throw new AssertionError("unauthorized SQL");});
        bind(connection);
        doThrow(new AccessDeniedException("fixture rejected")).when(actions).requireSqlConnection(scope,source,connection);
        assertThrows(AccessDeniedException.class,()->new AssetFoundationRepository(source,actions).receipt(scope,key));
        assertEquals(0,queries.get());verifyNoInteractions(source);
    }
}
