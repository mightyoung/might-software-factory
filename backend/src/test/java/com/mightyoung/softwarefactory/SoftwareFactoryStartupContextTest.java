package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator;
import com.mightyoung.softwarefactory.dualprevention.FindingReadService;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener;
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

    @Test
    void applicationStartsWithRealModulithListenerAndJdkRepositoryProxies() throws Exception {
        assertThat(port).isPositive();
        assertThat(AopUtils.isJdkDynamicProxy(credentials)).isTrue();
        assertThat(AopUtils.isAopProxy(workflowEventListener)).isTrue();
        assertThat(AopUtils.isCglibProxy(workflowEventListener)).isTrue();
        assertThat(queryValidator).isNotNull();
        assertThat(findingReadService).isNotNull();
        assertThat(eventPublicationRegistry).isNotNull();
        assertThat(AopUtils.getTargetClass(eventPublicationRepository).getName())
                .contains("JdbcEventPublicationRepository");

        var repositories = applicationContext.getBeansWithAnnotation(Repository.class);
        assertThat(repositories).hasSize(18);
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
}
