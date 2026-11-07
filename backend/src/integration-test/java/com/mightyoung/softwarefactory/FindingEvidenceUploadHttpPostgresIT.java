package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.files.UnconfiguredUploadProducerReadiness;
import com.mightyoung.softwarefactory.files.UploadProducerReadiness;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Actual TLS/session/CSRF refusal with the default unavailable producer. No producer-success,
 * current task authorization, command transaction or uploaded/verified bytes are proved here.
 * Uses only V14 business columns plus the V15 intent table, compatible with PR36 source.
 */
@SpringBootTest(classes=SoftwareFactoryApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FindingEvidenceUploadHttpPostgresIT {
    private static final String CSRF="/api/v1/auth/csrf";
    private static final String CURRENT="/api/v1/session/current";
    // Tables confirmed in original V5/V6/V7 plus V15, not inferred from the current application schema.
    private static final List<String> TENANT_TABLES=List.of("finding","workflow_binding","finding_command_receipt",
            "finding_transition","finding_confirmation","remediation_round","remediation_notice","evidence_set",
            "evidence_item","remediation_submission","verification","audit_event","file_object","file_link",
            "remediation_submission_evidence","finding_number_sequence","finding_evidence_upload_intent");
    private static IdentityAcceptanceHttps https;
    @LocalServerPort int port;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired UploadProducerReadiness producer;
    FindingEvidenceUploadIntentPostgresFixture fixture;
    IdentityAcceptanceHttps.Browser browser;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p)throws Exception {
        IdentityAcceptanceTarget.validateInputs(System.getenv());
        var target=FindingEvidenceUploadIntentPostgresFixture.target();
        // Inspect only: installation/grants must have been approved and performed separately.
        try(var connection=DriverManager.getConnection(target.jdbcUrl(),required("LAN_PG_USERNAME"),required("LAN_PG_PASSWORD"))){
            connection.setReadOnly(true);
            try(var statement=connection.prepareStatement("select exists(select 1 from public.flyway_schema_history_app where version='15' and success)");var rows=statement.executeQuery()){
                if(!rows.next()||!Boolean.TRUE.equals(rows.getObject(1))||rows.next())throw new IllegalStateException("Separately installed V15 is required");
            }
        }
        if(https==null)https=IdentityAcceptanceHttps.create();
        p.add("spring.datasource.url",target::jdbcUrl);p.add("spring.datasource.username",()->required("LAN_PG_APP_USERNAME"));p.add("spring.datasource.password",()->required("LAN_PG_APP_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size",()->4);p.add("spring.datasource.hikari.minimum-idle",()->0);
        p.add("server.address",()->"127.0.0.1");p.add("server.ssl.enabled",()->true);p.add("server.ssl.key-store",()->https.keyStore().toUri().toString());
        p.add("server.ssl.key-store-password",https::password);p.add("server.ssl.key-store-type",()->"PKCS12");p.add("server.ssl.key-alias",()->"identity-acceptance");
        p.add("spring.flyway.enabled",()->false);p.add("spring.sql.init.mode",()->"never");p.add("spring.session.jdbc.initialize-schema",()->"never");p.add("spring.session.jdbc.cleanup-cron",()->"-");
        p.add("spring.modulith.events.jdbc.schema-initialization.enabled",()->false);p.add("flowable.database-schema-update",()->false);p.add("flowable.check-process-definitions",()->false);
        p.add("flowable.async-executor-activate",()->false);p.add("flowable.async-history-executor-activate",()->false);
    }
    @BeforeEach void ownedSyntheticMetadataAndBrowser()throws Exception {
        assertThat(producer).isInstanceOf(UnconfiguredUploadProducerReadiness.class);
        fixture=new FindingEvidenceUploadIntentPostgresFixture(passwords);browser=https.browser(port);
        expect(browser.get(CSRF),204);assertThat(browser.cookie("SESSION")).isEmpty();
        var csrf=browser.cookie("XSRF-TOKEN").orElseThrow();assertThat(csrf.getSecure()).isTrue();assertThat(csrf.isHttpOnly()).isFalse();
    }
    @AfterEach void releaseOnlyOwnedSessionsAndConnections(){
        try{if(browser!=null)browser.close();}finally{if(fixture!=null)fixture.close();}
    }
    @AfterAll static void closeTls()throws Exception{if(https!=null){https.close();https=null;}}

    @Test void authenticatedValidDeclarationIsRefusedBeforeTheCommandTransactionWhenProducerIsUnavailable()throws Exception {
        login();var before=snapshot();
        var response=browser.postWithHeaders(path(),declaration(),browser.csrf(),Map.of("Idempotency-Key",UUID.randomUUID().toString()));
        assertError(response,503,"DEPENDENCY_UNAVAILABLE",true);
        assertThat(response.http().headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(snapshot()).isEqualTo(before);assertCurrentIdentity();
    }
    @Test void authenticatedMissingAndIncorrectCsrfAreRejectedWithoutBusinessEffects()throws Exception {
        login();var before=snapshot();
        for(String csrf:Arrays.asList(null,"invalid-synthetic-csrf")){
            var response=browser.postWithHeaders(path(),declaration(),csrf,Map.of("Idempotency-Key",UUID.randomUUID().toString()));
            assertError(response,403,"ACTION_DENIED",false);assertThat(snapshot()).isEqualTo(before);
        }
        assertCurrentIdentity();
    }
    @Test void anonymousRequestWithRealCsrfIsUnauthorizedAndDoesNotCreateAnAuthenticatedSession()throws Exception {
        var before=snapshot();
        var response=browser.postWithHeaders(path(),declaration(),browser.csrf(),Map.of("Idempotency-Key",UUID.randomUUID().toString()));
        assertError(response,401,"AUTH_REQUIRED",false);assertThat(browser.cookie("SESSION")).isEmpty();
        assertThat(snapshot()).isEqualTo(before);expect(browser.get(CURRENT),401);assertThat(browser.cookie("SESSION")).isEmpty();
    }

    private void login()throws Exception {
        var actor=fixture.business.remediator;
        expect(browser.post("/api/v1/auth/login",json.writeValueAsString(Map.of("loginKey",actor.loginKey(),"password",actor.password(),"tenantCode",fixture.business.tenantCode)),browser.csrf()),204);
        var session=browser.cookie("SESSION").orElseThrow();assertThat(session.getSecure()).isTrue();assertThat(session.isHttpOnly()).isTrue();assertCurrentIdentity();
        // Authentication events and JDBC Session persistence are expected; take business snapshots only after login.
    }
    private void assertCurrentIdentity()throws Exception {
        var response=browser.get(CURRENT);expect(response,200);var data=json.readTree(response.body()).path("data");var actor=fixture.business.remediator;
        assertThat(data.path("actorId").asString()).isEqualTo(actor.principalId().toString());assertThat(data.path("accountId").asString()).isEqualTo(actor.accountId().toString());
        assertThat(data.path("tenantId").asString()).isEqualTo(fixture.business.tenantId.toString());assertThat(data.path("memberId").asString()).isEqualTo(actor.memberId().toString());
    }
    private String path(){return "/api/v1/findings/"+fixture.business.legacyFindingId+"/remediation-rounds/1/evidence-uploads";}
    private String declaration(){return "{\"evidenceSetId\":\""+fixture.setId+"\",\"expectedFindingVersion\":1,\"expectedEvidenceRevision\":0,\"filename\":\"g021-default-producer-proof.txt\",\"declaredMediaType\":\"text/plain\",\"declaredSizeBytes\":64}";}
    private void assertError(IdentityAcceptanceHttps.Response response,int status,String code,boolean retryable)throws Exception {
        expect(response,status);assertThat(response.http().headers().allValues("Location")).isEmpty();
        assertThat(response.http().headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        var error=json.readTree(response.body());assertThat(error.has("code")).isTrue();assertThat(error.get("code").isString()).isTrue();
        assertThat(error.get("code").stringValue()).isEqualTo(code);assertThat(error.has("retryable")).isTrue();assertThat(error.get("retryable").isBoolean()).isTrue();
        assertThat(error.get("retryable").booleanValue()).isEqualTo(retryable);
        assertThat(error.path("fieldErrors").isArray()).isTrue();assertThat(error.path("fieldErrors").size()).isZero();
        assertThat(error.has("traceId")).isTrue();assertThat(error.get("traceId").isString()).isTrue();
        assertThatCode(()->UUID.fromString(error.get("traceId").stringValue())).doesNotThrowAnyException();
        assertThat(error.has("data")).isFalse();assertThat(error.has("statusUrl")).isFalse();assertThat(error.has("uploadIntentId")).isFalse();
        for(String privateValue:List.of(fixture.business.remediator.loginKey(),fixture.business.remediator.password(),fixture.business.tenantCode,
                fixture.business.legacyFindingId.toString(),fixture.setId.toString(),"g021-default-producer-proof.txt","org.postgresql","SQLException","storage_key"))
            assertThat(response.body().contains(privateValue)).as("No request, identity or dependency details in rejection").isFalse();
        var session=browser.cookie("SESSION");if(session.isPresent())assertThat(response.body().contains(session.get().getValue())).isFalse();
    }
    /** Only owned business rows and publication rows containing this tenant string; excludes engine tables. */
    private Snapshot snapshot(){
        return fixture.business.read(db->{
            Map<String,List<Map<String,Object>>> rows=new TreeMap<>();
            for(String table:TENANT_TABLES)rows.put(table,db.queryForList("select to_jsonb(t)::text row from public."+table+" t where tenant_id=? order by to_jsonb(t)::text",fixture.business.tenantId));
            rows.put("publication",db.queryForList("select (to_jsonb(t)-'completion_date')::text row from public.event_publication t where serialized_event like ? order by (to_jsonb(t)-'completion_date')::text","%"+fixture.business.tenantId+"%"));
            return new Snapshot(Map.copyOf(rows));
        });
    }
    private record Snapshot(Map<String,List<Map<String,Object>>> rows){
        @Override public String toString(){Map<String,Integer> counts=new TreeMap<>();rows.forEach((name,values)->counts.put(name,values.size()));return "OwnedUploadRefusalSnapshot[rowCounts="+counts+"]";}
    }
    private static void expect(IdentityAcceptanceHttps.Response response,int status){assertThat(response.status()).as("HTTP status (response details withheld)").isEqualTo(status);}
    private static String required(String key){var value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing explicit upload HTTP acceptance input");return value;}
}
