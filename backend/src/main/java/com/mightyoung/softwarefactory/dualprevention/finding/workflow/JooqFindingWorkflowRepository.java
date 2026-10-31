package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandClaim;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandResult;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Confirmation;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Notice;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Submission;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Transition;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Verification;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.WorkflowCommand;
import com.mightyoung.softwarefactory.files.FileEvidenceVerificationLevel;
import com.mightyoung.softwarefactory.files.VerifiedFileEvidenceSnapshot;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** jOOQ persistence with tenant-local RLS, row locking, and a single shared transaction boundary. */
@Repository
public final class JooqFindingWorkflowRepository implements FindingWorkflowRepository {

    private final DSLContext dsl;
    private final TransactionTemplate commandTransaction;

    public JooqFindingWorkflowRepository(DSLContext dsl, PlatformTransactionManager transactionManager) {
        this.dsl = dsl;
        this.commandTransaction = new TransactionTemplate(transactionManager);
        // Read fresh evidence rows after lock waits; V6's parent revision update also
        // turns stale REPEATABLE READ writers into serialization conflicts.
        this.commandTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public <T> T inTenantTransaction(UUID tenantId, Function<Transaction, T> work) {
        if (tenantId == null || work == null) throw new IllegalArgumentException("Tenant and command are required");
        return commandTransaction.execute(status -> {
            setLocalTenant(tenantId);
            return work.apply(new JooqTransaction(tenantId));
        });
    }

    private void setLocalTenant(UUID tenantId) {
        String applied = (String) dsl.fetchValue("select set_config(?, ?, true)",
                "app.tenant_id", tenantId.toString());
        if (!tenantId.toString().equals(applied)) {
            throw new IllegalStateException("Unable to apply the finding workflow tenant context");
        }
    }

    private final class JooqTransaction implements Transaction {
        private final UUID tenantId;

        private JooqTransaction(UUID tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        public Optional<FindingWorkflowCase> findForUpdate(UUID findingId) {
            Record row = dsl.fetchOne("""
                    select tenant_id, id, organization_id, code, description, discovered_at,
                           source_event_id, risk_id, grade, state, round_no, version,
                           created_by_member_id, updated_by_member_id,
                           source_category, urgency, location, interim_measure, backfill
                      from public.finding
                     where tenant_id = cast(? as uuid) and id = cast(? as uuid)
                     for update
                    """, tenantId, findingId);
            return Optional.ofNullable(row).map(JooqFindingWorkflowRepository::toFinding);
        }

        @Override
        public void insertFinding(FindingWorkflowCase finding) {
            if (!tenantId.equals(finding.tenantId())) {
                throw new IllegalArgumentException("A finding cannot be inserted under another tenant");
            }
            int inserted = dsl.execute("""
                    insert into public.finding
                        (tenant_id, id, organization_id, code, description, discovered_at,
                         source_event_id, risk_id, grade, state, round_no, version,
                         created_at, updated_at, created_by_member_id, updated_by_member_id,
                         source_category, urgency, location, interim_measure, backfill)
                    values (cast(? as uuid), cast(? as uuid), cast(? as uuid), ?, ?, ?,
                            cast(? as uuid), cast(? as uuid), ?, ?, ?, ?, current_timestamp,
                            current_timestamp, cast(? as uuid), cast(? as uuid), ?, ?, ?, ?, ?)
                    """, finding.tenantId(), finding.id(), finding.organizationId(), finding.code(),
                    finding.description(), finding.discoveredAt(), finding.sourceEventId(), finding.riskId(),
                    finding.grade(), finding.state().name(), finding.roundNo(), finding.version(),
                    finding.createdByMemberId(), finding.updatedByMemberId(),
                    finding.sourceCategory() == null ? null : finding.sourceCategory().name(),
                    finding.urgency() == null ? null : finding.urgency().name(), finding.location(),
                    finding.interimMeasure(), finding.backfill());
            if (inserted != 1) throw new IllegalStateException("Finding create did not insert one row");
        }

        @Override
        public String nextFindingCode(LocalDate businessDay) {
            Long sequence = (Long) dsl.fetchValue("""
                    with observed as (
                        select coalesce(max(split_part(code, '-', 3)::numeric), 0) + 1 as next_sequence
                          from public.finding
                         where tenant_id = cast(? as uuid)
                           and code ~ ('^YH-' || to_char(cast(? as date), 'YYYYMMDD') || '-[0-9]{4,}$')
                    )
                    insert into public.finding_number_sequence (tenant_id, business_day, last_sequence)
                    select cast(? as uuid), cast(? as date), observed.next_sequence
                      from observed
                    on conflict (tenant_id, business_day)
                    do update set last_sequence = greatest(
                        public.finding_number_sequence.last_sequence + 1,
                        excluded.last_sequence)
                    returning last_sequence
                    """, tenantId, businessDay, tenantId, businessDay);
            if (sequence == null || sequence < 1) {
                throw new IllegalStateException("Finding number sequence could not be allocated");
            }
            return "YH-" + businessDay.toString().replace("-", "") + "-"
                    + String.format(Locale.ROOT, "%04d", sequence);
        }

        @Override
        public void updateFinding(FindingWorkflowCase finding, long expectedVersion) {
            int updated = dsl.execute("""
                    update public.finding
                       set state = ?, round_no = ?, version = ?, updated_at = current_timestamp,
                           updated_by_member_id = cast(? as uuid)
                     where tenant_id = cast(? as uuid) and id = cast(? as uuid) and version = ?
                    """, finding.state().name(), finding.roundNo(), finding.version(),
                    finding.updatedByMemberId(), tenantId, finding.id(), expectedVersion);
            if (updated != 1) throw new FindingWorkflowVersionConflictException();
        }

        @Override
        public CommandClaim claimCommand(WorkflowCommand command) {
            int inserted = dsl.execute("""
                    insert into public.finding_command_receipt
                        (tenant_id, command_id, actor_id, action, request_hash)
                    values (cast(? as uuid), cast(? as uuid), cast(? as uuid), ?, ?)
                    on conflict (tenant_id, command_id) do nothing
                    """, tenantId, command.commandId(), command.actorId(), command.action(), command.requestHash());
            if (inserted == 1) return CommandClaim.newClaim();

            Record prior = dsl.fetchOne("""
                    select receipt.actor_id, receipt.action, receipt.request_hash,
                           receipt.result_finding_id, finding.code as result_code,
                           receipt.result_state, receipt.result_round_no, receipt.result_version
                      from public.finding_command_receipt receipt
                      left join public.finding
                        on finding.tenant_id = receipt.tenant_id
                       and finding.id = receipt.result_finding_id
                     where receipt.tenant_id = cast(? as uuid)
                       and receipt.command_id = cast(? as uuid)
                     for update of receipt
                    """, tenantId, command.commandId());
            if (prior == null) throw new IllegalStateException("Conflicting command receipt could not be read");
            if (!command.actorId().equals(prior.get("actor_id", UUID.class))
                    || !command.action().equals(prior.get("action", String.class))
                    || !command.requestHash().equals(prior.get("request_hash", String.class))) {
                throw new FindingWorkflowIdempotencyConflictException();
            }
            UUID resultFindingId = prior.get("result_finding_id", UUID.class);
            if (resultFindingId == null) {
                throw new IllegalStateException("An incomplete command receipt cannot be replayed");
            }
            String resultCode = prior.get("result_code", String.class);
            if (resultCode == null || resultCode.isBlank()) {
                throw new IllegalStateException("A completed finding command receipt has no visible finding code");
            }
            return CommandClaim.replay(new CommandResult(resultFindingId,
                    resultCode,
                    FindingWorkflowState.valueOf(prior.get("result_state", String.class)),
                    prior.get("result_round_no", Integer.class), prior.get("result_version", Long.class)));
        }

        @Override
        public void completeCommand(UUID commandId, CommandResult result) {
            int updated = dsl.execute("""
                    update public.finding_command_receipt
                       set result_finding_id = cast(? as uuid), result_state = ?, result_round_no = ?,
                           result_version = ?, completed_at = current_timestamp
                     where tenant_id = cast(? as uuid) and command_id = cast(? as uuid)
                       and result_finding_id is null
                    """, result.findingId(), result.state().name(), result.roundNo(), result.version(),
                    tenantId, commandId);
            if (updated != 1) throw new IllegalStateException("Command receipt could not be completed");
        }

        @Override
        public void appendTransition(Transition transition) {
            int inserted = dsl.execute("""
                    insert into public.finding_transition
                        (tenant_id, id, finding_id, from_state, to_state, command_id, actor_id,
                         occurred_at, reason, from_version, to_version)
                    values (cast(? as uuid), cast(? as uuid), cast(? as uuid), ?, ?, cast(? as uuid),
                            cast(? as uuid), ?, ?, ?, ?)
                    """, tenantId, UUID.randomUUID(), transition.findingId(),
                    transition.fromState() == null ? null : transition.fromState().name(),
                    transition.toState().name(), transition.commandId(), transition.actorId(),
                    transition.occurredAt(), transition.reason(), transition.fromVersion(), transition.toVersion());
            if (inserted != 1) throw new IllegalStateException("Finding transition did not persist");

            int audited = dsl.execute("""
                    insert into public.audit_event
                        (tenant_id, id, actor_id, action, resource_type, resource_id, result, reason,
                         diff_redacted, trace_id, policy_version, occurred_at)
                    select cast(? as uuid), cast(? as uuid), cast(? as uuid), receipt.action, 'finding',
                           cast(? as uuid), 'SUCCESS', cast(? as text),
                           jsonb_build_object(
                               'fromState', cast(? as text),
                               'toState', cast(? as text),
                               'fromVersion', cast(? as bigint),
                               'toVersion', cast(? as bigint)),
                           cast(? as varchar), 'finding-workflow-v1', ?
                      from public.finding_command_receipt receipt
                     where receipt.tenant_id = cast(? as uuid)
                       and receipt.command_id = cast(? as uuid)
                       and receipt.actor_id = cast(? as uuid)
                    """, tenantId, UUID.randomUUID(), transition.actorId(), transition.findingId(),
                    transition.reason(), transition.fromState() == null ? null : transition.fromState().name(),
                    transition.toState().name(), transition.fromVersion(), transition.toVersion(),
                    org.slf4j.MDC.get("traceId"), transition.occurredAt(), tenantId,
                    transition.commandId(), transition.actorId());
            if (audited != 1) throw new IllegalStateException("Finding audit event did not persist");
        }

        @Override
        public void appendConfirmation(Confirmation confirmation) {
            int inserted = dsl.execute("""
                    insert into public.finding_confirmation
                        (tenant_id, finding_id, stage, actor_member_id, decision, decided_at,
                         expected_version, command_id)
                    values (cast(? as uuid), cast(? as uuid), ?, cast(? as uuid), 'APPROVE', ?, ?, cast(? as uuid))
                    """, tenantId, confirmation.findingId(), confirmation.stage(), confirmation.actorMemberId(),
                    confirmation.decidedAt(), confirmation.expectedVersion(), confirmation.commandId());
            if (inserted != 1) throw new IllegalStateException("Finding confirmation did not persist");
        }

        @Override
        public Optional<UUID> findConfirmationActorMemberId(UUID findingId, String stage) {
            Record row = dsl.fetchOne("""
                    select actor_member_id
                      from public.finding_confirmation
                     where tenant_id = cast(? as uuid)
                       and finding_id = cast(? as uuid)
                       and stage = ?
                    """, tenantId, findingId, stage);
            UUID actorMemberId = row == null ? null : row.get("actor_member_id", UUID.class);
            return Optional.ofNullable(actorMemberId);
        }

        @Override
        public void openRound(UUID findingId, int roundNo, UUID commandId) {
            int inserted = dsl.execute("""
                    insert into public.remediation_round
                        (tenant_id, finding_id, round_no, state, opened_at, opened_by_command_id)
                    values (cast(? as uuid), cast(? as uuid), ?, 'OPEN', current_timestamp, cast(? as uuid))
                    """, tenantId, findingId, roundNo, commandId);
            if (inserted != 1) throw new IllegalStateException("Remediation round did not open");
        }

        @Override
        public void closeRound(UUID findingId, int roundNo, String state, UUID commandId) {
            if (!"FAILED".equals(state) && !"CLOSED".equals(state)) {
                throw new IllegalArgumentException("Unsupported closed round state");
            }
            int updated = dsl.execute("""
                    update public.remediation_round
                       set state = ?, closed_at = current_timestamp, closed_by_command_id = cast(? as uuid)
                     where tenant_id = cast(? as uuid) and finding_id = cast(? as uuid)
                       and round_no = ? and state = 'OPEN'
                    """, state, commandId, tenantId, findingId, roundNo);
            if (updated != 1) throw new FindingWorkflowRuleException("The remediation round is not open");
            String noticeState = "FAILED".equals(state) ? "NEEDS_REWORK" : "VERIFIED";
            int updatedNotice = dsl.execute("""
                    update public.remediation_notice set state = ?
                     where tenant_id = cast(? as uuid) and finding_id = cast(? as uuid)
                       and round_no = ? and state = 'ISSUED'
                    """, noticeState, tenantId, findingId, roundNo);
            if (updatedNotice != 1) {
                throw new FindingWorkflowRuleException("The remediation notice is not active for this round");
            }
        }

        @Override
        public Optional<Notice> findNotice(UUID findingId, int roundNo) {
            Record row = dsl.fetchOne("""
                    select id, finding_id, round_no, assignee_id, due_at, original_due_at, requirements
                      from public.remediation_notice
                     where tenant_id = cast(? as uuid) and finding_id = cast(? as uuid) and round_no = ?
                    """, tenantId, findingId, roundNo);
            return Optional.ofNullable(row).map(value -> new Notice(
                    value.get("id", UUID.class), value.get("finding_id", UUID.class),
                    value.get("round_no", Integer.class), value.get("assignee_id", UUID.class),
                    value.get("due_at", OffsetDateTime.class), value.get("original_due_at", OffsetDateTime.class),
                    value.get("requirements", String.class)));
        }

        @Override
        public void insertNotice(Notice notice, UUID commandId) {
            int inserted = dsl.execute("""
                    insert into public.remediation_notice
                        (tenant_id, id, finding_id, revision, round_no, assignee_id, due_at, original_due_at,
                         requirements, state, issued_at, command_id)
                    values (cast(? as uuid), cast(? as uuid), cast(? as uuid), ?, ?, cast(? as uuid), ?, ?,
                            ?, 'ISSUED', current_timestamp, cast(? as uuid))
                    """, tenantId, notice.id(), notice.findingId(), notice.roundNo(), notice.roundNo(),
                    notice.assigneeMemberId(), notice.dueAt(), notice.originalDueAt(),
                    notice.requirements(), commandId);
            if (inserted != 1) throw new IllegalStateException("Remediation notice did not persist");
        }

        @Override
        public void insertSubmission(Submission submission) {
            if (!tenantId.equals(submission.tenantId())) {
                throw new IllegalArgumentException("A remediation submission cannot be inserted under another tenant");
            }
            int inserted = dsl.execute("""
                    insert into public.remediation_submission
                        (tenant_id, id, finding_id, notice_id, round_no, submitted_by, submitted_at,
                         evidence_set_id, statement, expected_version, command_id)
                    values (cast(? as uuid), cast(? as uuid), cast(? as uuid), cast(? as uuid), ?,
                            cast(? as uuid), ?, cast(? as uuid), ?, ?, cast(? as uuid))
                    """, tenantId, submission.id(), submission.findingId(), submission.noticeId(),
                    submission.roundNo(), submission.submittedByMemberId(), submission.submittedAt(),
                    submission.evidenceSetId(), submission.statement(), submission.expectedVersion(),
                    submission.commandId());
            if (inserted != 1) throw new IllegalStateException("Remediation submission did not persist");

            for (VerifiedFileEvidenceSnapshot snapshot : submission.evidenceSnapshots()) {
                int snapshotInserted = dsl.execute("""
                        insert into public.remediation_submission_evidence
                            (tenant_id, submission_id, finding_id, round_no, evidence_set_id, submitted_by,
                             evidence_item_id, file_link_id, file_id, resource_type, resource_id, purpose,
                             owner_member_id, owner_actor_id, sha256, storage_version,
                             verification_receipt_id, verifier_id, verifier_key_id,
                             verification_policy_version, verification_level, verified_at)
                        values (cast(? as uuid), cast(? as uuid), cast(? as uuid), ?, cast(? as uuid),
                                cast(? as uuid), cast(? as uuid), cast(? as uuid), cast(? as uuid),
                                'FINDING_EVIDENCE_ITEM', cast(? as uuid), 'REMEDIATION_EVIDENCE',
                                cast(? as uuid), cast(? as uuid), ?, ?, cast(? as uuid), ?, ?, ?, ?, ?)
                        """, tenantId, submission.id(), submission.findingId(), submission.roundNo(),
                        submission.evidenceSetId(), submission.submittedByMemberId(), snapshot.evidenceItemId(),
                        snapshot.fileLinkId(), snapshot.fileId(), snapshot.evidenceItemId(), snapshot.ownerMemberId(),
                        snapshot.ownerActorId(), snapshot.sha256(), snapshot.storageVersion(),
                        snapshot.verificationReceiptId(), snapshot.verifierId(), snapshot.verifierKeyId(),
                        snapshot.verificationPolicyVersion(), snapshot.verificationLevel().name(),
                        snapshot.verifiedAt());
                if (snapshotInserted != 1) {
                    throw new IllegalStateException("Remediation submission evidence snapshot did not persist");
                }
            }

            int sealed = dsl.execute("""
                    update public.remediation_submission
                       set evidence_snapshots_sealed_at = current_timestamp
                     where tenant_id = cast(? as uuid)
                       and id = cast(? as uuid)
                       and evidence_snapshots_sealed_at is null
                    """, tenantId, submission.id());
            if (sealed != 1) {
                throw new IllegalStateException("Remediation submission evidence snapshot set did not seal");
            }
        }

        @Override
        public Optional<Submission> findSubmission(UUID findingId, int roundNo) {
            Record row = dsl.fetchOne("""
                    select id, finding_id, notice_id, round_no, submitted_by, submitted_at,
                           evidence_set_id, statement, expected_version, command_id,
                           evidence_snapshot_count, evidence_snapshot_manifest, evidence_snapshots_sealed_at
                      from public.remediation_submission
                     where tenant_id = cast(? as uuid) and finding_id = cast(? as uuid) and round_no = ?
                    """, tenantId, findingId, roundNo);
            if (row == null) return Optional.empty();
            UUID submissionId = row.get("id", UUID.class);
            Integer expectedSnapshotCount = row.get("evidence_snapshot_count", Integer.class);
            UUID[] expectedSnapshotManifest = row.get("evidence_snapshot_manifest", UUID[].class);
            OffsetDateTime sealedAt = row.get("evidence_snapshots_sealed_at", OffsetDateTime.class);
            if (expectedSnapshotCount == null || expectedSnapshotCount < 1
                    || expectedSnapshotManifest == null || sealedAt == null) {
                throw new IllegalStateException("Remediation submission has no sealed evidence snapshot manifest");
            }
            java.util.List<VerifiedFileEvidenceSnapshot> snapshots = findSubmissionEvidenceSnapshots(submissionId);
            UUID[] actualSnapshotManifest = snapshots.stream()
                    .map(VerifiedFileEvidenceSnapshot::evidenceItemId).toArray(UUID[]::new);
            if (expectedSnapshotCount != snapshots.size()
                    || !Arrays.equals(expectedSnapshotManifest, actualSnapshotManifest)) {
                throw new IllegalStateException("Remediation submission evidence snapshot manifest is inconsistent");
            }
            return Optional.of(new Submission(
                    tenantId, submissionId, row.get("finding_id", UUID.class),
                    row.get("notice_id", UUID.class), row.get("round_no", Integer.class),
                    row.get("submitted_by", UUID.class), row.get("submitted_at", OffsetDateTime.class),
                    row.get("evidence_set_id", UUID.class), snapshots,
                    row.get("statement", String.class), row.get("expected_version", Long.class),
                    row.get("command_id", UUID.class)));
        }

        private java.util.List<VerifiedFileEvidenceSnapshot> findSubmissionEvidenceSnapshots(UUID submissionId) {
            return dsl.fetch("""
                    select tenant_id, evidence_item_id, file_id, file_link_id, owner_member_id, owner_actor_id,
                           sha256, storage_version, verification_receipt_id, verifier_id, verifier_key_id,
                           verification_policy_version, verification_level, verified_at
                      from public.remediation_submission_evidence
                     where tenant_id = cast(? as uuid) and submission_id = cast(? as uuid)
                     order by evidence_item_id
                    """, tenantId, submissionId).map(value -> new VerifiedFileEvidenceSnapshot(
                    value.get("tenant_id", UUID.class), value.get("evidence_item_id", UUID.class),
                    value.get("file_id", UUID.class), value.get("file_link_id", UUID.class),
                    value.get("owner_member_id", UUID.class), value.get("owner_actor_id", UUID.class),
                    value.get("sha256", String.class), value.get("storage_version", String.class),
                    value.get("verification_receipt_id", UUID.class), value.get("verifier_id", String.class),
                    value.get("verifier_key_id", String.class),
                    value.get("verification_policy_version", String.class),
                    FileEvidenceVerificationLevel.valueOf(value.get("verification_level", String.class)),
                    value.get("verified_at", OffsetDateTime.class)));
        }

        @Override
        public void insertVerification(Verification verification) {
            int inserted = dsl.execute("""
                    insert into public.verification
                        (tenant_id, id, finding_id, submission_id, round_no, reviewer_id, decision,
                         reason, decided_at, command_id)
                    values (cast(? as uuid), cast(? as uuid), cast(? as uuid), cast(? as uuid), ?,
                            cast(? as uuid), ?, ?, ?, cast(? as uuid))
                    """, tenantId, UUID.randomUUID(), verification.findingId(), verification.submissionId(),
                    verification.roundNo(), verification.reviewerMemberId(), verification.decision().name(),
                    verification.reason(), verification.decidedAt(), verification.commandId());
            if (inserted != 1) throw new IllegalStateException("Verification did not persist");
        }
    }

    private static FindingWorkflowCase toFinding(Record row) {
        return new FindingWorkflowCase(
                row.get("tenant_id", UUID.class), row.get("id", UUID.class),
                row.get("organization_id", UUID.class), row.get("code", String.class),
                row.get("description", String.class), row.get("discovered_at", OffsetDateTime.class),
                row.get("source_event_id", UUID.class), row.get("risk_id", UUID.class),
                row.get("grade", String.class), FindingWorkflowState.valueOf(row.get("state", String.class)),
                row.get("round_no", Integer.class), row.get("version", Long.class),
                row.get("created_by_member_id", UUID.class), row.get("updated_by_member_id", UUID.class),
                enumValue(row.get("source_category", String.class), FindingSourceCategory.class),
                enumValue(row.get("urgency", String.class), FindingUrgency.class),
                row.get("location", String.class), row.get("interim_measure", String.class),
                row.get("backfill", Boolean.class));
    }

    private static <T extends Enum<T>> T enumValue(String value, Class<T> type) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
