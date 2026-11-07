package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingReviewHistoryService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
public final class FindingReviewHistoryController {
    private final FindingReviewHistoryService service;
    public FindingReviewHistoryController(FindingReviewHistoryService service) { this.service=service; }
    @GetMapping("/api/v1/findings/{id}/review-history")
    public FindingReviewHistoryApiModels.Response history(@PathVariable UUID id,
            @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String cursor) {
        return service.history(id,pageSize,cursor);
    }
}
