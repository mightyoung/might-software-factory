package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCommandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static com.mightyoung.softwarefactory.dualprevention.FindingReviewCycleRequestParser.*;

/** V3 commands use server-derived identity and return only committed historical results. */
@RestController
public final class FindingReviewCycleController {
    private final FindingWorkflowCommandService service;
    private final FindingReviewCycleRequestParser parser;
    public FindingReviewCycleController(FindingWorkflowCommandService service, FindingReviewCycleRequestParser parser) {
        this.service = service; this.parser = parser;
    }
    @PostMapping("/api/v1/findings/{id}/review-decisions")
    public ResponseEntity<FindingReviewCycleApiModels.Response> review(@PathVariable String id,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        UUID key = key(request);
        var result = service.reviewDecision(key, canonical(id), parser.review(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(FindingReviewCycleApiModels.response(result));
    }
    @PostMapping("/api/v1/findings/{id}/supplements")
    public ResponseEntity<FindingReviewCycleApiModels.Response> supplement(@PathVariable String id,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        UUID key = key(request);
        var result = service.supplement(key, canonical(id), parser.supplement(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(FindingReviewCycleApiModels.response(result));
    }
    private static UUID key(HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw invalid();
        return canonical(keys.getFirst());
    }
}
