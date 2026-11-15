package com.mightyoung.softwarefactory.files;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Append-only files SQL. Application-owned intent/authorization updates remain with the business caller. */
@Repository
public final class JooqFileIngestionLedger implements FileIngestionLedger {
    private final DSLContext configured;
    private final DataSource source;
    private final IngestionProofCodec codec;
    public JooqFileIngestionLedger(DSLContext configured,DataSource source,IngestionProofCodec codec){
        required(configured,source,codec);this.configured=configured;this.source=source;this.codec=codec;
    }
    @Override public <T> T inCallerTransaction(UUID tenantId,Function<FileIngestionTxContext,T> work){
        required(work);
        FileIngestionTxContext context=new FileIngestionTxContext(configured,source,tenantId,this);
        try{T result=work.apply(context);context.requireOpen();return result;}
        catch(RuntimeException|Error failure){context.invalidate();throw failure;}
        finally{context.close();}
    }
    private <T> T sql(FileIngestionTxContext context,Function<DSLContext,T> action){
        required(context);context.requireOwner(this);
        try{T result=action.apply(context.sql());context.requireOpen();return result;}
        catch(RuntimeException|Error failure){context.invalidate();throw failure;}
    }
    @Override public RecordedAllocation reserve(FileIngestionTxContext tx,AllocationRequest request){
        return sql(tx,db->{
            requireTenant(tx,request.tenantId());
            // Exact persisted replay is SELECT-only, including after the parent becomes terminal.
            // ON CONFLICT still fires BEFORE INSERT and is therefore not a terminal replay path.
            var existing=db.fetchOptional("select * from public.file_ingestion_allocation where tenant_id=? and id=?",
                    tx.tenantId(),request.allocationId());
            if(existing.isPresent()){
                if(!request.equals(allocation(existing.get())))throw unavailable();
                return loadState(db,tx.tenantId(),request.allocationId()).allocation();
            }
            Map<String,Object> row=allocationRow(request);
            insert(db,"file_ingestion_allocation",row," on conflict (tenant_id,id) do nothing");
            RecordedAllocation actual=loadState(db,tx.tenantId(),request.allocationId()).allocation();
            if(!request.equals(actual.request()))throw unavailable();
            return actual;
        });
    }
    @Override public RecordedAllocation load(FileIngestionTxContext tx,UUID id){
        return sql(tx,db->loadState(db,tx.tenantId(),id).allocation());
    }
    @Override public RecordedAllocation loadForAttempt(FileIngestionTxContext tx,UUID intentId,UUID attemptId,AllocationKind kind){
        return sql(tx,db->{
            required(intentId,attemptId,kind);
            UUID id=db.fetchOptional("select id from public.file_ingestion_allocation where tenant_id=? and intent_id=? and attempt_id=? and kind=?",
                    tx.tenantId(),intentId,attemptId,kind.name()).map(row->row.get("id",UUID.class)).orElseThrow(JooqFileIngestionLedger::unavailable);
            return loadState(db,tx.tenantId(),id).allocation();
        });
    }
    @Override public RetainedAllocationPage retainedForIntent(FileIngestionTxContext tx,UUID intentId,UUID afterAllocationId,int limit){
        return sql(tx,db->{
            required(intentId);if(limit<1||limit>32)throw invalid();
            var ids=db.fetch("""
                select a.id from public.file_ingestion_allocation a
                left join lateral (select event_kind,retained_bytes from public.file_ingestion_allocation_event
                    where tenant_id=a.tenant_id and allocation_id=a.id order by event_no desc limit 1) e on true
                where a.tenant_id=? and a.intent_id=?
                  and (cast(? as uuid) is null or a.id>cast(? as uuid))
                  and (coalesce(e.retained_bytes,a.reserved_bytes)>0 or e.event_kind='DISPOSAL_CLAIMED')
                  and coalesce(e.event_kind,'RETAIN') not in ('TRANSFER_FINALIZED','DISPOSAL_CONFIRMED')
                order by a.id limit ?
                """,tx.tenantId(),intentId,afterAllocationId,afterAllocationId,Math.addExact(limit,1)).getValues("id",UUID.class);
            boolean more=ids.size()>limit;var records=new java.util.ArrayList<RecordedAllocation>(Math.min(ids.size(),limit));
            for(UUID id:ids.subList(0,Math.min(ids.size(),limit))){
                AllocationState state=loadState(db,tx.tenantId(),id);RecordedAllocation a=state.allocation();
                boolean activeClaim=state.latest()!=null&&"DISPOSAL_CLAIMED".equals(state.latest().get("event_kind",String.class));
                if(!a.request().intentId().equals(intentId)||a.finalizedFileId()!=null||a.retainedBytes()==0&&!activeClaim)throw unavailable();
                records.add(a);
            }
            return new RetainedAllocationPage(records,more?records.getLast().request().allocationId():null);
        });
    }
    @Override public java.util.Optional<DisposalClaim> currentDisposalClaim(FileIngestionTxContext tx,UUID allocationId){
        return sql(tx,db->{
            AllocationState state=loadState(db,tx.tenantId(),allocationId);RecordedAllocation a=state.allocation();Record event=state.latest();
            if(event==null||a.finalizedFileId()!=null||!"DISPOSAL_CLAIMED".equals(event.get("event_kind",String.class)))return java.util.Optional.empty();
            return java.util.Optional.of(new DisposalClaim(a,a.disposalTombstoneId(),event.get("disposal_token",UUID.class),
                    event.get("disposal_epoch",Long.class),event.get("disposal_claim_technical_version",Long.class),event.get("eligibility_policy_sha256",String.class)));
        });
    }
    @Override public PhysicalCharges charges(FileIngestionTxContext tx,UUID owner){
        return sql(tx,db->{
            required(owner);
            Record r=db.fetchOne("""
                select coalesce(sum(case when a.owner_member_id=? and coalesce(e.event_kind,'RETAIN')<>'TRANSFER_FINALIZED'
                            then coalesce(e.retained_bytes,a.reserved_bytes) else 0 end),0)::numeric as owner_pending,
                       coalesce(sum(case when coalesce(e.event_kind,'RETAIN')<>'TRANSFER_FINALIZED'
                            then coalesce(e.retained_bytes,a.reserved_bytes) else 0 end),0)::numeric as tenant_pending,
                       coalesce(sum(case when a.owner_member_id=? and e.event_kind='TRANSFER_FINALIZED'
                            then e.retained_bytes else 0 end),0)::numeric as owner_finalized,
                       coalesce(sum(case when e.event_kind='TRANSFER_FINALIZED' then e.retained_bytes else 0 end),0)::numeric as tenant_finalized
                  from public.file_ingestion_allocation a
                  left join lateral (select event_kind,retained_bytes from public.file_ingestion_allocation_event
                     where tenant_id=a.tenant_id and allocation_id=a.id order by event_no desc limit 1) e on true
                 where a.tenant_id=?
                """,owner,owner,tx.tenantId());
            return new PhysicalCharges(number(r,"owner_pending"),number(r,"tenant_pending"),number(r,"owner_finalized"),number(r,"tenant_finalized"));
        });
    }
    @Override public RecordedAllocation recordQuarantine(FileIngestionTxContext tx,TechnicalFence fence,QuarantinedObject object){
        return sql(tx,db->{
            requireTenant(tx,object.tenantId());
            AllocationState loaded=loadState(db,tx.tenantId(),object.allocationId());
            RecordedAllocation allocation=loaded.allocation();
            kind(allocation,AllocationKind.QUARANTINE);requireFence(allocation.request(),fence);
            if(!allocation.request().intentId().equals(object.intentId())||!allocation.request().fileId().equals(object.fileId())
                    ||!allocation.request().attemptId().equals(object.sourceAttemptId()))throw unavailable();
            DurableObservation observation=codec.observe(allocation.request(),ObservationKind.COMPLETE_QUARANTINE,
                    object.identity(),object.sha256(),object.sizeBytes(),object.detectedMediaType());
            if(!observation.observationSha256().equals(object.observationSha256()))throw unavailable();
            return retain(db,loaded,fence,observation);
        });
    }
    @Override public RecordedAllocation recordObservation(FileIngestionTxContext tx,TechnicalFence fence,DurableObservation observation){
        return sql(tx,db->{
            requireTenant(tx,observation.allocation().tenantId());requireFence(observation.allocation(),fence);
            codec.validateObservation(observation);
            AllocationState loaded=loadState(db,tx.tenantId(),observation.allocation().allocationId());
            if(!loaded.allocation().request().equals(observation.allocation()))throw unavailable();
            return retain(db,loaded,fence,observation);
        });
    }
    private RecordedAllocation retain(DSLContext db,AllocationState loaded,TechnicalFence fence,DurableObservation observation){
        codec.validateObservation(observation);
        RecordedAllocation allocation=loaded.allocation();
        if(allocation.disposalTombstoneId()!=null||allocation.finalizedFileId()!=null)throw unavailable();
        if(allocation.baseline()!=null){
            if(!sameObservation(allocation.baseline(),observation))throw unavailable();
            return allocation;
        }
        append(db,loaded,EventKind.RETAIN,allocation.retainedBytes(),observation,fence.technicalVersion(),null,null,null);
        return loadState(db,allocation.request().tenantId(),allocation.request().allocationId()).allocation();
    }
    @Override public RecordedExecution recordExecution(FileIngestionTxContext tx,ExecutionDescriptor descriptor){
        return sql(tx,db->{requireTenant(tx,descriptor.tenantId());codec.validateExecution(descriptor);
            RecordedAllocation quarantine=loadState(db,tx.tenantId(),descriptor.quarantineAllocationId()).allocation();
            RecordedAllocation scratch=loadState(db,tx.tenantId(),descriptor.scratchAllocationId()).allocation();
            kind(quarantine,AllocationKind.QUARANTINE);kind(scratch,AllocationKind.SCANNER_SCRATCH);
            if(!quarantine.request().intentId().equals(descriptor.intentId())||!scratch.request().intentId().equals(descriptor.intentId())
                    ||!quarantine.request().fileId().equals(descriptor.fileId())||!scratch.request().fileId().equals(descriptor.fileId())
                    ||!quarantine.request().attemptId().equals(descriptor.sourceAttemptId())||!scratch.request().attemptId().equals(descriptor.scanAttemptId())
                    ||quarantine.baseline()==null||quarantine.baseline().kind()!=ObservationKind.COMPLETE_QUARANTINE)throw unavailable();
            insert(db,"file_ingestion_execution",executionRow(descriptor),"");
            return loadExecution(db,tx.tenantId(),descriptor.executionId());});
    }
    @Override public java.util.Optional<RecordedExecution> executionForAttempt(FileIngestionTxContext tx,UUID intentId,UUID attemptId){
        return sql(tx,db->db.fetchOptional("select * from public.file_ingestion_execution where tenant_id=? and intent_id=? and scan_attempt_id=?",
                tx.tenantId(),intentId,attemptId).map(this::execution));
    }
    @Override public RecordedAllocation recordRecoveryObservation(FileIngestionTxContext tx,RecoveryBinding binding,RecoveryObservation observation){
        return sql(tx,db->{requireTenant(tx,binding.tenantId());codec.validateRecovery(observation);
            AllocationState state=loadState(db,tx.tenantId(),binding.allocationId());
            requireRecoveryTarget(state,binding,observation);
            if(state.allocation().disposalTombstoneId()!=null||state.allocation().finalizedFileId()!=null)throw unavailable();
            validateRecoveryExecution(db,observation);
            appendRecovery(db,state,EventKind.RECOVERY_OBSERVED,state.allocation().retainedBytes(),observation,
                    binding.expectedCurrentTechnicalVersion(),null,null,null,null);
            return loadState(db,tx.tenantId(),binding.allocationId()).allocation();});
    }
    private static void requireRecoveryTarget(AllocationState state,RecoveryBinding binding,RecoveryObservation observation){
        RecordedAllocation a=state.allocation();
        if(!binding.equals(observation.binding())||!a.request().equals(observation.allocation())
                ||binding.targetEventNo()!=a.eventNo()||!Objects.equals(binding.targetEventSha256(),a.eventSha256())
                ||!Objects.equals(observation.baselineEventId(),state.baseline()==null?null:state.baseline().get("id",UUID.class))
                ||!Objects.equals(observation.baselineObservationSha256(),a.baseline()==null?null:a.baseline().observationSha256()))throw unavailable();
        if(a.baseline()!=null&&observation.presence()!=RecoveryPresence.ABSENT&&observation.presence()!=RecoveryPresence.UNKNOWN
                &&(observation.presence()!=RecoveryPresence.PRESENT_COMPLETE_UNCHANGED||!a.baseline().identity().equals(observation.identity())))throw unavailable();
    }
    private void validateRecoveryExecution(DSLContext db,RecoveryObservation observation){
        RecoveryBinding b=observation.binding();
        if(b.executionId()!=null){ExecutionDescriptor d=loadExecution(db,b.tenantId(),b.executionId()).descriptor();
            AllocationRequest a=observation.allocation();
            if(!d.descriptorSha256().equals(b.descriptorSha256())||!d.intentId().equals(a.intentId())||!d.fileId().equals(a.fileId())
                    ||!(a.kind()==AllocationKind.QUARANTINE?d.quarantineAllocationId().equals(a.allocationId()):d.scanAttemptId().equals(a.attemptId()))
                    ||a.kind()==AllocationKind.SCANNER_SCRATCH&&!d.scratchAllocationId().equals(a.allocationId()))throw unavailable();
            KernelExecutionFacts k=observation.kernelFacts();
            if(k!=null&&(!d.physicalResourceRealmId().equals(k.physicalResourceRealmId())||!d.profileSha256().equals(k.profileSha256())
                    ||!d.slotId().equals(k.slotId())))throw unavailable();
        }else if(observation.allocation().kind()==AllocationKind.SCANNER_SCRATCH){
            AllocationRequest a=observation.allocation();
            if(observation.processState()!=ProcessState.NONE_STARTED||db.fetchOptional("select execution_id from public.file_ingestion_execution where tenant_id=? and intent_id=? and scan_attempt_id=? and scratch_allocation_id=?",
                    a.tenantId(),a.intentId(),a.attemptId(),a.allocationId()).isPresent())throw unavailable();
        }
    }
    private RecordedExecution loadExecution(DSLContext db,UUID tenant,UUID id){
        return execution(db.fetchOptional("select * from public.file_ingestion_execution where tenant_id=? and execution_id=?",tenant,id)
                .orElseThrow(JooqFileIngestionLedger::unavailable));
    }
    private RecordedExecution execution(Record r){
        ExecutionDescriptor d=new ExecutionDescriptor(r.get("execution_id",UUID.class),r.get("tenant_id",UUID.class),r.get("intent_id",UUID.class),
                r.get("file_id",UUID.class),r.get("source_attempt_id",UUID.class),r.get("scan_attempt_id",UUID.class),r.get("quarantine_allocation_id",UUID.class),
                r.get("scratch_allocation_id",UUID.class),r.get("physical_resource_realm_id",UUID.class),r.get("profile_sha256",String.class),r.get("slot_id",String.class),
                r.get("claim_epoch",Long.class),r.get("claim_token",UUID.class),r.get("claim_technical_version",Long.class),r.get("canonical_descriptor",byte[].class),
                r.get("descriptor_sha256",String.class),time(r,"created_at"));codec.validateExecution(d);return new RecordedExecution(d);
    }
    private static Map<String,Object> executionRow(ExecutionDescriptor d){
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("execution_id",d.executionId());row.put("tenant_id",d.tenantId());row.put("intent_id",d.intentId());row.put("file_id",d.fileId());
        row.put("source_attempt_id",d.sourceAttemptId());row.put("scan_attempt_id",d.scanAttemptId());row.put("quarantine_allocation_id",d.quarantineAllocationId());
        row.put("scratch_allocation_id",d.scratchAllocationId());row.put("physical_resource_realm_id",d.physicalResourceRealmId());row.put("profile_sha256",d.profileSha256());
        row.put("slot_id",d.slotId());row.put("claim_epoch",d.claimEpoch());row.put("claim_token",d.claimToken());row.put("claim_technical_version",d.claimTechnicalVersion());
        row.put("canonical_descriptor",d.canonicalDescriptor());row.put("descriptor_sha256",d.descriptorSha256());row.put("created_at",d.createdAt());return row;
    }
    @Override public UUID recordGeneration(FileIngestionTxContext tx,AcceptedGeneration generation){
        return sql(tx,db->generation(db,tx.tenantId(),generation));
    }
    private UUID generation(DSLContext db,UUID tenant,AcceptedGeneration value){
        codec.verifyGeneration(value);
        var existing=db.fetchOptional("select * from public.file_scanner_generation where tenant_id=? and generation_sha256=?",tenant,value.generationSha256());
        if(existing.isPresent()){
            Record r=existing.get();
            if(!value.generationId().equals(r.get("id",UUID.class))||!value.manifestSha256().equals(r.get("manifest_sha256",String.class))
                    ||!Arrays.equals(value.canonicalManifest(),r.get("canonical_manifest",byte[].class))
                    ||!value.validatedAt().equals(time(r,"validated_at"))||!value.validUntil().equals(time(r,"valid_until")))throw unavailable();
            return value.generationId();
        }
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("tenant_id",tenant);row.put("id",value.generationId());row.put("generation_sha256",value.generationSha256());
        row.put("manifest_sha256",value.manifestSha256());row.put("canonical_manifest",value.canonicalManifest());
        row.put("validated_at",value.validatedAt());row.put("valid_until",value.validUntil());
        insert(db,"file_scanner_generation",row,"");return value.generationId();
    }
    @Override public VerifiedCheckpoint recordVerified(FileIngestionTxContext tx,TechnicalFence fence,QuarantinedObject quarantine,
                                                       ImmutableObjectAndReceipt verified,AcceptedGeneration accepted,ScanOutcome outcome){
        return sql(tx,db->{
            requireTenant(tx,fence.tenantId());
            SignedScanProofV1 proof=verified.proof();IngestionContentReceiptV2 receipt=verified.receipt();
            required(outcome);codec.requireCleanExecution(proof,outcome.execution(),outcome.kernelFacts());
            RecordedExecution actualExecution=loadExecution(db,tx.tenantId(),proof.executionId());
            if(!Arrays.equals(actualExecution.descriptor().canonicalDescriptor(),outcome.execution().descriptor().canonicalDescriptor()))throw unavailable();
            codec.requireExecutionFence(actualExecution.descriptor(),fence);
            if(!Arrays.equals(outcome.proof().canonicalPayload(),proof.canonicalPayload())
                    ||!Arrays.equals(outcome.proof().signature(),proof.signature()))throw unavailable();
            codec.verifyCurrentScan(proof,accepted);codec.verifyCurrentReceipt(receipt,proof);
            if(!fence.intentId().equals(proof.intentId())||!fence.tenantId().equals(proof.tenantId())
                    ||!fence.attemptId().equals(proof.scanAttemptId())||!quarantine.allocationId().equals(proof.quarantineAllocationId())
                    ||!quarantine.sourceAttemptId().equals(proof.sourceAttemptId()))throw unavailable();
            AllocationState sourceState=loadState(db,tx.tenantId(),quarantine.allocationId());
            RecordedAllocation sourceAllocation=sourceState.allocation();
            kind(sourceAllocation,AllocationKind.QUARANTINE);
            if(sourceAllocation.baseline()==null||sourceAllocation.baseline().kind()!=ObservationKind.COMPLETE_QUARANTINE
                    ||!sourceAllocation.request().fileId().equals(quarantine.fileId())
                    ||!sourceAllocation.request().attemptId().equals(quarantine.sourceAttemptId())
                    ||!sourceAllocation.baseline().identity().equals(quarantine.identity())
                    ||!sourceAllocation.baseline().observationSha256().equals(quarantine.observationSha256())
                    ||!proof.quarantineObservationSha256().equals(quarantine.observationSha256())
                    ||!sourceAllocation.baseline().sha256().equals(quarantine.sha256())
                    ||sourceAllocation.baseline().sizeBytes()!=quarantine.sizeBytes()
                    ||!sourceAllocation.baseline().mediaType().equals(quarantine.detectedMediaType()))throw unavailable();
            ImmutableObject immutable=verified.immutable();
            AllocationState copyState=loadState(db,tx.tenantId(),immutable.allocationId());
            kind(copyState.allocation(),AllocationKind.IMMUTABLE);requireFence(copyState.allocation().request(),fence);
            if(!copyState.allocation().request().fileId().equals(immutable.fileId()))throw unavailable();
            DurableObservation copyObservation=codec.observe(copyState.allocation().request(),ObservationKind.COMPLETE_IMMUTABLE,
                    immutable.identity(),immutable.sha256(),immutable.sizeBytes(),immutable.detectedMediaType());
            if(!receipt.immutableObservationSha256().equals(copyObservation.observationSha256()))throw unavailable();
            retain(db,copyState,fence,copyObservation);
            AllocationState receiptState=loadState(db,tx.tenantId(),verified.receiptObservation().allocation().allocationId());
            kind(receiptState.allocation(),AllocationKind.RECEIPT);requireFence(receiptState.allocation().request(),fence);
            if(!receiptState.allocation().request().equals(verified.receiptObservation().allocation()))throw unavailable();
            retain(db,receiptState,fence,verified.receiptObservation());
            generation(db,tx.tenantId(),accepted);
            Map<String,Object> proofRow=new LinkedHashMap<>();
            proofRow.put("tenant_id",proof.tenantId());proofRow.put("id",proof.proofId());proofRow.put("intent_id",proof.intentId());
            proofRow.put("file_id",proof.fileId());proofRow.put("source_attempt_id",proof.sourceAttemptId());proofRow.put("scan_attempt_id",proof.scanAttemptId());
            proofRow.put("quarantine_allocation_id",proof.quarantineAllocationId());proofRow.put("quarantine_observation_event_id",sourceState.baseline().get("id",UUID.class));
            proofRow.put("execution_id",proof.executionId());proofRow.put("descriptor_sha256",proof.descriptorSha256());
            proofRow.put("kernel_result_sha256",proof.kernelResultSha256());proofRow.put("process_state","REAPED");
            proofRow.put("generation_id",proof.generationId());proofRow.put("generation_sha256",proof.generationSha256());
            proofRow.put("quarantine_observation_sha256",proof.quarantineObservationSha256());proofRow.put("sha256",proof.sha256());
            proofRow.put("canonical_payload_sha256",IngestionProofCodec.sha256(proof.canonicalPayload()));proofRow.put("size_bytes",proof.sizeBytes());
            proofRow.put("detected_media_type",proof.detectedMediaType());proofRow.put("storage_version",proof.quarantineStorageVersion());
            proofRow.put("protocol","SCAN_PROOF_V1");proofRow.put("canonical_payload",proof.canonicalPayload());proofRow.put("signature",proof.signature());
            proofRow.put("key_id",proof.keyId());proofRow.put("started_at",proof.startedAt());proofRow.put("completed_at",proof.completedAt());
            proofRow.put("issued_at",proof.issuedAt());proofRow.put("not_before",proof.notBefore());proofRow.put("expires_at",proof.expiresAt());
            insert(db,"file_ingestion_scan_proof",proofRow,"");
            AllocationState completedCopy=loadState(db,tx.tenantId(),immutable.allocationId());
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("tenant_id",receipt.tenantId());row.put("id",receipt.receiptId());row.put("intent_id",receipt.intentId());row.put("file_id",receipt.fileId());
            row.put("source_attempt_id",receipt.sourceAttemptId());row.put("scan_attempt_id",receipt.scanAttemptId());
            row.put("immutable_allocation_id",receipt.immutableAllocationId());row.put("immutable_observation_event_id",completedCopy.baseline().get("id",UUID.class));
            row.put("scan_proof_id",receipt.scanProofId());row.put("scan_proof_payload_sha256",receipt.scanProofPayloadSha256());
            row.put("immutable_observation_sha256",receipt.immutableObservationSha256());row.put("canonical_payload_sha256",IngestionProofCodec.sha256(receipt.canonicalPayload()));
            row.put("sha256",receipt.sha256());row.put("size_bytes",receipt.sizeBytes());row.put("detected_media_type",receipt.detectedMediaType());
            row.put("storage_version",receipt.immutableStorageVersion());row.put("protocol","SCANNED_CONTENT_V2");row.put("canonical_payload",receipt.canonicalPayload());
            row.put("signature",receipt.signature());row.put("key_id",receipt.keyId());row.put("issued_at",receipt.issuedAt());
            row.put("not_before",receipt.notBefore());row.put("expires_at",receipt.expiresAt());
            insert(db,"file_ingestion_content_receipt",row,"");
            return new VerifiedCheckpoint(proof.proofId(),receipt.receiptId(),immutable.allocationId());
        });
    }
    @Override public VerifiedContentCheckpoint readVerified(FileIngestionTxContext tx,UUID intentId,VerifiedCheckpoint checkpoint){
        return sql(tx,db->readVerifiedInSnapshot(db,tx.tenantId(),intentId,checkpoint));
    }
    /** Read-only bridge seam; caller must pin and validate its existing managed tenant snapshot. */
    VerifiedContentCheckpoint readVerifiedInSnapshot(DSLContext db,UUID tenant,UUID intentId,VerifiedCheckpoint checkpoint){
            required(intentId,checkpoint);
            var persisted=new PersistedIngestionCheckpointReader(codec);
            Record proofRow=db.fetchOptional("select * from public.file_ingestion_scan_proof where tenant_id=? and id=? and intent_id=?",
                    tenant,checkpoint.proofId(),intentId).orElseThrow(JooqFileIngestionLedger::unavailable);
            SignedScanProofV1 proof=persisted.scan(proofRow);
            Record receiptRow=db.fetchOptional("select * from public.file_ingestion_content_receipt where tenant_id=? and id=? and intent_id=?",
                    tenant,checkpoint.receiptId(),intentId).orElseThrow(JooqFileIngestionLedger::unavailable);
            IngestionContentReceiptV2 receipt=persisted.receipt(receiptRow,proof);
            if(!receipt.immutableAllocationId().equals(checkpoint.immutableAllocationId()))throw unavailable();
            RecordedAllocation quarantine=loadState(db,tenant,proof.quarantineAllocationId()).allocation();
            if(quarantine.request().kind()!=AllocationKind.QUARANTINE||quarantine.baseline()==null
                    ||!quarantine.request().intentId().equals(intentId)||!quarantine.request().fileId().equals(proof.fileId())
                    ||!quarantine.request().attemptId().equals(proof.sourceAttemptId())
                    ||!quarantine.request().storageVersion().equals(proof.quarantineStorageVersion())
                    ||!quarantine.baselineEventId().equals(proofRow.get("quarantine_observation_event_id",UUID.class))
                    ||!quarantine.baseline().observationSha256().equals(proof.quarantineObservationSha256())
                    ||!quarantine.baseline().sha256().equals(proof.sha256())||quarantine.baseline().sizeBytes()!=proof.sizeBytes()
                    ||!quarantine.baseline().mediaType().equals(proof.detectedMediaType()))throw unavailable();
            RecordedAllocation immutable=loadState(db,tenant,receipt.immutableAllocationId()).allocation();
            UUID receiptAllocationId=db.fetchOptional("select id from public.file_ingestion_allocation where tenant_id=? and intent_id=? and attempt_id=? and kind=?",
                    tenant,intentId,receipt.scanAttemptId(),"RECEIPT").map(row->row.get("id",UUID.class)).orElseThrow(JooqFileIngestionLedger::unavailable);
            RecordedAllocation receiptAllocation=loadState(db,tenant,receiptAllocationId).allocation();
            requireCheckpointAllocation(immutable,receipt,AllocationKind.IMMUTABLE);
            requireCheckpointAllocation(receiptAllocation,receipt,AllocationKind.RECEIPT);
            if(!immutable.baselineEventId().equals(receiptRow.get("immutable_observation_event_id",UUID.class))
                    ||!immutable.baseline().observationSha256().equals(receipt.immutableObservationSha256())
                    ||!immutable.baseline().sha256().equals(receipt.sha256())||immutable.baseline().sizeBytes()!=receipt.sizeBytes()
                    ||!immutable.baseline().mediaType().equals(receipt.detectedMediaType())
                    ||!immutable.request().storageVersion().equals(receipt.immutableStorageVersion())
                    ||!receiptAllocation.baseline().sha256().equals(IngestionProofCodec.sha256(IngestionSignedCheckpointReader.receiptFileBytes(receipt)))
                    ||receiptAllocation.baseline().sizeBytes()!=IngestionSignedCheckpointReader.receiptFileBytes(receipt).length)throw unavailable();
            return new VerifiedContentCheckpoint(proof,receipt,immutable,receiptAllocation);
    }
    private static void requireCheckpointAllocation(RecordedAllocation a,IngestionContentReceiptV2 r,AllocationKind expected){
        if(a.request().kind()!=expected||!a.request().tenantId().equals(r.tenantId())||!a.request().intentId().equals(r.intentId())
                ||!a.request().fileId().equals(r.fileId())||!a.request().attemptId().equals(r.scanAttemptId())
                ||a.disposalTombstoneId()!=null||a.baseline()==null||a.retainedBytes()<a.baseline().sizeBytes()
                ||a.baseline().kind()!=(expected==AllocationKind.IMMUTABLE?ObservationKind.COMPLETE_IMMUTABLE:ObservationKind.RECEIPT))throw unavailable();
    }
    @Override public VerifiedContentCheckpoint publishVerified(FileIngestionTxContext tx,IngestionPublicationRequest request,AcceptedGeneration currentGeneration){
        return sql(tx,db->{
            required(request,currentGeneration);requireTenant(tx,request.tenantId());
            var checkpoint=readVerified(tx,request.intentId(),request.checkpoint());var receipt=checkpoint.receipt();
            codec.verifyCurrentScan(checkpoint.proof(),currentGeneration);codec.verifyCurrentReceipt(receipt,checkpoint.proof());
            var immutable=checkpoint.immutableAllocation();var storedReceipt=checkpoint.receiptAllocation();
            if(!request.ownerMemberId().equals(immutable.request().ownerMemberId())
                    ||!request.ownerMemberId().equals(storedReceipt.request().ownerMemberId())
                    ||immutable.finalizedFileId()!=null||storedReceipt.finalizedFileId()!=null)throw unavailable();
            var anchor=new LinkedHashMap<String,Object>();
            anchor.put("tenant_id",request.tenantId());anchor.put("intent_id",request.intentId());
            anchor.put("file_id",receipt.fileId());anchor.put("file_link_id",request.fileLinkId());anchor.put("evidence_item_id",request.evidenceItemId());
            anchor.put("finding_id",request.findingId());anchor.put("round_no",request.roundNo());anchor.put("evidence_set_id",request.evidenceSetId());
            anchor.put("owner_member_id",request.ownerMemberId());anchor.put("owner_actor_id",request.ownerActorId());
            anchor.put("publication_actor_id",request.publicationActorId());anchor.put("technical_version",request.technicalVersion());
            anchor.put("finding_version",request.findingVersion());anchor.put("evidence_revision",request.evidenceRevision());
            anchor.put("scan_proof_id",receipt.scanProofId());anchor.put("content_receipt_id",receipt.receiptId());
            anchor.put("immutable_allocation_id",immutable.request().allocationId());anchor.put("receipt_allocation_id",storedReceipt.request().allocationId());
            insert(db,"file_ingestion_publication",anchor,"");
            var file=new LinkedHashMap<String,Object>();file.put("tenant_id",request.tenantId());file.put("id",receipt.fileId());
            file.put("owner_member_id",request.ownerMemberId());file.put("owner_actor_id",request.ownerActorId());
            file.put("object_key","ingestion/"+immutable.request().rootGeneration()+"/"+immutable.request().serverObjectName());
            file.put("size_bytes",receipt.sizeBytes());file.put("mime",receipt.detectedMediaType());file.put("checksum",receipt.sha256());
            file.put("state","READY");file.put("storage_version",receipt.immutableStorageVersion());
            file.put("created_at",receipt.issuedAt());file.put("verified_at",receipt.issuedAt());insert(db,"file_object",file,"");
            var link=new LinkedHashMap<String,Object>();link.put("tenant_id",request.tenantId());link.put("id",request.fileLinkId());
            link.put("file_id",receipt.fileId());link.put("resource_type","FINDING_EVIDENCE_ITEM");link.put("resource_id",request.evidenceItemId());
            link.put("purpose","REMEDIATION_EVIDENCE");link.put("linked_by_actor_id",request.publicationActorId());link.put("linked_at",receipt.issuedAt());
            insert(db,"file_link",link,"");
            for(var allocation:java.util.List.of(immutable,storedReceipt)) {
                var state=loadState(db,tx.tenantId(),allocation.request().allocationId());
                append(db,state,EventKind.TRANSFER_FINALIZED,allocation.retainedBytes(),allocation.baseline(),
                        request.technicalVersion(),null,null,null,receipt.fileId());
            }
            return checkpoint;
        });
    }
    @Override public DisposalClaim claimDisposal(FileIngestionTxContext tx,DisposalEligibility eligibility,UUID allocationId,String expectedHash){
        return sql(tx,db->{
            requireTenant(tx,eligibility.tenantId());hash(expectedHash);AllocationState state=loadState(db,tx.tenantId(),allocationId);
            RecordedAllocation allocation=state.allocation();RecoveryObservation recovery=allocation.lastRecoveryObservation();
            if(!eligibility.terminalOrSuperseded()||recovery==null||!recovery.quiescent()||recovery.presence()==RecoveryPresence.UNKNOWN
                    ||allocation.finalizedFileId()!=null||!eligibility.intentId().equals(allocation.request().intentId())
                    ||!eligibility.attemptId().equals(allocation.request().attemptId())||!expectedHash.equals(recovery.observationSha256()))throw unavailable();
            validateRecoveryExecution(db,recovery);
            Instant now=db.fetchOne("select statement_timestamp() as now").get("now",OffsetDateTime.class).toInstant();
            if(now.isBefore(eligibility.eligibleAfter()))throw unavailable();
            long epoch=state.latest()!=null&&"DISPOSAL_CLAIMED".equals(state.latest().get("event_kind",String.class))
                    ?Math.addExact(state.latest().get("disposal_epoch",Long.class),1):1;
            UUID token=UUID.randomUUID();UUID eventId=appendRecovery(db,state,EventKind.DISPOSAL_CLAIMED,allocation.retainedBytes(),recovery,
                    eligibility.technicalVersion(),token,epoch,eligibility.eligibilityPolicySha256(),eligibility.technicalVersion());
            UUID tombstone=allocation.disposalTombstoneId()==null?eventId:allocation.disposalTombstoneId();
            return new DisposalClaim(loadState(db,tx.tenantId(),allocationId).allocation(),tombstone,token,epoch,
                    eligibility.technicalVersion(),eligibility.eligibilityPolicySha256());
        });
    }
    @Override public RecordedAllocation confirmDisposal(FileIngestionTxContext tx,DisposalClaim claim,DisposalObservation observation,long expectedTechnicalVersion){
        return sql(tx,db->{
            requireTenant(tx,claim.tenantId());nonnegative(expectedTechnicalVersion);AllocationState state=loadState(db,tx.tenantId(),claim.allocationId());
            DisposalClaim supplied=observation.claim();
            if(!claim.token().equals(supplied.token())||claim.epoch()!=supplied.epoch()||!claim.tombstoneId().equals(supplied.tombstoneId())
                    ||!claim.allocation().request().equals(supplied.allocation().request())||claim.technicalVersion()!=supplied.technicalVersion()
                    ||!claim.eligibilityPolicySha256().equals(supplied.eligibilityPolicySha256())
                    ||!claim.observationSha256().equals(supplied.observationSha256())
                    ||state.latest()==null||!"DISPOSAL_CLAIMED".equals(state.latest().get("event_kind",String.class))
                    ||!claim.token().equals(state.latest().get("disposal_token",UUID.class))||claim.epoch()!=state.latest().get("disposal_epoch",Long.class)
                    ||claim.technicalVersion()!=state.latest().get("disposal_claim_technical_version",Long.class)
                    ||!claim.eligibilityPolicySha256().equals(state.latest().get("eligibility_policy_sha256",String.class))
                    ||!claim.tombstoneId().equals(state.allocation().disposalTombstoneId())
                    ||!claim.observationSha256().equals(state.allocation().lastRecoveryObservation().observationSha256())
                    ||!Objects.equals(claim.allocation().baselineEventId(),state.allocation().baselineEventId())
                    ||!Objects.equals(claim.allocation().baseline()==null?null:claim.allocation().baseline().observationSha256(),
                        state.allocation().baseline()==null?null:state.allocation().baseline().observationSha256()))throw unavailable();
            RecoveryObservation absence=observation.observation();codec.validateRecovery(absence);requireRecoveryTarget(state,absence.binding(),absence);
            if(absence.binding().expectedCurrentTechnicalVersion()!=expectedTechnicalVersion
                    ||!Objects.equals(absence.binding().executionId(),claim.allocation().lastRecoveryObservation().binding().executionId())
                    ||!Objects.equals(absence.binding().descriptorSha256(),claim.allocation().lastRecoveryObservation().binding().descriptorSha256())
                    ||!Objects.equals(absence.stopWriteFenceSha256(),claim.allocation().lastRecoveryObservation().stopWriteFenceSha256()))throw unavailable();
            validateRecoveryExecution(db,absence);
            appendRecovery(db,state,EventKind.DISPOSAL_CONFIRMED,0,absence,expectedTechnicalVersion,claim.token(),claim.epoch(),
                    claim.eligibilityPolicySha256(),claim.technicalVersion());
            return loadState(db,tx.tenantId(),claim.allocationId()).allocation();
        });
    }

    private AllocationState loadState(DSLContext db,UUID tenant,UUID id){
        Record row=db.fetchOptional("select * from public.file_ingestion_allocation where tenant_id=? and id=?",tenant,id).orElseThrow(JooqFileIngestionLedger::unavailable);
        AllocationRequest request=allocation(row);Record baselineRow=null,last=null;DurableObservation baseline=null;RecoveryObservation recovery=null;
        UUID tombstone=null,finalized=null;long number=0,charge=request.reservedBytes();UUID predecessor=null;
        try(var events=db.resultQuery("select * from public.file_ingestion_allocation_event where tenant_id=? and allocation_id=? order by event_no",tenant,id).fetchSize(64).fetchLazy()){
            for(Record event:events){
                number=Math.addExact(number,1);if(event.get("event_no",Long.class)!=number||!Objects.equals(predecessor,event.get("predecessor_event_id",UUID.class)))throw unavailable();
                EventKind kind=persistedEnum(EventKind.class,event.get("event_kind",String.class));long retained=event.get("retained_bytes",Long.class);
                if(last!=null&&java.util.Set.of("DISPOSAL_CONFIRMED","TRANSFER_FINALIZED").contains(last.get("event_kind",String.class)))throw unavailable();
                if(!Objects.equals(baselineRow==null?null:baselineRow.get("id",UUID.class),event.get("baseline_event_id",UUID.class))
                        ||!Objects.equals(baseline==null?null:baseline.observationSha256(),event.get("baseline_observation_sha256",String.class)))throw unavailable();
                if(kind==EventKind.RETAIN){
                    if(tombstone!=null||retained!=charge)throw unavailable();
                    DurableObservation current=observation(request,event);codec.validateObservation(current);
                    if(current.objectAbsent())throw unavailable();
                    if(baseline!=null){if(!sameObservation(baseline,current))throw unavailable();}
                    else if(current.kind()!=ObservationKind.SCRATCH&&current.kind()!=ObservationKind.UNTRUSTED_PARTIAL){baseline=current;baselineRow=event;}
                }else if(kind==EventKind.RESIZE_CONFIRMED||kind==EventKind.RECOVERY_OBSERVED||kind==EventKind.DISPOSAL_CLAIMED||kind==EventKind.DISPOSAL_CONFIRMED){
                    RecoveryObservation current=recoveryObservation(request,event);codec.validateRecovery(current);
                    if(!Objects.equals(current.baselineEventId(),baselineRow==null?null:baselineRow.get("id",UUID.class))
                            ||!Objects.equals(current.baselineObservationSha256(),baseline==null?null:baseline.observationSha256()))throw unavailable();
                    if(kind==EventKind.RESIZE_CONFIRMED){
                        if(tombstone!=null||baseline==null||request.kind()==AllocationKind.SCANNER_SCRATCH
                                ||current.presence()!=RecoveryPresence.PRESENT_COMPLETE_UNCHANGED||!current.quiescent()||!current.writerQuiescent()
                                ||retained<1||retained!=baseline.sizeBytes()||retained>charge||current.binding().targetEventNo()!=number-1
                                ||!Objects.equals(current.binding().targetEventSha256(),last==null?null:last.get("observation_sha256",String.class)))throw unavailable();
                    }else if(kind==EventKind.RECOVERY_OBSERVED){
                        if(tombstone!=null||retained!=charge||current.binding().targetEventNo()!=number-1
                                ||!Objects.equals(current.binding().targetEventSha256(),last==null?null:last.get("observation_sha256",String.class)))throw unavailable();
                    }else if(kind==EventKind.DISPOSAL_CLAIMED){
                        UUID token=event.get("disposal_token",UUID.class);Long epoch=event.get("disposal_epoch",Long.class);
                        if(recovery==null||!Arrays.equals(recovery.canonicalObservation(),current.canonicalObservation())||!current.quiescent()
                                ||current.presence()==RecoveryPresence.UNKNOWN||retained!=charge||token==null||epoch==null
                                ||event.get("eligibility_policy_sha256")==null||event.get("disposal_claim_technical_version")==null
                                ||(tombstone==null&&epoch!=1)||(tombstone!=null&&(last==null||!"DISPOSAL_CLAIMED".equals(last.get("event_kind",String.class))
                                ||epoch!=Math.addExact(last.get("disposal_epoch",Long.class),1)||token.equals(last.get("disposal_token",UUID.class)))))throw unavailable();
                        if(tombstone==null)tombstone=event.get("id",UUID.class);
                    }else if(tombstone==null||last==null||!"DISPOSAL_CLAIMED".equals(last.get("event_kind",String.class))
                            ||!Objects.equals(event.get("disposal_token"),last.get("disposal_token"))||!Objects.equals(event.get("disposal_epoch"),last.get("disposal_epoch"))
                            ||!Objects.equals(event.get("disposal_claim_technical_version"),last.get("disposal_claim_technical_version"))
                            ||!Objects.equals(event.get("eligibility_policy_sha256"),last.get("eligibility_policy_sha256"))
                            ||retained!=0||current.presence()!=RecoveryPresence.ABSENT||!current.quiescent()
                            ||current.binding().targetEventNo()!=number-1||!Objects.equals(current.binding().targetEventSha256(),last.get("observation_sha256",String.class)))throw unavailable();
                    recovery=current;
                }else if(kind==EventKind.TRANSFER_FINALIZED){
                    DurableObservation transferred=observation(request,event);codec.validateObservation(transferred);
                    if(tombstone!=null||baseline==null||retained!=charge
                            ||request.kind()!=AllocationKind.IMMUTABLE&&request.kind()!=AllocationKind.RECEIPT
                            ||event.get("disposal_token")!=null||event.get("disposal_epoch")!=null
                            ||event.get("disposal_claim_technical_version")!=null||event.get("eligibility_policy_sha256")!=null
                            ||!request.fileId().equals(event.get("finalized_file_id",UUID.class))
                            ||!sameObservation(baseline,transferred))throw unavailable();
                    finalized=request.fileId();
                }else throw unavailable();
                if(kind!=EventKind.TRANSFER_FINALIZED&&event.get("finalized_file_id")!=null)throw unavailable();
                charge=retained;predecessor=event.get("id",UUID.class);last=event;
            }
        }
        return new AllocationState(new RecordedAllocation(request,number,predecessor,last==null?null:last.get("observation_sha256",String.class),charge,baseline,baselineRow==null?null:baselineRow.get("id",UUID.class),tombstone,finalized,recovery),baselineRow,last);
    }
    private UUID append(DSLContext db,AllocationState state,EventKind kind,long retained,DurableObservation observation,
                        long technicalVersion,UUID token,Long epoch,String policy){
        return append(db,state,kind,retained,observation,technicalVersion,token,epoch,policy,null);
    }
    private UUID append(DSLContext db,AllocationState state,EventKind kind,long retained,DurableObservation observation,
                        long technicalVersion,UUID token,Long epoch,String policy,UUID finalizedFileId){
        codec.validateObservation(observation);AllocationRequest a=state.allocation().request();
        if(!a.equals(observation.allocation()))throw unavailable();
        UUID id=UUID.randomUUID();Map<String,Object> row=new LinkedHashMap<>();
        row.put("tenant_id",a.tenantId());row.put("id",id);row.put("allocation_id",a.allocationId());
        row.put("event_no",Math.addExact(state.allocation().eventNo(),1));row.put("predecessor_event_id",state.allocation().eventId());
        row.put("event_kind",kind.name());row.put("retained_bytes",retained);row.put("observed_at",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        row.put("canonical_observation",observation.canonicalObservation());row.put("observation_sha256",observation.observationSha256());
        row.put("baseline_event_id",state.baseline()==null?null:state.baseline().get("id",UUID.class));
        row.put("baseline_observation_sha256",state.baseline()==null?null:state.allocation().baseline().observationSha256());
        row.put("disposal_token",token);row.put("disposal_epoch",epoch);row.put("finalized_file_id",finalizedFileId);
        row.put("technical_version",technicalVersion);row.put("eligibility_policy_sha256",policy);
        row.put("intent_id",a.intentId());row.put("file_id",a.fileId());row.put("attempt_id",a.attemptId());row.put("allocation_kind",a.kind().name());
        row.put("reservation_lease_epoch",a.reservationLeaseEpoch());row.put("observation_kind",observation.kind().name());
        row.put("observed_root_generation_sha256",a.rootGeneration());row.put("observed_server_object_name",a.serverObjectName());
        row.put("observed_storage_version",a.storageVersion());row.put("observed_sha256",observation.sha256());row.put("observed_size_bytes",observation.sizeBytes());
        row.put("observed_media_type",observation.mediaType());ObjectIdentity identity=observation.identity();
        row.put("observed_file_store_identity",identity==null?null:identity.fileStoreIdentity());row.put("observed_file_key",identity==null?null:identity.fileKey());
        row.put("observed_unix_device",identity==null?null:identity.unixDevice());row.put("observed_unix_inode",identity==null?null:identity.unixInode());
        row.put("observed_link_count",identity==null?null:Math.toIntExact(identity.linkCount()));row.put("durability_completed_at",identity==null?null:identity.durabilityCompletedAt());
        row.put("object_absent",observation.objectAbsent());row.put("absence_durable_at",observation.absenceDurableAt());
        row.put("child_reaped",observation.childReaped());row.put("child_reap_evidence_sha256",observation.childReapEvidenceSha256());
        insert(db,"file_ingestion_allocation_event",row,"");return id;
    }
    private UUID appendRecovery(DSLContext db,AllocationState state,EventKind kind,long retained,RecoveryObservation o,
                                long version,UUID token,Long epoch,String policy,Long originalClaimVersion){
        codec.validateRecovery(o);AllocationRequest a=state.allocation().request();
        if(!a.equals(o.allocation()))throw unavailable();
        UUID id=UUID.randomUUID();Map<String,Object> row=new LinkedHashMap<>();RecoveryBinding b=o.binding();
        row.put("tenant_id",a.tenantId());row.put("id",id);row.put("allocation_id",a.allocationId());
        row.put("event_no",Math.addExact(state.allocation().eventNo(),1));row.put("predecessor_event_id",state.allocation().eventId());
        row.put("event_kind",kind.name());row.put("retained_bytes",retained);row.put("observed_at",o.observedAt());
        row.put("canonical_observation",o.canonicalObservation());row.put("observation_sha256",o.observationSha256());
        row.put("baseline_event_id",o.baselineEventId());row.put("baseline_observation_sha256",o.baselineObservationSha256());
        row.put("disposal_token",token);row.put("disposal_epoch",epoch);row.put("disposal_claim_technical_version",originalClaimVersion);
        row.put("finalized_file_id",null);row.put("technical_version",version);row.put("eligibility_policy_sha256",policy);
        row.put("intent_id",a.intentId());row.put("file_id",a.fileId());row.put("attempt_id",a.attemptId());row.put("allocation_kind",a.kind().name());
        row.put("reservation_lease_epoch",a.reservationLeaseEpoch());row.put("observation_kind",a.kind()==AllocationKind.SCANNER_SCRATCH?"SCRATCH":"UNTRUSTED_PARTIAL");
        row.put("observed_root_generation_sha256",a.rootGeneration());row.put("observed_server_object_name",a.serverObjectName());row.put("observed_storage_version",a.storageVersion());
        ObjectIdentity i=o.identity();row.put("observed_sha256",null);row.put("observed_media_type",null);
        row.put("observed_size_bytes",i==null?null:i.measuredBytes());row.put("observed_file_store_identity",i==null?null:i.fileStoreIdentity());
        row.put("observed_file_key",i==null?null:i.fileKey());row.put("observed_unix_device",i==null?null:i.unixDevice());
        row.put("observed_unix_inode",i==null?null:i.unixInode());row.put("observed_link_count",i==null?null:Math.toIntExact(i.linkCount()));
        row.put("durability_completed_at",i==null?null:i.durabilityCompletedAt());row.put("object_absent",o.presence()==RecoveryPresence.ABSENT);
        row.put("absence_durable_at",o.presence()==RecoveryPresence.ABSENT?o.observedAt():null);row.put("child_reaped",false);row.put("child_reap_evidence_sha256",null);
        row.put("recovery_id",b.recoveryId());row.put("expected_current_technical_version",b.expectedCurrentTechnicalVersion());
        row.put("expected_intent_lease_epoch",b.expectedIntentLeaseEpoch());row.put("target_event_no",b.targetEventNo());row.put("target_event_sha256",b.targetEventSha256());
        row.put("execution_id",b.executionId());row.put("descriptor_sha256",b.descriptorSha256());row.put("recovery_reason",b.reason().name());
        row.put("retention_policy_sha256",b.retentionPolicySha256());row.put("recovery_presence",o.presence().name());row.put("process_state",o.processState().name());
        row.put("writer_quiescent",o.writerQuiescent());row.put("writer_quiescence_sha256",o.writerQuiescenceSha256());
        row.put("writer_quiescence_observed_at",o.writerQuiescenceObservedAt());row.put("stop_write_fence_sha256",o.stopWriteFenceSha256());
        KernelExecutionFacts k=o.kernelFacts();row.put("kernel_facts_present",k!=null);
        row.put("kernel_challenge",k==null?null:k.challenge());
        row.put("kernel_physical_resource_realm_id",k==null?null:k.physicalResourceRealmId());
        row.put("kernel_slot_id",k==null?null:k.slotId());
        row.put("kernel_profile_sha256",k==null?null:k.profileSha256());
        row.put("kernel_boot_id",k==null?null:k.bootId());
        row.put("kernel_supervisor_pid",k==null?null:k.supervisorPid());
        row.put("kernel_supervisor_start_ticks",k==null?null:k.supervisorStartTicks());
        row.put("kernel_child_pid",k==null?null:k.childPid());
        row.put("kernel_child_start_ticks",k==null?null:k.childStartTicks());
        row.put("kernel_child_absent_reason",k==null?null:k.childAbsentReason());
        row.put("kernel_pid_namespace",k==null?null:k.pidNamespace());
        row.put("kernel_mount_namespace",k==null?null:k.mountNamespace());
        row.put("kernel_net_namespace",k==null?null:k.netNamespace());
        row.put("kernel_ipc_namespace",k==null?null:k.ipcNamespace());
        row.put("kernel_uts_namespace",k==null?null:k.utsNamespace());
        row.put("kernel_cgroup_name",k==null?null:k.cgroupName());
        row.put("kernel_cgroup_mount_id",k==null?null:k.cgroupMountId());
        row.put("kernel_cgroup_inode",k==null?null:k.cgroupInode());
        row.put("kernel_pids_policy_sha256",k==null?null:k.pidsPolicySha256());
        row.put("kernel_pids_current",k==null?null:k.pidsCurrent());
        row.put("kernel_populated",k==null?null:k.populated());
        row.put("kernel_pids_controller_continuous",k==null?null:k.pidsControllerContinuous());
        row.put("kernel_no_migration_or_future_entry",k==null?null:k.noMigrationOrFutureEntry());
        row.put("kernel_slot_lock_exclusive",k==null?null:k.slotLockExclusive());
        row.put("kernel_slot_lock_identity_sha256",k==null?null:k.slotLockIdentitySha256());
        row.put("kernel_permanent_stop",k==null?null:k.permanentStop());
        row.put("kernel_stop_sha256",k==null?null:k.stopSha256());
        row.put("kernel_child_observed",k==null?null:k.childObserved());
        row.put("kernel_image_verified",k==null?null:k.imageVerified());
        row.put("kernel_wait_reaped",k==null?null:k.waitReaped());
        row.put("kernel_input_drained",k==null?null:k.inputDrained());
        row.put("kernel_stdout_drained",k==null?null:k.stdoutDrained());
        row.put("kernel_stderr_drained",k==null?null:k.stderrDrained());
        row.put("kernel_interrupted_output_discarded",k==null?null:k.interruptedOutputDiscarded());
        row.put("kernel_process_state",k==null?null:k.processState().name());
        row.put("kernel_control_sha256",k==null?null:k.kernelControlSha256());
        row.put("kernel_report_sha256",k==null?null:k.reportSha256());
        row.put("kernel_observed_at",k==null?null:k.observedAt());
        insert(db,"file_ingestion_allocation_event",row,"");return id;
    }
    private static RecoveryObservation recoveryObservation(AllocationRequest a,Record r){
        RecoveryBinding b=new RecoveryBinding(a.tenantId(),a.intentId(),a.allocationId(),r.get("recovery_id",UUID.class),
                r.get("expected_current_technical_version",Long.class),r.get("expected_intent_lease_epoch",Long.class),
                r.get("target_event_no",Long.class),r.get("target_event_sha256",String.class),r.get("execution_id",UUID.class),
                r.get("descriptor_sha256",String.class),persistedEnum(RecoveryReason.class,r.get("recovery_reason",String.class)),r.get("retention_policy_sha256",String.class));
        KernelExecutionFacts k=null;
        if(Boolean.TRUE.equals(r.get("kernel_facts_present",Boolean.class)))k=new KernelExecutionFacts(b.executionId(),b.descriptorSha256(),
                r.get("kernel_challenge",String.class),r.get("kernel_physical_resource_realm_id",UUID.class),r.get("kernel_slot_id",String.class),r.get("kernel_profile_sha256",String.class),
                r.get("kernel_boot_id",UUID.class),r.get("kernel_supervisor_pid",Long.class),r.get("kernel_supervisor_start_ticks",Long.class),r.get("kernel_child_pid",Long.class),
                r.get("kernel_child_start_ticks",Long.class),r.get("kernel_child_absent_reason",String.class),r.get("kernel_pid_namespace",Long.class),r.get("kernel_mount_namespace",Long.class),
                r.get("kernel_net_namespace",Long.class),r.get("kernel_ipc_namespace",Long.class),r.get("kernel_uts_namespace",Long.class),r.get("kernel_cgroup_name",UUID.class),
                r.get("kernel_cgroup_mount_id",Long.class),r.get("kernel_cgroup_inode",Long.class),r.get("kernel_pids_policy_sha256",String.class),r.get("kernel_pids_current",Long.class),
                r.get("kernel_populated",Boolean.class),r.get("kernel_pids_controller_continuous",Boolean.class),r.get("kernel_no_migration_or_future_entry",Boolean.class),r.get("kernel_slot_lock_exclusive",Boolean.class),
                r.get("kernel_slot_lock_identity_sha256",String.class),r.get("kernel_permanent_stop",Boolean.class),r.get("kernel_stop_sha256",String.class),r.get("kernel_child_observed",Boolean.class),
                r.get("kernel_image_verified",Boolean.class),r.get("kernel_wait_reaped",Boolean.class),r.get("kernel_input_drained",Boolean.class),r.get("kernel_stdout_drained",Boolean.class),
                r.get("kernel_stderr_drained",Boolean.class),r.get("kernel_interrupted_output_discarded",Boolean.class),persistedEnum(ProcessState.class,r.get("kernel_process_state",String.class)),r.get("kernel_control_sha256",String.class),
                r.get("kernel_report_sha256",String.class),time(r,"kernel_observed_at"));
        else {
            if(r.get("kernel_challenge")!=null)throw unavailable();
            if(r.get("kernel_physical_resource_realm_id")!=null)throw unavailable();
            if(r.get("kernel_slot_id")!=null)throw unavailable();
            if(r.get("kernel_profile_sha256")!=null)throw unavailable();
            if(r.get("kernel_boot_id")!=null)throw unavailable();
            if(r.get("kernel_supervisor_pid")!=null)throw unavailable();
            if(r.get("kernel_supervisor_start_ticks")!=null)throw unavailable();
            if(r.get("kernel_child_pid")!=null)throw unavailable();
            if(r.get("kernel_child_start_ticks")!=null)throw unavailable();
            if(r.get("kernel_child_absent_reason")!=null)throw unavailable();
            if(r.get("kernel_pid_namespace")!=null)throw unavailable();
            if(r.get("kernel_mount_namespace")!=null)throw unavailable();
            if(r.get("kernel_net_namespace")!=null)throw unavailable();
            if(r.get("kernel_ipc_namespace")!=null)throw unavailable();
            if(r.get("kernel_uts_namespace")!=null)throw unavailable();
            if(r.get("kernel_cgroup_name")!=null)throw unavailable();
            if(r.get("kernel_cgroup_mount_id")!=null)throw unavailable();
            if(r.get("kernel_cgroup_inode")!=null)throw unavailable();
            if(r.get("kernel_pids_policy_sha256")!=null)throw unavailable();
            if(r.get("kernel_pids_current")!=null)throw unavailable();
            if(r.get("kernel_populated")!=null)throw unavailable();
            if(r.get("kernel_pids_controller_continuous")!=null)throw unavailable();
            if(r.get("kernel_no_migration_or_future_entry")!=null)throw unavailable();
            if(r.get("kernel_slot_lock_exclusive")!=null)throw unavailable();
            if(r.get("kernel_slot_lock_identity_sha256")!=null)throw unavailable();
            if(r.get("kernel_permanent_stop")!=null)throw unavailable();
            if(r.get("kernel_stop_sha256")!=null)throw unavailable();
            if(r.get("kernel_child_observed")!=null)throw unavailable();
            if(r.get("kernel_image_verified")!=null)throw unavailable();
            if(r.get("kernel_wait_reaped")!=null)throw unavailable();
            if(r.get("kernel_input_drained")!=null)throw unavailable();
            if(r.get("kernel_stdout_drained")!=null)throw unavailable();
            if(r.get("kernel_stderr_drained")!=null)throw unavailable();
            if(r.get("kernel_interrupted_output_discarded")!=null)throw unavailable();
            if(r.get("kernel_process_state")!=null)throw unavailable();
            if(r.get("kernel_control_sha256")!=null)throw unavailable();
            if(r.get("kernel_report_sha256")!=null)throw unavailable();
            if(r.get("kernel_observed_at")!=null)throw unavailable();
        }
        String[] identityFields={"observed_file_store_identity","observed_file_key","observed_unix_device","observed_unix_inode","observed_link_count","durability_completed_at"};
        long known=Arrays.stream(identityFields).filter(field->r.get(field)!=null).count();
        if(known!=0&&known!=identityFields.length||known==0&&r.get("observed_size_bytes")!=null)throw unavailable();
        ObjectIdentity i=known==0?null:new ObjectIdentity(a.rootGeneration(),a.serverObjectName(),a.storageVersion(),
                r.get("observed_file_store_identity",String.class),r.get("observed_file_key",String.class),number(r,"observed_unix_device"),
                number(r,"observed_unix_inode"),r.get("observed_link_count",Long.class),r.get("observed_size_bytes",Long.class),time(r,"durability_completed_at"));
        RecoveryObservation o=new RecoveryObservation(b,a,persistedEnum(RecoveryPresence.class,r.get("recovery_presence",String.class)),
                persistedEnum(ProcessState.class,r.get("process_state",String.class)),r.get("writer_quiescent",Boolean.class),r.get("writer_quiescence_sha256",String.class),
                time(r,"writer_quiescence_observed_at"),k,i,r.get("baseline_event_id",UUID.class),r.get("baseline_observation_sha256",String.class),
                r.get("stop_write_fence_sha256",String.class),r.get("canonical_observation",byte[].class),r.get("observation_sha256",String.class),time(r,"observed_at"));
        if(!Objects.equals(r.get("observation_kind",String.class),a.kind()==AllocationKind.SCANNER_SCRATCH?"SCRATCH":"UNTRUSTED_PARTIAL")
                ||r.get("observed_sha256")!=null||r.get("observed_media_type")!=null||Boolean.TRUE.equals(r.get("child_reaped",Boolean.class))
                ||r.get("child_reap_evidence_sha256")!=null||!Objects.equals(r.get("object_absent",Boolean.class),o.presence()==RecoveryPresence.ABSENT)
                ||!Objects.equals(time(r,"absence_durable_at"),o.presence()==RecoveryPresence.ABSENT?o.observedAt():null)
                ||!a.intentId().equals(r.get("intent_id",UUID.class))||!a.fileId().equals(r.get("file_id",UUID.class))
                ||!a.attemptId().equals(r.get("attempt_id",UUID.class))||!a.kind().name().equals(r.get("allocation_kind",String.class))
                ||a.reservationLeaseEpoch()!=r.get("reservation_lease_epoch",Long.class)||!a.rootGeneration().equals(r.get("observed_root_generation_sha256",String.class))
                ||!a.serverObjectName().equals(r.get("observed_server_object_name",String.class))||!a.storageVersion().equals(r.get("observed_storage_version",String.class)))throw unavailable();
        return o;
    }
    private static Map<String,Object> allocationRow(AllocationRequest a){
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("tenant_id",a.tenantId());row.put("id",a.allocationId());row.put("intent_id",a.intentId());row.put("file_id",a.fileId());
        row.put("attempt_id",a.attemptId());row.put("owner_member_id",a.ownerMemberId());row.put("kind",a.kind().name());
        row.put("root_generation_sha256",a.rootGeneration());row.put("server_object_name",a.serverObjectName());row.put("storage_version",a.storageVersion());
        row.put("reserved_bytes",a.reservedBytes());row.put("reservation_lease_epoch",a.reservationLeaseEpoch());row.put("created_at",a.createdAt());return row;
    }
    private static AllocationRequest allocation(Record r){
        return new AllocationRequest(r.get("tenant_id",UUID.class),r.get("intent_id",UUID.class),r.get("file_id",UUID.class),r.get("owner_member_id",UUID.class),
                r.get("attempt_id",UUID.class),r.get("id",UUID.class),persistedEnum(AllocationKind.class,r.get("kind",String.class)),r.get("root_generation_sha256",String.class),
                r.get("server_object_name",String.class),r.get("storage_version",String.class),r.get("reserved_bytes",Long.class),r.get("reservation_lease_epoch",Long.class),time(r,"created_at"));
    }
    private static void requireNoRecoveryProjection(Record r){
        if(r.get("recovery_id")!=null)throw unavailable();
        if(r.get("expected_current_technical_version")!=null)throw unavailable();
        if(r.get("expected_intent_lease_epoch")!=null)throw unavailable();
        if(r.get("target_event_no")!=null)throw unavailable();
        if(r.get("target_event_sha256")!=null)throw unavailable();
        if(r.get("execution_id")!=null)throw unavailable();
        if(r.get("descriptor_sha256")!=null)throw unavailable();
        if(r.get("recovery_reason")!=null)throw unavailable();
        if(r.get("retention_policy_sha256")!=null)throw unavailable();
        if(r.get("recovery_presence")!=null)throw unavailable();
        if(r.get("process_state")!=null)throw unavailable();
        if(r.get("writer_quiescence_sha256")!=null)throw unavailable();
        if(r.get("writer_quiescence_observed_at")!=null)throw unavailable();
        if(r.get("stop_write_fence_sha256")!=null)throw unavailable();
        if(r.get("kernel_challenge")!=null)throw unavailable();
        if(r.get("kernel_physical_resource_realm_id")!=null)throw unavailable();
        if(r.get("kernel_slot_id")!=null)throw unavailable();
        if(r.get("kernel_profile_sha256")!=null)throw unavailable();
        if(r.get("kernel_boot_id")!=null)throw unavailable();
        if(r.get("kernel_supervisor_pid")!=null)throw unavailable();
        if(r.get("kernel_supervisor_start_ticks")!=null)throw unavailable();
        if(r.get("kernel_child_pid")!=null)throw unavailable();
        if(r.get("kernel_child_start_ticks")!=null)throw unavailable();
        if(r.get("kernel_child_absent_reason")!=null)throw unavailable();
        if(r.get("kernel_pid_namespace")!=null)throw unavailable();
        if(r.get("kernel_mount_namespace")!=null)throw unavailable();
        if(r.get("kernel_net_namespace")!=null)throw unavailable();
        if(r.get("kernel_ipc_namespace")!=null)throw unavailable();
        if(r.get("kernel_uts_namespace")!=null)throw unavailable();
        if(r.get("kernel_cgroup_name")!=null)throw unavailable();
        if(r.get("kernel_cgroup_mount_id")!=null)throw unavailable();
        if(r.get("kernel_cgroup_inode")!=null)throw unavailable();
        if(r.get("kernel_pids_policy_sha256")!=null)throw unavailable();
        if(r.get("kernel_pids_current")!=null)throw unavailable();
        if(r.get("kernel_populated")!=null)throw unavailable();
        if(r.get("kernel_pids_controller_continuous")!=null)throw unavailable();
        if(r.get("kernel_no_migration_or_future_entry")!=null)throw unavailable();
        if(r.get("kernel_slot_lock_exclusive")!=null)throw unavailable();
        if(r.get("kernel_slot_lock_identity_sha256")!=null)throw unavailable();
        if(r.get("kernel_permanent_stop")!=null)throw unavailable();
        if(r.get("kernel_stop_sha256")!=null)throw unavailable();
        if(r.get("kernel_child_observed")!=null)throw unavailable();
        if(r.get("kernel_image_verified")!=null)throw unavailable();
        if(r.get("kernel_wait_reaped")!=null)throw unavailable();
        if(r.get("kernel_input_drained")!=null)throw unavailable();
        if(r.get("kernel_stdout_drained")!=null)throw unavailable();
        if(r.get("kernel_stderr_drained")!=null)throw unavailable();
        if(r.get("kernel_interrupted_output_discarded")!=null)throw unavailable();
        if(r.get("kernel_process_state")!=null)throw unavailable();
        if(r.get("kernel_control_sha256")!=null)throw unavailable();
        if(r.get("kernel_report_sha256")!=null)throw unavailable();
        if(r.get("kernel_observed_at")!=null)throw unavailable();
        if(Boolean.TRUE.equals(r.get("writer_quiescent",Boolean.class))||Boolean.TRUE.equals(r.get("kernel_facts_present",Boolean.class)))throw unavailable();
    }
    private static DurableObservation observation(AllocationRequest a,Record r){
        requireNoRecoveryProjection(r);
        if(!a.intentId().equals(r.get("intent_id",UUID.class))||!a.fileId().equals(r.get("file_id",UUID.class))
                ||!a.attemptId().equals(r.get("attempt_id",UUID.class))||!a.kind().name().equals(r.get("allocation_kind",String.class))
                ||a.reservationLeaseEpoch()!=r.get("reservation_lease_epoch",Long.class)||!a.rootGeneration().equals(r.get("observed_root_generation_sha256",String.class))
                ||!a.serverObjectName().equals(r.get("observed_server_object_name",String.class))||!a.storageVersion().equals(r.get("observed_storage_version",String.class)))throw unavailable();
        String[] identityFields={"observed_file_store_identity","observed_file_key","observed_unix_device","observed_unix_inode","observed_link_count","durability_completed_at"};
        long known=Arrays.stream(identityFields).filter(field->r.get(field)!=null).count();
        if(known!=0&&known!=identityFields.length)throw unavailable();
        ObjectIdentity identity=known==0?null:new ObjectIdentity(a.rootGeneration(),a.serverObjectName(),a.storageVersion(),r.get("observed_file_store_identity",String.class),
                r.get("observed_file_key",String.class),number(r,"observed_unix_device"),number(r,"observed_unix_inode"),r.get("observed_link_count",Long.class),
                r.get("observed_size_bytes",Long.class),time(r,"durability_completed_at"));
        return new DurableObservation(a,persistedEnum(ObservationKind.class,r.get("observation_kind",String.class)),identity,r.get("observed_sha256",String.class),
                r.get("observed_size_bytes",Long.class),r.get("observed_media_type",String.class),r.get("object_absent",Boolean.class),time(r,"absence_durable_at"),
                r.get("child_reaped",Boolean.class),r.get("child_reap_evidence_sha256",String.class),r.get("canonical_observation",byte[].class),r.get("observation_sha256",String.class));
    }
    private static boolean sameContentProjection(DurableObservation a,DurableObservation b){
        return a.allocation().equals(b.allocation())&&a.kind()==b.kind()&&Objects.equals(a.identity(),b.identity())
                &&Objects.equals(a.sha256(),b.sha256())&&Objects.equals(a.sizeBytes(),b.sizeBytes())&&Objects.equals(a.mediaType(),b.mediaType());
    }
    private static void insert(DSLContext db,String table,Map<String,Object> row,String suffix){
        if(!java.util.Set.of("file_object","file_link","file_ingestion_publication","file_ingestion_execution","file_ingestion_allocation","file_ingestion_allocation_event","file_scanner_generation","file_ingestion_scan_proof","file_ingestion_content_receipt").contains(table))throw new IllegalArgumentException("Invalid files table");
        // Plain-SQL jOOQ bindings may encode OffsetDateTime as VARCHAR. Cast every temporal
        // column explicitly, including nullable observations; PostgreSQL then resolves the real type.
        var temporal=java.util.Set.of("created_at","observed_at","validated_at","valid_until","started_at","completed_at",
                "issued_at","not_before","expires_at","durability_completed_at","absence_durable_at",
                "writer_quiescence_observed_at","kernel_observed_at","verified_at","linked_at");
        String placeholders=String.join(",",row.keySet().stream().map(name->temporal.contains(name)?"cast(? as timestamptz)":"?").toList());
        Object[] values=row.values().stream().map(value->value instanceof Instant time?OffsetDateTime.ofInstant(time,ZoneOffset.UTC):value).toArray();
        db.execute("insert into public."+table+" ("+String.join(",",row.keySet())+") values ("+placeholders+")"+suffix,values);
    }
    private static Instant time(Record r,String field){OffsetDateTime value=r.get(field,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private static long number(Record r,String field){return r.get(field,BigDecimal.class).longValueExact();}
    private static void requireTenant(FileIngestionTxContext context,UUID tenant){if(!context.tenantId().equals(tenant))throw unavailable();}
    private static <E extends Enum<E>> E persistedEnum(Class<E> type,String value){
        if(value==null)throw unavailable();
        try{return Enum.valueOf(type,value);}catch(IllegalArgumentException unknown){throw unavailable();}
    }
    private static FileEvidenceTrustUnavailableException unavailable(){return new FileEvidenceTrustUnavailableException();}
    private record AllocationState(RecordedAllocation allocation,Record baseline,Record latest){}
    private static boolean sameObservation(DurableObservation a,DurableObservation b){return sameContentProjection(a,b)
            &&Arrays.equals(a.canonicalObservation(),b.canonicalObservation())&&a.observationSha256().equals(b.observationSha256());}
}
