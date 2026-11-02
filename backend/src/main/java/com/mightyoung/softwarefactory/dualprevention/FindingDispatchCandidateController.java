package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingDispatchCandidateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Minimal current-organization member search; tenant and action scope are server-derived. */
@RestController
public final class FindingDispatchCandidateController {
    private final FindingDispatchCandidateService service;
    public FindingDispatchCandidateController(FindingDispatchCandidateService service) { this.service=service; }

    @GetMapping("/api/v1/findings/{id}/dispatch-candidates")
    public ResponseEntity<FindingDispatchCandidateService.Result> candidates(@PathVariable String id,
            HttpServletRequest request) {
        Map<String,String[]> parameters=request.getParameterMap();
        if(!Set.of("q","limit","afterMemberId").containsAll(parameters.keySet())
                || parameters.values().stream().anyMatch(values->values.length!=1)) {
            throw new IllegalArgumentException("Invalid candidate query");
        }
        String prefix=single(parameters,"q");String size=single(parameters,"limit");String cursor=single(parameters,"afterMemberId");
        int limit=20;
        if(size!=null) {
            if(!size.matches("[1-9][0-9]?")) throw new IllegalArgumentException("Invalid candidate limit");
            limit=Integer.parseInt(size);
        }
        var result=service.candidates(canonical(id),prefix,cursor==null?null:canonical(cursor),limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    private static String single(Map<String,String[]> values,String name) {
        return values.containsKey(name)?values.get(name)[0]:null;
    }
    private static UUID canonical(String value) {
        UUID parsed=UUID.fromString(value);
        if(!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Canonical UUID required");
        return parsed;
    }
}
