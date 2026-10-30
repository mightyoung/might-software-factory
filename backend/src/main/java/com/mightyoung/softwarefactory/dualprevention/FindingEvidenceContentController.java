package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.files.VerifiedFileContent;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

/** Authorized download of the immutable file version pinned by a remediation submission. */
@RestController
public final class FindingEvidenceContentController {

    private final FindingEvidenceContentReader service;

    public FindingEvidenceContentController(FindingEvidenceContentReader service) {
        this.service = service;
    }

    @GetMapping(path = "/api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-items/{evidenceItemId}/content",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public void download(
            @PathVariable UUID findingId,
            @PathVariable int roundNo,
            @PathVariable UUID evidenceItemId,
            HttpServletResponse response) throws IOException {
        try (VerifiedFileContent content = service.openSubmittedEvidence(findingId, roundNo, evidenceItemId)) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"evidence.bin\"");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setContentLengthLong(content.sizeBytes());
            content.transferTo(response.getOutputStream());
        }
    }
}
