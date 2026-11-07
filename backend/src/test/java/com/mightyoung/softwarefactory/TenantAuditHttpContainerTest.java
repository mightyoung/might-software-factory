package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.auditops.TenantAuditHttpTransport;
import com.mightyoung.softwarefactory.auditops.TenantAuditQueryController;
import com.mightyoung.softwarefactory.auditops.TenantAuditQueryParser;
import com.mightyoung.softwarefactory.auditops.TenantAuditQueryService;
import com.mightyoung.softwarefactory.dualprevention.RiskScenarioHttpTransport;
import com.mightyoung.softwarefactory.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Actual Boot/Tomcat, MVC and production Session/security/cookie filters with controlled identity
 * and an unavailable connection source. No PostgreSQL, persisted HUMAN authority, HTTPS/browser,
 * successful audit query or production identity acceptance is established by this component fixture.
 */
class TenantAuditHttpContainerTest {
    private static final String AUDIT="/api/v1/audit-events/query";
    private static final String CSRF="/api/v1/auth/csrf";
    private static final String RISK="/api/v1/entities/risk_scenario/fixture";
    private static final String PRIVATE_FAILURE="synthetic-private-audit-connection-refusal";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @TempDir Path directory;

    @Test void missingAndFalseFlagsKeepTheAuditGraphAbsentAndTheSevenRiskRoutesWorking()throws Exception {
        for(Boolean enabled:new Boolean[]{null,false})try(var f=new Fixture(directory.resolve("disabled-"+enabled),enabled)){
            assertThat(f.context.getBeansOfType(TenantAuditQueryService.class)).isEmpty();
            assertThat(f.context.getBeansOfType(TenantAuditQueryParser.class)).isEmpty();
            assertThat(f.context.getBeansOfType(TenantAuditHttpTransport.class)).isEmpty();
            assertThat(f.context.getBeansOfType(TenantAuditQueryController.class)).isEmpty();
            assertThat(f.context.containsBean("tenantAuditQueryGraph")).isFalse();
            var browser=f.browser();browser.login();
            for(String path:List.of("/api/v1/entities/risk_scenario/schema",RISK,
                    "/api/v1/risk-scenarios/fixture/history","/api/v1/risk-scenarios/fixture/history/revision/hazards/hazard")){
                var response=browser.exchange("GET",path,null,Map.of());assertThat(response.status()).isEqualTo(200);
                assertThat(response.body()).containsExactly((byte)'{',(byte)'}');f.bounded(response);
            }
            for(String path:List.of("/api/v1/entities/risk_scenario/query","/api/v1/risk-scenarios/eligible-hazards")){
                var response=browser.post(path,"{}",browser.csrf(),Map.of());assertThat(response.status()).isEqualTo(200);f.bounded(response);
            }
            var definition=browser.exchange("GET","/api/v1/entities/risk_scenario/definition",null,Map.of());
            assertThat(definition.status()).isEqualTo(304);assertThat(definition.body()).isEmpty();
            var head=browser.exchange("HEAD",RISK,null,Map.of());assertThat(head.status()).isEqualTo(200);
            assertThat(head.body()).isEmpty();assertThat(head.values("Content-Length")).containsExactly("2");
            var missing=browser.post(AUDIT,"{}",browser.csrf(),Map.of());assertThat(missing.status()).isEqualTo(404);
            assertThat(f.scenario.borrowAttempts.get()).isZero();
        }
    }

    @Test void enabledSecurityRejectsBeforeMvcAndRetainsActualCsrfAndSecurityHeaders()throws Exception {
        try(var f=new Fixture(directory.resolve("anonymous"),true)){
            assertThat(f.context.getBeansOfType(TenantAuditQueryController.class)).hasSize(1);
            assertThat(f.context.getBeansOfType(TenantAuditQueryService.class)).hasSize(1);
            assertThat(f.context.getBeansOfType(TenantAuditHttpTransport.class)).hasSize(1);
            var browser=f.browser();
            // Deliberately malformed JSON would produce400 if MVC were entered before security.
            var denied=browser.post(AUDIT,"{",null,Map.of());error(denied,403,"ACTION_DENIED");f.bounded(denied);
            assertThat(denied.values("Set-Cookie")).anySatisfy(value->assertThat(value).startsWith("XSRF-TOKEN=").contains("Path=/", "Secure", "SameSite=Lax"));
            assertThat(denied.values("X-Content-Type-Options")).containsExactly("nosniff");
            assertThat(denied.values("X-Frame-Options")).containsExactly("DENY");
            assertThat(browser.cookies).doesNotContainKey("SESSION");
            var unauthorized=browser.post(AUDIT,"{",browser.csrf(),Map.of());error(unauthorized,401,"AUTH_REQUIRED");f.bounded(unauthorized);
            assertThat(f.scenario.lastIds.get()).isNull();assertThat(f.scenario.borrowAttempts.get()).isZero();
            assertThat(browser.cookies).doesNotContainKey("SESSION");
        }
    }

    @Test void authenticatedParserAndConnectionFailuresUseTheCanonicalGraphAndServerIssuedIds()throws Exception {
        try(var f=new Fixture(directory.resolve("authenticated"),true)){
            var browser=f.browser();browser.login();
            UUID suppliedRequest=UUID.randomUUID(),suppliedTrace=UUID.randomUUID();
            var supplied=Map.of("X-Request-Id",suppliedRequest.toString(),"X-Trace-Id",suppliedTrace.toString());
            var malformed=browser.post(AUDIT,"{\"unknown\":1}",browser.csrf(),supplied);
            var parsed=error(malformed,400,"BAD_REQUEST");auditHeaders(malformed);f.bounded(malformed);
            var first=f.scenario.lastIds.get();assertThat(first).isNotNull();
            assertThat(first.requestId()).isNotEqualTo(suppliedRequest).isNotEqualTo(first.traceId());
            assertThat(first.traceId()).isNotEqualTo(suppliedTrace);
            assertThat(parsed.path("traceId").asString()).isEqualTo(first.traceId().toString());
            assertThat(f.scenario.borrowAttempts.get()).isZero();
            var unavailable=browser.post(AUDIT,"{}",browser.csrf(),supplied);
            var failed=error(unavailable,503,"UNAVAILABLE");auditHeaders(unavailable);f.bounded(unavailable);
            assertThat(failed.path("retryable").isBoolean()).isTrue();assertThat(failed.path("retryable").asBoolean()).isFalse();
            var second=f.scenario.lastIds.get();assertThat(second.requestId()).isNotEqualTo(first.requestId()).isNotEqualTo(suppliedRequest);
            assertThat(second.traceId()).isNotEqualTo(first.traceId()).isNotEqualTo(suppliedTrace);
            assertThat(failed.path("traceId").asString()).isEqualTo(second.traceId().toString());
            assertThat(f.scenario.borrowAttempts.get()).isEqualTo(1);
            assertThat(new String(unavailable.body(),StandardCharsets.UTF_8)).doesNotContain(PRIVATE_FAILURE,"SQLException");
        }
    }

    @Test void staleCredentialsIncludeTheActualSessionFilterFinalCookieAndInvalidateTheStoredSession()throws Exception {
        try(var f=new Fixture(directory.resolve("revoked"),true)){
            var browser=f.browser();browser.login();assertThat(f.scenario.sessions).hasSize(1);
            f.scenario.credentialVersion.set(2);
            var response=browser.post(AUDIT,"{",browser.csrf(),Map.of());error(response,401,"AUTH_REQUIRED");f.bounded(response);
            // Identity writes its explicit deletions; Spring Session adds its own final expiry on unwind.
            assertThat(response.values("Set-Cookie").stream().filter(value->value.startsWith("SESSION=")).toList()).hasSize(2)
                    .allSatisfy(value->assertThat(value).contains("Max-Age=0", "HttpOnly", "Secure", "SameSite=Lax"));
            assertThat(response.values("Set-Cookie")).anySatisfy(value->assertThat(value).startsWith("XSRF-TOKEN=").contains("Max-Age=0"));
            assertThat(browser.cookies).doesNotContainKeys("SESSION","XSRF-TOKEN");assertThat(f.scenario.sessions).isEmpty();
            assertThat(f.scenario.lastIds.get()).isNull();assertThat(f.scenario.borrowAttempts.get()).isZero();
        }
    }

    @Test void liveProfilePermanentlyClosesAfterAnObservedFlagChange()throws Exception {
        try(var f=new Fixture(directory.resolve("drift"),true)){
            var profile=f.context.getBean(RiskScenarioTomcatProfile.class);assertThat(profile.getAsInt()).isPositive();
            f.properties.put("msf.audit-query.http.enabled","false");assertThatThrownBy(profile::getAsInt).isInstanceOf(IllegalStateException.class);
            f.properties.put("msf.audit-query.http.enabled","true");assertThatThrownBy(profile::getAsInt).isInstanceOf(IllegalStateException.class);
        }
    }

    private static JsonNode error(Wire response,int status,String code){
        assertThat(response.status()).isEqualTo(status);assertThat(response.body()).hasSizeLessThanOrEqualTo(8192);
        assertThat(response.values("Content-Length")).containsExactly(Integer.toString(response.body().length));
        assertThat(response.values("Content-Type")).singleElement().asString().startsWith("application/json");
        var node=JSON.readTree(response.body());assertThat(node.path("code").isString()).isTrue();assertThat(node.path("code").asString()).isEqualTo(code);
        assertThat(node.path("traceId").isString()).isTrue();assertThatCode(()->UUID.fromString(node.path("traceId").asString())).doesNotThrowAnyException();
        return node;
    }
    private static void auditHeaders(Wire response){
        assertThat(response.values("Content-Type")).containsExactly("application/json;charset=UTF-8");
        assertThat(response.values("Cache-Control")).containsExactly("no-store");assertThat(response.values("ETag")).isEmpty();assertThat(response.values("Vary")).isEmpty();
    }

    @Configuration(proxyBeanMethods=false)
    @EnableWebMvc @EnableSpringHttpSession
    @Import({RiskScenarioHttpConfiguration.class,SecurityConfiguration.class,SessionCookieConfiguration.class,
            SessionContextResolver.class,AuthenticationSessionController.class,TenantAuditQueryController.class,BootRegistrations.class})
    static class Application {
        @Bean static PropertySourcesPlaceholderConfigurer placeholders(){return new PropertySourcesPlaceholderConfigurer();}
        @Bean TomcatServletWebServerFactory tomcat(Scenario scenario){
            var factory=new TomcatServletWebServerFactory(0);factory.setAddress(InetAddress.getLoopbackAddress());factory.setBaseDirectory(scenario.directory.toFile());return factory;
        }
        @Bean ServletRegistrationBean<DispatcherServlet> dispatcherServletRegistration(ApplicationContext context){
            var registration=new ServletRegistrationBean<>(new DispatcherServlet((WebApplicationContext)context),"/");registration.setName("dispatcherServlet");registration.setLoadOnStartup(1);return registration;
        }
        @Bean MapSessionRepository sessionRepository(Scenario scenario){return new MapSessionRepository(scenario.sessions);}
        @Bean ObjectMapper objectMapper(){return JSON;}
        @Bean SessionSensitiveKeys retainedTestKeys(){return new SessionSensitiveKeys("audit-container-test",Map.of("audit-container-test",new byte[32]));}
        @Bean DataSource dataSource(Scenario scenario){return scenario.source;}
        @Bean JdbcRepositorySettings jdbcRepositorySettings()throws Exception {
            var value=new AtomicReference<JdbcRepositorySettings>();AssetFoundationSqlCatalogTest.fixture(false).run(c->{assertThat(c).hasNotFailed();value.set(c.getBean(JdbcRepositorySettings.class));});
            assertThat(((Enum<?>)value.get().getDatabaseType()).name()).isEqualTo("POSTGRES");return value.get();
        }
        @Bean AccountStatusReadRepository accountStatusReadRepository(Scenario scenario){return id->id.equals(scenario.accountId)?Optional.of(new AccountSecurityState("ACTIVE",scenario.credentialVersion.get())):Optional.empty();}
        @Bean AuthenticationEvents authenticationEvents(Scenario scenario){return event->scenario.loginEvents.incrementAndGet();}
        @Bean(name="localPasswordAuthenticationProvider") AuthenticationProvider provider(Scenario scenario){return new AuthenticationProvider(){
            @Override public boolean supports(Class<?> type){return type.getName().equals("com.mightyoung.softwarefactory.identity.LocalPasswordAuthenticationToken");}
            @Override public Authentication authenticate(Authentication input){
                if(!"audit-container-user".equals(input.getPrincipal())||!(input.getCredentials() instanceof char[] secret)||!Arrays.equals(secret,"audit-container-password".toCharArray()))throw new BadCredentialsException("Synthetic credentials rejected");
                scenario.providerCalls.incrementAndGet();return UsernamePasswordAuthenticationToken.authenticated(
                        VerifiedSessionPrincipalTestFixture.create(scenario.actorId,scenario.accountId,scenario.tenantId,scenario.memberId,1,1,1),null,List.of());
            }
        };}
        @Bean WebMvcConfigurer observeServerIds(Scenario scenario){return new WebMvcConfigurer(){
            @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
                @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){if(AUDIT.equals(request.getRequestURI()))scenario.lastIds.set(IdentityRequestIds.get(request));return true;}
            });}
        };}
        @Bean RiskProbe riskProbe(RiskScenarioHttpTransport transport){return new RiskProbe(transport);}
    }
    static class BootRegistrations implements ImportSelector {
        @Override public String[] selectImports(AnnotationMetadata metadata){return new String[]{
                "org.springframework.boot.session.autoconfigure.SessionRepositoryFilterConfiguration",
                "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration"};}
    }
    @RestController static class RiskProbe {
        private final RiskScenarioHttpTransport transport;RiskProbe(RiskScenarioHttpTransport transport){this.transport=transport;}
        @RequestMapping(path={"/api/v1/entities/risk_scenario/schema",RISK,"/api/v1/risk-scenarios/fixture/history",
                "/api/v1/risk-scenarios/fixture/history/revision/hazards/hazard"},method={RequestMethod.GET,RequestMethod.HEAD})
        public void read(HttpServletRequest request,HttpServletResponse response)throws IOException{stage(request,response);}
        @PostMapping({"/api/v1/entities/risk_scenario/query","/api/v1/risk-scenarios/eligible-hazards"})
        public void query(HttpServletRequest request,HttpServletResponse response)throws IOException{stage(request,response);}
        private void stage(HttpServletRequest request,HttpServletResponse response)throws IOException{transport.stage(request,response,200,
                Map.of("Content-Type",List.of("application/json"),"Cache-Control",List.of("no-store")),2,out->out.write("{}".getBytes(StandardCharsets.US_ASCII)));}
        @GetMapping("/api/v1/entities/risk_scenario/definition") public void unchanged(HttpServletRequest request,HttpServletResponse response)throws IOException{
            transport.stage(request,response,304,Map.of("ETag",List.of("\"fixture\"")),0,out->{throw new AssertionError("304 writer must not run");});
        }
    }

    private static final class Scenario {
        final Path directory;final UUID actorId=UUID.randomUUID(),accountId=UUID.randomUUID(),tenantId=UUID.randomUUID(),memberId=UUID.randomUUID();
        final Map<String,Session> sessions=new ConcurrentHashMap<>();final AtomicInteger borrowAttempts=new AtomicInteger(),providerCalls=new AtomicInteger(),loginEvents=new AtomicInteger();
        final AtomicLong credentialVersion=new AtomicLong(1);final AtomicReference<IdentityRequestIds.Ids> lastIds=new AtomicReference<>();
        final DataSource source=new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException{borrowAttempts.incrementAndGet();throw new SQLException(PRIVATE_FAILURE);}
            @Override public Connection getConnection(String username,String password)throws SQLException{borrowAttempts.incrementAndGet();throw new SQLException(PRIVATE_FAILURE);}
        };
        Scenario(Path directory){this.directory=directory;}
    }
    private static final class Fixture implements AutoCloseable {
        final ServletWebServerApplicationContext context=new ServletWebServerApplicationContext();final Map<String,Object> properties=new HashMap<>();final Scenario scenario;
        Fixture(Path directory,Boolean enabled)throws IOException {
            Files.createDirectories(directory);scenario=new Scenario(directory);if(enabled!=null)properties.put("msf.audit-query.http.enabled",enabled.toString());
            properties.put("server.port","0");context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("audit-container-fixture",properties));
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            context.registerBean(Scenario.class,()->scenario);new AnnotatedBeanDefinitionReader(context).register(Application.class);
            try{context.refresh();assertThat(context.getWebServer().getPort()).isPositive();assertThat(scenario.borrowAttempts.get()).isZero();}
            catch(RuntimeException|Error failure){try{context.close();}catch(RuntimeException|Error cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;}
        }
        Browser browser(){return new Browser(this);}
        void bounded(Wire response){
            var server=(TomcatWebServer)context.getWebServer();var protocol=(Http11NioProtocol)server.getTomcat().getConnector().getProtocolHandler();
            assertThat(response.headerBytes()).isLessThanOrEqualTo(protocol.getMaxHttpResponseHeaderSize()).isLessThanOrEqualTo(RiskScenarioHeaderBudget.MAX_BYTES);
        }
        @Override public void close(){context.close();}
    }
    private static final class Browser {
        final Fixture fixture;final Map<String,String> cookies=new LinkedHashMap<>();Browser(Fixture fixture){this.fixture=fixture;}
        String csrf(){var token=cookies.get("XSRF-TOKEN");assertThat(token).isNotBlank();return token;}
        void login()throws IOException {
            assertThat(exchange("GET",CSRF,null,Map.of()).status()).isEqualTo(204);assertThat(cookies).doesNotContainKey("SESSION");
            var response=post("/api/v1/auth/login","{\"loginKey\":\"audit-container-user\",\"password\":\"audit-container-password\",\"tenantCode\":\"audit-container-tenant\"}",csrf(),Map.of());
            assertThat(response.status()).isEqualTo(204);assertThat(response.values("Set-Cookie")).anySatisfy(value->assertThat(value).startsWith("SESSION=").contains("HttpOnly","Secure","SameSite=Lax"));
            assertThat(cookies).containsKey("SESSION");assertThat(fixture.scenario.providerCalls.get()).isEqualTo(1);assertThat(fixture.scenario.loginEvents.get()).isEqualTo(1);
            assertThat(fixture.scenario.sessions).hasSize(1);var saved=fixture.scenario.sessions.values().iterator().next();
            Object value=saved.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);assertThat(value).isInstanceOf(SecurityContext.class);
            assertThat(((SecurityContext)value).getAuthentication().getPrincipal()).isInstanceOf(VerifiedSessionPrincipal.class);
            assertThat(exchange("GET",CSRF,null,Map.of()).status()).isEqualTo(204);csrf();
        }
        Wire post(String path,String body,String csrf,Map<String,String> extra)throws IOException {
            var headers=new LinkedHashMap<>(extra);if(csrf!=null)headers.put("X-XSRF-TOKEN",csrf);return exchange("POST",path,body,headers);
        }
        Wire exchange(String method,String path,String body,Map<String,String> extra)throws IOException {
            byte[] payload=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);var request=new StringBuilder(method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n");
            if(!cookies.isEmpty())request.append("Cookie: ").append(String.join("; ",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList())).append("\r\n");
            extra.forEach((name,value)->request.append(name).append(": ").append(value).append("\r\n"));
            if(body!=null)request.append("Content-Type: application/json\r\nContent-Length: ").append(payload.length).append("\r\n");request.append("\r\n");
            byte[] bytes;try(var socket=new Socket(InetAddress.getLoopbackAddress(),fixture.context.getWebServer().getPort())){
                socket.setSoTimeout(10_000);socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(payload);socket.getOutputStream().flush();
                var output=new ByteArrayOutputStream();socket.getInputStream().transferTo(output);bytes=output.toByteArray();
            }
            String wire=new String(bytes,StandardCharsets.ISO_8859_1);int split=wire.indexOf("\r\n\r\n");assertThat(split).isNotNegative();var lines=wire.substring(0,split).split("\r\n");
            int status=Integer.parseInt(lines[0].split(" ")[1]);Map<String,List<String>> headers=new LinkedHashMap<>();
            for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');assertThat(colon).isPositive();headers.computeIfAbsent(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),ignored->new ArrayList<>()).add(lines[i].substring(colon+1).strip());}
            var response=new Wire(status,headers,Arrays.copyOfRange(bytes,split+4,bytes.length),split+4);
            for(String header:response.values("Set-Cookie")){int semi=header.indexOf(';');String pair=semi<0?header:header.substring(0,semi);int equals=pair.indexOf('=');if(equals<1)continue;
                String name=pair.substring(0,equals);if(header.contains("Max-Age=0"))cookies.remove(name);else cookies.put(name,pair.substring(equals+1));}
            return response;
        }
    }
    private record Wire(int status,Map<String,List<String>> headers,byte[] body,int headerBytes){List<String> values(String name){return headers.getOrDefault(name.toLowerCase(Locale.ROOT),List.of());}}
}
