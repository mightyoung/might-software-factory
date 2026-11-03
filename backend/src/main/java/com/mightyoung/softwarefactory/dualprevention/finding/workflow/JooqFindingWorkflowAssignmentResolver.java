package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.InitialRoute;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Resolves a complete effective route; missing or overlapping rows never select a random member. */
@Repository
public final class JooqFindingWorkflowAssignmentResolver implements FindingWorkflowAssignmentResolver {

    private final DSLContext dsl;

    public JooqFindingWorkflowAssignmentResolver(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public InitialRoute resolve(UUID tenantId, UUID organizationId) {
        Map<Stage, UUID> assignments = new EnumMap<>(Stage.class);
        for (Record row : dsl.fetch("""
                select stage, assignee_member_id
                  from public.finding_stage_assignment
                 where tenant_id = cast(? as uuid)
                   and organization_id = cast(? as uuid)
                   and valid_from <= current_timestamp
                   and (valid_to is null or valid_to > current_timestamp)
                """, tenantId, organizationId)) {
            Stage stage = Stage.parse(row.get("stage", String.class));
            UUID memberId = row.get("assignee_member_id", UUID.class);
            if (assignments.putIfAbsent(stage, memberId) != null) {
                throw new FindingWorkflowConfigurationException(
                        "More than one current member is configured for " + stage);
            }
        }
        try {
            return new InitialRoute(
                    assignments.get(Stage.PERSON_CONFIRM),
                    assignments.get(Stage.SAFETY_CONFIRM),
                    assignments.get(Stage.SAFETY_DISPATCH),
                    assignments.get(Stage.SAFETY_VERIFY));
        } catch (NullPointerException missing) {
            throw new FindingWorkflowConfigurationException(
                    "All four initial finding workflow stage assignments must be configured");
        }
    }

    @Override
    public FindingReviewCycleContracts.ReviewPair resolveReviewPair(UUID tenantId, UUID organizationId) {
        Map<String, UUID> pair = new java.util.HashMap<>();
        for (Record row : dsl.fetch("""
                select stage, assignee_member_id from public.finding_stage_assignment
                 where tenant_id = cast(? as uuid) and organization_id = cast(? as uuid)
                   and stage in ('PERSON_CONFIRM', 'SAFETY_CONFIRM')
                   and valid_from <= current_timestamp and (valid_to is null or valid_to > current_timestamp)
                """, tenantId, organizationId)) {
            if (pair.putIfAbsent(row.get("stage", String.class), row.get("assignee_member_id", UUID.class)) != null) {
                throw new FindingWorkflowConfigurationException("More than one effective review stage assignment");
            }
        }
        UUID person = pair.get("PERSON_CONFIRM"), safety = pair.get("SAFETY_CONFIRM");
        if (person == null || safety == null || person.equals(safety)) {
            throw new FindingWorkflowConfigurationException("Exactly one distinct member per review stage is required");
        }
        return new FindingReviewCycleContracts.ReviewPair(person, safety);
    }

    @Override
    public UUID resolveSafetyDispatcher(UUID tenantId, UUID organizationId) {
        var rows = dsl.fetch("""
                select assignee_member_id
                  from public.finding_stage_assignment
                 where tenant_id = cast(? as uuid)
                   and organization_id = cast(? as uuid)
                   and stage = 'SAFETY_DISPATCH'
                   and valid_from <= current_timestamp
                   and (valid_to is null or valid_to > current_timestamp)
                """, tenantId, organizationId);
        if (rows.size() != 1) {
            throw new FindingWorkflowConfigurationException(
                    "Exactly one current safety dispatcher must be configured for a rework round");
        }
        return rows.getFirst().get("assignee_member_id", UUID.class);
    }

    private enum Stage {
        PERSON_CONFIRM,
        SAFETY_CONFIRM,
        SAFETY_DISPATCH,
        SAFETY_VERIFY;

        static Stage parse(String value) {
            try {
                return valueOf(value);
            } catch (RuntimeException malformed) {
                throw new FindingWorkflowConfigurationException("Unsupported finding workflow assignment stage");
            }
        }
    }
}
