package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import static org.assertj.core.api.Assertions.*;

class ExplicitHeaderCsrfTokenRepositoryTest {
    private static final String OLD = "a1111111-1111-1111-1111-111111111111";

    @Test void configuredBeanSavesTheRealServlet6SessionTokenOnlyAsAnExplicitHeader() {
        var repository = new SecurityConfiguration().csrfTokenRepository();
        assertThat(repository).isInstanceOf(ExplicitHeaderCsrfTokenRepository.class);
        var request = request(); var response = new HeaderOnlyResponse();
        var token = repository.generateToken(request);
        assertThat(UUID.fromString(token.getToken()).toString()).isEqualTo(token.getToken());
        assertThat(token.getHeaderName()).isEqualTo("X-XSRF-TOKEN"); assertThat(token.getParameterName()).isEqualTo("_csrf");
        repository.saveToken(token, request, response);
        assertThat(response.headers).containsExactly("XSRF-TOKEN=" + token.getToken() + "; Path=/; Secure; SameSite=Lax");
        assertThat(response.headers.getFirst()).doesNotContain("HttpOnly", "Domain=", "Max-Age=", "Expires=", "Partitioned");
        var later = request(); later.setCookies(new Cookie("XSRF-TOKEN", token.getToken()));
        assertThat(repository.loadToken(later).getToken()).isEqualTo(token.getToken());
    }

    @Test void deletionPreservesTheRemovedMarkerAndDefaultDeferredRegenerationPath() {
        var repository = new ExplicitHeaderCsrfTokenRepository(); var request = request(); var response = new HeaderOnlyResponse();
        request.setCookies(new Cookie("XSRF-TOKEN", OLD)); assertThat(repository.loadToken(request).getToken()).isEqualTo(OLD);
        repository.saveToken(null, request, response);
        assertThat(repository.loadToken(request)).isNull();
        assertThat(response.headers).hasSize(1);
        var deletion = MockCookie.parse(response.headers.getFirst());
        assertThat(deletion.getName()).isEqualTo("XSRF-TOKEN"); assertThat(deletion.getValue()).isEmpty();
        assertThat(deletion.getMaxAge()).isZero(); assertThat(deletion.getExpires()).isNotNull();
        assertThat(deletion.getPath()).isEqualTo("/"); assertThat(deletion.getSecure()).isTrue();
        assertThat(deletion.isHttpOnly()).isFalse(); assertThat(deletion.getSameSite()).isEqualTo("Lax"); assertThat(deletion.getDomain()).isNull();
        var deferred = repository.loadDeferredToken(request, response); assertThat(response.headers).hasSize(1);
        var regenerated = deferred.get(); assertThat(regenerated.getToken()).isNotEqualTo(OLD);
        assertThat(deferred.isGenerated()).isTrue(); assertThat(deferred.get()).isSameAs(regenerated);
        assertThat(response.headers).hasSize(2);
        assertThat(response.headers.getLast()).isEqualTo("XSRF-TOKEN=" + regenerated.getToken() + "; Path=/; Secure; SameSite=Lax");
    }

    @Test void actualAuthenticationStrategyRotatesThroughTheAdapterAndItsDeferredSave() {
        var repository = new ExplicitHeaderCsrfTokenRepository(); var request = request(); var response = new HeaderOnlyResponse();
        request.setCookies(new Cookie("XSRF-TOKEN", OLD));
        new CsrfAuthenticationStrategy(repository).onAuthentication(new TestingAuthenticationToken("fixture", null), request, response);
        assertThat(response.headers).hasSize(1);
        CsrfToken current = (CsrfToken) request.getAttribute(CsrfToken.class.getName()); assertThat(current).isNotNull();
        assertThat(current.getToken()).isNotBlank(); // Default handler may expose an XOR-masked form.
        assertThat(response.headers).hasSize(2);
        String rotated = MockCookie.parse(response.headers.getLast()).getValue();
        assertThat(UUID.fromString(rotated).toString()).isEqualTo(rotated); assertThat(rotated).isNotEqualTo(OLD);
        assertThat(response.headers.getLast()).isEqualTo("XSRF-TOKEN=" + rotated + "; Path=/; Secure; SameSite=Lax");
    }

    @Test void loadingExistingCookiesRetainsTheDelegateSemanticsWithoutANewUuidRestriction() {
        var repository = new ExplicitHeaderCsrfTokenRepository(); var request = request();
        request.setCookies(new Cookie("XSRF-TOKEN", "legacy-nonempty-value"));
        assertThat(repository.loadToken(request).getToken()).isEqualTo("legacy-nonempty-value");
        request.setCookies(new Cookie("SESSION", OLD)); assertThat(repository.loadToken(request)).isNull();
    }

    @ParameterizedTest @MethodSource("mutations")
    void unknownOrChangedCookieAttributesAreRejectedInsteadOfDropped(Consumer<Cookie> mutation) {
        Cookie cookie = valid(); mutation.accept(cookie);
        assertThatThrownBy(() -> ExplicitHeaderCsrfTokenRepository.header(cookie)).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("CSRF cookie boundary is unavailable");
    }
    static Stream<Consumer<Cookie>> mutations() {
        return Stream.of(cookie -> cookie.setPath("/other"), cookie -> cookie.setSecure(false), cookie -> cookie.setHttpOnly(true),
                cookie -> cookie.setDomain("example.invalid"), cookie -> cookie.setAttribute("SameSite", "Strict"),
                cookie -> cookie.setAttribute("Partitioned", ""), cookie -> cookie.setAttribute("Unknown", "value"),
                cookie -> cookie.setMaxAge(60), cookie -> cookie.setMaxAge(-2), cookie -> cookie.setValue(""),
                cookie -> cookie.setValue("not-a-uuid"), cookie -> cookie.setValue(OLD.toUpperCase(java.util.Locale.ROOT)),
                cookie -> cookie.setValue(null), cookie -> cookie.setMaxAge(0));
    }

    @Test void otherCookieNamesAndMissingFixedAttributesCannotReachTheHeaderWriter() {
        assertThatThrownBy(() -> ExplicitHeaderCsrfTokenRepository.header(new Cookie("SESSION", OLD))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ExplicitHeaderCsrfTokenRepository.header(null)).isInstanceOf(IllegalStateException.class);
        var missing = valid(); missing.setAttribute("SameSite", null);
        assertThatThrownBy(() -> ExplicitHeaderCsrfTokenRepository.header(missing)).isInstanceOf(IllegalStateException.class);
    }
    private static Cookie valid() {
        var cookie = new Cookie("XSRF-TOKEN", OLD); cookie.setPath("/"); cookie.setMaxAge(-1); cookie.setSecure(true);
        cookie.setHttpOnly(false); cookie.setAttribute("SameSite", "Lax"); return cookie;
    }
    private static MockHttpServletRequest request() {
        var context = new MockServletContext(); context.setMajorVersion(6); context.setEffectiveMajorVersion(6);
        return new MockHttpServletRequest(context, "GET", "/api/v1/auth/csrf");
    }
    private static final class HeaderOnlyResponse extends HttpServletResponseWrapper {
        final List<String> headers = new ArrayList<>();
        HeaderOnlyResponse() { super(new MockHttpServletResponse()); }
        @Override public void addCookie(Cookie cookie) { throw new AssertionError("Native cookie path reached"); }
        @Override public void addHeader(String name, String value) { assertThat(name).isEqualTo("Set-Cookie"); headers.add(value); super.addHeader(name, value); }
    }
}
