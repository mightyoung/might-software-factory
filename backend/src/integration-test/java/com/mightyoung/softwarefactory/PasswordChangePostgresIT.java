package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.identity.IdentitySecurityAudit;
import com.mightyoung.softwarefactory.identity.IdentitySecurityEvents;
import com.mightyoung.softwarefactory.identity.IdentitySecurityTransaction;
import com.mightyoung.softwarefactory.identity.PasswordChangeService;
import com.mightyoung.softwarefactory.identity.PasswordCredentialStore;
import com.mightyoung.softwarefactory.identity.CurrentSessionService;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.LoginAttemptLimiter;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import static org.assertj.core.api.Assertions.*;

/** Opt-in actual HTTPS authentication + real password store/audit/events. No schema or grant provisioning. */
@SpringBootTest(classes=SoftwareFactoryApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PasswordChangePostgresIT.FaultConfiguration.class)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PasswordChangePostgresIT {
    private static final String PASSWORD="/api/v1/account/password";
    private static IdentityAcceptanceHttps https;
    @LocalServerPort private int port;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ObjectMapper json;
    @Autowired private Controls controls;
    @Autowired private PasswordTime passwordTime;
    private PasswordChangePostgresFixture fixture;
    private final List<IdentityAcceptanceHttps.Browser> clients=new ArrayList<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p)throws Exception {
        var target=PasswordChangePostgresFixture.validateTarget();
        if(https==null)https=IdentityAcceptanceHttps.create();
        p.add("spring.datasource.url",target::jdbcUrl);
        p.add("spring.datasource.username",()->System.getenv("LAN_PG_APP_USERNAME"));
        p.add("spring.datasource.password",()->System.getenv("LAN_PG_APP_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size",()->4);
        p.add("spring.datasource.hikari.minimum-idle",()->1);
        p.add("server.address",()->"127.0.0.1");p.add("server.ssl.enabled",()->true);
        p.add("server.ssl.key-store",()->https.keyStore().toUri().toString());p.add("server.ssl.key-store-password",https::password);
        p.add("server.ssl.key-store-type",()->"PKCS12");p.add("server.ssl.key-alias",()->"identity-acceptance");
        p.add("flowable.check-process-definitions",()->false);p.add("flowable.database-schema-update",()->false);
        p.add("flowable.async-executor-activate",()->false);p.add("flowable.async-history-executor-activate",()->false);
        p.add("spring.flyway.enabled",()->false);p.add("spring.session.jdbc.initialize-schema",()->"never");
        p.add("spring.modulith.events.jdbc.schema-initialization.enabled",()->false);
    }
    @BeforeEach void seedOnlyAfterGate() {controls.reset();passwordTime.clock.reset();fixture=new PasswordChangePostgresFixture(encoder);}
    @AfterEach void closeOwnedSessions() {
        controls.release();
        try{clients.forEach(IdentityAcceptanceHttps.Browser::close);}finally{clients.clear();if(fixture!=null)fixture.close();}
    }
    @AfterAll static void tlsCleanup()throws Exception{if(https!=null)https.close();}

    @Test void successfulChangeCommitsNewGenerationAndInvalidatesAllOldTenantSessions()throws Exception {
        var a1=login(fixture.business.tenantCode,fixture.business.reporter.password());
        var a2=login(fixture.business.tenantCode,fixture.business.reporter.password());
        var b=login(fixture.tenantBCode,fixture.business.reporter.password());
        var old=fixture.credentials();String next=newSecret();
        expect(change(a1,fixture.business.reporter.password(),next),204);
        var actual=fixture.credentials();assertThat(actual.version()).isEqualTo(old.version()+1);
        assertThat(old.encodedPassword().equals(actual.encodedPassword())).isFalse();
        assertThat(encoder.matches(next,actual.encodedPassword())).isTrue();
        assertThat(fixture.audits()).isEqualTo(1);assertThat(fixture.events("SUCCESS",null)).isEqualTo(1);
        assertAuditDiffOnly(old.version(),actual.version());
        for(var client:List.of(a1,a2,b))for(String path:List.of("/api/v1/session/current","/api/v1/session/bootstrap",
                "/api/v1/applications/safety/menu","/api/v1/organizations/reportable","/api/v1/entities/finding/schema"))expect(client.get(path),401);
        expect(loginResponse(client(),fixture.business.tenantCode,fixture.business.reporter.password()),401);
        expect(login(fixture.business.tenantCode,next).get("/api/v1/session/current"),200);
        expect(login(fixture.tenantBCode,next).get("/api/v1/session/current"),200);
    }

    @Test void incorrectCurrentPasswordLeavesCredentialUnchangedAndIndependentlyRecordsDenied()throws Exception {
        var client=login(fixture.business.tenantCode,fixture.business.reporter.password());
        var old=fixture.credentials();
        expect(change(client,"incorrect-owned-synthetic-password",newSecret()),403);
        var actual=fixture.credentials();assertThat(actual.version()).isEqualTo(old.version());
        assertThat(actual.encodedPassword().equals(old.encodedPassword())).isTrue();
        assertThat(fixture.audits()).isZero();assertThat(fixture.events("SUCCESS",null)).isZero();
        assertThat(fixture.events("DENIED","CURRENT_PASSWORD_REJECTED")).isEqualTo(1);
        expect(client.get("/api/v1/session/current"),200);
    }

    @Test void realSuccessfulAuditThenFailureRollsBackCredentialAndBothSuccessRecordsButCommitsFailedEvent()throws Exception {
        var client=login(fixture.business.tenantCode,fixture.business.reporter.password());
        var old=fixture.credentials();controls.failAfterAudit.set(true);
        expect(change(client,fixture.business.reporter.password(),newSecret()),503);
        assertThat(controls.realAuditReached.get()).isTrue();
        var actual=fixture.credentials();assertThat(actual.version()).isEqualTo(old.version());
        assertThat(actual.encodedPassword().equals(old.encodedPassword())).isTrue();
        assertThat(fixture.audits()).isZero();assertThat(fixture.events("SUCCESS",null)).isZero();
        assertThat(fixture.events("FAILED","AUDIT_UNAVAILABLE")).isEqualTo(1);
        expect(client.get("/api/v1/session/current"),200);
    }

    @Test void twoActuallyPreparedChangesSerializeAtGlobalLockAndOnlyOneGenerationCommits()throws Exception {
        var first=login(fixture.business.tenantCode,fixture.business.reporter.password());
        var second=login(fixture.business.tenantCode,fixture.business.reporter.password());
        PreparedBarrier barrier=new PreparedBarrier();AuditPause pause=new AuditPause();controls.preparing.set(barrier);controls.pause.set(pause);
        ExecutorService workers=Executors.newFixedThreadPool(2);String next=newSecret();
        try {
            Future<IdentityAcceptanceHttps.Response> a=workers.submit(()->change(first,fixture.business.reporter.password(),next));
            Future<IdentityAcceptanceHttps.Response> b=workers.submit(()->change(second,fixture.business.reporter.password(),next));
            assertThat(barrier.encoded.await(10,TimeUnit.SECONDS)).isTrue();barrier.release.countDown();
            int blocker=pause.entered.get(10,TimeUnit.SECONDS);observeWait(blocker,a,b);
            pause.release.countDown();
            var one=a.get(15,TimeUnit.SECONDS);var two=b.get(15,TimeUnit.SECONDS);
            assertThat(List.of(one.status(),two.status())).containsExactlyInAnyOrder(204,401);
            assertThat(fixture.credentials().version()).isEqualTo(2);
            assertThat(encoder.matches(next,fixture.credentials().encodedPassword())).isTrue();
            assertThat(fixture.audits()).isEqualTo(1);assertThat(fixture.events("SUCCESS",null)).isEqualTo(1);
            assertThat(fixture.events("DENIED","STALE_IDENTITY")).isEqualTo(1);
            assertAuditDiffOnly(1,2);
        }finally{barrier.release.countDown();pause.release.countDown();controls.preparing.set(null);controls.pause.set(null);workers.shutdownNow();assertThat(workers.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
    }

    @Test void runtimeHasOnlyApprovedColumnsAndCannotMutateIdentityOrReadChangeSecurityEvents()throws Exception {
        for(String sql:List.of("update public.account set status=status where id=?","update public.account set login_key=login_key where id=?",
                "update public.credential set kind=kind where account_id=?","delete from public.credential where account_id=?")) {
            denied(db->db.update(sql,fixture.business.reporter.accountId()));
        }
        denied(db->db.queryForList("select * from public.security_event where tenant_id=?",fixture.business.tenantId));
        denied(db->db.update("update public.security_event set result=result where tenant_id=?",fixture.business.tenantId));
        denied(db->db.update("delete from public.security_event where tenant_id=?",fixture.business.tenantId));
        var authenticated=login(fixture.business.tenantCode,fixture.business.reporter.password());
        expect(authenticated.get("/api/v1/security-events"),404);
        expect(authenticated.get("/api/v1/account/security-events"),404);
        String table="pw_denied_"+UUID.randomUUID().toString().replace("-","");
        denied(db->db.execute("create table public."+table+"(id integer)"));
    }

    @Test void strictUnknownAndDuplicateFieldsAreRejectedBeforeChangingTheCredential()throws Exception {
        var client=login(fixture.business.tenantCode,fixture.business.reporter.password());var before=fixture.credentials();
        String current=json.writeValueAsString(fixture.business.reporter.password()),next=json.writeValueAsString(newSecret());
        for(String body:List.of("{\"currentPassword\":"+current+",\"newPassword\":"+next+",\"tenantId\":\"ignored\"}",
                "{\"currentPassword\":"+current+",\"newPassword\":"+next+",\"newPassword\":"+next+"}")) {
            expect(client.post(PASSWORD,body,client.csrf()),400);
        }
        assertThat(fixture.credentials().version()).isEqualTo(before.version());
        assertThat(fixture.credentials().encodedPassword().equals(before.encodedPassword())).isTrue();
        assertThat(fixture.audits()).isZero();assertThat(fixture.events("DENIED","INVALID_NEW_PASSWORD")).isEqualTo(2);
    }

    @Test void preparedPasswordProofExpiresWithoutChangingCredentialAndDenialIsRecorded()throws Exception {
        var client=login(fixture.business.tenantCode,fixture.business.reporter.password());var before=fixture.credentials();
        passwordTime.clock.set(Instant.parse("2026-11-02T00:00:00Z"));
        PreparedBarrier barrier=new PreparedBarrier(1);controls.preparing.set(barrier);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<IdentityAcceptanceHttps.Response> request=worker.submit(()->change(client,fixture.business.reporter.password(),newSecret()));
            assertThat(barrier.encoded.await(10,TimeUnit.SECONDS)).isTrue();
            passwordTime.clock.set(Instant.parse("2026-11-02T00:01:01Z"));barrier.release.countDown();
            expect(request.get(15,TimeUnit.SECONDS),409);
            assertThat(fixture.credentials().version()).isEqualTo(before.version());
            assertThat(fixture.credentials().encodedPassword().equals(before.encodedPassword())).isTrue();
            assertThat(fixture.audits()).isZero();assertThat(fixture.events("SUCCESS",null)).isZero();
            assertThat(fixture.events("DENIED","PREPARATION_EXPIRED")).isEqualTo(1);
            expect(client.get("/api/v1/session/current"),200);
        }finally{barrier.release.countDown();controls.preparing.set(null);passwordTime.clock.reset();worker.shutdownNow();assertThat(worker.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
    }

    @Test void realWrongPasswordAttemptsReachBoundedRateLimitWithoutCredentialMutation()throws Exception {
        var client=login(fixture.business.tenantCode,fixture.business.reporter.password());var before=fixture.credentials();
        IdentityAcceptanceHttps.Response limited=null;
        int admittedRejections=0;
        for(int attempt=0;attempt<8;attempt++) {
            var response=change(client,"incorrect-owned-synthetic-password",newSecret());
            if(response.status()==429){limited=response;break;}
            expect(response,403);
            admittedRejections++;
            assertThat(fixture.events("DENIED","CURRENT_PASSWORD_REJECTED")).isEqualTo(admittedRejections);
        }
        assertThat(limited!=null).as("actual production password attempt throttle").isTrue();
        String retry=limited.http().headers().firstValue("Retry-After").orElse("");
        assertThat(retry).matches("[1-9][0-9]*");assertThat(Long.parseLong(retry)).isBetween(1L,40L);
        assertThat(fixture.credentials().version()).isEqualTo(before.version());
        assertThat(fixture.credentials().encodedPassword().equals(before.encodedPassword())).isTrue();
        assertThat(fixture.audits()).isZero();assertThat(fixture.events("SUCCESS",null)).isZero();
        assertThat(fixture.events("DENIED","CURRENT_PASSWORD_REJECTED")).isEqualTo(admittedRejections);
        long admittedEvents=fixture.totalEvents();
        assertThat(admittedEvents).isEqualTo(admittedRejections);
        // Source/reservation rejection occurs before admission, so repeated cooldown requests must not write events.
        for(int repeat=0;repeat<3;repeat++) {
            var response=change(client,"incorrect-owned-synthetic-password",newSecret());
            expect(response,429);
            String again=response.http().headers().firstValue("Retry-After").orElse("");
            assertThat(again).matches("[1-9][0-9]*");
            assertThat(fixture.totalEvents()).isEqualTo(admittedEvents);
        }
        assertThat(fixture.credentials().version()).isEqualTo(before.version());
        assertThat(fixture.credentials().encodedPassword().equals(before.encodedPassword())).isTrue();
        assertThat(fixture.audits()).isZero();assertThat(fixture.events("SUCCESS",null)).isZero();
        expect(client.get("/api/v1/session/current"),200);
    }

    private void denied(java.util.function.Consumer<JdbcTemplate> work) {
        fixture.runtimeTransaction(db->{Throwable failed=catchThrowable(()->work.accept(db));assertThat(failed).isInstanceOf(DataAccessException.class);
            Throwable cause=((DataAccessException)failed).getMostSpecificCause();assertThat(cause instanceof SQLException).isTrue();
            assertThat(((SQLException)cause).getSQLState()).isEqualTo("42501");});
    }
    private void assertAuditDiffOnly(long oldVersion,long newVersion) {
        fixture.read(fixture.business.tenantId,db->{String raw=db.queryForObject("select diff_redacted::text from public.audit_event where tenant_id=? and resource_id=? and action='account.password.change'",String.class,fixture.business.tenantId,fixture.business.reporter.accountId());
            var diff=json.readTree(raw);Set<String> names=new HashSet<>();diff.propertyNames().forEach(names::add);
            assertThat(names).containsExactlyInAnyOrder("oldCredentialVersion","newCredentialVersion");
            assertThat(diff.path("oldCredentialVersion").asLong()).isEqualTo(oldVersion);assertThat(diff.path("newCredentialVersion").asLong()).isEqualTo(newVersion);return null;});
    }
    private void observeWait(int blocker,Future<?> first,Future<?> second) {
        long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(System.nanoTime()<deadline){if(fixture.waitingPid(blocker)!=null){assertThat(first.isDone()||second.isDone()).isFalse();return;}LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));}
        throw new AssertionError("No actual advisory lock wait with matching pg_blocking_pids");
    }
    private IdentityAcceptanceHttps.Browser client(){var client=https.browser(port);clients.add(client);return client;}
    private IdentityAcceptanceHttps.Browser login(String tenant,String secret)throws Exception{var client=client();expect(loginResponse(client,tenant,secret),204);expect(client.get("/api/v1/auth/csrf"),204);return client;}
    private IdentityAcceptanceHttps.Response loginResponse(IdentityAcceptanceHttps.Browser client,String tenant,String secret)throws Exception{expect(client.get("/api/v1/auth/csrf"),204);return client.post("/api/v1/auth/login",json.writeValueAsString(Map.of("loginKey",fixture.business.reporter.loginKey(),"password",secret,"tenantCode",tenant)),client.csrf());}
    private IdentityAcceptanceHttps.Response change(IdentityAcceptanceHttps.Browser client,String current,String next)throws Exception{return client.post(PASSWORD,json.writeValueAsString(Map.of("currentPassword",current,"newPassword",next)),client.csrf());}
    private static String newSecret(){return "new-password-"+UUID.randomUUID();}
    private static void expect(IdentityAcceptanceHttps.Response response,int expected){assertThat(response.status()).as("Password acceptance HTTPS status").isEqualTo(expected);}
    private static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Password acceptance barrier deadline");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Password acceptance barrier interrupted");}}
    static final class PreparedBarrier {
        final CountDownLatch encoded,release=new CountDownLatch(1);
        PreparedBarrier(){this(2);}PreparedBarrier(int requests){encoded=new CountDownLatch(requests);}
        @Override public String toString(){return "PreparedBarrier[REDACTED]";}
    }
    /** Not a Clock bean: only the real password service constructor receives this controllable clock. */
    static final class PasswordTime {final PasswordClock clock=new PasswordClock(new AtomicReference<>(),ZoneOffset.UTC);}
    static final class PasswordClock extends Clock {
        private final AtomicReference<Instant> fixed;private final ZoneId zone;
        PasswordClock(AtomicReference<Instant> fixed,ZoneId zone){this.fixed=fixed;this.zone=zone;}
        void reset(){fixed.set(null);}void set(Instant time){fixed.set(Objects.requireNonNull(time));}
        @Override public ZoneId getZone(){return zone;}
        @Override public Clock withZone(ZoneId zone){return new PasswordClock(fixed,Objects.requireNonNull(zone));}
        @Override public Instant instant(){Instant value=fixed.get();return value==null?Clock.systemUTC().instant():value;}
    }
    static final class AuditPause {final AtomicBoolean first=new AtomicBoolean(true);final CompletableFuture<Integer> entered=new CompletableFuture<>();final CountDownLatch release=new CountDownLatch(1);}
    static final class Controls {
        final AtomicBoolean failAfterAudit=new AtomicBoolean(),realAuditReached=new AtomicBoolean();
        final AtomicReference<PreparedBarrier> preparing=new AtomicReference<>();final AtomicReference<AuditPause> pause=new AtomicReference<>();
        void release(){var b=preparing.get();if(b!=null)b.release.countDown();var p=pause.get();if(p!=null)p.release.countDown();}
        void reset(){release();failAfterAudit.set(false);realAuditReached.set(false);preparing.set(null);pause.set(null);}
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class FaultConfiguration {
        @Bean Controls passwordControls(){return new Controls();}
        @Bean PasswordTime passwordTime(){return new PasswordTime();}
        @Bean @Primary PasswordChangeService realPasswordServiceWithDedicatedProofClock(
                SessionContextResolver contexts,CurrentSessionService sessions,PasswordCredentialStore store,
                PasswordEncoder encoder,IdentitySecurityTransaction transaction,IdentitySecurityAudit audit,
                IdentitySecurityEvents events,@Qualifier("loginAttemptLimiter") LoginAttemptLimiter attempts,PasswordTime time){
            var clocks=new StaticListableBeanFactory(Map.of("passwordProofClock",time.clock)).getBeanProvider(Clock.class);
            return new PasswordChangeService(contexts,sessions,store,encoder,transaction,audit,events,attempts,clocks);
        }
        @Bean @Primary PasswordEncoder actualEncoderWithPreparationBarrier(@Qualifier("passwordEncoder") PasswordEncoder real,Controls controls){return new PasswordEncoder(){
            @Override public String encode(CharSequence raw){String encoded=real.encode(raw);var barrier=controls.preparing.get();if(barrier!=null){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Encoding must precede the security transaction");barrier.encoded.countDown();await(barrier.release);}return encoded;}
            @Override public boolean matches(CharSequence raw,String encoded){return real.matches(raw,encoded);}
            @Override public boolean upgradeEncoding(String encoded){return real.upgradeEncoding(encoded);}
        };}
        @Bean @Primary IdentitySecurityAudit actualAuditWithBoundedFailure(@Qualifier("jooqIdentitySecurityRecords") IdentitySecurityAudit real,Controls controls,JdbcTemplate jdbc){return (context,oldVersion,newVersion,trace,at)->{
            real.passwordChanged(context,oldVersion,newVersion,trace,at);controls.realAuditReached.set(true);
            if(controls.failAfterAudit.compareAndSet(true,false))throw new DataAccessResourceFailureException("Synthetic failure after actual password success audit");
            var pause=controls.pause.get();if(pause!=null&&pause.first.compareAndSet(true,false)){pause.entered.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));await(pause.release);}
        };}
    }
}
