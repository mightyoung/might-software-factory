package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.*;

class LoginSessionPersistenceFailureFilterTest {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void uncommittedSaveFailureClearsAuthenticationAndOnlyAuthenticationCookies() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        var response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("synthetic-unit", null, java.util.List.of()));
        new LoginSessionPersistenceFailureFilter().doFilter(request, response, (req, res) -> {
            response.setStatus(204);
            response.setHeader("Content-Length", "0");
            response.addHeader("Set-Cookie", "SESSION=synthetic-undelivered-token; Path=/; Secure; HttpOnly");
            response.addHeader("Set-Cookie", "XSRF-TOKEN=synthetic-undelivered-csrf; Path=/; Secure");
            response.addHeader("Set-Cookie", "theme=dark; Path=/; Secure");
            response.setHeader("X-Content-Type-Options", "nosniff");
            throw new DataAccessResourceFailureException("synthetic-internal-diagnostic");
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"code\":\"DEPENDENCY_UNAVAILABLE\"", "\"retryable\":true")
                .doesNotContain("synthetic-internal-diagnostic", "synthetic-undelivered");
        assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders("Set-Cookie")).contains("theme=dark; Path=/; Secure")
                .noneMatch(value -> value.contains("synthetic-undelivered"));
        assertThat(response.getHeaders("Set-Cookie")).anyMatch(value -> value.startsWith("SESSION=;") && value.contains("Max-Age=0") && value.contains("HttpOnly"));
        assertThat(response.getHeaders("Set-Cookie")).anyMatch(value -> value.startsWith("XSRF-TOKEN=;") && value.contains("Max-Age=0"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getSession(false)).isNull();
    }

    @Test void unknownCommitFailureIsUnavailableWithoutInventingRollbackOrRetry() throws Exception {
        var request = new MockHttpServletRequest("POST", "/factory/api/v1/auth/login");
        request.setContextPath("/factory");
        var response = new MockHttpServletResponse();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        new LoginSessionPersistenceFailureFilter().doFilter(request, response, (req, res) -> {
            calls.incrementAndGet();
            throw new TransactionSystemException("synthetic-unknown-commit");
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).doesNotContain("rollback", "synthetic-unknown-commit");
    }

    @Test void committedResponseKeepsTheOriginalFailureInsteadOfPretendingToChangeStatus() throws Exception {
        var response = new MockHttpServletResponse();
        response.setStatus(204);response.flushBuffer();
        var failure = new DataAccessResourceFailureException("synthetic-late-failure");
        assertThatThrownBy(() -> new LoginSessionPersistenceFailureFilter().doFilter(
                new MockHttpServletRequest("POST", "/api/v1/auth/login"), response,
                (req, res) -> { throw failure; })).isSameAs(failure);
        assertThat(response.getStatus()).isEqualTo(204);
    }

    @Test void otherRoutesAndNonPostLoginDoNotAcquireThisFailureBoundary() {
        for (var request : java.util.List.of(new MockHttpServletRequest("POST", "/api/v1/findings"),
                new MockHttpServletRequest("GET", "/api/v1/auth/login"))) {
            var failure = new DataAccessResourceFailureException("synthetic-other-operation");
            assertThatThrownBy(() -> new LoginSessionPersistenceFailureFilter().doFilter(request,
                    new MockHttpServletResponse(), (req, res) -> { throw failure; })).isSameAs(failure);
        }
    }

    @Test void registrationPrecedesTheConfiguredSessionFilterAndRejectsAnImpossibleOrder() {
        var configuration = new SecurityConfiguration();
        var registration = configuration.loginSessionPersistenceFailureBoundary(
                new MockEnvironment().withProperty("spring.session.servlet.filter-order", "-1000"));
        assertThat(registration.getOrder()).isEqualTo(-1001);
        assertThat(configuration.loginSessionPersistenceFailureBoundary(new MockEnvironment()).getOrder())
                .isEqualTo(org.springframework.session.web.http.SessionRepositoryFilter.DEFAULT_ORDER - 1);
        assertThatThrownBy(() -> configuration.loginSessionPersistenceFailureBoundary(new MockEnvironment()
                .withProperty("spring.session.servlet.filter-order", Integer.toString(Integer.MIN_VALUE))))
                .isInstanceOf(IllegalStateException.class);
    }
}
