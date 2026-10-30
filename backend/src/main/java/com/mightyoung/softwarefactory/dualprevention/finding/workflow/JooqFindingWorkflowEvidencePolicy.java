package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Accepts only a tenant-local READY evidence set with at least one READY item. */
@Repository
public final class JooqFindingWorkflowEvidencePolicy implements FindingWorkflowEvidencePolicy {

    private final DSLContext dsl;

    public JooqFindingWorkflowEvidencePolicy(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void requireReady(UUID tenantId, UUID findingId, int roundNo, UUID evidenceSetId) {
        if (evidenceSetId == null) {
            throw new FindingWorkflowEvidenceException("A verified evidence set is required");
        }
        Boolean ready = (Boolean) dsl.fetchValue("""
                select exists (
                    select 1
                      from public.evidence_set es
                     where es.tenant_id = cast(? as uuid)
                       and es.id = cast(? as uuid)
                       and es.state = 'READY'
                       and es.verified_at is not null
                       and exists (
                           select 1
                             from public.evidence_item ei
                            where ei.tenant_id = es.tenant_id
                              and ei.evidence_set_id = es.id
                              and ei.state = 'READY'
                       )
                )
                """, tenantId, evidenceSetId);
        if (!Boolean.TRUE.equals(ready)) {
            throw new FindingWorkflowEvidenceException(
                    "A ready evidence set with at least one verified item is required");
        }
    }
}
