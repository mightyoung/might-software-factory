package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.io.IOException;
import java.util.Objects;
import java.util.regex.Pattern;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static jakarta.servlet.http.HttpServletResponse.*;

/** Dormant transport for a future opt-in route. Not discovered by Spring or directly exposed. */
final class StudioPlayerHttp {
    private static final Pattern VERSION = Pattern.compile("[1-9][0-9]{0,18}");
    private static final String NO_STORE = "private, no-store";

    private final SessionSensitiveRequests requests;
    private final StudioPlayerRead owner;

    StudioPlayerHttp(SessionSensitiveRequests requests, StudioPlayerRead owner) {
        this.requests = Objects.requireNonNull(requests);
        this.owner = Objects.requireNonNull(owner);
    }

    void get(String screen, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", NO_STORE);
        if (!"GET".equals(request.getMethod())) {
            response.setHeader("Allow", "GET");
            response.setStatus(SC_METHOD_NOT_ALLOWED);
            return;
        }
        StudioPlayerResponse result;
        try {
            var capture = requests.capture(request);
            try {
                Target target;
                Long version;
                var tenant = capture.context().tenantId();
                try {
                    target = new Target(tenant, screen);
                    version = version(request);
                } catch (IllegalArgumentException rejected) {
                    response.setStatus(SC_BAD_REQUEST);
                    return;
                }
                // Do not call ServletWebRequest.checkNotModified or return an ETag-bearing
                // ResponseEntity: each retrieval needs the current guarded read, even on a hit.
                result = StudioPlayerResponse.fromPublication(owner.read(capture, target, version));
            } finally {
                capture.close();
            }
        } catch (Forbidden rejected) {
            response.setStatus(SC_FORBIDDEN);
            return;
        } catch (UnauthenticatedSessionException rejected) {
            response.setStatus(SC_UNAUTHORIZED);
            return;
        } catch (SessionSensitiveException rejected) {
            response.setStatus(switch (rejected.reason()) {
                case REJECTED -> SC_UNAUTHORIZED;
                case CONFLICT -> SC_CONFLICT;
                case UNAVAILABLE, NO_TRUSTED_LINEAGE -> SC_SERVICE_UNAVAILABLE;
            });
            return;
        } catch (TransactionException | DataAccessException | IllegalStateException unavailable) {
            response.setStatus(SC_SERVICE_UNAVAILABLE);
            return;
        }
        response.setStatus(result.status());
        result.headers().forEach(response::setHeader);
        if (result instanceof StudioPlayerResponse.Content content) {
            response.getOutputStream().write(content.bytes());
        }
    }

    private static Long version(HttpServletRequest request) {
        var params = request.getParameterMap();
        if (params.isEmpty()) return null;
        var values = params.get("version");
        if (params.size() != 1 || values == null || values.length != 1
                || !VERSION.matcher(values[0]).matches()) throw new IllegalArgumentException();
        return Long.parseLong(values[0]);
    }
}
