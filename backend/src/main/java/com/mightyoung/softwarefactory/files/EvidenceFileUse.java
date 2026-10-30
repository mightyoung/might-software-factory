package com.mightyoung.softwarefactory.files;

import java.util.UUID;

/** Verified server context for using one file as an item in one finding evidence round. */
public record EvidenceFileUse(
        UUID tenantId,
        UUID actorId,
        UUID memberId,
        UUID findingId,
        int roundNo,
        UUID evidenceSetId,
        UUID evidenceSetOwnerMemberId,
        UUID evidenceItemId,
        UUID fileId,
        String recordedVerificationLevel) {
}
