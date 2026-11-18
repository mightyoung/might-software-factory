package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import java.util.UUID;

/** Persisted publication is a wake hint only. The intent and files ledger own recoverable state. */
public record FindingEvidenceQuarantinedEvent(UUID tenantId, UUID intentId, long technicalVersion) {
    public FindingEvidenceQuarantinedEvent {
        java.util.Objects.requireNonNull(tenantId);java.util.Objects.requireNonNull(intentId);
        if(technicalVersion<1)throw new IllegalArgumentException("Invalid ingestion event version");
    }
}
