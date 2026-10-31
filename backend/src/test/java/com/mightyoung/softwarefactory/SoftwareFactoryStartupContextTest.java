package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator;
import com.mightyoung.softwarefactory.dualprevention.FindingReadService;
import com.mightyoung.softwarefactory.identity.AccountPasswordCredentialReadRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Starts the real application context without opening a database connection. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/not-a-real-database",
                "spring.datasource.username=unused",
                "spring.datasource.password=unused",
                "spring.main.lazy-initialization=true",
                "spring.autoconfigure.exclude=org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration,org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
                "spring.flyway.enabled=false",
                "spring.session.store-type=none",
                "spring.session.jdbc.initialize-schema=never",
                "flowable.database-schema-update=false",
                "flowable.async-executor-activate=false",
                "flowable.async-history-executor-activate=false"
        })
@Import(SoftwareFactoryStartupContextTest.TestEventPublicationConfiguration.class)
class SoftwareFactoryStartupContextTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEventPublicationConfiguration {

        @Bean
        EventPublicationRegistry eventPublicationRegistry() {
            return mock(EventPublicationRegistry.class);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AccountPasswordCredentialReadRepository credentials;

    @Autowired
    private EntityQueryValidator queryValidator;

    @Autowired
    private FindingReadService findingReadService;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void applicationContextProvidesJdkProxyRepositoriesAndFindingQueryDependencies() throws Exception {
        assertThat(port).isPositive();
        assertThat(AopUtils.isJdkDynamicProxy(credentials)).isTrue();
        assertThat(queryValidator).isNotNull();
        assertThat(findingReadService).isNotNull();

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
}
