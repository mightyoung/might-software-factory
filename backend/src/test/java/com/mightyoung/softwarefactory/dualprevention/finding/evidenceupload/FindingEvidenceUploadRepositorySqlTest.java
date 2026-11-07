package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.datasource.ConnectionHolder;
import javax.sql.DataSource;
import java.sql.Connection;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FindingEvidenceUploadRepositorySqlTest {
    final UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();
    final List<String> sql=new ArrayList<>();
    final DataSource source=mock(DataSource.class);
    final Manager manager=new Manager();
    String physicalTenant=tenant.toString();
    Boolean physicalReadOnly;
    Integer physicalIsolation;
    boolean physicalAutoCommit;
    final AuthorizationWriteGuard guard=mock(AuthorizationWriteGuard.class);
    final OffsetDateTime now=OffsetDateTime.parse("2026-11-03T00:00:00Z");
    boolean failAudit;
    final MockConnection jdbc=new MockConnection(context -> {
        String query=context.sql(); sql.add(query);
        if(query.contains("set_config('app.tenant_id'")) return row("set_config",String.class,tenant.toString());
        if(query.contains("select current_setting('app.tenant_id', true)")) {
            var a=DSL.field("tenant",String.class); var b=DSL.field("isolation",String.class); var c=DSL.field("readonly",String.class);
            var result=DSL.using(SQLDialect.POSTGRES).newResult(a,b,c);
            var record=DSL.using(SQLDialect.POSTGRES).newRecord(a,b,c);
            record.values(physicalTenant,TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "repeatable read" : "read committed",
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "on" : "off");result.add(record);
            return new MockResult[]{new MockResult(1,result)};
        }
        if(query.equals("select current_timestamp as now")) return row("now",OffsetDateTime.class,now);
        if(query.startsWith("insert into public.audit_event") && failAudit) throw new SQLException("audit unavailable");
        if(query.startsWith("insert into")) return new MockResult[]{new MockResult(1)};
        return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("unused",String.class)))};
    }) {
        @Override public boolean getAutoCommit(){return physicalAutoCommit;}
        @Override public boolean isReadOnly(){return physicalReadOnly!=null ? physicalReadOnly : TransactionSynchronizationManager.isCurrentTransactionReadOnly();}
        @Override public int getTransactionIsolation(){return physicalIsolation!=null ? physicalIsolation : TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();}
    };
    final DSLContext dsl=DSL.using(jdbc,SQLDialect.POSTGRES);
    @BeforeEach void bindManager(){manager.source=source;manager.jdbc=jdbc;}
    <T> MockResult[] row(String name,Class<T> type,T value) {
        var field=DSL.field(name,type);
        var result=DSL.using(SQLDialect.POSTGRES).newResult(field);
        var record=DSL.using(SQLDialect.POSTGRES).newRecord(field); record.set(field,value); result.add(record);
        return new MockResult[]{new MockResult(1,result)};
    }
    JooqFindingEvidenceUploadRepository writes() { return new JooqFindingEvidenceUploadRepository(dsl,manager,guard,source); }
    @Test void ownsReadCommittedCommitAndLocksQuotaBeforeBusinessRows() {
        doAnswer(call -> {sql.add("GUARD");return null;}).when(guard).forFindingCommand(tenant);
        String result=writes().inTenantTransaction(tenant,tx -> {
            assertThat(manager.commits).isZero();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.finding(id);tx.evidenceSet(id); return "durable";
        });
        assertThat(result).isEqualTo("durable"); assertThat(manager.commits).isEqualTo(1);
        int quotaIndex=java.util.stream.IntStream.range(0,sql.size()).filter(i -> sql.get(i).contains("pg_advisory_xact_lock(?, ?)")).findFirst().orElseThrow();
        int findingIndex=java.util.stream.IntStream.range(0,sql.size()).filter(i -> sql.get(i).contains("from public.finding")).findFirst().orElseThrow();
        int setIndex=java.util.stream.IntStream.range(0,sql.size()).filter(i -> sql.get(i).contains("from public.evidence_set")).findFirst().orElseThrow();
        assertThat(sql.indexOf("GUARD")).isLessThan(quotaIndex);
        assertThat(quotaIndex).isLessThan(findingIndex);assertThat(findingIndex).isLessThan(setIndex);
        assertThat(sql.get(findingIndex)).contains("for update");assertThat(sql.get(setIndex)).contains("for update");
        verify(guard,atLeast(3)).requireFindingCommand(tenant);
    }
    @Test void insertAndSuccessAuditShareCallbackAndAuditFailureRollsBack() {
        failAudit=true;
        var owner=new CurrentTenantContext(id,id,tenant,id,0,0,1);
        var command=new ReserveCommand(id,id,1,id,0,0,"evidence.pdf","application/pdf",1);
        assertThatThrownBy(() -> writes().inTenantTransaction(tenant,tx -> tx.insert(owner,command,new Notice(id,id,true),
                command.fingerprint(owner,id),FindingEvidenceUploadServiceTest.capability()))).isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(manager.commits).isZero(); assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(String.join("\n",sql)).contains("on conflict (tenant_id,owner_actor_id,operation,idempotency_key) do nothing",
                "insert into public.audit_event").doesNotContain("file_object","file_link","update public.finding");
    }
    @Test void rollbackOnlyCannotReturnSuccess() {
        manager.rollbackOnly=true;
        assertThatThrownBy(() -> writes().inTenantTransaction(tenant,tx -> "not committed"))
                .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(manager.commits).isZero(); assertThat(manager.rollbacks).isEqualTo(1);
    }
    @Test void commitFailureDoesNotReturnCallbackResultAndSnapshotCannotEscape() {
        AtomicReference<FindingEvidenceUploadRepository.Transaction> escaped=new AtomicReference<>(); manager.failCommit=true;
        assertThatThrownBy(() -> writes().inTenantTransaction(tenant,tx -> {escaped.set(tx);return "not committed";}))
                .isInstanceOf(IllegalStateException.class).hasMessage("commit failed");
        assertThatThrownBy(() -> escaped.get().now()).isInstanceOf(IllegalStateException.class);
    }
    @Test void statusOwnsReadOnlyRepeatableReadAndRejectsEscapedOrCrossThreadSnapshot() throws Exception {
        var reads=new JooqFindingEvidenceUploadReadRepository(dsl,manager,source);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        var escaped=reads.inTenantReadSnapshot(tenant,snapshot -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var thread=new Thread(() -> {try{snapshot.now();}catch(Throwable e){failure.set(e);}});
            thread.start();try {thread.join();}catch(InterruptedException e){throw new IllegalStateException(e);}
            snapshot.finding(id);return snapshot;
        });
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(escaped::now).isInstanceOf(IllegalStateException.class);
        assertThat(String.join("\n",sql)).doesNotContain("for update","advisory"); verifyNoInteractions(guard);
    }
    @Test void ambientBoundResourceRejectedBeforeAnySqlOrGuard() {
        Object key=new Object(); TransactionSynchronizationManager.bindResource(key,new Object());
        try { assertThatThrownBy(() -> writes().inTenantTransaction(tenant,tx -> 1)).isInstanceOf(IllegalStateException.class); }
        finally {TransactionSynchronizationManager.unbindResource(key);}
        assertThat(sql).isEmpty(); verifyNoInteractions(guard);
    }
    @Test void sameModeRequiresNewCannotBorrowOriginalCallbackAndOriginalStillWorksAfterResume() {
        writes().inTenantTransaction(tenant,tx -> {
            var inner=new TransactionTemplate(manager);inner.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            inner.execute(status -> {
                int before=sql.size();
                assertThatThrownBy(() -> tx.finding(id)).isInstanceOf(IllegalStateException.class);
                assertThat(sql).hasSize(before);return null;
            });
            tx.finding(id);return null;
        });
        assertThat(manager.commits).isEqualTo(2);
    }
    @Test void readOnlyRequiresNewCannotBorrowOriginalSnapshot() {
        var reads=new JooqFindingEvidenceUploadReadRepository(dsl,manager,source);
        reads.inTenantReadSnapshot(tenant,snapshot -> {
            var inner=new TransactionTemplate(manager);inner.setReadOnly(true);
            inner.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            inner.execute(status -> {
                assertThatThrownBy(snapshot::now).isInstanceOf(IllegalStateException.class);return null;
            });
            snapshot.now();return null;
        });
        assertThat(manager.commits).isEqualTo(2);
    }
    @Test void losingOriginalSynchronizationCannotAuthorizeSameHolder() {
        assertThatThrownBy(() -> writes().inTenantTransaction(tenant,tx -> {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            tx.now();return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThat(manager.commits).isZero();
    }
    @Test void replacementHolderOrPhysicalConnectionCannotBorrowCallback() {
        writes().inTenantTransaction(tenant,tx -> {
            var original=(MutableHolder)TransactionSynchronizationManager.unbindResource(source);
            var replacement=new ConnectionHolder(jdbc);replacement.setSynchronizedWithTransaction(true);
            TransactionSynchronizationManager.bindResource(source,replacement);
            try {assertThatThrownBy(tx::now).isInstanceOf(IllegalStateException.class);}
            finally {TransactionSynchronizationManager.unbindResource(source);TransactionSynchronizationManager.bindResource(source,original);}
            original.replace(new MockConnection(context -> new MockResult[0]));
            try {assertThatThrownBy(tx::now).isInstanceOf(IllegalStateException.class);}
            finally {original.replace(jdbc);}
            return null;
        });
    }
    @Test void rollbackOnlyHolderAndSavepointRollbackPermanentlyInvalidateCallback() {
        var escaped=new AtomicReference<FindingEvidenceUploadRepository.Transaction>();
        assertThatThrownBy(() -> writes().inTenantTransaction(tenant,tx -> {
            escaped.set(tx);
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);
            holder.setRollbackOnly();assertThatThrownBy(tx::now).isInstanceOf(IllegalStateException.class);
            holder.resetRollbackOnly();
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.savepointRollback(new Object());
            holder.resetRollbackOnly();
            assertThatThrownBy(tx::now).isInstanceOf(IllegalStateException.class);
            return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThat(manager.commits).isZero();
        assertThatThrownBy(() -> escaped.get().now()).isInstanceOf(IllegalStateException.class);
    }
    @Test void physicalTenantIsolationReadOnlyAndAutocommitMismatchesFailClosed() {
        for(int mismatch=0;mismatch<4;mismatch++) {
            int candidate=mismatch;
            assertThatThrownBy(() -> writes().inTenantTransaction(tenant,tx -> {
                if(candidate==0)physicalTenant=UUID.randomUUID().toString();
                if(candidate==1)physicalIsolation=Connection.TRANSACTION_REPEATABLE_READ;
                if(candidate==2)physicalReadOnly=true;
                if(candidate==3)physicalAutoCommit=true;
                tx.now();return null;
            })).isInstanceOf(IllegalStateException.class);
            physicalTenant=tenant.toString();physicalIsolation=null;physicalReadOnly=null;physicalAutoCommit=false;
        }
        assertThat(manager.commits).isZero();
    }
    @Test void invalidatedSecurityGuardIsRequiredAgainBeforeWriteOperations() {
        assertThatThrownBy(() -> writes().inTenantTransaction(tenant,tx -> {
            doThrow(new com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException("invalidated"))
                    .when(guard).requireFindingCommand(tenant);
            tx.quota(id,id);return null;
        })).isInstanceOf(com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException.class);
        assertThat(String.join("\n",sql)).doesNotContain("count(*) as tenant_count");
        assertThat(manager.commits).isZero();
    }
    static final class MutableHolder extends ConnectionHolder {
        MutableHolder(Connection connection){super(connection);}
        void replace(Connection connection){setConnection(connection);}
    }
    static final class Manager extends AbstractPlatformTransactionManager {
        int commits,rollbacks;boolean failCommit,rollbackOnly;
        DataSource source;Connection jdbc;
        final class Tx implements SmartTransactionObject {
            ConnectionHolder holder;
            @Override public boolean isRollbackOnly(){return rollbackOnly || holder!=null && holder.isRollbackOnly();}
            @Override public void flush(){}
        }
        @Override protected Object doGetTransaction(){var tx=new Tx();tx.holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);return tx;}
        @Override protected boolean isExistingTransaction(Object transaction){return ((Tx)transaction).holder!=null;}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){
            var tx=(Tx)transaction;tx.holder=new MutableHolder(jdbc);tx.holder.setSynchronizedWithTransaction(true);
            TransactionSynchronizationManager.bindResource(source,tx.holder);
        }
        @Override protected Object doSuspend(Object transaction){((Tx)transaction).holder=null;return TransactionSynchronizationManager.unbindResource(source);}
        @Override protected void doResume(Object transaction,Object resources){TransactionSynchronizationManager.bindResource(source,resources);}
        @Override protected void doCleanupAfterCompletion(Object transaction){TransactionSynchronizationManager.unbindResource(source);}
        @Override protected void doCommit(DefaultTransactionStatus status){if(failCommit)throw new IllegalStateException("commit failed");commits++;}
        @Override protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
