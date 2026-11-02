package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.identity.SessionLifetimePolicy;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual jar processes and identity-table login only. Graceful restart is not host-loss acceptance. */
@Execution(ExecutionMode.SAME_THREAD)
class IdentitySessionLifecyclePostgresIT {
    private static final String CURRENT="/api/v1/session/current";
    private final ObjectMapper json=new ObjectMapper();

    @Test void originalTwoTenantSessionsSurviveANewApplicationJvmWithoutAnotherLogin() throws Exception {
        var target=PasswordChangePostgresFixture.validateTarget();Path jar=packagedJar();Path logs=privateRun("restart");
        try(var fixture=fixture();var https=IdentityAcceptanceHttps.create()) {
            List<Process> children=new ArrayList<>();
            try {
                int port=freePort();Process first=start(jar,logs,"first.log",target.jdbcUrl(),port,https);children.add(first);ready(first,port,https);
                try(var tenantA=https.browser(port);var tenantB=https.browser(port)) {
                    login(tenantA,fixture.business.reporter,fixture.business.tenantCode);
                    login(tenantB,fixture.business.reporter,fixture.tenantBCode);
                    assertCurrent(tenantA,fixture.business.reporter,fixture.business.tenantId,fixture.business.reporter.memberId());
                    assertCurrent(tenantB,fixture.business.reporter,fixture.tenantB,fixture.memberB);
                    String cookieA=sessionCookie(tenantA),cookieB=sessionCookie(tenantB);
                    var before=sessions(fixture,fixture.business.reporter.accountId());assertThat(before.size()).isEqualTo(2);
                    assertThat(before.values().stream().allMatch(row->row.generation==1)).isTrue();
                    stopGracefully(first);
                    assertSameSessions(sessions(fixture,fixture.business.reporter.accountId()),before);
                    Process restarted=start(jar,logs,"restarted.log",target.jdbcUrl(),port,https);children.add(restarted);ready(restarted,port,https);
                    assertThat(restarted.pid()!=first.pid()).isTrue();
                    // These browsers retain their original cookie jars. No login is issued after restart.
                    assertCurrent(tenantA,fixture.business.reporter,fixture.business.tenantId,fixture.business.reporter.memberId());
                    assertCurrent(tenantB,fixture.business.reporter,fixture.tenantB,fixture.memberB);
                    expect(tenantA.get("/api/v1/session/bootstrap"),200);expect(tenantB.get("/api/v1/session/bootstrap"),200);
                    assertThat(cookieA.equals(sessionCookie(tenantA))).as("Original first-tenant cookie is retained").isTrue();
                    assertThat(cookieB.equals(sessionCookie(tenantB))).as("Original second-tenant cookie is retained").isTrue();
                    assertSameSessions(sessions(fixture,fixture.business.reporter.accountId()),before);
                    stopGracefully(restarted);
                }
            } finally { stopAll(children); }
        }
    }

    @Test void logoutOnAnotherJvmInvalidatesAnIndependentOldCookieEvenAfterRestart() throws Exception {
        var target=PasswordChangePostgresFixture.validateTarget();Path jar=packagedJar();Path logs=privateRun("cross-node-logout");
        try(var fixture=fixture();var https=IdentityAcceptanceHttps.create()) {
            List<Process> children=new ArrayList<>();
            try {
                int portA=freePort(),portB=anotherPort(portA);
                Process a=start(jar,logs,"node-a.log",target.jdbcUrl(),portA,https);children.add(a);ready(a,portA,https);
                Process b=start(jar,logs,"node-b.log",target.jdbcUrl(),portB,https);children.add(b);ready(b,portB,https);
                try(var original=https.browser(portA)) {
                    login(original,fixture.business.reporter,fixture.business.tenantCode);
                    String retained=sessionCookie(original);
                    var before=sessions(fixture,fixture.business.reporter.accountId());assertThat(before.size()).isEqualTo(1);
                    SessionRecord authenticated=before.get(fixture.business.tenantId);
                    try(var otherNode=original.copyTo(portB)) {
                        assertCurrent(otherNode,fixture.business.reporter,fixture.business.tenantId,fixture.business.reporter.memberId());
                        assertSameSessions(sessions(fixture,fixture.business.reporter.accountId()),before);
                        csrf(otherNode);expect(otherNode.post("/api/v1/auth/logout",null,otherNode.csrf()),204);
                        assertThat(sessionExists(fixture,authenticated)).isFalse();
                        // The source jar is independent: logout cannot remove its local cookie.
                        assertThat(retained.equals(sessionCookie(original))).isTrue();
                        try(var neverVisitedAfterLogout=original.copyTo(portA)) {
                            expect(original.get(CURRENT),401);
                            stopGracefully(a);
                            Process restarted=start(jar,logs,"node-a-restarted.log",target.jdbcUrl(),portA,https);children.add(restarted);ready(restarted,portA,https);
                            assertThat(restarted.pid()!=a.pid()).isTrue();
                            assertThat(retained.equals(sessionCookie(neverVisitedAfterLogout))).isTrue();
                            expect(neverVisitedAfterLogout.get(CURRENT),401);
                            assertThat(sessionExists(fixture,authenticated)).isFalse();
                            assertThat(sessions(fixture,fixture.business.reporter.accountId()).isEmpty()).isTrue();
                            stopGracefully(restarted);
                        }
                    }
                }
                stopGracefully(b);
            } finally { stopAll(children); }
        }
    }

    @Test void anUnvisitedOldGenerationCannotReviveAfterAnotherNodeChangesPassword() throws Exception {
        var target=PasswordChangePostgresFixture.validateTarget();Path jar=packagedJar();Path logs=privateRun("cross-node-password");
        try(var fixture=fixture();var https=IdentityAcceptanceHttps.create()) {
            List<Process> children=new ArrayList<>();
            try {
                int portA=freePort(),portB=anotherPort(portA);
                Process a=start(jar,logs,"node-a.log",target.jdbcUrl(),portA,https);children.add(a);ready(a,portA,https);
                Process b=start(jar,logs,"node-b.log",target.jdbcUrl(),portB,https);children.add(b);ready(b,portB,https);
                try(var oldTenantB=https.browser(portA);var control=https.browser(portA);var changer=https.browser(portB)) {
                    login(oldTenantB,fixture.business.reporter,fixture.tenantBCode);
                    login(control,fixture.business.person,fixture.business.tenantCode);
                    login(changer,fixture.business.reporter,fixture.business.tenantCode);
                    var before=sessions(fixture,fixture.business.reporter.accountId());assertThat(before.size()).isEqualTo(2);
                    SessionRecord old=before.get(fixture.tenantB);assertThat(old.generation).isEqualTo(1);
                    var controlBefore=sessions(fixture,fixture.business.person.accountId());assertThat(controlBefore.size()).isEqualTo(1);
                    String retained=sessionCookie(oldTenantB);String next=UUID.randomUUID()+"-owned-next-password";
                    stopGracefully(a); // No request touches the old session while password change occurs.
                    csrf(changer);
                    expect(changer.post("/api/v1/account/password",json.writeValueAsString(Map.of(
                            "currentPassword",fixture.business.reporter.password(),"newPassword",next)),changer.csrf()),204);
                    assertThat(fixture.credentials().version()).isEqualTo(2);
                    assertThat(fixture.audits()).isEqualTo(1);assertThat(fixture.events("SUCCESS",null)).isEqualTo(1);
                    var unvisited=sessions(fixture,fixture.business.reporter.accountId());assertThat(unvisited.size()).isEqualTo(1);
                    assertThat(old.sameIdentityAndAge(unvisited.get(fixture.tenantB))).as("Old persisted generation survives unvisited until the request checks it").isTrue();
                    assertThat(sessionExists(fixture,old)).isTrue();
                    Process restarted=start(jar,logs,"node-a-restarted.log",target.jdbcUrl(),portA,https);children.add(restarted);ready(restarted,portA,https);
                    assertThat(restarted.pid()!=a.pid()).isTrue();
                    assertThat(retained.equals(sessionCookie(oldTenantB))).isTrue();
                    expect(oldTenantB.get(CURRENT),401);assertThat(sessionExists(fixture,old)).isFalse();
                    assertCurrent(control,fixture.business.person,fixture.business.tenantId,fixture.business.person.memberId());
                    assertSameSessions(sessions(fixture,fixture.business.person.accountId()),controlBefore);
                    try(var oldPassword=https.browser(portA);var fresh=https.browser(portA)) {
                        csrf(oldPassword);expect(oldPassword.post("/api/v1/auth/login",loginBody(fixture.business.reporter,fixture.tenantBCode,fixture.business.reporter.password()),oldPassword.csrf()),401);
                        expect(oldPassword.get(CURRENT),401);
                        csrf(fresh);expect(fresh.post("/api/v1/auth/login",loginBody(fixture.business.reporter,fixture.tenantBCode,next),fresh.csrf()),204);
                        assertCurrent(fresh,fixture.business.reporter,fixture.tenantB,fixture.memberB);
                        var current=sessions(fixture,fixture.business.reporter.accountId());assertThat(current.size()).isEqualTo(1);
                        assertThat(current.get(fixture.tenantB).generation).isEqualTo(2);
                    }
                    assertThat(fixture.audits()).isEqualTo(1);assertThat(fixture.events("SUCCESS",null)).isEqualTo(1);
                    stopGracefully(restarted);
                }
                stopGracefully(b);
            } finally { stopAll(children); }
        }
    }

    private PasswordChangePostgresFixture fixture() {
        return new PasswordChangePostgresFixture(new DelegatingPasswordEncoder("bcrypt",Map.of("bcrypt",new BCryptPasswordEncoder(12))));
    }
    private void login(IdentityAcceptanceHttps.Browser browser,BusinessWorkflowPostgresFixture.Actor actor,String tenant)throws Exception {
        csrf(browser);expect(browser.post("/api/v1/auth/login",loginBody(actor,tenant,actor.password()),browser.csrf()),204);
    }
    private String loginBody(BusinessWorkflowPostgresFixture.Actor actor,String tenant,String password)throws Exception {
        return json.writeValueAsString(Map.of("loginKey",actor.loginKey(),"password",password,"tenantCode",tenant));
    }
    private static void csrf(IdentityAcceptanceHttps.Browser browser)throws Exception {expect(browser.get("/api/v1/auth/csrf"),204);}
    private void assertCurrent(IdentityAcceptanceHttps.Browser browser,BusinessWorkflowPostgresFixture.Actor actor,UUID tenant,UUID member)throws Exception {
        var response=browser.get(CURRENT);expect(response,200);JsonNode data=json.readTree(response.body()).path("data");
        assertThat(data.path("accountId").asString()).isEqualTo(actor.accountId().toString());
        assertThat(data.path("actorId").asString()).isEqualTo(actor.principalId().toString());
        assertThat(data.path("tenantId").asString()).isEqualTo(tenant.toString());assertThat(data.path("memberId").asString()).isEqualTo(member.toString());
        assertThat(data.has("credentialVersion")).isFalse();assertThat(data.has("secretHash")).isFalse();
    }
    private static void expect(IdentityAcceptanceHttps.Response response,int status){assertThat(response.status()).as("Actual child JVM HTTPS response").isEqualTo(status);}
    private static String sessionCookie(IdentityAcceptanceHttps.Browser browser){return browser.cookie("SESSION").orElseThrow(()->new IllegalStateException("Expected actual session cookie")).getValue();}

    /** Secret-bearing identifiers never use an assertion representation or diagnostic dump. */
    private record SessionRecord(String id,UUID actor,UUID account,UUID tenant,UUID member,long tenantEpoch,long memberEpoch,long generation,Instant authenticatedAt) {
        boolean sameIdentityAndAge(SessionRecord other){return other!=null&&id.equals(other.id)&&actor.equals(other.actor)&&account.equals(other.account)&&tenant.equals(other.tenant)&&member.equals(other.member)&&tenantEpoch==other.tenantEpoch&&memberEpoch==other.memberEpoch&&generation==other.generation&&authenticatedAt.equals(other.authenticatedAt);}
        @Override public String toString(){return "OwnedJdbcSession[REDACTED]";}
    }
    private static Map<UUID,SessionRecord> sessions(PasswordChangePostgresFixture fixture,UUID account) {
        if(!List.of(fixture.business.reporter.accountId(),fixture.business.person.accountId()).contains(account))throw new IllegalArgumentException("Only this fixture's accounts may be inspected");
        return fixture.read(fixture.business.tenantId,db->{
            Map<UUID,SessionRecord> result=new LinkedHashMap<>();
            for(var stored:db.queryForList("select primary_id,session_id from public.spring_session where principal_name=?",account.toString())) {
                Map<String,Object> attributes=new LinkedHashMap<>();
                db.query("select attribute_name,attribute_bytes from public.spring_session_attributes where session_primary_id=?",row->{attributes.put(row.getString(1),new DeserializingConverter().convert(row.getBytes(2)));},stored.get("primary_id"));
                Object value=attributes.get(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                if(!(value instanceof SecurityContext security)||security.getAuthentication()==null||!(security.getAuthentication().getPrincipal() instanceof VerifiedSessionPrincipal principal)||!account.equals(principal.accountId())||security.getAuthentication().getCredentials()!=null)throw new IllegalStateException("Expected owned credential-free authenticated JDBC context");
                Object time=attributes.get(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE);
                if(!(time instanceof Instant authenticatedAt))throw new IllegalStateException("Expected persisted authentication instant");
                var record=new SessionRecord((String)stored.get("session_id"),principal.actorId(),principal.accountId(),principal.tenantId(),principal.memberId(),principal.tenantAuthEpoch(),principal.memberAuthEpoch(),principal.credentialVersion(),authenticatedAt);
                if(result.put(record.tenant,record)!=null)throw new IllegalStateException("Expected one owned session per selected tenant");
            }
            return Map.copyOf(result);
        });
    }
    private static boolean sessionExists(PasswordChangePostgresFixture fixture,SessionRecord record){return fixture.read(fixture.business.tenantId,db->db.queryForObject("select count(*)=1 from public.spring_session where session_id=? and principal_name=?",Boolean.class,record.id,record.account.toString()));}
    private static void assertSameSessions(Map<UUID,SessionRecord> actual,Map<UUID,SessionRecord> expected){assertThat(actual.keySet()).isEqualTo(expected.keySet());for(var entry:expected.entrySet())assertThat(entry.getValue().sameIdentityAndAge(actual.get(entry.getKey()))).as("Persisted ID, verified identity/generation and absolute authentication age remain unchanged").isTrue();}

    private Process start(Path jar,Path work,String name,String jdbcUrl,int port,IdentityAcceptanceHttps https)throws Exception {
        Path log=Files.createFile(work.resolve(name),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xmx512m","-XX:MaxMetaspaceSize=192m","-XX:MaxDirectMemorySize=64m","-XX:ActiveProcessorCount=2",
                "-jar",jar.toString());builder.directory(work.toFile());
        var environment=builder.environment();environment.clear();
        environment.put("LAN_PG_APP_JDBC_URL",jdbcUrl);environment.put("LAN_PG_APP_USERNAME",required("LAN_PG_APP_USERNAME"));environment.put("LAN_PG_APP_PASSWORD",required("LAN_PG_APP_PASSWORD"));
        Map<String,Object> settings=new LinkedHashMap<>();
        settings.put("spring.datasource.url",jdbcUrl);settings.put("spring.datasource.username",required("LAN_PG_APP_USERNAME"));settings.put("spring.datasource.password",required("LAN_PG_APP_PASSWORD"));
        settings.put("spring.datasource.hikari.maximum-pool-size",2);settings.put("spring.datasource.hikari.minimum-idle",0);
        settings.put("server.tomcat.threads.max",16);settings.put("server.tomcat.threads.min-spare",2);settings.put("server.tomcat.max-connections",32);
        settings.put("server.address","127.0.0.1");settings.put("server.port",port);settings.put("server.ssl.enabled",true);settings.put("server.ssl.key-store",https.keyStore().toUri().toString());settings.put("server.ssl.key-store-password",https.password());settings.put("server.ssl.key-store-type","PKCS12");settings.put("server.ssl.key-alias","identity-acceptance");settings.put("server.shutdown","graceful");settings.put("spring.lifecycle.timeout-per-shutdown-phase","10s");
        settings.put("spring.flyway.enabled",false);settings.put("spring.session.jdbc.initialize-schema","never");settings.put("spring.session.jdbc.cleanup-cron","-");settings.put("spring.modulith.events.jdbc.schema-initialization.enabled",false);
        settings.put("flowable.database-schema-update","false");settings.put("flowable.check-process-definitions",false);settings.put("flowable.async-executor-activate",false);settings.put("flowable.async-history-executor-activate",false);settings.put("flowable.history-cleaning-enabled",false);settings.put("flowable.idm.enabled",false);settings.put("flowable.eventregistry.enabled",false);
        settings.put("logging.level.org.jooq.tools.LoggerListener","OFF");settings.put("spring.mvc.log-request-details",false);settings.put("server.tomcat.accesslog.enabled",false);
        environment.put("SPRING_APPLICATION_JSON",json.writeValueAsString(settings));
        assertThat(environment.keySet().stream().noneMatch(key->key.contains("OPERATOR")||key.equals("LAN_PG_USERNAME")||key.equals("LAN_PG_PASSWORD")||key.endsWith("_ACK"))).isTrue();
        return builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void ready(Process child,int port,IdentityAcceptanceHttps https)throws Exception {
        // A separate readiness browser never touches or replaces the authenticated cookie jar.
        try(var readiness=https.browser(port)) {
            long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
            while(System.nanoTime()<deadline&&child.isAlive()) {
                try{if(readiness.get("/api/v1/auth/csrf").status()==204)return;}catch(Exception notReady){/* Private child log only; no exception/request dump. */}
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            }
            throw new AssertionError("Owned application did not become HTTPS ready; inspect retained private log");
        }
    }
    private static Path privateRun(String scenario)throws Exception {
        Path root=Path.of(required("MSF_IDENTITY_JVM_LOG_ROOT"));
        if(!root.isAbsolute()||root.getFileName()==null||!root.getFileName().toString().startsWith(".env.identity-jvm-"))throw new IllegalStateException("Explicit absolute ignored JVM log root required");
        root=root.normalize();Path repo=root.getParent();
        if(repo==null||!Files.exists(repo.resolve(".git"))||!Files.isRegularFile(repo.resolve(".gitignore")))throw new IllegalStateException("Log root must be directly below the checkout");
        ProcessBuilder ignoredBuilder=new ProcessBuilder("git","-C",repo.toString(),"check-ignore","-q","--",root.toString());ignoredBuilder.environment().keySet().removeIf(key->key.startsWith("LAN_PG_")||key.contains("OPERATOR"));Process ignored=ignoredBuilder.start();
        if(!ignored.waitFor(5,TimeUnit.SECONDS)){ignored.destroyForcibly();throw new IllegalStateException("Ignored-log check timed out");}if(ignored.exitValue()!=0)throw new IllegalStateException("Private JVM logs must be git ignored");
        privateDirectory(root);Path work=root.resolve(scenario+"-"+UUID.randomUUID());privateDirectory(work);
        Files.writeString(work.resolve("scope.txt"),"Actual owned runtime-only JVMs and identity-table HTTPS login. Graceful lifecycle only; no host/forced-loss acceptance.\n",java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(work.resolve("scope.txt"),PosixFilePermissions.fromString("rw-------"));return work;
    }
    private static void privateDirectory(Path path)throws Exception {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))||!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"))))throw new IllegalStateException("Private owned JVM log directory required");
    }
    private static int freePort()throws Exception{try(var socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    private static int anotherPort(int first)throws Exception{int candidate;do{candidate=freePort();}while(candidate==first);return candidate;}
    private static void stopGracefully(Process child)throws Exception{assertThat(child.isAlive()).as("Owned JVM is alive before requested graceful stop").isTrue();child.destroy();assertThat(child.waitFor(30,TimeUnit.SECONDS)).as("Owned JVM must stop gracefully").isTrue();assertThat(child.isAlive()).isFalse();}
    private static void stopAll(List<Process> children)throws Exception {
        Exception failure=null;
        for(Process child:children)try{if(child.isAlive()){child.destroy();if(!child.waitFor(30,TimeUnit.SECONDS)){child.destroyForcibly();if(!child.waitFor(5,TimeUnit.SECONDS))throw new IllegalStateException("Owned JVM cleanup could not finish");throw new IllegalStateException("Forced cleanup was required; graceful acceptance failed");}}}catch(Exception caught){if(failure==null)failure=caught;else failure.addSuppressed(caught);}
        if(failure!=null)throw failure;
    }
    private static Path packagedJar(){for(Path jar:List.of(Path.of("target/backend-0.1.0-SNAPSHOT.jar"),Path.of("backend/target/backend-0.1.0-SNAPSHOT.jar")))if(Files.isRegularFile(jar))return jar.toAbsolutePath();throw new IllegalStateException("Package actual application jar before JVM identity acceptance");}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing required identity acceptance input");return value;}
}
