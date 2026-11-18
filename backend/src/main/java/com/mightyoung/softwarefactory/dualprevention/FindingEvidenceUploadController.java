package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadService;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceReceiveService;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.mightyoung.softwarefactory.dualprevention.FindingEvidenceUploadRequestParser.*;

/** Session and CSRF use the application filter chain; bytes remain pending explicit finalization. */
@RestController
public final class FindingEvidenceUploadController {
    private static final String BASE = "/api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-uploads";
    private final FindingEvidenceUploadService service;
    private final FindingEvidenceUploadRequestParser parser;
    private final FindingEvidenceReceiveService receiver;
    public FindingEvidenceUploadController(FindingEvidenceUploadService service, FindingEvidenceUploadRequestParser parser, FindingEvidenceReceiveService receiver) {
        this.service = service; this.parser = parser; this.receiver=receiver;
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
    @GetMapping("/api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-set")
    public ResponseEntity<FindingEvidenceUploadApiModels.SetResponse> currentSet(@PathVariable String findingId,
            @PathVariable String roundNo, HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        noQuery(request);
        try { if (request.getInputStream().read() != -1) throw invalid(); }
        catch (java.io.IOException failure) { throw invalid(); }
        var finding = canonical(findingId);
        int round = round(roundNo);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(FindingEvidenceUploadApiModels.setResponse(service.currentSet(finding, round), finding.toString(), round));
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
    @PutMapping(BASE + "/{intentId}/content")
    public ResponseEntity<FindingEvidenceUploadApiModels.Response> receive(@PathVariable String findingId,
            @PathVariable String roundNo,@PathVariable String intentId,HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setHeader("Cache-Control","no-store");noQuery(request);
        for(String header:new String[]{"Transfer-Encoding","Content-Encoding","Content-Range","Range","Trailer"}) {
            if(request.getHeader(header)!=null)throw invalid();
        }
        var lengths=Collections.list(request.getHeaders("Content-Length"));
        var media=Collections.list(request.getHeaders("Content-Type"));
        if(lengths.size()!=1||!lengths.getFirst().matches("[1-9][0-9]{0,8}"))throw invalid();
        long length=Long.parseLong(lengths.getFirst());
        if(length>67_108_864L)throw new FindingEvidenceUploadException(FindingEvidenceUploadException.Reason.DECLARED_SIZE_EXCEEDED);
        if(request.getContentLengthLong()!=length)throw invalid();
        if(media.size()!=1||!media.getFirst().matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))
            throw new FindingEvidenceUploadException(FindingEvidenceUploadException.Reason.MEDIA_TYPE_NOT_ALLOWED);
        var result=receiver.receive(canonical(findingId),round(roundNo),canonical(intentId),media.getFirst(),length,request.getInputStream());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(FindingEvidenceUploadApiModels.response(result.status(),null));
    }
    @PostMapping(BASE + "/{intentId}/cancel")
    public ResponseEntity<FindingEvidenceUploadApiModels.Response> cancel(@PathVariable String findingId,
            @PathVariable String roundNo,@PathVariable String intentId,HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setHeader("Cache-Control","no-store");noQuery(request);
        if(request.getHeader("Transfer-Encoding")!=null||request.getContentLengthLong()>0||request.getInputStream().read()!=-1)throw invalid();
        var result=receiver.cancel(canonical(findingId),round(roundNo),canonical(intentId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(FindingEvidenceUploadApiModels.response(result,null));
    }
    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
    }
}
