package com.mightyoung.softwarefactory.identity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mightyoung.softwarefactory.testing.CiPg18EphemeralTarget;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;
import static com.mightyoung.softwarefactory.identity.ReauthIssuerContracts.*;

/** Actual PG18/V20/runtime role and production issuer→revoke. Synthetic current session, no HTTP claim. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class CiPg18ReauthPostgresIT {
    private CiPg18ReauthFixture fixture;
    @BeforeAll void open(){fixture=CiPg18ReauthFixture.open(CiPg18EphemeralTarget.from(System.getenv(),CiPg18EphemeralTarget.Lane.REAUTH));}
    @AfterAll void close(){if(fixture!=null)fixture.close();}

    @Test void originalSchemaPrivilegesAndActualGuardAreEnforced()throws Exception{
        fixture.verifyHistory();fixture.verifySchema();fixture.verifyRights();
        try(var c=caseData();Connection raw=fixture.runtime().getConnection()){
            raw.setAutoCommit(false);
            try{
                SQLException refused=null;
                try{CiPg18ReauthFixture.execute(raw,"""
                        insert into public.sensitive_reauth_proof(id,account_id,actor_id,source_primary_id,issued_credential_version,action,
                        target_primary_id,expected_authority_version,body_key_id,body_digest,secret_digest,state,version,created_at,expires_at,issuance_receipt_id)
                        values(?,?,?,?,1,'SESSION_REVOKE',?,1,'k0',?,?,'ISSUED',1,statement_timestamp(),statement_timestamp()+interval '5 minutes',?)
                        """,UUID.randomUUID(),c.actor.account(),c.actor.actor(),c.source.primary(),c.target.primary(),"0".repeat(64),"1".repeat(64),UUID.randomUUID());}catch(SQLException failure){refused=failure;}
                assertThat(refused!=null).as("unguarded proof write is refused").isTrue();
                assertThat(refused.getSQLState()).isEqualTo("23514");
                boolean globalGuardOrigin=refused instanceof org.postgresql.util.PSQLException pg
                        &&pg.getServerErrorMessage()!=null&&pg.getServerErrorMessage().getWhere()!=null
                        &&pg.getServerErrorMessage().getWhere().contains("require_sensitive_global_guard");
                assertThat(globalGuardOrigin).as("refusal originates in actual global guard").isTrue();
            }finally{raw.rollback();}
        }
    }

    @Test void actualIssuerFeedsActualActiveTargetRevoke()throws Exception{
        try(var c=caseData();var issued=(Issued)c.issue(UUID.randomUUID())){
            UUID proof=issued.metadata().proofId();assertThat(proofState(proof)).isEqualTo("ISSUED");
            assertThat(fixture.count("select count(*) from public.sensitive_reauth_issuance_receipt where id=? and proof_id=?",issued.metadata().issuanceReceiptId(),proof)).isEqualTo(1);
            assertThat(fixture.count("select count(*) from public.identity_sensitive_event where proof_id=? and operation='ISSUE_REAUTH' and result='SUCCESS' and target_primary_id is null and challenge_id is null",proof)).isEqualTo(1);
            c.graph.revoke(c.current,c.target,1,UUID.randomUUID(),issued);
            assertThat(proofState(proof)).isEqualTo("CONSUMED");assertThat(authorityState(c.target)).isEqualTo("REVOKED");
            assertThat(fixture.count("select count(*) from public.session_authority_command_receipt where proof_id=? and result_authority_version=2",proof)).isEqualTo(1);
            assertThat(fixture.extraBorrows).hasValue(0);
        }
    }

    @Test void absentAuthorityTargetGetsDirectRevocationFence()throws Exception{
        try(var c=caseData()){
            var absent=fixture.session(c.actor,false);
            try(var issued=(Issued)c.graph.issue(c.current,c.actor,absent,0,UUID.randomUUID())){
                c.graph.revoke(c.current,absent,0,UUID.randomUUID(),issued);
                assertThat(fixture.value("select binding_kind from public.account_session_authority where primary_id=?",absent.primary())).isEqualTo("REMOTE_ABSENCE");
                assertThat(fixture.count("select count(*) from public.account_session_authority where primary_id=? and state='REVOKED' and version=1",absent.primary())).isEqualTo(1);
                assertThat(proofState(issued.metadata().proofId())).isEqualTo("CONSUMED");
            }
        }
    }

    @Test void issuanceAndConsumptionReceiptsReplayWithoutRedeliveringToken()throws Exception{
        try(var c=caseData()){
            UUID issueKey=UUID.randomUUID(),revokeKey=UUID.randomUUID();
            try(var issued=(Issued)c.issue(issueKey)){
                c.graph.revoke(c.current,c.target,1,revokeKey,issued);long[] before=counts(c);
                try(var replay=c.issue(issueKey)){assertThat(replay).isInstanceOf(Replay.class);assertThat(replay.metadata()).isEqualTo(issued.metadata());assertThat(((Replay)replay).tokenDelivery()).isEqualTo("UNAVAILABLE_ON_REPLAY");}
                c.graph.revoke(c.current,c.target,1,revokeKey,issued);assertThat(counts(c)).containsExactly(before);
                assertThatThrownBy(()->c.graph.issue(c.current,c.actor,c.target,0,issueKey)).isInstanceOf(SessionSensitiveException.class)
                        .satisfies(error->assertThat(((SessionSensitiveException)error).reason()).isEqualTo(SessionSensitiveException.Reason.CONFLICT));
                assertThat(counts(c)).containsExactly(before);
            }
        }
    }

    @Test void explicitReissueAcrossRetainedKeysReplacesOnlyExactLiveProof()throws Exception{
        try(var c=caseData()){
            var g1=new CiPg18ReauthGraph(fixture,fixture.keys("k1","k0","k1"));var other=fixture.session(c.actor);g1.register(c.actor,other);
            try(var old=(Issued)g1.issue(c.current,c.actor,c.target,1,UUID.randomUUID());var unrelated=(Issued)g1.issue(c.current,c.actor,other,1,UUID.randomUUID())){
                var g2=new CiPg18ReauthGraph(fixture,fixture.keys("k2","k0","k1","k2"));
                try(var fresh=(Issued)g2.issue(c.current,c.actor,c.target,1,UUID.randomUUID())){
                    assertThat(proofState(old.metadata().proofId())).isEqualTo("REVOKED");assertThat(proofState(unrelated.metadata().proofId())).isEqualTo("ISSUED");
                    assertThatThrownBy(()->g2.revoke(c.current,c.target,1,UUID.randomUUID(),old)).isInstanceOf(SessionSensitiveException.class);
                    g2.revoke(c.current,c.target,1,UUID.randomUUID(),fresh);assertThat(proofState(fresh.metadata().proofId())).isEqualTo("CONSUMED");
                }
                var missing=new CiPg18ReauthGraph(fixture,fixture.keys("k2","k0","k2"));long before=fixture.count("select count(*) from public.sensitive_reauth_proof where account_id=?",c.actor.account());
                assertThatThrownBy(()->missing.issue(c.current,c.actor,other,1,UUID.randomUUID())).isInstanceOf(SessionSensitiveException.class).hasMessage("UNAVAILABLE");
                assertThat(proofState(unrelated.metadata().proofId())).isEqualTo("ISSUED");
                assertThat(fixture.count("select count(*) from public.sensitive_reauth_proof where account_id=?",c.actor.account())).isEqualTo(before);
            }
        }
    }

    @Test void currentCredentialAndSourceChangesMeetBothGuardCommitOrders()throws Exception{
        try(var c=caseData();var executor=Executors.newFixedThreadPool(2)){
            var prepared=new CountDownLatch(1);PasswordEncoder delegate=PasswordEncoderFactories.createDelegatingPasswordEncoder();
            PasswordEncoder encoder=new PasswordEncoder(){public String encode(CharSequence raw){return delegate.encode(raw);}public boolean matches(CharSequence raw,String hash){boolean ok=delegate.matches(raw,hash);prepared.countDown();return ok;}};
            var graph=new CiPg18ReauthGraph(fixture,fixture.keys("k0","k0"),encoder);AtomicReference<Future<Throwable>> attempt=new AtomicReference<>();
            String changed="{bcrypt}"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("replacement-"+UUID.randomUUID());
            fixture.adminTransaction(List.of(c.actor.tenant()),connection->{
                attempt.set(executor.submit(()->{try(var current=new CiPg18ReauthSession(c.actor,c.source);var ignored=graph.issue(current,c.actor,c.target,1,UUID.randomUUID())){return null;}catch(Throwable failure){return failure;}}));
                assertThat(prepared.await(3,TimeUnit.SECONDS)).isTrue();
                assertThat(executor.submit(this::waitForBlockedRuntime).get(4,TimeUnit.SECONDS)).isTrue();
                // Deliberately same version: this tests the actual locked exact-hash re-read, not only generation.
                CiPg18ReauthFixture.execute(connection,"update public.credential set secret_hash=? where account_id=? and kind='PASSWORD'",changed,c.actor.account());
            });
            assertThat(attempt.get().get(10,TimeUnit.SECONDS)).isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");assertThat(proofCount(c)).isZero();
        }
        try(var c=caseData();var executor=Executors.newFixedThreadPool(2)){
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);
            fixture.afterProof.set(()->{held.countDown();await(release);});
            Future<Outcome> first=executor.submit(()->{try(var current=new CiPg18ReauthSession(c.actor,c.source)){return c.graph.issue(current,c.actor,c.target,1,UUID.randomUUID());}});
            assertThat(held.await(3,TimeUnit.SECONDS)).isTrue();
            Future<?> revocation=executor.submit(()->{
                try(var current=new CiPg18ReauthSession(c.actor,c.target);var issued=(Issued)c.graph.issue(current,c.actor,c.source,1,UUID.randomUUID())){c.graph.revoke(current,c.source,1,UUID.randomUUID(),issued);}
            });
            try{assertThat(waitForBlockedRuntime()).isTrue();}finally{release.countDown();}
            try(var issued=(Issued)first.get(10,TimeUnit.SECONDS)){
                revocation.get(10,TimeUnit.SECONDS);assertThat(proofState(issued.metadata().proofId())).isEqualTo("ISSUED");assertThat(authorityState(c.source)).isEqualTo("REVOKED");
                assertThatThrownBy(()->c.graph.revoke(c.current,c.target,1,UUID.randomUUID(),issued)).isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
            }
        }
    }

    @Test void lateActualAuditFailureRollsBackIssuanceAndTerminalization()throws Exception{
        for(Level level:List.of(Level.INFO,Level.DEBUG))try(var c=caseData();var old=(Issued)c.issue(UUID.randomUUID())){
            long[] before=counts(c);int proofs=fixture.proofWrites.get(),receipts=fixture.receiptWrites.get(),rollbacks=fixture.issueRollbacks.get();
            diagnostics(level,c.actor.password(),()->{
                fixture.fault.set(CiPg18ReauthFixture.Fault.AUDIT_WRITE);
                assertThatThrownBy(()->c.issue(UUID.randomUUID())).isInstanceOf(SessionSensitiveException.class).hasMessage("UNAVAILABLE");
            });
            assertThat(fixture.proofWrites.get()-proofs).isEqualTo(1);assertThat(fixture.receiptWrites.get()-receipts).isEqualTo(1);assertThat(fixture.issueRollbacks.get()-rollbacks).isEqualTo(1);
            assertThat(counts(c)).containsExactly(before);assertThat(proofState(old.metadata().proofId())).isEqualTo("ISSUED");
        }
    }

    @Test void deferredCommitConstraintFailureRollsBackTheWholeGraph()throws Exception{
        for(Level level:List.of(Level.INFO,Level.DEBUG))try(var c=caseData();var old=(Issued)c.issue(UUID.randomUUID())){
            long[] before=counts(c);int proofs=fixture.proofWrites.get(),receipts=fixture.receiptWrites.get(),audits=fixture.successAuditWrites.get(),commits=fixture.issueCommits.get();
            diagnostics(level,c.actor.password(),()->{
                fixture.lastSqlState.set(null);fixture.fault.set(CiPg18ReauthFixture.Fault.DEFERRED_TARGET);
                assertThatThrownBy(()->c.issue(UUID.randomUUID())).isInstanceOf(SessionSensitiveException.class).hasMessage("UNAVAILABLE");
            });
            assertThat(fixture.lastSqlState).hasValue("23514");assertThat(fixture.proofWrites.get()-proofs).isEqualTo(1);assertThat(fixture.receiptWrites.get()-receipts).isEqualTo(1);assertThat(fixture.successAuditWrites.get()-audits).isEqualTo(1);
            assertThat(fixture.issueCommits.get()-commits).isZero();assertThat(counts(c)).containsExactly(before);assertThat(proofState(old.metadata().proofId())).isEqualTo("ISSUED");
        }
    }

    @Test void commitThenThrowKeepsOneDurableIssueAndNeverDeliversItsToken()throws Exception{
        for(Level level:List.of(Level.INFO,Level.DEBUG))for(var fault:List.of(CiPg18ReauthFixture.Fault.COMMIT_SQL,CiPg18ReauthFixture.Fault.COMMIT_RUNTIME,CiPg18ReauthFixture.Fault.COMMIT_ERROR))try(var c=caseData()){
            UUID key=UUID.randomUUID();int commits=fixture.issueCommits.get();
            diagnostics(level,c.actor.password(),()->{fixture.fault.set(fault);assertThatThrownBy(()->c.issue(key)).isInstanceOf(SessionSensitiveException.class).hasMessage("UNAVAILABLE");});
            assertThat(counts(c)).containsExactly(1,1,0,1);assertThat(fixture.issueCommits.get()-commits).isEqualTo(1);
            try(var replay=c.issue(key)){assertThat(replay).isInstanceOf(Replay.class);assertThat(((Replay)replay).tokenDelivery()).isEqualTo("UNAVAILABLE_ON_REPLAY");}
            assertThat(counts(c)).containsExactly(1,1,0,1);
        }
    }

    @Test void crossAccountSourceTargetAndTenantContextCannotBorrowTheProof()throws Exception{
        try(var c=caseData();var proof=(Issued)c.issue(UUID.randomUUID())){
            // A second legitimate source for the same account is authorized in its own right,
            // but cannot consume a proof bound to the first source.
            var secondSource=fixture.session(c.actor);c.graph.register(c.actor,secondSource);
            try(var secondCurrent=new CiPg18ReauthSession(c.actor,secondSource);
                    var own=(Issued)c.graph.issue(secondCurrent,c.actor,c.target,1,UUID.randomUUID())){
                long[] before=accountEffects(c.actor.account());
                assertThat(proofState(own.metadata().proofId())).isEqualTo("ISSUED");
                assertThatThrownBy(()->c.graph.revoke(secondCurrent,c.target,1,UUID.randomUUID(),proof))
                        .isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
                assertThat(accountEffects(c.actor.account())).containsExactly(before);
                assertThat(proofState(own.metadata().proofId())).isEqualTo("ISSUED");
                assertThat(proofState(proof.metadata().proofId())).isEqualTo("ISSUED");
                assertThat(authorityState(c.target)).isEqualTo("ACTIVE");
            }
            // V4's unique HUMAN/account index forbids a second legitimate HUMAN actor for
            // the same account. A different legitimate actor/account must also be refused
            // when its target is owned, so denial cannot be attributed only to target ownership.
            var other=fixture.actor();var otherSource=fixture.session(other);var otherTarget=fixture.session(other);
            c.graph.register(other,otherSource);c.graph.register(other,otherTarget);
            try(var current=new CiPg18ReauthSession(other,otherSource);
                    var own=(Issued)c.graph.issue(current,other,otherTarget,1,UUID.randomUUID())){
                long[] originalBefore=accountEffects(c.actor.account()),otherBefore=accountEffects(other.account());
                assertThat(proofState(own.metadata().proofId())).isEqualTo("ISSUED");
                assertThatThrownBy(()->c.graph.revoke(current,otherTarget,1,UUID.randomUUID(),proof))
                        .isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
                assertThat(accountEffects(c.actor.account())).containsExactly(originalBefore);
                assertThat(accountEffects(other.account())).containsExactly(otherBefore);
                assertThat(proofState(own.metadata().proofId())).isEqualTo("ISSUED");
                assertThat(proofState(proof.metadata().proofId())).isEqualTo("ISSUED");
                assertThat(authorityState(otherTarget)).isEqualTo("ACTIVE");
                assertThatThrownBy(()->c.graph.revoke(current,c.target,1,UUID.randomUUID(),proof)).isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
                assertThatThrownBy(()->c.graph.issue(current,other,c.target,1,UUID.randomUUID())).isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
                CapturedHumanRequest captured=c.graph.requests.capture(current.request);
                try{c.graph.boundaries.read(captured,boundary->{
                    Connection connection=DataSourceUtils.getConnection(fixture.runtime());
                    try{c.graph.boundaries.requireSnapshot(captured,boundary,fixture.runtime(),connection);
                        assertThat(((Number)CiPg18ReauthFixture.scalar(connection,"select count(*) from public.tenant_member where tenant_id=?",c.actor.tenant())).longValue()).isZero();
                        assertThat(((Number)CiPg18ReauthFixture.scalar(connection,"select count(*) from public.tenant_member where tenant_id=?",other.tenant())).longValue()).isEqualTo(1);
                        c.graph.boundaries.requireSnapshot(captured,boundary,fixture.runtime(),connection);return null;
                    }catch(SQLException failure){throw CiPg18ReauthFixture.rejected();}finally{DataSourceUtils.releaseConnection(connection,fixture.runtime());}
                });}finally{captured.close();}
            }
            assertThat(proofState(proof.metadata().proofId())).isEqualTo("ISSUED");assertThat(authorityState(c.target)).isEqualTo("ACTIVE");
            long[] beforeMemberEpoch=accountEffects(c.actor.account());
            fixture.adminTransaction(List.of(c.actor.tenant()),connection->CiPg18ReauthFixture.execute(connection,"update public.tenant_member set auth_epoch=auth_epoch+1 where tenant_id=? and id=?",c.actor.tenant(),c.actor.member()));
            assertThatThrownBy(()->c.issue(UUID.randomUUID())).isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
            assertThatThrownBy(()->c.graph.revoke(c.current,c.target,1,UUID.randomUUID(),proof)).isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
            assertThat(accountEffects(c.actor.account())).containsExactly(beforeMemberEpoch);
            assertThat(proofState(proof.metadata().proofId())).isEqualTo("ISSUED");
            assertThat(authorityState(c.target)).isEqualTo("ACTIVE");
        }
        // Tenant epoch invalidation has its own fresh, otherwise-current member/session.
        // It must not be accidentally covered by the earlier member-epoch refusal.
        try(var c=caseData();var proof=(Issued)c.issue(UUID.randomUUID())){
            long[] before=accountEffects(c.actor.account());
            fixture.adminTransaction(List.of(c.actor.tenant()),connection->CiPg18ReauthFixture.execute(connection,
                    "update public.tenant set auth_epoch=auth_epoch+1 where id=?",c.actor.tenant()));
            assertThatThrownBy(()->c.issue(UUID.randomUUID())).isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
            assertThatThrownBy(()->c.graph.revoke(c.current,c.target,1,UUID.randomUUID(),proof))
                    .isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
            assertThat(accountEffects(c.actor.account())).containsExactly(before);
            assertThat(proofState(proof.metadata().proofId())).isEqualTo("ISSUED");
            assertThat(authorityState(c.target)).isEqualTo("ACTIVE");
        }
    }

    private long[] accountEffects(UUID account){return new long[]{
            fixture.count("select count(*) from public.sensitive_reauth_proof where account_id=?",account),
            fixture.count("select count(*) from public.sensitive_reauth_issuance_receipt where account_id=?",account),
            fixture.count("select count(*) from public.session_authority_command_receipt where account_id=?",account),
            fixture.count("select count(*) from public.identity_sensitive_event where subject_account_id=? and operation in ('ISSUE_REAUTH','REVOKE_SESSION') and result='SUCCESS'",account),
            fixture.count("select count(*) from public.account_session_authority where account_id=?",account),
            fixture.count("select coalesce(sum(version),0) from public.account_session_authority where account_id=?",account)};}

    private Case caseData(){
        var actor=fixture.actor();var graph=new CiPg18ReauthGraph(fixture,fixture.keys("k0","k0"));var source=fixture.session(actor);var target=fixture.session(actor);
        graph.register(actor,source);graph.register(actor,target);return new Case(actor,source,target,graph,new CiPg18ReauthSession(actor,source));
    }
    private record Case(CiPg18ReauthFixture.Actor actor,CiPg18ReauthFixture.Session source,CiPg18ReauthFixture.Session target,CiPg18ReauthGraph graph,CiPg18ReauthSession current)implements AutoCloseable{
        Outcome issue(UUID key){return graph.issue(current,actor,target,1,key);}
        @Override public void close(){current.close();}
        @Override public String toString(){return "ReauthCase[private]";}
    }
    private long[] counts(Case c){return new long[]{proofCount(c),fixture.count("select count(*) from public.sensitive_reauth_issuance_receipt where account_id=? and source_primary_id=?",c.actor.account(),c.source.primary()),
            fixture.count("select count(*) from public.session_authority_command_receipt where account_id=? and source_primary_id=?",c.actor.account(),c.source.primary()),
            fixture.count("select count(*) from public.identity_sensitive_event where subject_account_id=? and source_primary_id=? and operation in ('ISSUE_REAUTH','REVOKE_SESSION') and result='SUCCESS'",c.actor.account(),c.source.primary())};}
    private long proofCount(Case c){return fixture.count("select count(*) from public.sensitive_reauth_proof where account_id=? and source_primary_id=?",c.actor.account(),c.source.primary());}
    private String proofState(UUID proof){return (String)fixture.value("select state from public.sensitive_reauth_proof where id=?",proof);}
    private String authorityState(CiPg18ReauthFixture.Session session){return (String)fixture.value("select state from public.account_session_authority where primary_id=?",session.primary());}
    private boolean waitForBlockedRuntime(){
        long until=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(System.nanoTime()<until){if(Boolean.TRUE.equals(fixture.value("select exists(select 1 from pg_locks l join pg_stat_activity a on a.pid=l.pid where l.locktype='advisory' and l.classid=1297303111::oid and l.objid=1::oid and not l.granted and a.usename='msf_ci_reauth_runtime')")))return true;
            try{Thread.sleep(20);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw CiPg18ReauthFixture.rejected();}}
        return false;
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw CiPg18ReauthFixture.rejected();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw CiPg18ReauthFixture.rejected();}}
    private void diagnostics(Level level,String password,Runnable action){
        Logger logger=(Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);Level original=logger.getLevel();var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);logger.setLevel(level);
        PrintStream stdout=System.out,stderr=System.err;var bytes=new ByteArrayOutputStream();var stream=new PrintStream(bytes,true,StandardCharsets.UTF_8);
        try{System.setOut(stream);System.setErr(stream);action.run();}
        finally{System.setOut(stdout);System.setErr(stderr);logger.detachAppender(appender);logger.setLevel(original);stream.close();}
        StringBuilder text=new StringBuilder(bytes.toString(StandardCharsets.UTF_8));
        for(var event:appender.list){text.append(event.getFormattedMessage());for(var failure=event.getThrowableProxy();failure!=null;failure=failure.getCause())text.append(failure.getMessage());}
        assertThat(text.toString()).doesNotContain(password,"PRIVATE_REAUTH_PG_DRIVER_SENTINEL");
        for(String canary:fixture.privateCanaries())assertThat(text.toString()).doesNotContain(canary);
    }
}
