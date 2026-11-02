package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipal;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ObjectInputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real JDBC Session serialization only; does not claim business identity-table acceptance. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CredentialVersionSessionPostgresIT {

    @Autowired private FindByIndexNameSessionRepository<?> sessionRepository;
    @Autowired private SessionContextResolver resolver;

    @DynamicPropertySource
    static void lanPostgresProperties(DynamicPropertyRegistry properties) {
        var target = LanPostgresCompatibilityIT.DatabaseTarget.preflightFromEnvironment(System.getenv());
        properties.add("spring.datasource.url", target::jdbcUrl);
        properties.add("spring.datasource.username", () -> System.getenv("LAN_PG_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("LAN_PG_PASSWORD"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aNonDefaultCredentialVersionSurvivesTheJdbcSecurityContextRoundTrip() {
        var principal = VerifiedSessionPrincipalTestFixture.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7L, 11L, 73L);
        withStoredContext(sessionRepository, principal, restored -> {
            VerifiedSessionPrincipal reloaded = (VerifiedSessionPrincipal) restored.getAuthentication().getPrincipal();
            assertThat(reloaded).isNotSameAs(principal);
            assertThat(reloaded.credentialVersion()).isEqualTo(73L);
            SecurityContextHolder.setContext(restored);
            var tenant = resolver.currentTenant();
            assertThat(tenant.accountId()).isEqualTo(principal.accountId());
            assertThat(tenant.credentialVersion()).isEqualTo(73L);
            assertThat(tenant.tenantAuthEpoch()).isEqualTo(7L);
            assertThat(tenant.memberAuthEpoch()).isEqualTo(11L);
        });
    }

    @Test
    void anUnversionedLegacyPrincipalCannotBecomeTrustedThroughJdbcSessionReload() throws Exception {
        var fixture = new ClassPathResource(
                "com/mightyoung/softwarefactory/identity/verified-principal-before-credential-version.ser");
        VerifiedSessionPrincipal legacy;
        try (var input = new ObjectInputStream(fixture.getInputStream())) {
            legacy = (VerifiedSessionPrincipal) input.readObject();
        }
        assertThat(legacy.credentialVersion()).isZero();
        withStoredContext(sessionRepository, legacy, restored -> {
            var reloaded = (VerifiedSessionPrincipal) restored.getAuthentication().getPrincipal();
            assertThat(reloaded.credentialVersion()).isZero();
            SecurityContextHolder.setContext(restored);
            assertThatThrownBy(resolver::currentTenant).isInstanceOf(UnauthenticatedSessionException.class);
        });
    }

    private static <S extends Session> void withStoredContext(
            FindByIndexNameSessionRepository<S> repository, VerifiedSessionPrincipal principal,
            Consumer<SecurityContext> assertions) {
        S session = repository.createSession();
        String id = session.getId();
        try {
            SecurityContext context = new SecurityContextImpl(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            repository.save(session);
            Session reloaded = repository.findById(id);
            assertThat(reloaded).isNotNull();
            SecurityContext restored = reloaded.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            assertThat(restored).isNotNull();
            assertions.accept(restored);
        } finally {
            repository.deleteById(id);
        }
    }
}
