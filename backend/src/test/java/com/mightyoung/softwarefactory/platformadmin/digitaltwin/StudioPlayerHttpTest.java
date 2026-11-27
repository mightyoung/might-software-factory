package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.TransactionSystemException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioPlayerHttpTest {
    private final SessionSensitiveRequests requests = mock(SessionSensitiveRequests.class);
    private final CapturedHumanRequest capture = mock(CapturedHumanRequest.class);
    private final StudioPlayerRead owner = mock(StudioPlayerRead.class);
    private final StudioPlayerHttp endpoint = new StudioPlayerHttp(requests, owner);
    private final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
    private final Target target = new Target(context.tenantId(), "floor");
    private final String sha256 = "a".repeat(64);

    @Test void pinnedReadBuildsTenantFromCapturedRequestAndNeverUsesConditionalCacheShortcut() throws Exception {
        var request = request();
        request.setParameter("version", "8");
        request.addHeader("If-None-Match", "\"" + sha256 + "\"");
        request.addHeader("X-Tenant-Id", UUID.randomUUID().toString());
        var bytes = "{\"studio\":8}".getBytes(StandardCharsets.UTF_8);
        when(owner.read(capture, target, 8L)).thenReturn(Optional.of(
                new StudioPlayerPublication.Published(target, 8, sha256, bytes)));

        var response = run(request, "floor");
        assertEquals(200, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
        assertEquals("\"" + sha256 + "\"", response.getHeader("ETag"));
        assertArrayEquals(bytes, response.getContentAsByteArray());
        var order = inOrder(requests, capture, owner);
        order.verify(requests).capture(request);
        order.verify(capture).context();
        order.verify(owner).read(capture, target, 8L);
        order.verify(capture).close();
    }

    @Test void exactMissingAndForbiddenHaveNoBodyOrCacheValidator() throws Exception {
        var request = request();
        request.setParameter("version", "4");
        when(owner.read(capture, target, 4L)).thenReturn(Optional.empty()).thenThrow(new Forbidden());

        assertEmpty(404, run(request, "floor"));
        assertEmpty(403, run(request, "floor"));
        verify(owner, times(2)).read(capture, target, 4L);
        verifyNoMoreInteractions(owner);
        verify(capture, times(2)).close();
    }

    @Test void duplicateAndNonCanonicalParametersFailBeforeOwnerAndCloseCapture() throws Exception {
        var request = request();
        request.setParameter("version", "1", "2");
        assertEmpty(400, run(request, "floor"));
        request.setParameter("version", "01");
        assertEmpty(400, run(request, "floor"));
        request.setParameter("version", "9223372036854775808");
        assertEmpty(400, run(request, "floor"));
        request.setParameter("version", "1");
        request.setParameter("tenant", "another");
        assertEmpty(400, run(request, "floor"));
        request.removeAllParameters();
        assertEmpty(400, run(request, "../floor"));
        verifyNoInteractions(owner);
        verify(capture, times(5)).close();
    }

    @Test void unauthenticatedNeverReachesOwnerAndCommitFailureIsNotAMiss() throws Exception {
        var request = request();
        when(requests.capture(request)).thenThrow(SessionSensitiveException.rejected());
        assertEmpty(401, run(request, "floor"));
        verifyNoInteractions(owner);
        verifyNoInteractions(capture);

        var valid = request();
        when(owner.read(capture, target, null)).thenThrow(new TransactionSystemException("internal DB failure"));
        assertEmpty(503, run(valid, "floor"));
        verify(owner).read(capture, target, null);
        verify(capture).close();
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/studio/screens/floor/published");
        when(requests.capture(request)).thenReturn(capture);
        when(capture.context()).thenReturn(context);
        return request;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, String key) throws Exception {
        var response = new MockHttpServletResponse();
        endpoint.get(key, request, response);
        return response;
    }

    private static void assertEmpty(int status, MockHttpServletResponse response) {
        assertEquals(status, response.getStatus());
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
        assertNull(response.getHeader("ETag"));
        assertArrayEquals(new byte[0], response.getContentAsByteArray());
    }
}
