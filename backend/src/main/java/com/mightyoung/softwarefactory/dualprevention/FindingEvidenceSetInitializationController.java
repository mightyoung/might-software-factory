package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static com.mightyoung.softwarefactory.dualprevention.FindingEvidenceSetInitializationRequestParser.*;

/** Empty-set creation only; the service derives owner and commits before returning. */
@RestController
public final class FindingEvidenceSetInitializationController {
    private final FindingEvidenceSetInitializationService service;
    private final FindingEvidenceSetInitializationRequestParser parser;
    public FindingEvidenceSetInitializationController(FindingEvidenceSetInitializationService service,
            FindingEvidenceSetInitializationRequestParser parser) { this.service = service; this.parser = parser; }
    @PostMapping("/api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-sets")
    public ResponseEntity<FindingEvidenceSetInitializationApiModels.Response> initialize(@PathVariable String findingId,
            @PathVariable String roundNo, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (request.getQueryString() != null) throw invalid();
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw invalid();
        var result = service.initialize(parser.parse(request, canonical(findingId), round(roundNo), canonical(keys.getFirst())));
        return ResponseEntity.status(result.replayed() ? 200 : 201).cacheControl(CacheControl.noStore())
                .body(FindingEvidenceSetInitializationApiModels.response(result));
    }
}
