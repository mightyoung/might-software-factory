package com.mightyoung.softwarefactory.files;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Source contract checks only. Actual PostgreSQL trigger, RLS and race acceptance remains separate. */
class FileIngestionMigrationContractTest {
    String sql(String name) throws Exception {try(var stream=getClass().getResourceAsStream("/db/migration/"+name)){assertThat(stream).isNotNull();return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}}
    final String migration="V18__private_ingestion_scanning.sql";
    @Test void historicalMigrationsRemainByteExact() throws Exception {
        var hashes=Map.of("V15__finding_evidence_upload_intent.sql","5483d276715098bfd0ae75146219f135964e88d0d344ee89cf5e216de1b0e8af",
                "V16__finding_evidence_set_initialization_receipt.sql","7d405b429b556e7757a26dfa7f7cfe826c334165462e5048a1658a6edb5fade8",
                "V17__finding_review_cycles_and_supplements.sql","b99491ea23f2f7df6ab47df84ea0ae462d4dd83784f73190115a8549fe5c2731");
        for(var entry:hashes.entrySet())assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql(entry.getKey()).getBytes(StandardCharsets.UTF_8))))
                .as(entry.getKey()).isEqualTo(entry.getValue());
    }
    @Test void sourceKeepsTechnicalPersistencePrivateAndRejectsPublication() throws Exception {
        String source=sql(migration);
        assertThat(source).doesNotContainIgnoringCase("security definer","grant ","create role","truncate ","insert into public.file_object","insert into public.file_link","insert into public.evidence_item");
        assertThat(source).contains("File ingestion publication or reduction is not enabled","new.state='FINALIZED'","File ingestion history is append-only");
        for(String table:new String[]{"file_ingestion_execution","file_ingestion_allocation","file_ingestion_allocation_event","file_scanner_generation","file_ingestion_scan_proof","file_ingestion_content_receipt"})
            assertThat(source).contains("alter table public."+table+" force row level security");
    }
    @Test void bothRetryClaimBranchesDeclareIntrinsicDueTimeAndSpentBudgetChecks() throws Exception {
        String source=sql(migration);
        String receive=source.substring(source.indexOf("elsif old.state in ('INTENT_CREATED','RETRY_WAIT')"),source.indexOf("elsif old.state in ('QUARANTINED','RETRY_WAIT')"));
        String scan=source.substring(source.indexOf("elsif old.state in ('QUARANTINED','RETRY_WAIT')"),source.indexOf("elsif old.state='RECEIVING' and new.state='QUARANTINED'"));
        for(String branch:new String[]{receive,scan})assertThat(branch).contains("old.next_attempt_at is null or old.next_attempt_at>statement_timestamp()","old.attempt_no>=3");
        assertThat(receive).contains("new.attempt_no<>old.attempt_no","QUARANTINE_NOT_REUSABLE");
        assertThat(scan).contains("new.attempt_no<>old.attempt_no+1","unused_bundle=old.attempt_id=q.attempt_id");
    }
    @Test void sourcePinsConservedChargesAndInactiveTerminalPredicates() throws Exception {
        String source=sql(migration);
        assertThat(source).contains("new.retained_bytes<>previous_charge","previous_charge=a.reserved_bytes","DISPOSAL_CONFIRMED",
                "new.retained_bytes<>0","new.writer_quiescent","new.kernel_pids_current<>0","new.kernel_populated","new.last_error_code is distinct from 'ATTEMPTS_EXHAUSTED'",
                "old.next_attempt_at>statement_timestamp()","new.last_error_code is distinct from 'EXPIRED'",
                "old.last_error_code is not distinct from 'QUARANTINE_NOT_REUSABLE'");
    }    @Test void sourcePinsExecutionOriginalClaimAndKernelAbsentRecoveryWithoutPublication() throws Exception {
        String source=sql(migration);
        assertThat(source).contains("current_execution_id uuid","unique (physical_resource_realm_id,execution_id)",
                "unique (tenant_id,intent_id,scan_attempt_id)","deferrable initially deferred",
                "intent.technical_version<new.claim_technical_version","execution.claim_epoch,execution.claim_token,execution.claim_technical_version",
                "event_kind in ('RETAIN','RECOVERY_OBSERVED')","File ingestion original complete baseline reference is immutable",
                "disposal_claim_technical_version","process_state='REAPED'","KERNEL_ABSENT",
                "new.kernel_pids_controller_continuous","new.kernel_no_migration_or_future_entry",
                "new.kernel_interrupted_output_discarded","new.kernel_child_pid is null");
    }
    @Test void neverAdmittedScratchAndExecutionSerializeBothOrdersOnExactParentLock() throws Exception {
        String source=sql(migration);
        String event=source.substring(source.indexOf("create function public.check_file_ingestion_event()"),source.indexOf("create trigger check_file_ingestion_event"));
        String execution=source.substring(source.indexOf("create function public.check_file_ingestion_execution()"),source.indexOf("create trigger check_file_ingestion_execution"));
        assertThat(event).contains("id=a.intent_id for update","File ingestion never-admitted scratch already has an execution",
                "scan_attempt_id=a.attempt_id and scratch_allocation_id=a.id","new.execution_id is null and new.process_state='NONE_STARTED' and new.writer_quiescent");
        assertThat(execution).contains("id=new.intent_id for update","allocation_id in (new.quarantine_allocation_id,new.scratch_allocation_id)",
                "event_kind in ('DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED')");
        // These are source declarations. Real PostgreSQL both-order concurrency remains a separate mandatory gate.
    }
    @Test void retryRecoveryCasRequiresNewSameTransactionEligibleEventAndPreservesEveryOtherField() throws Exception {
        String source=sql(migration);
        String branch=source.substring(source.indexOf("elsif new.state=old.state and old.state='RETRY_WAIT'"),
                source.indexOf("elsif old.state in ('INTENT_CREATED','RETRY_WAIT')"));
        assertThat(branch).contains("e.append_transaction_id=pg_current_xact_id()","e.technical_version=old.technical_version",
                "e.recovery_reason='EXPIRED_WORK'","e.recovery_reason='SUPERSEDED'","e.expected_intent_lease_epoch=old.lease_epoch",
                "a.id is distinct from old.quarantine_allocation_id","x.scratch_allocation_id=a.id",
                "allowed=array['technical_version','technical_updated_at']");
        assertThat(branch).doesNotContain("'TERMINAL'","'RECEIVING'","'SCANNING'","'attempt_no','lease_epoch'");
        assertThat(source).contains("new.append_transaction_id=pg_current_xact_id()","append_transaction_id xid8 not null");
        // Actual PostgreSQL old-event replay, direct SQL CAS and concurrent callback behavior are not executed here.
    }
    @Test void newAllocationLocksExactVisibleParentBeforeRejectingFiveTerminalStates() throws Exception {
        String source=sql(migration);
        String guard=source.substring(source.indexOf("create function public.check_file_ingestion_allocation_parent()"),
                source.indexOf("create function public.check_file_ingestion_event()"));
        assertThat(guard).contains("select * into strict parent from public.finding_evidence_upload_intent",
                "where tenant_id=new.tenant_id and id=new.intent_id for update",
                "parent.state in ('FAILED','REJECTED','CANCELLED','EXPIRED','FINALIZED')",
                "before insert on public.file_ingestion_allocation");
        assertThat(guard.indexOf("for update")).isLessThan(guard.indexOf("if parent.state"));
        assertThat(guard).doesNotContainIgnoringCase("security definer","set role","lease_until>","tenant_guard","quota");
        // BEFORE INSERT shares the parent UPDATE lock; actual both-order RC barrier races remain NOT RUN.
    }
    @Test void emptyInitialTerminalEdgesPinBothShapesCancellationPriorityAndExactFiveColumns() throws Exception {
        String source=sql(migration);
        String branch=source.substring(source.indexOf("elsif old.state='INTENT_CREATED' and new.state in ('CANCELLED','EXPIRED')"),
                source.indexOf("elsif old.state in ('INTENT_CREATED','RETRY_WAIT')"));
        assertThat(branch).contains("old.attempt_no<>0 or new.attempt_no<>0 or old.lease_epoch<>0 or new.lease_epoch<>0",
                "old.last_error_code is not null","where tenant_id=old.tenant_id and intent_id=old.id",
                "new.state='CANCELLED' and (not new.cancel_requested or new.last_error_code is distinct from 'CANCELLED')",
                "new.state='EXPIRED' and (old.cancel_requested or new.cancel_requested or old.expires_at>statement_timestamp()",
                "allowed=array['state','technical_version','technical_updated_at','cancel_requested','last_error_code']");
        for(String field:new String[]{"attempt_id","reserved_scan_attempt_id","lease_token","lease_until","heartbeat_at","next_attempt_at",
                "quarantine_allocation_id","immutable_allocation_id","scan_proof_id","content_receipt_id","scanner_generation_sha256","current_execution_id"})
            assertThat(branch).contains("old."+field+" is not null or new."+field+" is not null");
        assertThat(branch).doesNotContain("old.technical_version<>0","new.technical_version=0","new.retained_bytes",
                "insert into","update public.file_ingestion");
        assertThat(source).contains("new.technical_version<>old.technical_version+1","new.technical_updated_at<old.technical_updated_at",
                "old.cancel_requested and not new.cancel_requested","(to_jsonb(new)-allowed) is distinct from (to_jsonb(old)-allowed)");
        // Prior committed cancellation markers may have version1; no allocation/event/charge write is introduced.
    }

}
