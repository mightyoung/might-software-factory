package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SQL and managed-callback evidence only; real PG rollback/concurrency remain acceptance gates. */
class FindingEvidenceSetInitializationRepositorySqlTest {
    final UUID tenant=UUID.randomUUID(),member=UUID.randomUUID(),id=UUID.randomUUID();
    final CurrentTenantContext owner=new CurrentTenantContext(id,id,tenant,member,0,0,1);
    final InitializeCommand command=new InitializeCommand(id,id,1,7);
    final FindingEvidenceUploadContracts.Notice notice=new FindingEvidenceUploadContracts.Notice(id,member,true);
    final DataSource source=mock(DataSource.class);
    final AuthorizationWriteGuard guard=mock(AuthorizationWriteGuard.class);
    final List<String> sql=new ArrayList<>();final List<Object[]> bindings=new ArrayList<>();
    final OffsetDateTime now=OffsetDateTime.parse("2026-11-03T00:00:00Z");
    boolean failReceipt,failAudit,receiptCollision,failCommit;
    int commits,rollbacks;
    final MockConnection jdbc=new MockConnection(context -> {
        String query=context.sql();sql.add(query);bindings.add(context.bindings());
        if(query.contains("select current_setting('app.tenant_id', true)")){
            var a=DSL.field("tenant",String.class);var b=DSL.field("isolation",String.class);var c=DSL.field("readonly",String.class);
            var rows=DSL.using(SQLDialect.POSTGRES).newResult(a,b,c);var row=DSL.using(SQLDialect.POSTGRES).newRecord(a,b,c);
            row.values(tenant.toString(),"read committed","off");rows.add(row);return new MockResult[]{new MockResult(1,rows)};
        }
        if(query.contains("set_config('app.tenant_id'"))return scalar("tenant",String.class,tenant.toString());
        if(query.equals("select current_timestamp as now"))return scalar("now",OffsetDateTime.class,now);
        if(query.contains("count(*) as tenant_count")){
            var a=DSL.field("owner_count",Long.class);var b=DSL.field("tenant_count",Long.class);
            var rows=DSL.using(SQLDialect.POSTGRES).newResult(a,b);var row=DSL.using(SQLDialect.POSTGRES).newRecord(a,b);
            row.values(3L,19L);rows.add(row);return new MockResult[]{new MockResult(1,rows)};
        }
        if(query.startsWith("insert into public.finding_evidence_set_initialization_receipt")){
            if(failReceipt)throw new SQLException("receipt unavailable");return new MockResult[]{new MockResult(receiptCollision?0:1)};
        }
        if(query.startsWith("insert into public.audit_event") && failAudit)throw new SQLException("audit unavailable");
        if(query.startsWith("insert into"))return new MockResult[]{new MockResult(1)};
        return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("unused",String.class)))};
    }){
        @Override public boolean getAutoCommit(){return false;}
        @Override public boolean isReadOnly(){return false;}
        @Override public int getTransactionIsolation(){return TRANSACTION_READ_COMMITTED;}
    };
    final DSLContext dsl=DSL.using(jdbc,SQLDialect.POSTGRES);
    final AbstractPlatformTransactionManager manager=new AbstractPlatformTransactionManager(){
        final class Tx implements SmartTransactionObject{
            ConnectionHolder holder;
            @Override public boolean isRollbackOnly(){return holder!=null && holder.isRollbackOnly();}
            @Override public void flush(){}
        }
        @Override protected Object doGetTransaction(){return new Tx();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){
            var tx=(Tx)transaction;tx.holder=new ConnectionHolder(jdbc);tx.holder.setSynchronizedWithTransaction(true);
            TransactionSynchronizationManager.bindResource(source,tx.holder);
        }
        @Override protected void doCommit(DefaultTransactionStatus status){if(failCommit)throw new IllegalStateException("commit failed");commits++;}
        @Override protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
        @Override protected void doCleanupAfterCompletion(Object transaction){TransactionSynchronizationManager.unbindResource(source);}
    };
    final JooqFindingEvidenceSetInitializationRepository repository=new JooqFindingEvidenceSetInitializationRepository(dsl,manager,guard,source);
    <T> MockResult[] scalar(String name,Class<T> type,T value){
        var field=DSL.field(name,type);var rows=DSL.using(SQLDialect.POSTGRES).newResult(field);
        var row=DSL.using(SQLDialect.POSTGRES).newRecord(field);row.set(field,value);rows.add(row);
        return new MockResult[]{new MockResult(1,rows)};
    }
    @Test void emptySetReceiptAndAuditCommitTogetherWithNoBusinessWorkflowOrFilesWrites(){
        var result=repository.inTenantTransaction(tenant,tx -> {
            assertThat(commits).isZero();assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            return tx.initialize(owner,command,notice,command.fingerprint(owner,id));
        });
        assertThat(result.evidenceSetId()).isNotNull();assertThat(result.result(false).initialEvidenceRevision()).isZero();
        assertThat(commits).isEqualTo(1);assertThat(rollbacks).isZero();
        String statements=String.join("\n",sql);
        assertThat(statements).contains("insert into public.evidence_set","'UPLOADING',cast(? as timestamptz),0)",
                "insert into public.finding_evidence_set_initialization_receipt","insert into public.audit_event")
                .doesNotContain("insert into public.evidence_item","file_object","file_link","event_publication","finding_command_receipt",
                        "update public.finding","update public.evidence_set","verified_at");
        verify(guard).forFindingCommand(tenant);verify(guard,atLeast(2)).requireFindingCommand(tenant);
    }
    @Test void currentRoundUnsealedEditableSetCountIncludesNonemptySetsAndLocksQuotaBeforeFinding(){
        doAnswer(call -> {sql.add("GUARD");bindings.add(new Object[0]);return null;}).when(guard).forFindingCommand(tenant);
        Quota quota=repository.inTenantTransaction(tenant,tx -> {tx.finding(id);return tx.quota(member);});
        assertThat(quota).isEqualTo(new Quota(3,19));
        int lock=index("pg_advisory_xact_lock");int finding=index("from public.finding");int count=index("count(*) as tenant_count");
        assertThat(sql.indexOf("GUARD")).isLessThan(lock);assertThat(lock).isLessThan(finding);assertThat(finding).isLessThan(count);
        assertThat(bindings.get(lock)).containsExactly(JooqFindingEvidenceUploadRepository.QUOTA_NAMESPACE,tenant.hashCode());
        assertThat(sql.get(count)).contains("f.round_no=s.round_no","r.round_no=s.round_no","s.submission_sealed_at is null",
                "s.state in ('UPLOADING','QUARANTINED','READY')","f.state='RECTIFYING'","r.state='OPEN'")
                .doesNotContain("evidence_item","size_bytes","delete","expires_at");
        assertThat(bindings.get(count)).containsExactly(member,tenant);
    }
    @Test void receiptOrAuditFailureRollsBackInsteadOfReturningSet(){
        failReceipt=true;
        assertThatThrownBy(() -> repository.inTenantTransaction(tenant,tx -> tx.initialize(owner,command,notice,command.fingerprint(owner,id))))
                .isInstanceOf(org.jooq.exception.DataAccessException.class);
        failReceipt=false;failAudit=true;
        assertThatThrownBy(() -> repository.inTenantTransaction(tenant,tx -> tx.initialize(owner,command,notice,command.fingerprint(owner,id))))
                .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(commits).isZero();assertThat(rollbacks).isEqualTo(2);
    }
    @Test void outOfProtocolUniqueCollisionRollsBackProvisionalSetAndNeverAudits(){
        receiptCollision=true;
        assertThatThrownBy(() -> repository.inTenantTransaction(tenant,tx -> tx.initialize(owner,command,notice,command.fingerprint(owner,id))))
                .isInstanceOfSatisfying(FindingEvidenceSetInitializationException.class,e -> assertThat(e.reason()).isEqualTo(FindingEvidenceSetInitializationException.Reason.IDEMPOTENCY_CONFLICT));
        assertThat(commits).isZero();assertThat(rollbacks).isEqualTo(1);
        assertThat(String.join("\n",sql)).contains("on conflict (tenant_id,owner_actor_id,operation,idempotency_key) do nothing")
                .doesNotContain("insert into public.audit_event");
    }
    @Test void commitFailureAndRollbackOnlyCannotReturnSuccessfulInitialization(){
        failCommit=true;
        assertThatThrownBy(() -> repository.inTenantTransaction(tenant,tx -> tx.initialize(owner,command,notice,command.fingerprint(owner,id))))
                .isInstanceOf(IllegalStateException.class).hasMessage("commit failed");
        failCommit=false;
        assertThatThrownBy(() -> repository.inTenantTransaction(tenant,tx -> {
            var result=tx.initialize(owner,command,notice,command.fingerprint(owner,id));
            ((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).setRollbackOnly();return result;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(commits).isZero();
    }
    @Test void initializationContextCannotEscapeOrSurviveSavepointRollback(){
        var escaped=new AtomicReference<FindingEvidenceSetInitializationRepository.Transaction>();
        assertThatThrownBy(() -> repository.inTenantTransaction(tenant,tx -> {
            escaped.set(tx);
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.savepointRollback(new Object());
            ((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).resetRollbackOnly();
            tx.quota(member);return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> escaped.get().quota(member)).isInstanceOf(IllegalStateException.class);
        assertThat(commits).isZero();
    }
    @Test void independentNamespaceLookupUsesServerActorAndNoFindingReceipt(){
        repository.inTenantTransaction(tenant,tx -> tx.existing(owner.actorId(),command.idempotencyKey()));
        int lookup=index("select * from public.finding_evidence_set_initialization_receipt");
        assertThat(bindings.get(lookup)).containsExactly(tenant,owner.actorId(),OPERATION,command.idempotencyKey());
        assertThat(sql.get(lookup)).doesNotContain("finding_command_receipt","finding_evidence_upload_intent");
    }
    int index(String fragment){return java.util.stream.IntStream.range(0,sql.size()).filter(i -> sql.get(i).contains(fragment)).findFirst().orElseThrow();}
}
