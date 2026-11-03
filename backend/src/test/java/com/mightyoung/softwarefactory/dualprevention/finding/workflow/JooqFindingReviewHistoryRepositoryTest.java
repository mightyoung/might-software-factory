package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import com.mightyoung.softwarefactory.workflow.WorkflowEngineUnavailableException;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mock connection/transaction contract evidence; no claim of real PostgreSQL RR isolation. */
class JooqFindingReviewHistoryRepositoryTest {
    final UUID tenant=UUID.randomUUID(),finding=UUID.randomUUID(),org=UUID.randomUUID();
    final FindingReadScope scope=new FindingReadScope(UUID.randomUUID(),tenant,UUID.randomUUID(),Set.of(org));
    final DataSource source=mock(DataSource.class);
    final Connection connection=mock(Connection.class);
    final ConnectionHolder holder=new ConnectionHolder(connection);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final List<String> queries=new ArrayList<>();
    boolean includeRows;
    boolean timeoutsValid=true;
    JooqFindingReviewHistoryRepository repository;
    @BeforeEach void setup() throws Exception {
        when(connection.isReadOnly()).thenReturn(true);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        var jdbc=new MockConnection(call -> {
            queries.add(call.sql());
            var dsl=DSL.using(SQLDialect.POSTGRES);
            if(call.sql().contains("current_setting")) {
                var t=DSL.field("tenant",String.class);var i=DSL.field("isolation",String.class);var r=DSL.field("readonly",String.class);
                var st=DSL.field("statement_timeout_valid",Boolean.class);var lt=DSL.field("lock_timeout_valid",Boolean.class);
                var rows=dsl.newResult(t,i,r,st,lt);rows.add(dsl.newRecord(t,i,r,st,lt).values(tenant.toString(),"repeatable read","on",timeoutsValid,timeoutsValid));
                return new MockResult[]{new MockResult(1,rows)};
            }
            if(call.sql().contains("set_config")) {
                var f=DSL.field("set_config",String.class);var rows=dsl.newResult(f);rows.add(dsl.newRecord(f).values(tenant.toString()));
                return new MockResult[]{new MockResult(1,rows)};
            }
            if (includeRows && call.sql().contains("union all")) {
                var fields=new org.jooq.Field<?>[]{DSL.field("id",String.class),DSL.field("origin",String.class),DSL.field("review_cycle",Integer.class),DSL.field("stage",String.class),DSL.field("decision",String.class),DSL.field("actor_member_id",UUID.class),DSL.field("occurred_at",java.time.OffsetDateTime.class),DSL.field("text",String.class),DSL.field("recorded_grade",String.class),DSL.field("next_review_cycle",Integer.class)};
                var rows=dsl.newResult(fields);var row=dsl.newRecord(fields);
                row.fromArray("legacy-confirmation:"+finding+":PERSON","LEGACY_CONFIRMATION",1,"PERSON","APPROVE",scope.memberId(),java.time.OffsetDateTime.parse("2026-01-01T08:00:00+08:00"),null,null,null);rows.add(row);
                return new MockResult[]{new MockResult(1,rows)};
            }
            if (includeRows && call.sql().contains("created_by_member_id")) {
                var fields=new org.jooq.Field<?>[]{DSL.field("id",UUID.class),DSL.field("organization_id",UUID.class),DSL.field("created_by_member_id",UUID.class),DSL.field("state",String.class),DSL.field("version",Long.class),DSL.field("review_cycle",Integer.class),DSL.field("review_protocol",String.class)};
                var rows=dsl.newResult(fields);var row=dsl.newRecord(fields);row.fromArray(finding,org,scope.memberId(),"CLOSED",7L,1,"LEGACY");rows.add(row);
                return new MockResult[]{new MockResult(1,rows)};
            }
            var f=DSL.field("id",UUID.class);return new MockResult[]{new MockResult(0,dsl.newResult(f))};
        });
        when(connection.prepareStatement(anyString())).thenAnswer(call -> jdbc.prepareStatement(call.getArgument(0)));
        when(manager.getTransaction(any())).thenAnswer(call -> {
            var definition=(TransactionDefinition)call.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);assertThat(definition.getTimeout()).isEqualTo(5);
            holder.setSynchronizedWithTransaction(true);TransactionSynchronizationManager.bindResource(source,holder);
            TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return new SimpleTransactionStatus();
        });
        repository=new JooqFindingReviewHistoryRepository(source,manager);
    }
    @AfterEach void clear() { TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear(); }
    @Test void parameterizedUnionMapsTypedFieldsAndPreservesNullLegacyValues() {
        includeRows=true;
        repository.inTenantReadSnapshot(tenant,snapshot -> {
            assertThat(snapshot.find(scope,finding)).contains(new FindingReviewHistoryRepository.Current(finding,org,scope.memberId(),"CLOSED",7,1,FindingReviewProtocol.LEGACY));
            var entries=snapshot.entries(scope,finding,null,21);
            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().id()).isEqualTo("legacy-confirmation:"+finding+":PERSON");
            assertThat(entries.getFirst().recordedGrade()).isNull();assertThat(entries.getFirst().text()).isNull();
            assertThat(entries.getFirst().occurredAt().getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
            return null;
        });
        assertThat(queries.stream().filter(q -> q.contains("union all")).findFirst().orElseThrow()).contains("order by h.review_cycle, h.occurred_at, h.origin, h.id").doesNotContain(finding.toString(),tenant.toString(),org.toString());
    }

    @Test void snapshotQueriesPinnedConnectionAndCannotEscapeLifetime() {
        AtomicReference<FindingReviewHistoryRepository.Snapshot> escaped=new AtomicReference<>();
        repository.inTenantReadSnapshot(tenant,snapshot -> { escaped.set(snapshot);assertThat(snapshot.find(scope,finding)).isEmpty();return null; });
        assertThatThrownBy(() -> escaped.get().find(scope,finding)).isInstanceOf(WorkflowEngineUnavailableException.class);
        assertThat(queries).anySatisfy(q -> assertThat(q).contains("statement_timeout")).anySatisfy(q -> assertThat(q).contains("lock_timeout"));
        assertThat(queries).allSatisfy(q -> assertThat(q.toLowerCase()).doesNotContain("for update","for share","insert ","delete "));
        verifyNoInteractions(source);
    }
    @Test void changedTimeoutOrSynchronizationInvalidatesOriginalSnapshot() {
        repository.inTenantReadSnapshot(tenant,snapshot -> {
            timeoutsValid=false;
            assertThatThrownBy(() -> snapshot.find(scope,finding)).isInstanceOf(WorkflowEngineUnavailableException.class);
            timeoutsValid=true;
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            assertThatThrownBy(() -> snapshot.find(scope,finding)).isInstanceOf(WorkflowEngineUnavailableException.class);
            return null;
        });
    }

    @Test void replacingHolderOrClearingSynchronizationsInvalidatesSnapshot() {
        repository.inTenantReadSnapshot(tenant,snapshot -> {
            TransactionSynchronizationManager.unbindResource(source);
            TransactionSynchronizationManager.bindResource(source,new ConnectionHolder(connection));
            assertThatThrownBy(() -> snapshot.find(scope,finding)).isInstanceOf(WorkflowEngineUnavailableException.class);
            return null;
        });
    }
    @Test void ambientResourcesAreRejectedBeforeStartingTransaction() {
        TransactionSynchronizationManager.bindResource(source,holder);
        assertThatThrownBy(() -> repository.inTenantReadSnapshot(tenant,snapshot -> null)).isInstanceOf(WorkflowEngineUnavailableException.class);
        verifyNoInteractions(manager);
    }
    @Test void snapshotCannotBeUsedOnAnotherThread() {
        repository.inTenantReadSnapshot(tenant,snapshot -> {
            var failure=new AtomicReference<Throwable>();
            var thread=new Thread(() -> {try {snapshot.find(scope,finding);}catch(Throwable error){failure.set(error);}});
            thread.start();try {thread.join(1000);}catch(InterruptedException error){throw new AssertionError(error);}
            assertThat(failure.get()).isInstanceOf(WorkflowEngineUnavailableException.class);return null;
        });
    }
}
