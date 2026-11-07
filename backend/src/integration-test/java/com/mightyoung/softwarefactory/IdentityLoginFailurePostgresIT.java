package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.identity.SessionLifetimePolicy;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTPS authentication and JDBC Session writes; only request-scoped JDBC fault instrumentation is synthetic. */
@SpringBootTest(classes=SoftwareFactoryApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityLoginFailurePostgresIT.FaultConfiguration.class)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class IdentityLoginFailurePostgresIT {
    private static final String CSRF="/api/v1/auth/csrf", LOGIN="/api/v1/auth/login", CURRENT="/api/v1/session/current";
    private static final String FAULT_HEADER="X-Identity-Closure-Fault";
    private static IdentityAcceptanceHttps https;
    @LocalServerPort int port;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired Faults faults;
    private BusinessWorkflowPostgresFixture fixture;
    private final List<IdentityAcceptanceHttps.Browser> browsers=new ArrayList<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p)throws Exception {
        IdentityAcceptanceTarget.validateInputs(System.getenv());
        var target=BusinessWorkflowPostgresFixture.validateTarget();
        if(https==null)https=IdentityAcceptanceHttps.create();
        p.add("spring.datasource.url",target::jdbcUrl);
        p.add("spring.datasource.username",()->System.getenv("LAN_PG_APP_USERNAME"));
        p.add("spring.datasource.password",()->System.getenv("LAN_PG_APP_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size",()->4);p.add("spring.datasource.hikari.minimum-idle",()->1);
        p.add("server.address",()->"127.0.0.1");p.add("server.ssl.enabled",()->true);
        p.add("server.ssl.key-store",()->https.keyStore().toUri().toString());
        p.add("server.ssl.key-store-password",https::password);p.add("server.ssl.key-store-type",()->"PKCS12");p.add("server.ssl.key-alias",()->"identity-acceptance");
        p.add("spring.session.jdbc.cleanup-cron",()->"-");
        p.add("spring.session.jdbc.initialize-schema",()->"never");p.add("spring.flyway.enabled",()->false);
        p.add("spring.modulith.events.jdbc.schema-initialization.enabled",()->false);
        p.add("flowable.database-schema-update",()->false);p.add("flowable.check-process-definitions",()->false);
        p.add("flowable.async-executor-activate",()->false);p.add("flowable.async-history-executor-activate",()->false);
    }
    @BeforeEach void seed(){faults.armed.set(null);fixture=new BusinessWorkflowPostgresFixture(passwords);}
    @AfterEach void close(){faults.armed.set(null);try{browsers.forEach(IdentityAcceptanceHttps.Browser::close);}finally{browsers.clear();if(fixture!=null)fixture.close();}}
    @AfterAll static void closeTls()throws Exception{if(https!=null)https.close();}

    @Test void actualAuthenticationReadTimeoutDoesNotAuthenticateAndSameBrowserRecovers()throws Exception {
        var client=browser();expect(client.get(CSRF),204);
        var beforeEvents=authenticationEventIds();
        var fault=new Fault(Mode.AUTHENTICATION_READ,fixture.reporter.accountId());faults.armed.set(fault);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(Connection owner=ownerConnection()) {
            owner.setAutoCommit(false);
            try(Statement lock=owner.createStatement()) {
                lock.execute("set local lock_timeout='2s'");lock.execute("set local statement_timeout='5s'");
                int blocker=pid(owner);lock.execute("lock table public.account in access exclusive mode");
                Future<IdentityAcceptanceHttps.Response> pending=worker.submit(()->post(client,fault));
                int waiter=fault.backendPid.get(5,TimeUnit.SECONDS);
                boolean observed=observeBlocked(owner,blocker,waiter,pending);
                var response=pending.get(8,TimeUnit.SECONDS);
                assertDependencyUnavailable(response);
                assertAuthenticationEvent(beforeEvents,"AUTHENTICATION_UNAVAILABLE",false);
                assertThat(observed).as("actual PostgreSQL relation-lock waiter").isTrue();
                assertThat(fault.sqlState.get()).isEqualTo("57014");
                assertThat(fault.readOnly.get()).as("real authentication read-only transaction").isTrue();
            } finally {owner.rollback();}
            assertThat(ownedSessionCount()).isZero();
            expect(client.get(CURRENT),401);
            faults.armed.set(null);expect(post(client,null),204);
            expect(client.get(CURRENT),200);assertThat(ownedSessionCount()).isEqualTo(1);
        } finally {faults.armed.set(null);worker.shutdownNow();assertThat(worker.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }

    @Test void lateJdbcSessionAttributeFailureRollsBackParentAndAuthenticationThenLoginRecovers()throws Exception {
        var client=browser();expect(client.get(CSRF),204);
        assertThat(client.cookie("SESSION").isPresent()).isFalse();
        var beforeEvents=authenticationEventIds();
        var fault=new Fault(Mode.SESSION_WRITE,fixture.reporter.accountId());faults.armed.set(fault);
        var response=post(client,fault);
        // Deliberately strict: an erroneous 204/500 must expose the production response-boundary bug.
        assertDependencyUnavailable(response);
        assertAuthenticationEvent(beforeEvents,"SESSION_OUTCOME_UNKNOWN",true);
        var proof=fault.sessionProof.get();assertThat(proof).as("actual parent and authentication attribute written before fault").isNotNull();
        assertThat(proof.parents()).isEqualTo(1);assertThat(proof.authenticationAttributes()).isEqualTo(1);
        assertThat(proof.authenticationTimeAttributes()).isEqualTo(1);assertThat(proof.autoCommit()).isFalse();
        assertThat(fault.injected.get()).isTrue();
        fixture.read(db->{
            assertThat(db.queryForObject("select count(*) from public.spring_session where primary_id=?",Long.class,proof.primaryId())).isZero();
            assertThat(db.queryForObject("select count(*) from public.spring_session_attributes where session_primary_id=?",Long.class,proof.primaryId())).isZero();return null;
        });
        assertThat(ownedSessionCount()).isZero();
        String responseCookie=client.cookie("SESSION").map(java.net.HttpCookie::getValue).orElse(null);
        if(responseCookie==null) {
            assertThat(response.http().headers().allValues("Set-Cookie").stream()
                    .noneMatch(value->value.startsWith("SESSION=")&&!value.startsWith("SESSION=;")))
                    .as("failure did not issue a browser authentication cookie").isTrue();
        }
        // Without an issued cookie this is only a known-rolled-back-ID negative replay, not a browser-received cookie.
        String staleCookie=responseCookie!=null?responseCookie:Base64.getEncoder().encodeToString(proof.sessionId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        expect(browser().getWithHeaders(CURRENT,Map.of("Cookie","SESSION="+staleCookie)),401);
        expect(client.get(CURRENT),401);
        faults.armed.set(null);expect(client.get(CSRF),204);expect(post(client,null),204);
        expect(client.get(CURRENT),200);assertThat(ownedSessionCount()).isEqualTo(1);
    }

    @Test void earlySessionSaveFailureCannotPublishTheSessionThatFinallySavesSuccessfully()throws Exception {
        var client=browser();expect(client.get(CSRF),204);
        assertThat(client.cookie("SESSION").isPresent()).isFalse();
        var beforeEvents=authenticationEventIds();
        var fault=new Fault(Mode.EARLY_SESSION_WRITE,fixture.reporter.accountId());faults.armed.set(fault);
        var response=post(client,fault);

        assertDependencyUnavailable(response);
        assertAuthenticationEvent(beforeEvents,"SESSION_OUTCOME_UNKNOWN",true);
        assertThat(fault.earlyFlushTriggered.get()).isTrue();assertThat(fault.injected.get()).isTrue();
        var proof=fault.sessionProof.get();assertThat(proof).isNotNull();
        assertThat(proof.parents()).isEqualTo(1);assertThat(proof.authenticationAttributes()).isEqualTo(1);
        assertThat(proof.authenticationTimeAttributes()).isEqualTo(1);assertThat(proof.autoCommit()).isFalse();
        // Spring Session's finally block retries its save after the controlled first-save failure.
        // This is a committed, unpublished owned session; it is deliberately not called a rollback.
        assertThat(ownedSessionCount()).isEqualTo(1);
        fixture.read(db->{
            assertThat(db.queryForObject("select count(*) from public.spring_session where primary_id=? and session_id=? and principal_name=?",Long.class,proof.primaryId(),proof.sessionId(),fixture.reporter.accountId().toString())).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from public.spring_session_attributes where session_primary_id=? and attribute_name in ('SPRING_SECURITY_CONTEXT',?)",Long.class,proof.primaryId(),SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE)).isEqualTo(2);return null;
        });
        var sessionHeaders=response.http().headers().allValues("Set-Cookie").stream()
                .filter(value->value.startsWith("SESSION=")).toList();
        assertThat(sessionHeaders.isEmpty()).isFalse();
        assertThat(sessionHeaders.stream().allMatch(value->value.startsWith("SESSION=;")
                &&value.toLowerCase(Locale.ROOT).contains("max-age=0")))
                .as("only expiring SESSION cookies may leave the failed login").isTrue();
        assertThat(client.cookie("SESSION").isPresent()).isFalse();
        expect(client.get(CURRENT),401);
        faults.armed.set(null);expect(client.get(CSRF),204);expect(post(client,null),204);
        expect(client.get(CURRENT),200);assertThat(ownedSessionCount()).isEqualTo(2);
    }

    private IdentityAcceptanceHttps.Browser browser(){var b=https.browser(port);browsers.add(b);return b;}
    private IdentityAcceptanceHttps.Response post(IdentityAcceptanceHttps.Browser b,Fault fault)throws Exception {
        return b.postWithHeaders(LOGIN,json.writeValueAsString(Map.of("loginKey",fixture.reporter.loginKey(),"password",fixture.reporter.password(),"tenantCode",fixture.tenantCode)),b.csrf(),fault==null?Map.of():Map.of(FAULT_HEADER,fault.token));
    }
    private long ownedSessionCount(){return fixture.read(db->db.queryForObject("select count(*) from public.spring_session where principal_name=?",Long.class,fixture.reporter.accountId().toString()));}
    private Set<UUID> authenticationEventIds() throws SQLException {
        try(var owner=ownerConnection();var statement=owner.createStatement();
            var rows=statement.executeQuery("select attempt_id from public.authentication_event")) {
            var ids=new HashSet<UUID>();while(rows.next())ids.add(rows.getObject(1,UUID.class));return ids;
        }
    }
    private void assertAuthenticationEvent(Set<UUID> before,String outcome,boolean verified) throws SQLException {
        var added=authenticationEventIds();added.removeAll(before);assertThat(added).hasSize(1);
        try(var owner=ownerConnection();var statement=owner.prepareStatement(
                "select outcome,account_id,actor_id,tenant_id,member_id from public.authentication_event where attempt_id=?")) {
            statement.setObject(1,added.iterator().next());try(var row=statement.executeQuery()) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo(outcome);
                assertThat(row.getObject(2,UUID.class)).isEqualTo(verified?fixture.reporter.accountId():null);
                assertThat(row.getObject(3,UUID.class)).isEqualTo(verified?fixture.reporter.principalId():null);
                assertThat(row.getObject(4,UUID.class)).isEqualTo(verified?fixture.tenantId:null);
                assertThat(row.getObject(5,UUID.class)).isEqualTo(verified?fixture.reporter.memberId():null);
                assertThat(row.next()).isFalse();
            }
        }
    }
    private static Connection ownerConnection()throws SQLException {
        var target=IdentityAcceptanceTarget.validateInputs(System.getenv());
        return DriverManager.getConnection(target.jdbcUrl(),required("LAN_PG_USERNAME"),required("LAN_PG_PASSWORD"));
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing acceptance setting "+name);return value;}
    private static int pid(Connection connection)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery("select pg_backend_pid()")){r.next();return r.getInt(1);}}
    private static boolean observeBlocked(Connection observer,int blocker,int waiter,Future<?> pending)throws SQLException {
        long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        try(var s=observer.prepareStatement("select exists(select 1 from pg_locks where pid=? and locktype='relation' and not granted and ?=any(pg_blocking_pids(pid)))")) {
            s.setInt(1,waiter);s.setInt(2,blocker);
            while(System.nanoTime()<deadline){try(var r=s.executeQuery()){r.next();if(r.getBoolean(1))return true;}if(pending.isDone())break;LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));}
        }return false;
    }
    private void assertDependencyUnavailable(IdentityAcceptanceHttps.Response response)throws Exception {
        expect(response,503);
        var error=json.readTree(response.body());
        assertThat(error.path("code").asString()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        assertThat(UUID.fromString(error.path("traceId").asString())).isNotNull();
        assertThat(response.body()).doesNotContain("P0001","57014","Controlled owned-session","SPRING_SESSION_ATTRIBUTES");
    }

    private static void expect(IdentityAcceptanceHttps.Response response,int status){assertThat(response.status()).as("real HTTPS status").isEqualTo(status);}
    enum Mode{AUTHENTICATION_READ,SESSION_WRITE,EARLY_SESSION_WRITE}
    static final class Fault {
        final Mode mode;final UUID account;final String token=UUID.randomUUID().toString();
        final CompletableFuture<Integer> backendPid=new CompletableFuture<>();
        final AtomicReference<String> sqlState=new AtomicReference<>();
        final AtomicBoolean readOnly=new AtomicBoolean(),injected=new AtomicBoolean();
        final AtomicBoolean earlyFlushTriggered=new AtomicBoolean();
        final AtomicReference<SessionProof> sessionProof=new AtomicReference<>();
        Fault(Mode mode,UUID account){this.mode=mode;this.account=account;}
    }
    record SessionProof(String primaryId,String sessionId,int parents,int authenticationAttributes,int authenticationTimeAttributes,boolean autoCommit){
        @Override public String toString(){return "SessionProof[REDACTED]";}
    }
    static final class Faults{final AtomicReference<Fault> armed=new AtomicReference<>();final ThreadLocal<Fault> current=new ThreadLocal<>();}

    @TestConfiguration(proxyBeanMethods=false)
    static class FaultConfiguration {
        @Bean static Faults identityClosureFaults(){return new Faults();}
        @Bean static BeanPostProcessor identityClosureDataSource(Faults faults){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){return name.equals("dataSource")&&bean instanceof DataSource source?new InstrumentedDataSource(source,faults):bean;}
        };}
        @Bean FilterRegistrationBean<Filter> identityClosureRequestScope(Faults faults){
            Filter filter=(request,response,chain)->{var fault=faults.armed.get();
                if(fault!=null&&fault.token.equals(((HttpServletRequest)request).getHeader(FAULT_HEADER)))faults.current.set(fault);
                try{chain.doFilter(request,response);}finally{faults.current.remove();}
            };
            var bean=new FilterRegistrationBean<>(filter);bean.setOrder(Integer.MIN_VALUE);return bean;
        }
        @Bean FilterRegistrationBean<Filter> identityClosureEarlySessionFlush(Faults faults){
            Filter filter=(request,response,chain)->{
                Fault fault=faults.current.get();
                if(fault==null||fault.mode!=Mode.EARLY_SESSION_WRITE){chain.doFilter(request,response);return;}
                var wrapped=new HttpServletResponseWrapper((HttpServletResponse)response){
                    @Override public void setStatus(int status){
                        super.setStatus(status);
                        if(status==204&&fault.earlyFlushTriggered.compareAndSet(false,true)){
                            try{super.flushBuffer();}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
                        }
                    }
                };
                chain.doFilter(request,wrapped);
            };
            var bean=new FilterRegistrationBean<>(filter);bean.setOrder(SessionRepositoryFilter.DEFAULT_ORDER+1);return bean;
        }
    }
    static final class InstrumentedDataSource extends DelegatingDataSource implements AutoCloseable {
        private final Faults faults;
        InstrumentedDataSource(DataSource target,Faults faults){super(target);this.faults=faults;}
        @Override public Connection getConnection()throws SQLException{return instrument(super.getConnection());}
        @Override public Connection getConnection(String user,String password)throws SQLException{return instrument(super.getConnection(user,password));}
        private Connection instrument(Connection raw){
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try {
                    Fault fault=faults.current.get();
                    if(fault!=null&&method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql){
                        String normalized=sql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
                        boolean authentication=fault.mode==Mode.AUTHENTICATION_READ&&normalized.contains("from public.account a")&&normalized.contains("c.secret_hash");
                        boolean attributes=(fault.mode==Mode.SESSION_WRITE||fault.mode==Mode.EARLY_SESSION_WRITE)&&normalized.contains("spring_session_attributes")&&(normalized.startsWith("insert")||normalized.startsWith("update"));
                        if(authentication){
                            if(raw.getAutoCommit())throw new SQLException("Authentication must use its managed transaction","25000");
                            fault.readOnly.set(raw.isReadOnly());
                            try(var settings=raw.createStatement()){settings.execute("set local statement_timeout='1500ms'");}
                            fault.backendPid.complete(pid(raw));
                        }
                        Object statement=method.invoke(raw,args);
                        if(authentication||attributes)return instrumentStatement((PreparedStatement)statement,raw,fault,authentication,attributes);
                        return statement;
                    }
                    return method.invoke(raw,args);
                }catch(InvocationTargetException failed){throw failed.getCause();}
            });
        }
        private PreparedStatement instrumentStatement(PreparedStatement actual,Connection raw,Fault fault,boolean authentication,boolean attributes){
            return (PreparedStatement)Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(proxy,method,args)->{
                try {
                    Object result=method.invoke(actual,args);
                    if(attributes&&Set.of("executeUpdate","executeBatch","executeLargeUpdate","executeLargeBatch","execute").contains(method.getName())&&!fault.injected.get()){
                        SessionProof proof=proof(raw,fault.account);
                        if(proof!=null&&proof.authenticationAttributes()==1&&proof.authenticationTimeAttributes()==1&&fault.injected.compareAndSet(false,true)){
                            fault.sessionProof.set(proof);
                            throw new SQLException("Controlled owned-session failure after actual attribute persistence","P0001");
                        }
                    }
                    return result;
                }catch(InvocationTargetException failed){Throwable cause=failed.getCause();if(authentication&&cause instanceof SQLException sql)fault.sqlState.set(sql.getSQLState());throw cause;}
            });
        }
        private SessionProof proof(Connection connection,UUID account)throws SQLException {
            try(var s=connection.prepareStatement("select s.primary_id,s.session_id,(select count(*) from public.spring_session_attributes a where a.session_primary_id=s.primary_id and a.attribute_name='SPRING_SECURITY_CONTEXT'),(select count(*) from public.spring_session_attributes a where a.session_primary_id=s.primary_id and a.attribute_name=?) from public.spring_session s where s.principal_name=?")){
                s.setString(1,SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE);s.setString(2,account.toString());
                try(var r=s.executeQuery()){if(!r.next())return null;SessionProof proof=new SessionProof(r.getString(1),r.getString(2),1,r.getInt(3),r.getInt(4),connection.getAutoCommit());if(r.next())throw new SQLException("Ambiguous owned session in failure fixture","21000");return proof;}
            }
        }
        @Override public void close()throws Exception{if(getTargetDataSource() instanceof AutoCloseable closeable)closeable.close();}
    }
}
