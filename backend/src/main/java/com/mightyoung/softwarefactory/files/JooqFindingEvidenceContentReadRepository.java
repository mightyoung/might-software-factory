package com.mightyoung.softwarefactory.files;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads sealed snapshots under the caller's request authorization scope. FOR SHARE covers file_link and
 * file_object only; it does not lock the principal/member/role/grant rows used to resolve that scope.
 */
@Repository
public final class JooqFindingEvidenceContentReadRepository implements FindingEvidenceContentReadRepository {

    private final DSLContext dsl;

    public JooqFindingEvidenceContentReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<VerifiedFileEvidenceSnapshot> findReadableSealedSnapshot(
            FindingEvidenceReadScope scope, UUID findingId, int roundNo, UUID evidenceItemId) {
        if (scope == null || scope.tenantId() == null || scope.organizationIds().isEmpty()
                || findingId == null || roundNo < 1 || evidenceItemId == null) {
            return Optional.empty();
        }
        String appliedTenant = (String) dsl.fetchValue("select set_config(?, ?, true)",
                "app.tenant_id", scope.tenantId().toString());
        if (!scope.tenantId().toString().equals(appliedTenant)) {
            return Optional.empty();
        }
        Record row = dsl.fetchOne("""
                select se.tenant_id, se.evidence_item_id, se.file_id, se.file_link_id,
                       se.owner_member_id, se.owner_actor_id, se.sha256, se.storage_version,
                       se.verification_receipt_id, se.verifier_id, se.verifier_key_id,
                       se.verification_policy_version, se.verification_level, se.verified_at
                  from public.remediation_submission_evidence se
                  join public.remediation_submission rs
                    on rs.tenant_id = se.tenant_id
                   and rs.id = se.submission_id
                   and rs.finding_id = se.finding_id
                   and rs.round_no = se.round_no
                   and rs.evidence_set_id = se.evidence_set_id
                   and rs.submitted_by = se.submitted_by
                  join public.evidence_set es
                    on es.tenant_id = se.tenant_id
                   and es.id = se.evidence_set_id
                   and es.finding_id = se.finding_id
                   and es.round_no = se.round_no
                   and es.owner_member_id = se.owner_member_id
                  join public.evidence_item ei
                    on ei.tenant_id = se.tenant_id
                   and ei.id = se.evidence_item_id
                   and ei.evidence_set_id = se.evidence_set_id
                   and ei.file_id = se.file_id
                  join public.finding f
                    on f.tenant_id = se.tenant_id
                   and f.id = se.finding_id
                  join public.file_link fl
                    on fl.tenant_id = se.tenant_id
                   and fl.id = se.file_link_id
                   and fl.file_id = se.file_id
                  join public.file_object fo
                    on fo.tenant_id = se.tenant_id
                   and fo.id = se.file_id
                 where se.tenant_id = cast(? as uuid)
                   and se.finding_id = cast(? as uuid)
                   and se.round_no = ?
                   and se.evidence_item_id = cast(? as uuid)
                   and f.organization_id = any(cast(? as uuid[]))
                   and rs.evidence_snapshots_sealed_at is not null
                   and rs.evidence_snapshot_count > 0
                   and rs.evidence_snapshot_count = cardinality(rs.evidence_snapshot_manifest)
                   and rs.evidence_snapshot_manifest = (
                        select array_agg(item.id order by item.id)
                          from public.evidence_item item
                         where item.tenant_id = se.tenant_id
                           and item.evidence_set_id = se.evidence_set_id
                   )
                   and rs.evidence_snapshot_manifest = (
                        select array_agg(snapshot.evidence_item_id order by snapshot.evidence_item_id)
                          from public.remediation_submission_evidence snapshot
                         where snapshot.tenant_id = se.tenant_id
                           and snapshot.submission_id = se.submission_id
                   )
                   and se.evidence_item_id = any(rs.evidence_snapshot_manifest)
                   and es.submission_sealed_at is not null
                   and fl.resource_type = 'FINDING_EVIDENCE_ITEM'
                   and fl.resource_id = se.evidence_item_id
                   and fl.purpose = 'REMEDIATION_EVIDENCE'
                   and fl.revoked_at is null
                   and fo.state = 'READY'
                   and fo.owner_member_id = se.owner_member_id
                   and fo.owner_actor_id = se.owner_actor_id
                   and fo.checksum = se.sha256
                   and fo.storage_version = se.storage_version
                   and se.verification_level in ('CONTENT','SCANNED_CONTENT_V2')
                 for share of fl, fo
                """, scope.tenantId(), findingId, roundNo, evidenceItemId,
                scope.organizationIds().toArray(UUID[]::new));
        if (row == null) return Optional.empty();
        return Optional.of(new VerifiedFileEvidenceSnapshot(
                row.get("tenant_id", UUID.class), row.get("evidence_item_id", UUID.class),
                row.get("file_id", UUID.class), row.get("file_link_id", UUID.class),
                row.get("owner_member_id", UUID.class), row.get("owner_actor_id", UUID.class),
                row.get("sha256", String.class), row.get("storage_version", String.class),
                row.get("verification_receipt_id", UUID.class), row.get("verifier_id", String.class),
                row.get("verifier_key_id", String.class), row.get("verification_policy_version", String.class),
                FileEvidenceVerificationLevel.valueOf(row.get("verification_level", String.class)),
                row.get("verified_at", OffsetDateTime.class)));
    }
}
