package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.files.EvidenceFileUse;
import com.mightyoung.softwarefactory.files.FileEvidenceAccessException;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import com.mightyoung.softwarefactory.files.FindingEvidenceFileAccess;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Validates evidence rows and asks the file module to verify every file-backed item. */
@Repository
public final class JooqFindingWorkflowEvidencePolicy implements FindingWorkflowEvidencePolicy {

    private final DSLContext dsl;
    private final FindingEvidenceFileAccess fileEvidenceAccess;

    public JooqFindingWorkflowEvidencePolicy(DSLContext dsl, FindingEvidenceFileAccess fileEvidenceAccess) {
        this.dsl = dsl;
        this.fileEvidenceAccess = fileEvidenceAccess;
    }

    @Override
    public void requireReady(
            CurrentTenantContext context, UUID findingId, int roundNo, UUID evidenceSetId) {
        if (context == null || context.tenantId() == null || context.actorId() == null || context.memberId() == null
                || findingId == null || roundNo < 1 || evidenceSetId == null) {
            throw new FindingWorkflowEvidenceException("A verified evidence set is required");
        }
        Record evidenceSet = dsl.fetchOne("""
                select es.owner_member_id, es.state, es.verified_at
                  from public.evidence_set es
                 where es.tenant_id = cast(? as uuid)
                   and es.id = cast(? as uuid)
                   and es.finding_id = cast(? as uuid)
                   and es.round_no = ?
                 for update of es
                """, context.tenantId(), evidenceSetId, findingId, roundNo);
        if (evidenceSet == null || !context.memberId().equals(evidenceSet.get("owner_member_id", UUID.class))
                || !"READY".equals(evidenceSet.get("state", String.class))
                || evidenceSet.get("verified_at", java.time.OffsetDateTime.class) == null) {
            throw new FindingWorkflowEvidenceException("A ready evidence set owned by the submitter is required");
        }

        List<Record> items = dsl.fetch("""
                select ei.id, ei.source_kind, ei.file_id, ei.state, ei.verification_level
                  from public.evidence_item ei
                 where ei.tenant_id = cast(? as uuid)
                   and ei.evidence_set_id = cast(? as uuid)
                 order by ei.id
                 for update of ei
                """, context.tenantId(), evidenceSetId);
        if (items.isEmpty()) {
            throw new FindingWorkflowEvidenceException("At least one evidence item is required");
        }
        UUID ownerMemberId = evidenceSet.get("owner_member_id", UUID.class);
        for (Record item : items) {
            UUID itemId = item.get("id", UUID.class);
            UUID fileId = item.get("file_id", UUID.class);
            if (!"FILE".equals(item.get("source_kind", String.class)) || fileId == null
                    || !"READY".equals(item.get("state", String.class))
                    || !"CONTENT".equals(item.get("verification_level", String.class))) {
                throw new FindingWorkflowEvidenceException(
                        "Evidence items must be ready files with a trusted content verification");
            }
            try {
                fileEvidenceAccess.requireVerifiedContentEvidence(new EvidenceFileUse(
                        context.tenantId(), context.actorId(), context.memberId(), findingId, roundNo,
                        evidenceSetId, ownerMemberId, itemId, fileId,
                        item.get("verification_level", String.class)));
            } catch (FileEvidenceAccessException denied) {
                throw new FindingWorkflowEvidenceException("An evidence file is unavailable or unauthorized");
            } catch (FileEvidenceTrustUnavailableException unavailable) {
                throw unavailable;
            }
        }
    }
}
