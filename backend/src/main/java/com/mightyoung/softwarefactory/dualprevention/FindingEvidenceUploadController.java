package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.mightyoung.softwarefactory.dualprevention.FindingEvidenceUploadRequestParser.*;

/** Intent metadata only; session and CSRF use the application's security filter chain. */
@RestController
public final class FindingEvidenceUploadController {
    private static final String BASE = "/api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-uploads";
    private final FindingEvidenceUploadService service;
    private final FindingEvidenceUploadRequestParser parser;
    public FindingEvidenceUploadController(FindingEvidenceUploadService service, FindingEvidenceUploadRequestParser parser) {
        this.service = service; this.parser = parser;
    }
    @PostMapping(BASE)
    public ResponseEntity<FindingEvidenceUploadApiModels.Response> reserve(@PathVariable String findingId,
            @PathVariable String roundNo, HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        noQuery(request);
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw invalid();
        var finding = canonical(findingId);
        int round = round(roundNo);
        var result = service.reserve(parser.parse(request, finding, round, canonical(keys.getFirst())));
        String url = "/api/v1/findings/" + finding + "/remediation-rounds/" + round + "/evidence-uploads/" + result.status().intentId();
        return ResponseEntity.status(result.replayed() ? 200 : 201).location(URI.create(url))
                .cacheControl(CacheControl.noStore()).body(FindingEvidenceUploadApiModels.response(result.status(), url));
    }
    @GetMapping(BASE + "/{intentId}")
    public ResponseEntity<FindingEvidenceUploadApiModels.Response> status(@PathVariable String findingId,
            @PathVariable String roundNo, @PathVariable String intentId, HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        noQuery(request);
        try { if (request.getInputStream().read() != -1) throw invalid(); }
        catch (java.io.IOException failure) { throw invalid(); }
        var result = service.status(canonical(findingId), round(roundNo), canonical(intentId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(FindingEvidenceUploadApiModels.response(result, null));
    }
    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
    }
}
