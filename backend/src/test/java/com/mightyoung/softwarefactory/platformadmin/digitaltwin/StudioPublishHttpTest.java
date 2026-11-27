package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.TransactionSystemException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioPublishHttpTest {
    private final SessionSensitiveRequests requests = mock(SessionSensitiveRequests.class);
    private final CapturedHumanRequest captured = mock(CapturedHumanRequest.class);
    private final StudioPublish owner = mock(StudioPublish.class);
    private final StudioPublishHttp adapter = new StudioPublishHttp(requests, owner);
    private final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
    private final Target target = new Target(context.tenantId(), "floor");
    private final byte[] bytes = "{\"snapshotVersion\":1}".getBytes(StandardCharsets.UTF_8);
    private final String sha256 = "b".repeat(64);

    @Test void emitsOnlyCommittedReceiptWithoutCallerOrStorageFacts() throws Exception {
        var input = request("0");
        input.addHeader("X-Tenant-Id", "ignored");
        var response = spy(new MockHttpServletResponse());
        when(owner.publish(captured, target, 0, bytes)).thenReturn(decision(Outcome.APPEND, 1));
        adapter.postAfterSecurity("floor", input, response);

        assertEquals(201, response.getStatus());
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
        assertEquals("application/json", response.getContentType());
        var json = JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        assertEquals(Set.of("outcome", "version", "sha256"), json.propertyNames());
        assertEquals("APPEND", json.get("outcome").asString());
        assertEquals("1", json.get("version").asString());
        assertEquals(sha256, json.get("sha256").asString());
        var order = inOrder(requests, captured, owner, response);
        order.verify(requests).capture(input);
        order.verify(captured).context();
        order.verify(owner).publish(captured, target, 0, bytes);
        order.verify(captured).close();
        order.verify(response).getOutputStream();
    }

    @Test void unchangedReturnsSameVersionAndStaleOrFailedCommitNeverReturnsReceipt() throws Exception {
        var input = request("8");
        when(owner.publish(captured, target, 8, bytes))
                .thenReturn(decision(Outcome.UNCHANGED, 8)).thenThrow(new VersionConflict())
                .thenThrow(new TransactionSystemException("simulated commit failure"));
        var unchanged = run(input);
        assertEquals(200, unchanged.getStatus());
        assertTrue(unchanged.getContentAsString().contains("UNCHANGED"));
        assertError(409, run(request("8")));
        assertError(503, run(request("8")));
        verify(captured, times(3)).close();
    }

    @Test void invalidInputCannotReachOwnerAndRoleDenialDoesNotReturnReceipt() throws Exception {
        assertError(400, run(request("01")));
        var oversize = request("1");
        oversize.setContent(new byte[StudioDraftDecoder.MAX_BYTES + 1]);
        assertError(413, run(oversize));
        verifyNoInteractions(owner);

        var input = request("1");
        when(owner.publish(captured, target, 1, bytes)).thenThrow(new Forbidden());
        assertError(403, run(input));
        verify(captured, times(3)).close();
    }

    @Test void servletCsrfBoundaryRejectsBeforeCaptureAndOnlyVerifiedHeaderAllowsCall() throws Exception {
        var repository = new HttpSessionCsrfTokenRepository();
        var filter = new CsrfFilter(repository);
        filter.setRequestHandler(new CsrfTokenRequestAttributeHandler());

        var denied = request("1");
        var noToken = new MockHttpServletResponse();
        filter.doFilter(denied, noToken, (request, response) ->
                fail("A request without a token must not reach the Studio handler"));
        assertEquals(403, noToken.getStatus());
        verifyNoInteractions(requests, owner);

        var input = request("1");
        var token = repository.generateToken(input);
        repository.saveToken(token, input, new MockHttpServletResponse());
        input.addHeader(token.getHeaderName(), token.getToken());
        when(owner.publish(captured, target, 1, bytes)).thenReturn(decision(Outcome.APPEND, 2));
        var accepted = new MockHttpServletResponse();
        filter.doFilter(input, accepted, (request, response) ->
                adapter.postAfterSecurity("floor", input, accepted));
        assertEquals(201, accepted.getStatus());
        verify(owner).publish(captured, target, 1, bytes);
    }

    @Test void rejectedCaptureCannotReachTheOwner() throws Exception {
        var input = request("0");
        when(requests.capture(input)).thenThrow(SessionSensitiveException.rejected());
        assertError(401, run(input));
        verifyNoInteractions(captured, owner);
    }

    private MockHttpServletRequest request(String version) {
        var input = new MockHttpServletRequest("POST", "/api/v1/admin/studio/screens/floor/publish");
        input.setContentType("application/json");
        input.setContent(bytes);
        input.addHeader(StudioPublishInput.EXPECTED_VERSION, version);
        when(requests.capture(input)).thenReturn(captured);
        when(captured.context()).thenReturn(context);
        return input;
    }

    private MockHttpServletResponse run(MockHttpServletRequest input) throws Exception {
        var response = new MockHttpServletResponse();
        adapter.postAfterSecurity("floor", input, response);
        return response;
    }

    private Decision decision(Outcome outcome, long version) {
        return new Decision(outcome, version, sha256, new Metrics(1, 0, 0, 0, 0));
    }

    private static void assertError(int status, MockHttpServletResponse response) {
        assertEquals(status, response.getStatus());
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
        assertNull(response.getHeader("ETag"));
        assertEquals(0, response.getContentAsByteArray().length);
    }
}
