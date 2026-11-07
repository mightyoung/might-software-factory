package com.mightyoung.softwarefactory.files;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionRepository.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL publication/rollback checks with synthetic signed checkpoints. No scanner acceptance. */
class IngestionFinalizationPostgresIT {
    static final String URL="jdbc:postgresql://192.168.1.2:54318/msf_upload_finalize_20261108_test";
    Values v;
    final UUID finding=UUID.randomUUID(),set=UUID.randomUUID(),notice=UUID.randomUUID(),account=UUID.randomUUID(),human=UUID.randomUUID(),service=UUID.randomUUID();
    DataSource source,adminSource;
    JdbcTemplate jdbc,admin;
    TransactionTemplate writes,seed;
    FileIngestionLedger files;
    JooqFindingEvidenceIngestionRepository intents;
    VerifiedCheckpoint checkpoint;
    UUID owner;
    @BeforeEach void setup(){
        if(!URL.equals(System.getenv("LAN_PG_FINALIZE_TEST_JDBC_URL"))
                ||!"I_CONFIRM_FINALIZE_FIXTURE_WRITES:msf_upload_finalize_20261108_test".equals(System.getenv("LAN_PG_FINALIZE_TEST_ACK")))
            throw new IllegalStateException("Finalization fixtures require the exact disposable database and acknowledgement");
        v=new Values();owner=v.allocation(AllocationKind.QUARANTINE,v.sourceAttempt,"quarantine").ownerMemberId();
        source=dataSource("LAN_PG_APP_USERNAME","LAN_PG_APP_PASSWORD");adminSource=dataSource("MSF_MIGRATOR_USER","MSF_MIGRATOR_PASSWORD");
        jdbc=new JdbcTemplate(source);admin=new JdbcTemplate(adminSource);writes=transaction(source);seed=transaction(adminSource);
        var guard=new PostgresAuthorizationWriteGuard(source);var dsl=DSL.using(new TransactionAwareDataSourceProxy(source),SQLDialect.POSTGRES);
        files=new JooqFileIngestionLedger(dsl,source,v.codec);
        intents=new JooqFindingEvidenceIngestionRepository(dsl,source,guard,new DataSourceTransactionManager(source),event->{});
        seed.executeWithoutResult(status->{
            admin.queryForObject("select set_config('app.tenant_id',?,true)",String.class,v.tenant.toString());
            admin.update("insert into public.tenant(id,code,name,status) values (?,?,?,'ACTIVE')",v.tenant,"finalize-"+v.tenant,"synthetic publication test");
            admin.update("insert into public.account(id,login_key,status) values (?,?,'ACTIVE')",account,"finalize-"+account);
            admin.update("insert into public.principal(id,kind,account_id,status) values (?,'HUMAN',?,'ACTIVE')",human,account);
            admin.update("insert into public.principal(id,kind,status) values (?,'SERVICE','ACTIVE')",service);
            admin.update("insert into public.tenant_member(tenant_id,id,account_id,status,display_name) values (?,?,?,'ACTIVE','fixture')",v.tenant,owner,account);
            UUID org=UUID.randomUUID();admin.update("insert into public.org_unit(tenant_id,id,code,name) values (?,?,?,'fixture')",v.tenant,org,"org-"+org);
            admin.update("insert into public.finding(tenant_id,id,organization_id,code,description,discovered_at,grade,state,round_no,version,created_by_member_id,updated_by_member_id) values (?,?,?,?,?,current_timestamp,'UNKNOWN','RECTIFYING',1,1,?,?)",v.tenant,finding,org,"F-"+finding,"fixture",owner,owner);
            admin.update("insert into public.remediation_round(tenant_id,finding_id,round_no,state,opened_at,opened_by_command_id) values (?,?,1,'OPEN',current_timestamp,?)",v.tenant,finding,UUID.randomUUID());
            admin.update("insert into public.remediation_notice(tenant_id,id,finding_id,revision,round_no,assignee_id,due_at,original_due_at,requirements,state,issued_at,command_id) values (?,?,?,1,1,?,current_timestamp+interval '1 day',current_timestamp+interval '1 day','fixture','ISSUED',current_timestamp,?)",v.tenant,notice,finding,owner,UUID.randomUUID());
            admin.update("insert into public.evidence_set(tenant_id,id,finding_id,round_no,owner_member_id,state,created_at) values (?,?,?,1,?,'UPLOADING',current_timestamp)",v.tenant,set,finding,owner);
            insertIntent(admin);
        });
        receiveAndScanCheckpoint();
    }
    void insertIntent(JdbcTemplate db){
db.update("insert into public.finding_evidence_upload_intent(tenant_id,id,owner_account_id,owner_actor_id,owner_member_id,operation,idempotency_key,fingerprint_version,payload_sha256,finding_id,round_no,notice_id,evidence_set_id,expected_finding_version,expected_evidence_revision,filename,declared_media_type,declared_size_bytes,producer_configuration_version,upload_policy_version,state,created_at,expires_at) values (?,?,?,?,?,'FINDING_REMEDIATION_UPLOAD_INTENT_V1',?,'upload-intent-v1',?,?,1,?,?,1,0,'proof.png','image/png',3,'config','policy-v1','INTENT_CREATED',current_timestamp,current_timestamp+interval '30 minutes')",v.tenant,v.intent,account,human,owner,UUID.randomUUID(),"f".repeat(64),finding,notice,set);
    }
    static DataSource dataSource(String user,String password){
        return new DriverManagerDataSource(URL,Objects.requireNonNull(System.getenv(user)),Objects.requireNonNull(System.getenv(password)));
    }
    static TransactionTemplate transaction(DataSource ds){var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);return tx;}
    void tenant(){jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,v.tenant.toString());}
    long count(String table){return writes.execute(s->{tenant();return jdbc.queryForObject("select count(*) from public."+table+" where tenant_id=?",Long.class,v.tenant);});}
    PhysicalCharges charges(){return writes.execute(s->{tenant();return files.inCallerTransaction(v.tenant,f->files.charges(f,owner));});}
    IngestionPublicationRequest request(TechnicalIntent i){return new IngestionPublicationRequest(v.tenant,v.intent,checkpoint,finding,1,set,
        UUID.nameUUIDFromBytes((v.intent+":item").getBytes(java.nio.charset.StandardCharsets.UTF_8)),UUID.nameUUIDFromBytes((v.intent+":link").getBytes(java.nio.charset.StandardCharsets.UTF_8)),owner,human,service,i.technicalVersion(),1,0);}
    TechnicalIntent publish(boolean failLate){return intents.inIntentTransaction(v.tenant,v.intent,tx->{var i=tx.current();var p=request(i);
        return files.inCallerTransaction(v.tenant,f->{var content=files.publishVerified(f,p,v.generation());
            var result=tx.finalizeEvidence(i,content,p.evidenceItemId(),p.fileLinkId(),service);
            if(failLate)throw new IllegalStateException("test late publication failure");return result;});});}
    void assertUnpublished(){assertThat(count("file_object")).isZero();assertThat(count("file_link")).isZero();assertThat(count("evidence_item")).isZero();assertThat(count("file_ingestion_publication")).isZero();
        assertThat(intents.<Phase>inIntentTransaction(v.tenant,v.intent,tx->tx.current().phase())).isEqualTo(Phase.VERIFIED_PENDING_FINALIZATION);}
    @Test void publishesEveryLogicalRowAndBothChargesInOneCommit(){
        var before=charges();assertThat(publish(false).phase()).isEqualTo(Phase.FINALIZED);
        for(String table:List.of("file_object","file_link","evidence_item","file_ingestion_publication"))assertThat(count(table)).as(table).isEqualTo(1);
        var after=charges();assertThat(after.ownerTotal()).isEqualTo(before.ownerTotal());assertThat(after.tenantTotal()).isEqualTo(before.tenantTotal());
        assertThat(after.ownerFinalized()).isEqualTo(131072);assertThat(after.ownerPending()).isEqualTo(before.ownerPending()-131072);
        writes.executeWithoutResult(s->{tenant();assertThat(jdbc.queryForObject("select state from public.evidence_set where tenant_id=? and id=?",String.class,v.tenant,set)).isEqualTo("READY");
            var loaded=files.inCallerTransaction(v.tenant,f->files.readVerified(f,v.intent,checkpoint));assertThat(loaded.immutableAllocation().finalizedFileId()).isEqualTo(v.file);assertThat(loaded.receiptAllocation().finalizedFileId()).isEqualTo(v.file);});
        var storage=new FileIngestionProperties.Storage(java.nio.file.Path.of("/private/tmp/msf-test-quarantine"),
                java.nio.file.Path.of("/private/tmp/msf-test-immutable"),java.nio.file.Path.of("/private/tmp/msf-test-receipts"),"a".repeat(64));
        var properties=new FileIngestionProperties(false,storage,null,null,Set.of(),null,null);
        var reader=new PublishedIngestionContentReader(DSL.using(new TransactionAwareDataSourceProxy(source),SQLDialect.POSTGRES),source,
                (JooqFileIngestionLedger)files,properties,v.codec,null);
        for(boolean readOnly:List.of(true,false)){
            var snapshot=transaction(source);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.setReadOnly(readOnly);
            snapshot.executeWithoutResult(s->{tenant();var receipt=reader.findByReceiptId(v.tenant,checkpoint.receiptId()).orElseThrow();
                assertThat(receipt.fileId()).isEqualTo(v.file);assertThat(receipt.verificationLevel()).isEqualTo(FileEvidenceVerificationLevel.SCANNED_CONTENT_V2);
                assertThat(receipt.sha256()).isEqualTo(v.proof().sha256());assertThat(receipt.receiptId()).isEqualTo(checkpoint.receiptId());});
        }
    }
    @Test void lateFailureRollsBackAllRowsTransfersAndIntent(){var before=charges();assertThatThrownBy(()->publish(true)).isInstanceOf(IllegalStateException.class);assertUnpublished();assertThat(charges()).isEqualTo(before);}
    @Test void deferredConstraintRejectsPublicationWithoutBusinessItemAndFinalizedIntent(){
        var before=charges();assertThatThrownBy(()->intents.inIntentTransaction(v.tenant,v.intent,tx->files.inCallerTransaction(v.tenant,f->files.publishVerified(f,request(tx.current()),v.generation())))).isInstanceOf(RuntimeException.class);
        assertUnpublished();assertThat(charges()).isEqualTo(before);
    }
    @Test void cancellationAndStaleRevisionCannotPublish(){
        intents.inIntentTransaction(v.tenant,v.intent,tx->{var i=tx.current();var c=Change.unchanged(i);tx.compareAndSet(i,new Change(c.phase(),c.leaseEpoch(),c.attemptNo(),c.attemptId(),c.reservedScanAttemptId(),c.leaseToken(),c.leaseUntil(),c.heartbeatAt(),c.nextAttemptAt(),c.quarantineAllocationId(),c.immutableAllocationId(),c.scanProofId(),c.contentReceiptId(),c.scannerGeneration(),c.lastErrorCode(),true,c.currentExecutionId()),service,i.policyVersion());return null;});
        assertThatThrownBy(()->publish(false)).isInstanceOf(RuntimeException.class);assertUnpublished();
    }
    @Test void independentlyChangedEvidenceRevisionRejectsPublication(){
        writes.executeWithoutResult(s->{tenant();jdbc.update("update public.evidence_set set evidence_revision=evidence_revision+1 where tenant_id=? and id=?",v.tenant,set);});
        assertThatThrownBy(()->publish(false)).isInstanceOf(RuntimeException.class);assertUnpublished();
    }
    @Test void crossTenantCheckpointLookupCannotReadOrWritePublication(){
        UUID other=UUID.randomUUID();assertThatThrownBy(()->writes.execute(s->{jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,other.toString());return files.inCallerTransaction(other,f->files.readVerified(f,v.intent,checkpoint));})).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertUnpublished();
    }
    @Test void expiredCheckpointFreshnessRejectsBeforePublishingButHistoricalVerificationSurvives(){
        var later=new IngestionProofCodec(Map.of("key1",new byte[32]),"key1",Clock.fixed(Values.NOW.plusSeconds(1000),ZoneOffset.UTC));
        var ledger=new JooqFileIngestionLedger(DSL.using(new TransactionAwareDataSourceProxy(source),SQLDialect.POSTGRES),source,later);
        assertThatThrownBy(()->intents.inIntentTransaction(v.tenant,v.intent,tx->ledger.inCallerTransaction(v.tenant,f->ledger.publishVerified(f,request(tx.current()),v.generation())))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        writes.executeWithoutResult(s->{tenant();assertThat(ledger.inCallerTransaction(v.tenant,f->ledger.readVerified(f,v.intent,checkpoint)).receipt().receiptId()).isEqualTo(checkpoint.receiptId());});
        assertUnpublished();
    }
    @Test void pendingUploadPreventsSetSealWithExactDiagnostic(){
        assertThatThrownBy(()->writes.executeWithoutResult(s->{tenant();
            jdbc.update("update public.evidence_set set state='READY',verified_at=current_timestamp where tenant_id=? and id=?",v.tenant,set);
            jdbc.update("insert into public.remediation_submission(tenant_id,id,finding_id,notice_id,round_no,submitted_by,submitted_at,evidence_set_id,statement,expected_version,command_id) values (?,?,?,?,1,?,current_timestamp,?,'fixture',1,?)",v.tenant,UUID.randomUUID(),finding,notice,owner,set,UUID.randomUUID());
            jdbc.update("update public.evidence_set set submission_sealed_at=transaction_timestamp(),evidence_revision=evidence_revision+1 where tenant_id=? and id=?",v.tenant,set);
        })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("Evidence set still has unpublished uploads");
        assertUnpublished();
    }
    @Test void twoIntentsAtSameStartingRevisionCanPublishOnlyOne(){
        var first=v;var firstCheckpoint=checkpoint;v=new Values(first.tenant,owner);
        seed.executeWithoutResult(s->{admin.queryForObject("select set_config('app.tenant_id',?,true)",String.class,v.tenant.toString());insertIntent(admin);});
        receiveAndScanCheckpoint();var second=v;var secondCheckpoint=checkpoint;
        v=first;checkpoint=firstCheckpoint;publish(false);
        v=second;checkpoint=secondCheckpoint;assertThatThrownBy(()->publish(false)).isInstanceOf(RuntimeException.class);
        assertThat(count("file_object")).isEqualTo(1);assertThat(count("file_ingestion_publication")).isEqualTo(1);
        assertThat(intents.<Phase>inIntentTransaction(v.tenant,v.intent,tx->tx.current().phase())).isEqualTo(Phase.VERIFIED_PENDING_FINALIZATION);
    }
    @Test void finalizedRetryOrphansStayChargedUntilConfirmedAbsentAndTransferredAllocationsStayTerminal(){
        var orphans=new ArrayList<UUID>();
        intents.inIntentTransaction(v.tenant,v.intent,tx->files.inCallerTransaction(v.tenant,f->{
            for(var kind:List.of(AllocationKind.IMMUTABLE,AllocationKind.RECEIPT)){
                var request=v.allocation(kind,UUID.randomUUID(),"orphan-"+kind.name().toLowerCase(Locale.ROOT));
                orphans.add(files.reserve(f,request).request().allocationId());
            }return null;
        }));
        publish(false);long retained=charges().ownerTotal();
        for(UUID allocation:orphans){
            long before=charges().ownerTotal();
            intents.inIntentTransaction(v.tenant,v.intent,tx->files.inCallerTransaction(v.tenant,f->{
                var i=tx.current();var current=files.load(f,allocation);var observation=absent(i,current);
                files.recordRecoveryObservation(f,observation.binding(),observation);
                files.claimDisposal(f,new DisposalEligibility(v.tenant,v.intent,current.request().attemptId(),i.technicalVersion(),true,
                        "f".repeat(64),Values.NOW.minusSeconds(100)),allocation,observation.observationSha256());
                tx.compareAndSet(i,Change.unchanged(i),service,i.policyVersion());return null;
            }));
            assertThat(charges().ownerTotal()).isEqualTo(before);
            intents.inIntentTransaction(v.tenant,v.intent,tx->files.inCallerTransaction(v.tenant,f->{
                var i=tx.current();var current=files.load(f,allocation);var claim=files.currentDisposalClaim(f,allocation).orElseThrow();
                files.confirmDisposal(f,claim,v.codec.observeDisposal(claim,absent(i,current)),i.technicalVersion());
                tx.compareAndSet(i,Change.unchanged(i),service,i.policyVersion());return null;
            }));
            assertThat(charges().ownerTotal()).isEqualTo(before-65536);assertThat(charges().ownerFinalized()).isEqualTo(131072);
        }
        assertThat(charges().ownerTotal()).isEqualTo(retained-131072);
        for(var kind:List.of(AllocationKind.IMMUTABLE,AllocationKind.RECEIPT)){
            UUID allocation=v.allocation(kind,v.scanAttempt,kind==AllocationKind.IMMUTABLE?"immutable":"receipt").allocationId();
            assertThatThrownBy(()->writes.executeWithoutResult(s->{tenant();
                long version=jdbc.queryForObject("select technical_version from public.finding_evidence_upload_intent where tenant_id=? and id=?",Long.class,v.tenant,v.intent);
                jdbc.update("""
                    insert into public.file_ingestion_allocation_event
                    select (jsonb_populate_record(null::public.file_ingestion_allocation_event,
                        to_jsonb(e)||jsonb_build_object('id',cast(? as uuid),'event_no',e.event_no+1,
                            'predecessor_event_id',e.id,'event_kind','RECOVERY_OBSERVED','technical_version',cast(? as bigint)))).*
                    from public.file_ingestion_allocation_event e where tenant_id=? and allocation_id=? and event_kind='TRANSFER_FINALIZED'
                    """,UUID.randomUUID(),version,v.tenant,allocation);
            })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("File ingestion event predecessor is invalid");
        }
        assertThat(charges().ownerFinalized()).isEqualTo(131072);assertThat(count("file_ingestion_publication")).isEqualTo(1);
    }
    @Test void sealedV2SubmissionReadsExactStatementSubmitterAndSnapshotWithinOrganizationScope(){
        publish(false);
        var dsl=DSL.using(new TransactionAwareDataSourceProxy(source),SQLDialect.POSTGRES);
        var manager=new DataSourceTransactionManager(source);
        var workflow=new com.mightyoung.softwarefactory.dualprevention.finding.workflow.JooqFindingWorkflowRepository(
                dsl,manager,new PostgresAuthorizationWriteGuard(source));
        UUID submission=UUID.randomUUID();String statement="已更换防护设施并完成整改，申请验收。";
        workflow.inTenantTransaction(v.tenant,tx->{
            tx.findForUpdate(finding).orElseThrow();
            var anchor=jdbc.queryForMap("select evidence_item_id,file_link_id from public.file_ingestion_publication where tenant_id=? and intent_id=?",v.tenant,v.intent);
            var receipt=files.inCallerTransaction(v.tenant,f->files.readVerified(f,v.intent,checkpoint)).receipt();
            var snapshot=new VerifiedFileEvidenceSnapshot(v.tenant,(UUID)anchor.get("evidence_item_id"),v.file,
                    (UUID)anchor.get("file_link_id"),owner,human,receipt.sha256(),receipt.immutableStorageVersion(),receipt.receiptId(),
                    receipt.verifierId(),receipt.keyId(),receipt.policyVersion(),FileEvidenceVerificationLevel.SCANNED_CONTENT_V2,
                    receipt.issuedAt().atOffset(ZoneOffset.UTC));
            tx.insertSubmission(new com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Submission(
                    v.tenant,submission,finding,notice,1,owner,OffsetDateTime.now(ZoneOffset.UTC),set,List.of(snapshot),statement,1,UUID.randomUUID()));
            return null;
        });
        UUID org=writes.execute(s->{tenant();return jdbc.queryForObject("select organization_id from public.finding where tenant_id=? and id=?",UUID.class,v.tenant,finding);});
        var repository=new com.mightyoung.softwarefactory.dualprevention.finding.JooqFindingRepository(dsl,manager);
        var scope=new com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope(human,v.tenant,owner,Set.of(org));
        var detail=repository.findDetailContext(scope,finding).orElseThrow();
        assertThat(detail.submittedRemediations()).singleElement().satisfies(saved->{
            assertThat(saved.id()).isEqualTo(submission);assertThat(saved.roundNo()).isEqualTo(1);assertThat(saved.statement()).isEqualTo(statement);
            assertThat(saved.submitterMemberId()).isEqualTo(owner);assertThat(saved.submitterName()).isEqualTo("fixture");
            assertThat(saved.evidence()).singleElement().satisfies(item->{
                assertThat(item.itemId()).isEqualTo(UUID.nameUUIDFromBytes((v.intent+":item").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                assertThat(item.fileId()).isEqualTo(v.file);assertThat(item.sha256()).isEqualTo(v.proof().sha256());
                assertThat(item.verificationLevel()).isEqualTo("SCANNED_CONTENT_V2");
            });
        });
        writes.executeWithoutResult(s->{tenant();
            assertThat(jdbc.queryForObject("select evidence_snapshots_sealed_at is not null and evidence_snapshot_count=1 from public.remediation_submission where tenant_id=? and id=?",Boolean.class,v.tenant,submission)).isTrue();
            assertThat(jdbc.queryForObject("select submission_sealed_at is not null from public.evidence_set where tenant_id=? and id=?",Boolean.class,v.tenant,set)).isTrue();
        });
        assertThat(repository.findDetailContext(new com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope(human,UUID.randomUUID(),owner,Set.of(org)),finding)).isEmpty();
        assertThat(repository.findDetailContext(new com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope(human,v.tenant,owner,Set.of(UUID.randomUUID())),finding)).isEmpty();
        assertThatThrownBy(()->repository.findDetailContext(new com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope(human,v.tenant,owner,Set.of()),finding))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    RecoveryObservation absent(TechnicalIntent i,RecordedAllocation allocation){
        var binding=new RecoveryBinding(v.tenant,v.intent,allocation.request().allocationId(),UUID.randomUUID(),i.technicalVersion(),
                i.leaseEpoch(),allocation.eventNo(),allocation.eventSha256(),null,null,RecoveryReason.TERMINAL,"f".repeat(64));
        // Test-only trusted absence/writer observations exercise SQL; no actual filesystem absence is claimed.
        return v.codec.observeRecovery(binding,allocation.request(),RecoveryPresence.ABSENT,ProcessState.NONE_STARTED,true,
                "a".repeat(64),Values.NOW,null,null,null,null,"b".repeat(64),Values.NOW);
    }
    void receiveAndScanCheckpoint(){
        intents.inIntentTransaction(v.tenant,v.intent,tx->files.inCallerTransaction(v.tenant,f->{
            for(var kind:List.of(AllocationKind.QUARANTINE,AllocationKind.SCANNER_SCRATCH,AllocationKind.IMMUTABLE,AllocationKind.RECEIPT))
                files.reserve(f,v.allocation(kind,kind==AllocationKind.QUARANTINE?v.sourceAttempt:v.scanAttempt,switch(kind){case QUARANTINE->"quarantine";case SCANNER_SCRATCH->"scratch";case IMMUTABLE->"immutable";case RECEIPT->"receipt";}));
            var i=tx.current();var now=tx.now().toInstant();tx.compareAndSet(i,new Change(Phase.RECEIVING,1,0,v.sourceAttempt,v.scanAttempt,UUID.randomUUID(),now.plusSeconds(90),now,null,v.quarantine().allocationId(),null,null,null,null,null,false,null),service,i.policyVersion());return null;}));
        intents.inIntentTransaction(v.tenant,v.intent,tx->files.inCallerTransaction(v.tenant,f->{var i=tx.current();files.recordQuarantine(f,i.fence(),v.quarantine());tx.compareAndSet(i,new Change(Phase.QUARANTINED,1,0,v.sourceAttempt,v.scanAttempt,null,null,null,null,i.quarantineAllocationId(),null,null,null,null,null,false,null),service,i.policyVersion());return null;}));
        intents.inIntentTransaction(v.tenant,v.intent,tx->files.inCallerTransaction(v.tenant,f->{var i=tx.current();var e=v.execution().descriptor();files.recordExecution(f,e);var now=tx.now().toInstant();tx.compareAndSet(i,new Change(Phase.SCANNING,2,1,v.scanAttempt,v.scanAttempt,e.claimToken(),now.plusSeconds(90),now,null,i.quarantineAllocationId(),null,null,null,null,null,false,e.executionId()),service,i.policyVersion());return null;}));
        checkpoint=intents.inIntentTransaction(v.tenant,v.intent,tx->files.inCallerTransaction(v.tenant,f->{var i=tx.current();var p=v.proof();var r=v.receipt(p);var a=v.allocation(AllocationKind.IMMUTABLE,v.scanAttempt,"immutable");var registration=v.allocation(AllocationKind.RECEIPT,v.scanAttempt,"receipt");byte[] bytes=IngestionSignedCheckpointReader.receiptFileBytes(r);
            var observation=v.codec.observe(registration,ObservationKind.RECEIPT,v.identity(registration,bytes.length),IngestionProofCodec.sha256(bytes),(long)bytes.length,"application/vnd.msf.scanned-content-receipt");
            var immutable=new ImmutableObject(v.tenant,v.intent,v.file,a.allocationId(),v.sourceAttempt,v.scanAttempt,v.identity(a,3),p.sha256(),p.detectedMediaType(),3,p.quarantineObservationSha256());
            var saved=files.recordVerified(f,i.fence(),v.quarantine(),new ImmutableObjectAndReceipt(immutable,p,r,observation),v.generation(),v.outcome());
            tx.compareAndSet(i,new Change(Phase.VERIFIED_PENDING_FINALIZATION,2,1,v.scanAttempt,v.scanAttempt,null,null,null,null,i.quarantineAllocationId(),saved.immutableAllocationId(),saved.proofId(),saved.receiptId(),v.generation().generationSha256(),null,false,i.currentExecutionId()),service,i.policyVersion());return saved;}));
    }
    static final class Values {
    static final Instant NOW=Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    final byte[] key=new byte[32];
    final IngestionProofCodec codec=new IngestionProofCodec(Map.of("key1",key),"key1",Clock.fixed(NOW,ZoneOffset.UTC));
    final UUID tenant,ownerMember;
    final UUID intent=UUID.randomUUID(),file=UUID.randomUUID(),sourceAttempt=UUID.randomUUID(),scanAttempt=UUID.randomUUID();
    Values(){this(UUID.randomUUID(),UUID.randomUUID());}
    Values(UUID tenant,UUID ownerMember){this.tenant=tenant;this.ownerMember=ownerMember;}
    AllocationRequest allocation(AllocationKind kind,UUID attempt,String name){return new AllocationRequest(tenant,intent,file,
            ownerMember,attempt,UUID.nameUUIDFromBytes((intent+":"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            kind,"a".repeat(64),name+"-"+intent,"v1",65536,1,NOW.minusSeconds(2));}
    ObjectIdentity identity(AllocationRequest a,long size){return new ObjectIdentity(a.rootGeneration(),a.serverObjectName(),a.storageVersion(),"store","inode-1",1,2,1,size,NOW.minusSeconds(1));}
    AcceptedGeneration generation(){byte[] bytes="synthetic-manifest-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new AcceptedGeneration(UUID.nameUUIDFromBytes((tenant+":generation").getBytes(java.nio.charset.StandardCharsets.UTF_8)),"b".repeat(64),IngestionProofCodec.sha256(bytes),bytes,NOW.minusSeconds(60),NOW.plusSeconds(900));}
    QuarantinedObject quarantine(){var a=allocation(AllocationKind.QUARANTINE,sourceAttempt,"quarantine");var identity=identity(a,3);
        var observation=codec.observe(a,ObservationKind.COMPLETE_QUARANTINE,identity,"c".repeat(64),3L,"image/png");
        return new QuarantinedObject(tenant,intent,file,a.allocationId(),sourceAttempt,identity,"c".repeat(64),"image/png",3,observation.observationSha256());}
    UUID id(long id){return UUID.nameUUIDFromBytes((intent+":"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    TechnicalFence scanFence(){return new TechnicalFence(tenant,intent,scanAttempt,id(15),3,2);}
    RecordedExecution execution(){
        var plan=new ScanPlan(tenant,intent,file,sourceAttempt,scanAttempt,quarantine().allocationId(),
                allocation(AllocationKind.SCANNER_SCRATCH,scanAttempt,"scratch").allocationId(),id(10),id(11),"e".repeat(64),"slot1",2,2,
                generation().generationSha256(),quarantine().observationSha256());
        return new RecordedExecution(ExecutionDescriptor.forClaim(plan,scanFence(),NOW.minusSeconds(2)));
    }
    KernelExecutionFacts facts(){var d=execution().descriptor();return new KernelExecutionFacts(d.executionId(),d.descriptorSha256(),"1".repeat(64),
            d.physicalResourceRealmId(),d.slotId(),d.profileSha256(),id(13),100,1000,101L,1001L,null,
            1L,2L,3L,4L,5L,d.executionId(),1,2,"f".repeat(64),0,false,true,true,true,"a".repeat(64),
            false,null,true,true,true,true,true,true,false,ProcessState.REAPED,"b".repeat(64),"c".repeat(64),NOW);}
    ScanOutcome outcome(){return new ScanOutcome(proof(),execution(),facts());}
    SignedScanProofV1 proof(){return codec.issueScan(id(8),quarantine(),scanFence(),generation(),execution(),facts(),
            new IngestionProofCodec.ProofPolicy("policy-v1","d".repeat(64),"verifier-v1"),
            new IngestionProofCodec.ScanTimes(NOW.minusSeconds(1),NOW,NOW,NOW,NOW.plusSeconds(600)));}
    IngestionContentReceiptV2 receipt(SignedScanProofV1 proof){var a=allocation(AllocationKind.IMMUTABLE,scanAttempt,"immutable");var identity=identity(a,3);
        var immutable=new ImmutableObject(tenant,intent,file,a.allocationId(),sourceAttempt,scanAttempt,identity,"c".repeat(64),"image/png",3,proof.quarantineObservationSha256());
        var observation=codec.observe(a,ObservationKind.COMPLETE_IMMUTABLE,identity,"c".repeat(64),3L,"image/png");
        return codec.issueReceipt(id(9),immutable,proof,observation.observationSha256(),
                new IngestionProofCodec.ReceiptTimes(NOW,NOW,NOW.plusSeconds(600)));}

    }
}
