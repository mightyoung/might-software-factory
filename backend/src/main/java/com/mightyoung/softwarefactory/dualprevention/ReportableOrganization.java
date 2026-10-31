package com.mightyoung.softwarefactory.dualprevention;

import java.util.Objects;
import java.util.UUID;

/** A tenant organization the current member may use to report a finding. */
public record ReportableOrganization(UUID id, String code, String name) {
    public ReportableOrganization {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
    }
}
