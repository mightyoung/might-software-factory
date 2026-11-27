package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Objects;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static jakarta.servlet.http.HttpServletResponse.*;

/** Dormant write adapter. A future route must run after the application's authenticated CSRF chain. */
final class StudioPublishHttp {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SessionSensitiveRequests requests;
    private final StudioPublish owner;

    StudioPublishHttp(SessionSensitiveRequests requests, StudioPublish owner) {
        this.requests = Objects.requireNonNull(requests);
        this.owner = Objects.requireNonNull(owner);
    }

    void postAfterSecurity(String screen, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "private, no-store");
        if (!"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "POST");
            response.setStatus(SC_METHOD_NOT_ALLOWED);
            return;
        }
        Decision decision;
        try {
            var capture = requests.capture(request);
            try {
                var target = new Target(capture.context().tenantId(), screen);
                var input = StudioPublishInput.parse(request);
                // The owner never returns a decision until the transaction has committed.
                decision = owner.publish(capture, target, input.expectedVersion(), input.bytes());
            } finally {
                capture.close();
            }
        } catch (StudioPublishInput.Rejected rejected) {
            response.setStatus(rejected.status());
            return;
        } catch (Forbidden rejected) {
            response.setStatus(SC_FORBIDDEN);
            return;
        } catch (VersionConflict rejected) {
            response.setStatus(SC_CONFLICT);
            return;
        } catch (IllegalArgumentException rejected) {
            response.setStatus(SC_BAD_REQUEST);
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
        byte[] receipt = JSON.writeValueAsBytes(new Receipt(decision.outcome().name(),
                Long.toString(decision.version()), decision.canonicalSha256()));
        response.setStatus(decision.outcome() == Outcome.APPEND ? SC_CREATED : SC_OK);
        response.setContentType("application/json");
        response.getOutputStream().write(receipt);
    }

    /** String version preserves 64-bit precision for JSON clients. No metrics, tenant or audit records. */
    private record Receipt(String outcome, String version, String sha256) {}
}
