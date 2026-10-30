package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Accepts only the submitter's tenant-local evidence set with every item content-verified and READY. */
@Repository
public final class JooqFindingWorkflowEvidencePolicy implements FindingWorkflowEvidencePolicy {

    private final DSLContext dsl;

    public JooqFindingWorkflowEvidencePolicy(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void requireReady(
            UUID tenantId, UUID findingId, int roundNo, UUID submittedByMemberId, UUID evidenceSetId) {
        if (tenantId == null || findingId == null || roundNo < 1 || submittedByMemberId == null
                || evidenceSetId == null) {
            throw new FindingWorkflowEvidenceException("A verified evidence set is required");
        }
        Boolean ready = (Boolean) dsl.fetchValue("""
                select exists (
                    select 1
                      from public.evidence_set es
                     where es.tenant_id = cast(? as uuid)
                       and es.id = cast(? as uuid)
                       and es.finding_id = cast(? as uuid)
                       and es.round_no = ?
                       and es.owner_member_id = cast(? as uuid)
                       and es.state = 'READY'
                       and es.verified_at is not null
                       and exists (
                           select 1
                             from public.evidence_item any_item
                            where any_item.tenant_id = es.tenant_id
                              and any_item.evidence_set_id = es.id
                       )
                       and not exists (
                           select 1
                             from public.evidence_item invalid_item
                            where invalid_item.tenant_id = es.tenant_id
                              and invalid_item.evidence_set_id = es.id
                              and (invalid_item.state <> 'READY'
                                   or invalid_item.verification_level <> 'CONTENT')
                       )
                       and exists (
                           select 1
                             from public.evidence_item ei
                            where ei.tenant_id = es.tenant_id
                              and ei.evidence_set_id = es.id
                              and ei.state = 'READY'
                              and ei.verification_level = 'CONTENT'
                       )
                )
                """, tenantId, evidenceSetId, findingId, roundNo, submittedByMemberId);
        if (!Boolean.TRUE.equals(ready)) {
            throw new FindingWorkflowEvidenceException(
                    "A ready evidence set with every item verified at CONTENT level is required");
        }
    }
}
