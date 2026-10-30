package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.files.EvidenceFileUse;
import com.mightyoung.softwarefactory.files.FileEvidenceAccessException;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import com.mightyoung.softwarefactory.files.FindingEvidenceFileAccess;
import com.mightyoung.softwarefactory.files.VerifiedFileEvidenceSnapshot;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
    public List<VerifiedFileEvidenceSnapshot> requireReady(
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

        // The V6 item-write trigger advances this already-locked parent row. Read children
        // without row locks so UPDATE/DELETE writers cannot form a parent/child lock cycle.
        List<Record> items = dsl.fetch("""
                select ei.id, ei.source_kind, ei.file_id, ei.sha256, ei.state, ei.verification_level
                  from public.evidence_item ei
                 where ei.tenant_id = cast(? as uuid)
                   and ei.evidence_set_id = cast(? as uuid)
                 order by ei.id
                """, context.tenantId(), evidenceSetId);
        if (items.isEmpty()) {
            throw new FindingWorkflowEvidenceException("At least one evidence item is required");
        }
        UUID ownerMemberId = evidenceSet.get("owner_member_id", UUID.class);
        List<VerifiedFileEvidenceSnapshot> snapshots = new ArrayList<>(items.size());
        for (Record item : items) {
            UUID itemId = item.get("id", UUID.class);
            UUID fileId = item.get("file_id", UUID.class);
            if (!"FILE".equals(item.get("source_kind", String.class)) || fileId == null
                    || !"READY".equals(item.get("state", String.class))
                    || !"CONTENT".equals(item.get("verification_level", String.class))) {
                throw new FindingWorkflowEvidenceException(
                        "Evidence items must be ready files with a trusted content verification");
            }
            String sha256 = item.get("sha256", String.class);
            if (sha256 != null) sha256 = sha256.strip();
            if (sha256 == null || sha256.isBlank()) {
                throw new FindingWorkflowEvidenceException("Every evidence file must have a recorded digest");
            }
            try {
                EvidenceFileUse use = new EvidenceFileUse(
                        context.tenantId(), context.actorId(), context.memberId(), findingId, roundNo,
                        evidenceSetId, ownerMemberId, itemId, fileId,
                        item.get("verification_level", String.class), sha256);
                VerifiedFileEvidenceSnapshot snapshot = fileEvidenceAccess.requireVerifiedContentEvidence(use);
                if (!snapshotMatches(use, snapshot)) {
                    throw new FindingWorkflowEvidenceException(
                            "The verified file snapshot does not match its evidence item");
                }
                snapshots.add(snapshot);
            } catch (FileEvidenceAccessException denied) {
                throw new FindingWorkflowEvidenceException("An evidence file is unavailable or unauthorized");
            } catch (FileEvidenceTrustUnavailableException unavailable) {
                throw unavailable;
            }
        }
        return List.copyOf(snapshots);
    }

    private static boolean snapshotMatches(EvidenceFileUse use, VerifiedFileEvidenceSnapshot snapshot) {
        return snapshot != null
                && Objects.equals(use.tenantId(), snapshot.tenantId())
                && Objects.equals(use.evidenceItemId(), snapshot.evidenceItemId())
                && Objects.equals(use.fileId(), snapshot.fileId())
                && Objects.equals(use.evidenceSetOwnerMemberId(), snapshot.ownerMemberId())
                && Objects.equals(use.actorId(), snapshot.ownerActorId())
                && use.recordedSha256().strip().toLowerCase(Locale.ROOT).equals(snapshot.sha256())
                && snapshot.fileLinkId() != null
                && snapshot.verificationReceiptId() != null
                && snapshot.verificationLevel() == com.mightyoung.softwarefactory.files.FileEvidenceVerificationLevel.CONTENT;
    }

    @Override
    public void requirePinnedEvidence(List<VerifiedFileEvidenceSnapshot> evidenceSnapshots) {
        if (evidenceSnapshots == null || evidenceSnapshots.isEmpty()) {
            throw new FindingWorkflowEvidenceException("A pinned evidence snapshot is required for verification");
        }
        for (VerifiedFileEvidenceSnapshot snapshot : evidenceSnapshots) {
            try {
                fileEvidenceAccess.requirePreviouslySubmittedEvidence(snapshot);
            } catch (FileEvidenceAccessException denied) {
                throw new FindingWorkflowEvidenceException("A submitted evidence snapshot is invalid");
            } catch (FileEvidenceTrustUnavailableException unavailable) {
                throw unavailable;
            }
        }
    }
}
