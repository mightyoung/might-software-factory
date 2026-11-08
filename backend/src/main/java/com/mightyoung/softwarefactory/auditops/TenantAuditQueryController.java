package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.IdentityRequestIds;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicitly disabled until the composition root installs the complete query and transport graph. */
@RestController
@ConditionalOnProperty(prefix="msf.audit-query.http", name="enabled", havingValue="true", matchIfMissing=false)
public final class TenantAuditQueryController {
    private final TenantAuditQueryService service;
    private final TenantAuditQueryParser parser;
    private final SessionSensitiveRequests requests;
    private final TenantAuditHttpTransport transport;
    public TenantAuditQueryController(TenantAuditQueryService service, TenantAuditQueryParser parser,
            SessionSensitiveRequests requests, TenantAuditHttpTransport transport) {
        this.service=Objects.requireNonNull(service);this.parser=Objects.requireNonNull(parser);
        this.requests=Objects.requireNonNull(requests);this.transport=Objects.requireNonNull(transport);
    }
    /** Composition identity only; this does not establish authorization or database readiness. */
    public boolean usesDependencies(TenantAuditQueryService service, TenantAuditQueryParser parser,
            SessionSensitiveRequests requests, TenantAuditHttpTransport transport) {
        return this.service == service && this.parser == parser
                && this.requests == requests && this.transport == transport;
    }
    @PostMapping("/api/v1/audit-events/query")
    public void query(HttpServletRequest request,HttpServletResponse response) throws IOException {
        TenantAuditQueryService.EncodedResult result;
        try {
            var ids=IdentityRequestIds.get(request);var input=parser.query(request);var capture=requests.capture(request);
            try { result=service.query(capture,input,ids.requestId(),ids.traceId()); }
            finally { capture.close(); }
        } catch (TenantAuditQueryContracts.Failure | SessionSensitiveException | UnauthenticatedSessionException
                | AccessDeniedException | TenantAuditQueryService.Unavailable | org.springframework.dao.DataAccessException
                | org.jooq.exception.DataAccessException | org.springframework.transaction.TransactionException failure) {
            TenantAuditHttpResponses.error(request,response,transport,failure);return;
        }
        // Transport failures, including known runtime types, must never be turned into a second stage.
        TenantAuditHttpResponses.read(request,response,transport,result);
    }
}
