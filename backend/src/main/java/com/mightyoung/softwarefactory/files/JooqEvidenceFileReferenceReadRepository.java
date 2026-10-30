package com.mightyoung.softwarefactory.files;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Checks the tenant-local file link against the exact current evidence item, finding, round, and owner. */
@Repository
public final class JooqEvidenceFileReferenceReadRepository implements EvidenceFileReferenceReadRepository {

    private final DSLContext dsl;

    public JooqEvidenceFileReferenceReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean hasAuthorizedEvidenceReference(EvidenceFileUse use) {
        if (use == null || use.tenantId() == null || use.fileId() == null || use.evidenceItemId() == null
                || use.evidenceSetId() == null || use.findingId() == null || use.roundNo() < 1
                || use.evidenceSetOwnerMemberId() == null) {
            return false;
        }
        Record link = dsl.fetchOne("""
                select fl.id
                  from public.file_link fl
                  join public.evidence_item ei
                    on ei.tenant_id = fl.tenant_id
                   and ei.id = fl.resource_id
                   and ei.file_id = fl.file_id
                  join public.evidence_set es
                    on es.tenant_id = ei.tenant_id
                   and es.id = ei.evidence_set_id
                 where fl.tenant_id = cast(? as uuid)
                   and fl.file_id = cast(? as uuid)
                   and fl.resource_type = 'FINDING_EVIDENCE_ITEM'
                   and fl.resource_id = cast(? as uuid)
                   and fl.purpose = 'REMEDIATION_EVIDENCE'
                   and fl.revoked_at is null
                   and ei.tenant_id = cast(? as uuid)
                   and ei.evidence_set_id = cast(? as uuid)
                   and ei.id = cast(? as uuid)
                   and es.finding_id = cast(? as uuid)
                   and es.round_no = ?
                   and es.owner_member_id = cast(? as uuid)
                 for share of fl
                """, use.tenantId(), use.fileId(), use.evidenceItemId(), use.tenantId(),
                use.evidenceSetId(), use.evidenceItemId(), use.findingId(), use.roundNo(),
                use.evidenceSetOwnerMemberId());
        return link != null;
    }
}
