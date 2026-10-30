package com.mightyoung.softwarefactory.files;

/** Resolves the registered evidence-item link and its tenant/finding/round/owner authorization. */
public interface EvidenceFileReferenceReadRepository {

    boolean hasAuthorizedEvidenceReference(EvidenceFileUse use);
}
