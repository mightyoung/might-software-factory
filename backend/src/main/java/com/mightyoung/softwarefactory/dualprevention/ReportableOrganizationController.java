package com.mightyoung.softwarefactory.dualprevention;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Same-origin endpoint exposing only organizations authorized for finding.create. */
@RestController
public final class ReportableOrganizationController {

    private final ReportableOrganizationService service;

    public ReportableOrganizationController(ReportableOrganizationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/organizations/reportable")
    public ReportableOrganizationsResponse reportable() {
        return new ReportableOrganizationsResponse(service.reportable());
    }

    public record ReportableOrganizationsResponse(List<ReportableOrganization> data) {
        public ReportableOrganizationsResponse {
            data = List.copyOf(data);
        }
    }
}
