package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ErrorResponse;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Fixed asset wire metadata and redacted errors; request trace is shared with command audit/publication. */
final class AssetFoundationHttpResponses {
    private static final String TRACE = AssetFoundationHttpResponses.class.getName() + ".trace";
    private AssetFoundationHttpResponses() { }
    static UUID trace(HttpServletRequest request) {
        if (request.getAttribute(TRACE) instanceof UUID trace) return trace;
        UUID trace = UUID.randomUUID(); request.setAttribute(TRACE, trace); return trace;
    }
    static Metadata metadata(UUID trace) { return new Metadata(trace.toString(), trace.toString(), "1"); }
    static ResponseEntity<ErrorResponse> lifecycleFailure(
            com.mightyoung.softwarefactory.support.G014JdbcTransactionManager.LifecycleException failure, UUID trace) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(
                com.mightyoung.softwarefactory.dualprevention.G014LifecycleHttpResponses.error(failure, trace));
    }
    static ResponseEntity<ErrorResponse> error(int status, UUID trace) {
        String code = switch (status) {
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "TEMPORARILY_UNAVAILABLE";
            default -> "INVALID_REQUEST";
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(
                code, "请求未完成", trace.toString(), status == 503, List.of(), null));
    }
}
