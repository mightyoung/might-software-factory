package com.mightyoung.softwarefactory.files;

import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConnectionProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.datasource.ConnectionHolder;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Reads signed, published V2 checkpoints; historical trust does not require a new scan. */
final class PublishedIngestionContentReader implements StorageContentVerificationPort, VerifiedStorageVersionReadPort {
    interface Publications { Optional<Publication> find(UUID tenant, UUID file, UUID receipt); }
    record Publication(UUID tenantId, UUID fileId, UUID fileLinkId, UUID evidenceItemId,
            UUID findingId, int roundNo, UUID evidenceSetId, UUID ownerMemberId, UUID ownerActorId,
            boolean currentLinkValid, VerifiedContentCheckpoint checkpoint) {}

    private final java.util.concurrent.Semaphore contentBudget=new java.util.concurrent.Semaphore(1,true);
    private final Publications publications;
    private final FileIngestionProperties.Storage storage;
    private final IngestionProofCodec codec;
    private final StorageContentVerificationPort legacyReceipts;
    private final VerifiedStorageVersionReadPort legacyContent;

    PublishedIngestionContentReader(DSLContext configured, DataSource source,
            JooqFileIngestionLedger ledger, FileIngestionProperties properties, IngestionProofCodec codec,
            LocalPrivateFileStorageAdapter legacy) {
        this(database(configured,source,ledger), properties.storage(),codec,
                legacy==null?new UnconfiguredStorageContentVerificationAdapter():legacy,
                legacy==null?new UnconfiguredVerifiedStorageVersionReadPort():legacy);
    }
    PublishedIngestionContentReader(Publications publications, FileIngestionProperties.Storage storage,
            IngestionProofCodec codec, StorageContentVerificationPort legacyReceipts, VerifiedStorageVersionReadPort legacyContent) {
        this.publications=Objects.requireNonNull(publications);this.storage=Objects.requireNonNull(storage);
        this.codec=Objects.requireNonNull(codec);this.legacyReceipts=Objects.requireNonNull(legacyReceipts);
        this.legacyContent=Objects.requireNonNull(legacyContent);
    }

    @Override public Optional<ContentVerificationReceipt> findVerifiedContent(EvidenceFileUse use, FileObjectMetadata file) {
        if(use==null||file==null)return Optional.empty();
        if(!FileEvidenceVerificationLevel.SCANNED_CONTENT_V2.name().equals(use.recordedVerificationLevel()))
            return legacyReceipts.findVerifiedContent(use,file);
        return lookup(use.tenantId(),use.fileId(),null).filter(p -> p.currentLinkValid()&&
                Objects.equals(p.findingId(),use.findingId())&&p.roundNo()==use.roundNo()
                &&Objects.equals(p.evidenceSetId(),use.evidenceSetId())&&Objects.equals(p.evidenceItemId(),use.evidenceItemId())
                &&Objects.equals(p.ownerMemberId(),use.memberId())&&Objects.equals(p.ownerMemberId(),use.evidenceSetOwnerMemberId())
                &&Objects.equals(p.ownerActorId(),use.actorId())&&Objects.equals(p.ownerMemberId(),file.ownerMemberId())
                &&Objects.equals(p.ownerActorId(),file.ownerActorId())&&Objects.equals(p.tenantId(),file.tenantId())
                &&Objects.equals(p.fileId(),file.fileId())&&file.state()==FileObjectState.READY
                &&p.checkpoint().receipt().sizeBytes()==file.sizeBytes()
                &&Objects.equals(p.checkpoint().receipt().detectedMediaType(),file.mimeType())
                &&Objects.equals(p.checkpoint().receipt().sha256(),file.sha256())
                &&Objects.equals(p.checkpoint().receipt().sha256(),use.recordedSha256())
                &&Objects.equals(p.checkpoint().receipt().immutableStorageVersion(),file.storageVersion())
                &&file.verifiedAt()!=null&&file.verifiedAt().toInstant().equals(p.checkpoint().receipt().issuedAt()))
                .map(PublishedIngestionContentReader::receipt);
    }
    @Override public Optional<ContentVerificationReceipt> findByReceiptId(UUID tenant,UUID receipt) {
        Optional<Publication> published=lookup(tenant,null,receipt);
        return published.isPresent()?published.map(PublishedIngestionContentReader::receipt):legacyReceipts.findByReceiptId(tenant,receipt);
    }
    private Optional<Publication> lookup(UUID tenant,UUID file,UUID receipt) {
        if(tenant==null||file==null&&receipt==null)return Optional.empty();
        try {
            return publications.find(tenant,file,receipt).filter(p->{
                var r=p.checkpoint().receipt();codec.verifyReceipt(r,p.checkpoint().proof());
                return tenant.equals(p.tenantId())&&tenant.equals(r.tenantId())&&p.fileId().equals(r.fileId())
                        &&(file==null||file.equals(r.fileId()))&&(receipt==null||receipt.equals(r.receiptId()))
                        &&p.checkpoint().immutableAllocation().request().allocationId().equals(r.immutableAllocationId())
                        &&p.checkpoint().immutableAllocation().request().fileId().equals(r.fileId())
                        &&p.checkpoint().immutableAllocation().baseline()!=null
                        &&p.checkpoint().immutableAllocation().baseline().sha256().equals(r.sha256())
                        &&p.checkpoint().immutableAllocation().baseline().sizeBytes()==r.sizeBytes()
                        &&p.checkpoint().immutableAllocation().request().storageVersion().equals(r.immutableStorageVersion());
            });
        } catch(RuntimeException failure){throw new FileEvidenceTrustUnavailableException();}
    }
    private static ContentVerificationReceipt receipt(Publication p) {
        var r=p.checkpoint().receipt();
        return new ContentVerificationReceipt(r.receiptId(),r.tenantId(),r.fileId(),r.sha256(),r.immutableStorageVersion(),
                FileEvidenceVerificationLevel.SCANNED_CONTENT_V2,r.verifierId(),r.keyId(),r.policyVersion(),r.issuedAt().atOffset(ZoneOffset.UTC));
    }
    @Override public VerifiedFileContent openVerifiedVersion(VerifiedFileEvidenceSnapshot snapshot) {
        if(snapshot==null)throw new FileEvidenceTrustUnavailableException();
        if(snapshot.verificationLevel()!=FileEvidenceVerificationLevel.SCANNED_CONTENT_V2)return legacyContent.openVerifiedVersion(snapshot);
        Publication p=lookup(snapshot.tenantId(),snapshot.fileId(),snapshot.verificationReceiptId())
                .filter(value->matches(value,snapshot)).orElseThrow(FileEvidenceTrustUnavailableException::new);
        if(!contentBudget.tryAcquire())throw new FileEvidenceTrustUnavailableException();
        LocalPrivateUploadProducer.PrivateDirectory directory=null;
        LocalPrivateUploadProducer.Opened opened=null;
        try {
            directory=LocalPrivateUploadProducer.PrivateDirectory.open(storage.immutableRoot(),storage.rootGeneration(),Clock.systemUTC());
            opened=directory.openPublished(p.checkpoint().immutableAllocation());
            opened.hash(snapshot.sha256(),p.checkpoint().receipt().sizeBytes(),Deadline.after(Duration.ofSeconds(30)));
            return new Content(directory,opened,snapshot.sha256(),p.checkpoint().receipt().sizeBytes(),contentBudget);
        } catch(IOException|RuntimeException failure){
            if(opened!=null)try{opened.close();}catch(IOException ignored){}
            if(directory!=null)try{directory.close();}catch(IOException ignored){}
            contentBudget.release();throw new FileEvidenceTrustUnavailableException();
        }
    }
    private static boolean matches(Publication p,VerifiedFileEvidenceSnapshot s) {
        var r=receipt(p);
        return p.fileLinkId().equals(s.fileLinkId())&&p.evidenceItemId().equals(s.evidenceItemId())
                &&p.ownerMemberId().equals(s.ownerMemberId())&&p.ownerActorId().equals(s.ownerActorId())
                &&r.sha256().equals(s.sha256())&&r.storageVersion().equals(s.storageVersion())
                &&r.verifierId().equals(s.verifierId())&&r.verifierKeyId().equals(s.verifierKeyId())
                &&r.verificationPolicyVersion().equals(s.verificationPolicyVersion())&&r.verifiedAt().isEqual(s.verifiedAt());
    }
    private static final class Content implements VerifiedFileContent {
        final LocalPrivateUploadProducer.PrivateDirectory directory;final LocalPrivateUploadProducer.Opened opened;
        final String sha;final long size;final java.util.concurrent.Semaphore budget;boolean closed;
        Content(LocalPrivateUploadProducer.PrivateDirectory directory,LocalPrivateUploadProducer.Opened opened,String sha,long size,java.util.concurrent.Semaphore budget){
            this.directory=directory;this.opened=opened;this.sha=sha;this.size=size;this.budget=budget;
        }
        @Override public long sizeBytes(){return size;}
        @Override public synchronized void transferTo(OutputStream output)throws IOException {
            if(closed)throw new IOException("Verified content is closed");
            var deadline=Deadline.after(Duration.ofSeconds(30));opened.verifyUnchanged();opened.channel.position(0);
            // Verify the actual bytes to be returned before releasing any of them to the response.
            // One bounded 64 MiB lease prevents concurrent read buffers exceeding the content budget.
            byte[] bytes=new byte[Math.toIntExact(size)];ByteBuffer buffer=ByteBuffer.wrap(bytes);
            while(buffer.hasRemaining()){deadline.remainingNanos();if(opened.channel.read(buffer)<0)throw new IOException("Verified content changed");}
            if(opened.channel.read(ByteBuffer.allocate(1))!=-1)throw new IOException("Verified content changed");
            opened.verifyUnchanged();if(!sha.equals(IngestionProofCodec.sha256(bytes)))throw new IOException("Verified content changed");
            output.write(bytes);
        }
        @Override public synchronized void close()throws IOException{if(!closed){closed=true;try{opened.close();}finally{try{directory.close();}finally{budget.release();}}}}
    }
    private static Publications database(DSLContext configured,DataSource source,JooqFileIngestionLedger ledger) {
        return (tenant,file,receipt)->{
            ConnectionHolder holder=requireSnapshot(source,tenant);
            Connection connection=DataSourceUtils.getTargetConnection(holder.getConnection());
            var db=DSL.using(configured.configuration().derive(new DefaultConnectionProvider(connection)));
            Record row=db.fetchOptional("select p.*, exists(select 1 from public.file_link fl where fl.tenant_id=p.tenant_id and fl.id=p.file_link_id "
                    +"and fl.file_id=p.file_id and fl.resource_id=p.evidence_item_id and fl.resource_type='FINDING_EVIDENCE_ITEM' "
                    +"and fl.purpose='REMEDIATION_EVIDENCE' and fl.revoked_at is null) as current_link_valid "
                    +"from public.file_ingestion_publication p where p.tenant_id=? and "
                    +(file==null?"content_receipt_id=?":"file_id=?")
                    +(file!=null&&receipt!=null?" and content_receipt_id=?":""),
                    file!=null&&receipt!=null?new Object[]{tenant,file,receipt}:new Object[]{tenant,file==null?receipt:file}).orElse(null);
            if(row==null)return Optional.empty();
            var checkpoint=ledger.readVerifiedInSnapshot(db,tenant,row.get("intent_id",UUID.class),new VerifiedCheckpoint(
                    row.get("scan_proof_id",UUID.class),row.get("content_receipt_id",UUID.class),row.get("immutable_allocation_id",UUID.class)));
            if(!checkpoint.receiptAllocation().request().allocationId().equals(row.get("receipt_allocation_id",UUID.class)))throw new FileEvidenceTrustUnavailableException();
            if(requireSnapshot(source,tenant)!=holder||DataSourceUtils.getTargetConnection(holder.getConnection())!=connection)
                throw new FileEvidenceTrustUnavailableException();
            return Optional.of(new Publication(row.get("tenant_id",UUID.class),row.get("file_id",UUID.class),
                    row.get("file_link_id",UUID.class),row.get("evidence_item_id",UUID.class),row.get("finding_id",UUID.class),
                    row.get("round_no",Integer.class),row.get("evidence_set_id",UUID.class),row.get("owner_member_id",UUID.class),
                    row.get("owner_actor_id",UUID.class),row.get("current_link_valid",Boolean.class),checkpoint));
        };
    }
    static ConnectionHolder requireSnapshot(DataSource source,UUID tenant) {
        Object bound=TransactionSynchronizationManager.getResource(source);
        boolean readOnly=TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        Integer isolation=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        boolean supported=readOnly?Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(isolation)
                :Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(isolation)
                        ||Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(isolation);
        if(!(bound instanceof ConnectionHolder holder)||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()
                ||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                ||!supported)
            throw new FileEvidenceTrustUnavailableException();
        try {
            Connection connection=DataSourceUtils.getTargetConnection(holder.getConnection());
            if(connection.getAutoCommit()||connection.isReadOnly()!=readOnly||connection.getTransactionIsolation()!=isolation)
                throw new FileEvidenceTrustUnavailableException();
            try(var statement=connection.prepareStatement("select current_setting('app.tenant_id',true), current_setting('transaction_isolation'), current_setting('transaction_read_only')");var row=statement.executeQuery()) {
                if(!row.next()||!tenant.toString().equals(row.getString(1))
                        ||!(isolation==TransactionDefinition.ISOLATION_REPEATABLE_READ?"repeatable read":"read committed").equals(row.getString(2))
                        ||!(readOnly?"on":"off").equals(row.getString(3)))throw new FileEvidenceTrustUnavailableException();
            }
            return holder;
        } catch(SQLException failure){throw new FileEvidenceTrustUnavailableException();}
    }
}
