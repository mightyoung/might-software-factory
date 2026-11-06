package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator;
import com.mightyoung.softwarefactory.dualprevention.FindingReadService;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener;
import com.mightyoung.softwarefactory.dualprevention.AssetFoundationCapabilityUnavailable;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationService;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationReadService;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationQueryService;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationHistoryService;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationDefinitionService;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationSelectorService;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationQueryController;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationHistoryController;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationDefinitionController;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationSelectorController;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationRequestParser;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEventListener;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationFailure;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowState;
import com.mightyoung.softwarefactory.identity.AccountPasswordCredentialReadRepository;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Starts the non-lazy application with the real Spring Modulith JDBC publication registry.
 * H2 is scoped to this test; Flyway remains disabled and no external database is contacted.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:software_factory_startup;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.main.lazy-initialization=false",
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "spring.session.jdbc.initialize-schema=always",
                "spring.flyway.enabled=false",
                "flowable.database-schema-update=true",
                "flowable.async-executor-activate=false",
                "flowable.async-history-executor-activate=false"
        })
class SoftwareFactoryStartupContextTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AccountPasswordCredentialReadRepository credentials;

    @Autowired
    private EntityQueryValidator queryValidator;

    @Autowired
    private FindingReadService findingReadService;

    @Autowired
    private FindingWorkflowEventListener workflowEventListener;

    @Autowired
    private EventPublicationRegistry eventPublicationRegistry;

    @Autowired
    private EventPublicationRepository eventPublicationRepository;

    @Autowired
    private ApplicationEventPublisher applicationEvents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired private AssetFoundationService assetCommands;
    @Autowired private AssetFoundationReadService assetReads;
    @Autowired private AssetFoundationRequestParser assetParser;
    @Autowired private AssetFoundationEventListener assetListener;
    @Autowired private SessionSensitiveRequests sensitiveRequests;

    @Test
    void applicationStartsWithRealModulithListenerAndJdkRepositoryProxies() throws Exception {
        assertThat(port).isPositive();
        assertThat(AopUtils.isJdkDynamicProxy(credentials)).isTrue();
        assertThat(AopUtils.isAopProxy(workflowEventListener)).isTrue();
        assertThat(AopUtils.isCglibProxy(workflowEventListener)).isTrue();
        assertThat(queryValidator).isNotNull();
        assertThat(findingReadService).isNotNull();
        assertThat(eventPublicationRegistry).isNotNull();
        assertThat(assetCommands).isNotNull();
        assertThat(assetReads).isNotNull();
        assertThat(assetParser).isNotNull();
        for(Class<?> service:java.util.List.of(AssetFoundationQueryService.class,AssetFoundationHistoryService.class,
                AssetFoundationDefinitionService.class,AssetFoundationSelectorService.class)) {
            assertThat(applicationContext.getBeansOfType(service)).hasSize(1);
        }
        var readControllers=java.util.Set.of(AssetFoundationQueryController.class,AssetFoundationHistoryController.class,
                AssetFoundationDefinitionController.class,AssetFoundationSelectorController.class);
        var mappings=applicationContext.getBean("requestMappingHandlerMapping",
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        long remainingReadMappings=mappings.getHandlerMethods().values().stream()
                .filter(handler->readControllers.contains(handler.getBeanType())).count();
        assertThat(remainingReadMappings).isEqualTo(38);
        long allAssetMappings=mappings.getHandlerMethods().values().stream()
                .filter(handler->readControllers.contains(handler.getBeanType())
                        ||handler.getBeanType()==com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationController.class).count();
        assertThat(allAssetMappings).isEqualTo(67);
        assertThat(AopUtils.isCglibProxy(assetListener)).isTrue();
        assertThat(applicationContext.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(javax.sql.DataSource.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(org.jooq.DSLContext.class)).hasSize(1);
        assertThat(AopUtils.getTargetClass(eventPublicationRepository).getName())
                .contains("JdbcEventPublicationRepository");

        var repositories = applicationContext.getBeansWithAnnotation(Repository.class);
        assertThat(repositories).hasSize(31).containsKeys(
                "jooqFindingReviewHistoryRepository", "jooqAuthenticationEvents", "jooqFindingDispatchCandidateReadRepository",
                "jooqFindingEvidenceUploadRepository", "jooqFindingEvidenceUploadReadRepository",
                "jooqFindingEvidenceSetInitializationRepository", "jooqFindingDefinitionReadRepository",
                "jooqFindingEvidenceIngestionRepository", "jooqFileIngestionLedger", "jooqDirectoryReferenceReadRepository");
        repositories.forEach((name, repository) ->
                assertThat(AopUtils.isJdkDynamicProxy(repository)).as(name).isTrue());

        var csrfRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf"))
                .GET()
                .build();
        var csrfResponse = HttpClient.newHttpClient().send(csrfRequest, HttpResponse.BodyHandlers.discarding());
        assertThat(csrfResponse.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void modulithListenerPublishesAndCompletesThroughTheJdbcRegistry() throws InterruptedException {
        int completedBefore = eventPublicationRepository.findCompletedPublications().size();
        var event = new FindingWorkflowChangedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "STARTUP_PROXY_REGRESSION",
                null,
                FindingWorkflowState.PERSON_CONFIRM_PENDING,
                1,
                1);

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> applicationEvents.publishEvent(event));

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        int completedAfter = completedBefore;
        while (completedAfter <= completedBefore && System.nanoTime() < deadline) {
            Thread.sleep(25);
            completedAfter = eventPublicationRepository.findCompletedPublications().size();
        }
        assertThat(completedAfter).isGreaterThan(completedBefore);
    }

    @Test
    void unsupportedDatabaseRejectsARealCapturedReadBeforeAuthorityOrBusinessSql() {
        var principal=VerifiedSessionPrincipalTestFixture.create(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        var authentication=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal,"",java.util.List.of());
        var security=org.springframework.security.core.context.SecurityContextHolder.getContext();
        var previous=security.getAuthentication();security.setAuthentication(authentication);
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(request.getSession());
        var captured=sensitiveRequests.capture(request);
        try {
            Throwable refusal=catchThrowable(()->assetReads.workshop(captured,UUID.randomUUID()));
            assertThat(refusal).isInstanceOf(AssetFoundationFailure.class);
            assertThat(((AssetFoundationFailure)refusal).status()).isEqualTo(503);
            assertThat(AssetFoundationCapabilityUnavailable.causedBy(refusal)).isTrue();
            Throwable commandRefusal=catchThrowable(()->assetCommands.createWorkshop(captured,UUID.randomUUID(),UUID.randomUUID(),
                    new AssetContracts.WorkshopCreate("STARTUP","Synthetic workshop",UUID.randomUUID(),null)));
            assertThat(commandRefusal).isInstanceOf(AssetFoundationFailure.class);
            assertThat(((AssetFoundationFailure)commandRefusal).status()).isEqualTo(503);
            assertThat(AssetFoundationCapabilityUnavailable.causedBy(commandRefusal)).isTrue();
        } finally { captured.close();security.setAuthentication(previous); }
    }

    /** Ordinary original-manager publication only; this is not a sealed PostgreSQL command acceptance. */
    @Test
    void assetTraceListenerUsesTheRealDefaultRegistryAndAsyncCompletion()throws InterruptedException {
        int before=eventPublicationRepository.findCompletedPublications().size();
        var event=new AssetFoundationChangedEvent(UUID.randomUUID(),UUID.randomUUID(),"workshop",UUID.randomUUID(),
                "workshop.update",1,java.time.Instant.parse("2026-11-04T00:00:00.123456Z"),UUID.randomUUID(),"UPDATE");
        new TransactionTemplate(transactionManager).executeWithoutResult(status->applicationEvents.publishEvent(event));
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        int after=before;
        while(after<=before&&System.nanoTime()<deadline) { Thread.sleep(25);after=eventPublicationRepository.findCompletedPublications().size(); }
        assertThat(after).isGreaterThan(before);
    }
}
