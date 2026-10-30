package com.mightyoung.softwarefactory.files;

import java.util.Optional;
import java.util.UUID;

/** Resolves the registered evidence-item link and its tenant/finding/round/owner authorization. */
public interface EvidenceFileReferenceReadRepository {

    Optional<UUID> findAuthorizedEvidenceReference(EvidenceFileUse use);
}
