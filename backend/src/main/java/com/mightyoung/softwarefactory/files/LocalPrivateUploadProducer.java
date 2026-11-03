package com.mightyoung.softwarefactory.files;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static com.mightyoung.softwarefactory.files.FileIngestionPort.RejectionReason.*;

/**
 * Opt-in private V2 producer. The configured service identity and private roots must be trusted;
 * chmod does not defend against hostile same-UID writers. Existing V1 storage/receipts are untouched.
 * Unknown/partial objects are retained under their original allocations; this class never releases
 * SQL capacity or makes business metadata READY. Resource promotion executes real controls/probes.
 */
public final class LocalPrivateUploadProducer implements FileIngestionPort,UploadProducerReadiness,AutoCloseable {
    private static final long RECEIPT_RESERVATION_BYTES=65_536;
    private final FileIngestionProperties properties;
    private final IngestionProofCodec codec;
    private final Clock clock;
    private final ThreadPoolExecutor io;
    private final ThreadPoolExecutor streamClosers;
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile Resources resources;

    LocalPrivateUploadProducer(FileIngestionProperties properties,IngestionProofCodec codec) {
        this(properties,codec,Clock.systemUTC());
    }
    LocalPrivateUploadProducer(FileIngestionProperties properties,IngestionProofCodec codec,Clock clock) {
        this.properties=Objects.requireNonNull(properties);this.codec=Objects.requireNonNull(codec);this.clock=Objects.requireNonNull(clock);
        if(!properties.enabled())throw new IllegalArgumentException("Private ingestion is not configured");
        int concurrency=properties.limits().concurrency();
        io=new ThreadPoolExecutor(concurrency,concurrency,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name("file-private-io-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        streamClosers=new ThreadPoolExecutor(concurrency,concurrency,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name("file-upload-close-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    }
    @Override public Capability requireReady() {
        noTransaction();Resources current=ready();
        try{current.scanner.requireAccepted(current.accepted);return current.accepted.capability();}
        catch(IOException|FileEvidenceTrustUnavailableException unavailable){invalidate(current);throw unavailable();}
    }
    @Override public ProducerResources currentResources() {
        noTransaction();Resources current=ready();requireReady();
        if(current!=resources)throw unavailable();
        return new ProducerResources(current.accepted.generation(),properties.storage().rootGeneration(),current.profile.realm(),current.profile.sha256(),RECEIPT_RESERVATION_BYTES,
                current.profile.slots().stream().map(slot->new ScanSlot(slot.id(),current.profile.scratchBytes())).toList());
    }
    @Override public boolean isCurrent(Capability capability) {
        Resources current=resources;
        return !closed.get()&&current!=null&&current.scanner.isLocallyCurrent(current.accepted)
                &&current.accepted.capability().equals(capability);
    }
    @Override public boolean isCurrentGeneration(AcceptedGeneration generation) {
        Resources current=resources;
        if(closed.get()||current==null||generation==null||!current.scanner.isLocallyCurrent(current.accepted))return false;
        AcceptedGeneration actual=current.accepted.generation();
        return actual.generationId().equals(generation.generationId())&&actual.generationSha256().equals(generation.generationSha256())
                &&actual.manifestSha256().equals(generation.manifestSha256())&&actual.validatedAt().equals(generation.validatedAt())
                &&actual.validUntil().equals(generation.validUntil())&&MessageDigest.isEqual(actual.canonicalManifest(),generation.canonicalManifest());
    }
    private synchronized Resources ready() {
        codec.requireSigningAvailable();
        if(closed.get())throw unavailable();
        Resources current=resources;
        if(current!=null&&current.scanner.isLocallyCurrent(current.accepted))return current;
        if(current!=null) {resources=null;current.close();}
        PrivateDirectory quarantine=null,immutable=null,receipts=null;ClamScanProcessAdapter scanner=null;
        try {
            Deadline deadline=Deadline.after(Duration.ofMinutes(5));
            var profile=ImmutableContainmentProfile.load(properties,deadline);
            var manifest=ScannerGenerationManifest.inspect(properties,profile,deadline,clock.instant());
            quarantine=PrivateDirectory.open(properties.storage().quarantineRoot(),properties.storage().rootGeneration(),clock);
            immutable=PrivateDirectory.open(properties.storage().immutableRoot(),properties.storage().rootGeneration(),clock);
            receipts=PrivateDirectory.open(properties.storage().receiptRoot(),properties.storage().rootGeneration(),clock);
            quarantine.validateWriterControl();immutable.validateWriterControl();receipts.validateWriterControl();
            scanner=new ClamScanProcessAdapter(properties,profile,manifest,new ScannerProcessLauncher(properties,profile),clock);
            var accepted=scanner.accept(deadline);
            // An unavailable codec must fail before admitting any upload rather than after scanner work.
            codec.verifyGeneration(accepted.generation());
            Resources next=new Resources(profile,scanner,accepted,quarantine,immutable,receipts);
            resources=next;return next;
        } catch(IOException|RuntimeException failure) {
            if(scanner!=null)scanner.close();closeQuietly(quarantine);closeQuietly(immutable);closeQuietly(receipts);
            throw unavailable();
        }
    }
    private void invalidate(Resources current){synchronized(this){if(resources==current){resources=null;current.close();}}}

    @Override public QuarantinedObject receive(ReceiveAllocation allocation,InputStream input,Deadline deadline) {
        noTransaction();Objects.requireNonNull(allocation);Objects.requireNonNull(input);Objects.requireNonNull(deadline);
        Resources current=ready();requireReady();
        if(!current.accepted.generation().generationSha256().equals(allocation.expectedProducerGenerationSha256())
                ||!properties.allowedMediaTypes().contains(allocation.declaredMediaType()))throw unavailable();
        requireFresh(allocation.quarantine(),AllocationKind.QUARANTINE);
        return external(()->{
            noTransaction();
            var request=allocation.quarantine().request();
            try(var allocationIo=current.quarantine.writerFence(request,false)) {
            if(allocationIo==null)throw unavailable();
            var written=current.quarantine.writeLocked(request,input,allocation.declaredSizeBytes(),deadline);
            String media;
            try(var source=current.quarantine.open(written.identity())) {
                media=MediaPolicy.detect(source.channel,allocation.declaredSizeBytes(),allocation.declaredMediaType(),properties.limits().maxImagePixels(),deadline);
                source.verifyUnchanged();
            }
            if(!isCurrentGeneration(current.accepted.generation()))throw unavailable();
            var observation=codec.observe(request,ObservationKind.COMPLETE_QUARANTINE,written.identity(),written.sha256(),written.identity().measuredBytes(),media);
            return new QuarantinedObject(request.tenantId(),request.intentId(),request.fileId(),request.allocationId(),request.attemptId(),
                    written.identity(),written.sha256(),media,written.identity().measuredBytes(),observation.observationSha256());
            }
        },deadline,input);
    }
    @Override public ScanOutcome scan(QuarantinedObject source,ScanAllocation allocation,
            AcceptedGeneration generation,TechnicalFence fence,RecordedExecution execution,PreparedSlotLease lease,Deadline deadline) {
        noTransaction();Objects.requireNonNull(source);Objects.requireNonNull(allocation);Objects.requireNonNull(fence);Objects.requireNonNull(deadline);
        Resources current=ready();requireReady();
        if(!isCurrentGeneration(generation)||!allocation.fence().equals(fence)
                ||!source.tenantId().equals(fence.tenantId())||!source.intentId().equals(fence.intentId())
                ||!source.fileId().equals(allocation.scratch().request().fileId())||source.sourceAttemptId().equals(fence.attemptId()))throw unavailable();
        return external(()->{
            noTransaction();
            try(var opened=current.quarantine.openBaseline(source)) {
                opened.hash(source.sha256(),source.sizeBytes(),deadline);opened.channel.position(0);
                var result=current.scanner.scan(current.accepted,source,allocation,execution,lease,Channels.newInputStream(opened.channel),deadline);
                opened.verifyUnchanged();opened.hash(source.sha256(),source.sizeBytes(),deadline);
                Instant now=now();Instant expires=min(now.plus(properties.limits().proofFreshness()),generation.validUntil());
                var proof=codec.issueScan(UUID.randomUUID(),source,fence,generation,execution,result.kernelFacts(),
                        new IngestionProofCodec.ProofPolicy(properties.policyVersion(),current.accepted.generation().generationSha256(),properties.verifierId()),
                        new IngestionProofCodec.ScanTimes(result.startedAt(),result.completedAt(),now,now,expires));
                return new ScanOutcome(proof,execution,result.kernelFacts());
            } catch(ClamScanProcessAdapter.RejectedContent rejected){throw new ContentRejected(SCANNER_REJECTED);}
        },deadline,null);
    }
    @Override public ImmutableObjectAndReceipt publishImmutable(QuarantinedObject source,ScanOutcome outcome,
            CopyAllocation copy,ReceiptAllocation receipt,Deadline deadline) {
        noTransaction();Objects.requireNonNull(source);Objects.requireNonNull(outcome);var proof=outcome.proof();Objects.requireNonNull(copy);Objects.requireNonNull(receipt);Objects.requireNonNull(deadline);
        Resources current=ready();requireReady();codec.verifyCurrentScan(proof,current.accepted.generation());
        requireFresh(copy.immutable(),AllocationKind.IMMUTABLE);requireFresh(receipt.receipt(),AllocationKind.RECEIPT);
        var target=copy.immutable().request();var registration=receipt.receipt().request();
        if(!source.tenantId().equals(target.tenantId())||!source.intentId().equals(target.intentId())||!source.fileId().equals(target.fileId())
                ||!proof.scanAttemptId().equals(target.attemptId())||!target.tenantId().equals(registration.tenantId())
                ||!target.intentId().equals(registration.intentId())||!target.fileId().equals(registration.fileId())
                ||!target.attemptId().equals(registration.attemptId())||target.reservedBytes()<source.sizeBytes())throw unavailable();
        return external(()->{
            noTransaction();Written written;
            try(var opened=current.quarantine.openBaseline(source)) {
                opened.hash(source.sha256(),source.sizeBytes(),deadline);opened.channel.position(0);
                written=current.immutable.write(target,Channels.newInputStream(opened.channel),source.sizeBytes(),deadline);
                opened.verifyUnchanged();
                if(!written.sha256().equals(source.sha256()))throw new QuarantineNotReusable();
            }
            var immutable=new ImmutableObject(source.tenantId(),source.intentId(),source.fileId(),target.allocationId(),
                    source.sourceAttemptId(),proof.scanAttemptId(),written.identity(),source.sha256(),source.detectedMediaType(),source.sizeBytes(),source.observationSha256());
            var observation=codec.observe(target,ObservationKind.COMPLETE_IMMUTABLE,immutable.identity(),immutable.sha256(),immutable.sizeBytes(),immutable.detectedMediaType());
            Instant now=now();var evidence=codec.issueReceipt(UUID.randomUUID(),immutable,proof,observation.observationSha256(),
                    new IngestionProofCodec.ReceiptTimes(now,now,min(proof.expiresAt(),current.accepted.generation().validUntil())));
            byte[] bytes=receiptBytes(evidence);
            if(bytes.length>registration.reservedBytes())throw unavailable();
            var stored=current.receipts.write(registration,new java.io.ByteArrayInputStream(bytes),bytes.length,deadline);
            var receiptObservation=codec.observe(registration,ObservationKind.RECEIPT,stored.identity(),stored.sha256(),(long)bytes.length,"application/vnd.msf.scanned-content-receipt");
            if(!isCurrentGeneration(current.accepted.generation()))throw unavailable();
            return new ImmutableObjectAndReceipt(immutable,proof,evidence,receiptObservation);
        },deadline,null);
    }
    @Override public PreparedSlotLease prepareSlot(ScanPlan plan,Deadline deadline) {
        noTransaction();Objects.requireNonNull(plan);Objects.requireNonNull(deadline);
        Resources current=ready();requireReady();
        if(!isCurrentGeneration(current.accepted.generation())||!plan.generationSha256().equals(current.accepted.generation().generationSha256()))throw unavailable();
        return current.scanner.prepareSlot(current.accepted,plan,deadline);
    }
    @Override public RecoveryObservation observeRecovery(RecordedAllocation allocation,java.util.Optional<RecordedExecution> execution,
            RecoveryBinding binding,Deadline deadline) {
        noTransaction();Objects.requireNonNull(allocation);Objects.requireNonNull(execution);Objects.requireNonNull(binding);Objects.requireNonNull(deadline);
        requireRecoveryBinding(allocation,execution,binding);
        return external(()->{
            noTransaction();var request=allocation.request();
            try {
            if(request.kind()==AllocationKind.SCANNER_SCRATCH) {
                return observeScratchRecovery(allocation,execution,binding,deadline);
            }
            try(var directory=recoveryDirectory(request);var fence=directory.writerFence(request,true)) {
                if(fence==null)return recoveryUnknown(allocation,binding);
                ObjectIdentity identity=directory.observePartial(request,deadline);
                RecoveryPresence presence=identity==null?RecoveryPresence.ABSENT:RecoveryPresence.PRESENT_UNTRUSTED_PARTIAL;
                var baseline=allocation.baseline();
                if(identity!=null&&baseline!=null) {
                    if(!sameObject(identity,baseline.identity()))throw unavailable();
                    try(var opened=directory.open(baseline.identity())){opened.hash(baseline.sha256(),baseline.sizeBytes(),deadline);}
                    presence=RecoveryPresence.PRESENT_COMPLETE_UNCHANGED;
                }
                Instant observed=now();return codec.observeRecovery(binding,request,presence,ProcessState.NONE_STARTED,true,fence.proofHash,observed,
                        null,identity,baselineId(allocation),baselineHash(allocation),fence.stopHash,observed);
            }
            }catch(IOException|FileEvidenceTrustUnavailableException unavailable){return recoveryUnknown(allocation,binding);}
        },deadline,null);
    }
    private RecoveryObservation observeScratchRecovery(RecordedAllocation allocation,java.util.Optional<RecordedExecution> execution,
            RecoveryBinding binding,Deadline deadline)throws IOException {
        // Retained immutable control/profile resolution is deliberately independent of current CLEAN resources.
        if(execution.isEmpty()) {
            if(binding.executionId()!=null)throw unavailable();
            try(var directory=PrivateDirectory.open(properties.storage().quarantineRoot(),properties.storage().rootGeneration(),clock);
                    var fence=directory.writerFence(allocation.request(),true)) {
                if(fence==null)return recoveryUnknown(allocation,binding);Instant observed=now();
                return codec.observeRecovery(binding,allocation.request(),RecoveryPresence.ABSENT,ProcessState.NONE_STARTED,true,fence.proofHash,observed,null,null,
                        baselineId(allocation),baselineHash(allocation),fence.stopHash,observed);
            }
        }
        return ScannerProcessLauncher.observeRecovery(properties,codec,allocation,execution.get(),binding,deadline,clock);
    }
    private void requireRecoveryBinding(RecordedAllocation allocation,java.util.Optional<RecordedExecution> execution,RecoveryBinding binding) {
        var request=allocation.request();
        if(!request.tenantId().equals(binding.tenantId())||!request.intentId().equals(binding.intentId())
                ||!request.allocationId().equals(binding.allocationId())||allocation.eventNo()!=binding.targetEventNo()
                ||!Objects.equals(allocation.eventSha256(),binding.targetEventSha256())||allocation.finalizedFileId()!=null
                ||!request.rootGeneration().equals(properties.storage().rootGeneration()))throw unavailable();
        if(binding.executionId()!=null&&(execution.isEmpty()||!execution.get().descriptor().executionId().equals(binding.executionId())
                ||!execution.get().descriptor().descriptorSha256().equals(binding.descriptorSha256())))throw unavailable();
    }
    private PrivateDirectory recoveryDirectory(AllocationRequest request)throws IOException {
        Path root=switch(request.kind()){case QUARANTINE->properties.storage().quarantineRoot();case IMMUTABLE->properties.storage().immutableRoot();case RECEIPT->properties.storage().receiptRoot();default->throw unavailable();};
        return PrivateDirectory.open(root,request.rootGeneration(),clock);
    }
    private RecoveryObservation recoveryUnknown(RecordedAllocation allocation,RecoveryBinding binding) {
        return codec.observeRecovery(binding,allocation.request(),RecoveryPresence.UNKNOWN,ProcessState.UNKNOWN,false,null,null,null,null,
                baselineId(allocation),baselineHash(allocation),null,now());
    }
    private static UUID baselineId(RecordedAllocation a){return a.baselineEventId();}
    private static String baselineHash(RecordedAllocation a){return a.baseline()==null?null:a.baseline().observationSha256();}
    private static boolean sameObject(ObjectIdentity a,ObjectIdentity b){return b!=null&&a.rootGeneration().equals(b.rootGeneration())
            &&a.serverObjectName().equals(b.serverObjectName())&&a.storageVersion().equals(b.storageVersion())&&a.fileStoreIdentity().equals(b.fileStoreIdentity())
            &&a.fileKey().equals(b.fileKey())&&a.unixDevice()==b.unixDevice()&&a.unixInode()==b.unixInode()&&a.linkCount()==b.linkCount()&&a.measuredBytes()==b.measuredBytes();}
    @Override public DisposalObservation dispose(DisposalClaim claim,RecoveryBinding postClaim,Deadline deadline) {
        noTransaction();Objects.requireNonNull(claim);Objects.requireNonNull(postClaim);Objects.requireNonNull(deadline);
        var claimed=claim.allocation();var requestIdentity=claimed.request();
        if(!postClaim.tenantId().equals(requestIdentity.tenantId())||!postClaim.intentId().equals(requestIdentity.intentId())
                ||!postClaim.allocationId().equals(requestIdentity.allocationId())||postClaim.targetEventNo()<claimed.eventNo()
                ||postClaim.expectedCurrentTechnicalVersion()<claim.technicalVersion())throw unavailable();
        return external(()->{
            noTransaction();var allocation=claim.allocation();var request=allocation.request();var previous=allocation.lastRecoveryObservation();
            if(!request.rootGeneration().equals(properties.storage().rootGeneration())||allocation.finalizedFileId()!=null
                    ||!claim.tombstoneId().equals(allocation.disposalTombstoneId())||previous==null||!previous.quiescent())throw unavailable();
            codec.validateRecovery(previous);
            if(request.kind()==AllocationKind.SCANNER_SCRATCH) {
                if(previous.kernelFacts()!=null)return ScannerProcessLauncher.disposeScratch(properties,codec,claim,postClaim,deadline,clock);
                if(previous.binding().executionId()!=null||previous.processState()!=ProcessState.NONE_STARTED||!previous.writerQuiescent())throw unavailable();
                try(var directory=PrivateDirectory.open(properties.storage().quarantineRoot(),properties.storage().rootGeneration(),clock);var fence=directory.writerFence(request,true)) {
                    if(fence==null||!fence.stopHash.equals(previous.stopWriteFenceSha256()))throw unavailable();Instant observed=now();
                    return codec.observeDisposal(claim,codec.observeRecovery(postClaim,request,RecoveryPresence.ABSENT,ProcessState.NONE_STARTED,true,fence.proofHash,observed,null,null,
                            previous.baselineEventId(),previous.baselineObservationSha256(),fence.stopHash,observed));
                }
            }
            try(var directory=recoveryDirectory(request);var fence=directory.writerFence(request,true)) {
                if(fence==null||!fence.stopHash.equals(previous.stopWriteFenceSha256()))throw unavailable();
                ObjectIdentity present=directory.observePartial(request,deadline);
                if(present!=null) {
                    if(previous.identity()==null||!sameObject(present,previous.identity()))throw unavailable();
                    if(allocation.baseline()!=null) {
                        try(var opened=directory.open(allocation.baseline().identity())){opened.hash(allocation.baseline().sha256(),allocation.baseline().sizeBytes(),deadline);}
                    }
                    directory.disposeObserved(request,present,deadline);
                }
                if(directory.observePartial(request,deadline)!=null)throw unavailable();
                Instant observed=now();var absence=codec.observeRecovery(postClaim,request,RecoveryPresence.ABSENT,ProcessState.NONE_STARTED,
                        true,fence.proofHash,observed,null,null,previous.baselineEventId(),previous.baselineObservationSha256(),fence.stopHash,observed);
                return codec.observeDisposal(claim,absence);
            }
        },deadline,null);
    }
    private void requireFresh(RecordedAllocation allocation,AllocationKind kind) {
        FileIngestionContracts.kind(allocation,kind);
        if(allocation.eventNo()!=0||allocation.baseline()!=null||!allocation.request().rootGeneration().equals(properties.storage().rootGeneration()))throw unavailable();
    }
    private <T>T external(Callable<T> work,Deadline deadline,InputStream input) {
        if(closed.get())throw unavailable();deadline.remainingNanos();Future<T> future;
        try{future=io.submit(work);}catch(RuntimeException refused){throw unavailable();}
        try{return future.get(deadline.remainingNanos(),TimeUnit.NANOSECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();future.cancel(true);closeInputLater(input);throw unavailable();}
        catch(TimeoutException|FileEvidenceTrustUnavailableException timedOut){future.cancel(true);closeInputLater(input);throw unavailable();}
        catch(ExecutionException failure){
            Throwable cause=failure.getCause();if(cause instanceof ContentRejected rejected)throw rejected;
            if(cause instanceof QuarantineNotReusable unusable)throw unusable;
            if(cause instanceof IOException)throw unavailable();if(cause instanceof RuntimeException runtime)throw runtime;
            if(cause instanceof Error error)throw error;throw unavailable();
        }
    }
    private void closeInputLater(InputStream input){if(input!=null)try{streamClosers.execute(()->{try{input.close();}catch(IOException ignored){}});}catch(java.util.concurrent.RejectedExecutionException held){ /* Existing blocked closers stay bounded; unknown readers retain their I/O slots. */ }}
    static void noTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.getResourceMap().entrySet().stream().anyMatch(entry->entry.getKey() instanceof javax.sql.DataSource||entry.getValue() instanceof ConnectionHolder))throw unavailable();
    }
    @Override public synchronized void close(){if(closed.compareAndSet(false,true)){io.shutdownNow();streamClosers.shutdownNow();Resources old=resources;resources=null;if(old!=null)old.close();}}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    private static Instant min(Instant left,Instant right){return left.isBefore(right)?left:right;}
    private static byte[] receiptBytes(IngestionContentReceiptV2 receipt)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var output=new DataOutputStream(bytes)){
            output.writeUTF("SCANNED_CONTENT_V2");byte[] payload=receipt.canonicalPayload();output.writeInt(payload.length);output.write(payload);output.write(receipt.signature());
        }return bytes.toByteArray();
    }
    private static void closeQuietly(AutoCloseable resource){if(resource!=null)try{resource.close();}catch(Exception ignored){}}
    private static FileEvidenceTrustUnavailableException unavailable(){return new FileEvidenceTrustUnavailableException();}
    private record Resources(ImmutableContainmentProfile profile,ClamScanProcessAdapter scanner,ClamScanProcessAdapter.AcceptedResources accepted,
            PrivateDirectory quarantine,PrivateDirectory immutable,PrivateDirectory receipts) implements AutoCloseable {
        public void close(){scanner.close();closeQuietly(quarantine);closeQuietly(immutable);closeQuietly(receipts);}
    }
    record Written(ObjectIdentity identity,String sha256){}
    private enum IoMode { WRITE, READ, STOP }
    private record AllocationIoKey(UUID tenantId,UUID intentId,UUID attemptId,UUID allocationId,
            String rootGeneration,String serverObjectName,String storageVersion) {
        static AllocationIoKey of(AllocationRequest request){return new AllocationIoKey(request.tenantId(),request.intentId(),request.attemptId(),request.allocationId(),request.rootGeneration(),request.serverObjectName(),request.storageVersion());}
        static AllocationIoKey of(QuarantinedObject source){var identity=source.identity();return new AllocationIoKey(source.tenantId(),source.intentId(),source.sourceAttemptId(),source.allocationId(),identity.rootGeneration(),identity.serverObjectName(),identity.storageVersion());}
    }


    /** Descriptor-relative CREATE_NEW and retained directory channels; unsupported providers deny. */
    static final class PrivateDirectory implements AutoCloseable {
        // POSIX fcntl locks can be dropped by closing another fd for the same inode in this JVM.
        // Claim memory ownership BEFORE opening that inode; cross-JVM exclusion is still the OS lock.
        private static final java.util.concurrent.ConcurrentHashMap<String,Object> WRITER_OWNERS=new java.util.concurrent.ConcurrentHashMap<>();
        private final Path path;private final String generation;private final Clock clock;private final UserPrincipal owner;
        private final List<DirectoryStream<Path>> chain;private final SecureDirectoryStream<Path> directory;private final FileChannel parentSync;
        private final Object key;private final String store;
        private PrivateDirectory(Path path,String generation,Clock clock,UserPrincipal owner,List<DirectoryStream<Path>> chain,
                SecureDirectoryStream<Path> directory,FileChannel sync,Object key,String store){this.path=path;this.generation=generation;this.clock=clock;this.owner=owner;this.chain=chain;this.directory=directory;this.parentSync=sync;this.key=key;this.store=store;}
        static PrivateDirectory open(Path path,String generation,Clock clock)throws IOException {
            FileIngestionProperties.absolute(path);var chain=new ArrayList<DirectoryStream<Path>>();FileChannel sync=null;
            try {
                DirectoryStream<Path> first=Files.newDirectoryStream(path.getRoot());chain.add(first);
                if(!(first instanceof SecureDirectoryStream<?>))throw unavailable();
                @SuppressWarnings("unchecked") SecureDirectoryStream<Path> current=(SecureDirectoryStream<Path>)first;
                for(Path component:path){DirectoryStream<Path> next=current.newDirectoryStream(component,LinkOption.NOFOLLOW_LINKS);chain.add(next);if(!(next instanceof SecureDirectoryStream<?>))throw unavailable();@SuppressWarnings("unchecked")var typed=(SecureDirectoryStream<Path>)next;current=typed;}
                var posix=current.getFileAttributeView(PosixFileAttributeView.class).readAttributes();
                UserPrincipal owner=currentOwner(path);
                if(!posix.owner().equals(owner)||!posix.permissions().equals(PosixFilePermissions.fromString("rwx------")))throw unavailable();
                Object key=current.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();if(key==null)throw unavailable();
                sync=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);sync.force(true);
                String store=Files.getFileStore(path).name()+"|"+Files.getFileStore(path).type()+"|"+Files.getAttribute(path,"unix:dev",LinkOption.NOFOLLOW_LINKS);
                var result=new PrivateDirectory(path,generation,clock,owner,chain,current,sync,key,store);result.verifyDirectory();return result;
            }catch(IOException|RuntimeException failure){if(sync!=null)try{sync.close();}catch(IOException ignored){}for(int i=chain.size()-1;i>=0;i--)try{chain.get(i).close();}catch(IOException ignored){}throw failure;}
        }
        Written write(AllocationRequest allocation,InputStream input,long expected,Deadline deadline)throws IOException {
            try(var fence=writerFence(allocation,false)) {
                if(fence==null)throw unavailable();
                return writeLocked(allocation,input,expected,deadline);
            }
        }
        private Written writeLocked(AllocationRequest allocation,InputStream input,long expected,Deadline deadline)throws IOException {
            deadline.remainingNanos();if(Thread.currentThread().isInterrupted())throw unavailable();
            verifyDirectory();if(!allocation.rootGeneration().equals(generation)||expected<1||expected>allocation.reservedBytes())throw unavailable();
            Path name=Path.of(allocation.serverObjectName());FileIngestionContracts.token(allocation.serverObjectName());
            var digest=digest();long count=0;
            Set<OpenOption> options=Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try(SeekableByteChannel opened=directory.newByteChannel(name,options,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                if(!(opened instanceof FileChannel channel))throw unavailable();
                byte[] bytes=new byte[32_768];int n;
                while((n=input.read(bytes,0,(int)Math.min(bytes.length,Math.max(1,expected-count+1))))>=0) {
                    deadline.remainingNanos();if(Thread.currentThread().isInterrupted())throw unavailable();if(n==0)continue;
                    count=Math.addExact(count,n);if(count>expected)throw new ContentRejected(INVALID_LENGTH);
                    ByteBuffer buffer=ByteBuffer.wrap(bytes,0,n);while(buffer.hasRemaining()){deadline.remainingNanos();channel.write(buffer);}digest.update(bytes,0,n);
                }
                if(count!=expected)throw new ContentRejected(INVALID_LENGTH);
                deadline.remainingNanos();verifyRegular(name,count,false);channel.force(true);
                directory.getFileAttributeView(name,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).setPermissions(Set.of(PosixFilePermission.OWNER_READ));
                channel.force(true);
            }
            deadline.remainingNanos();parentSync.force(true);verifyDirectory();ObjectIdentity identity=identity(allocation.serverObjectName(),allocation.storageVersion(),count);
            return new Written(identity,HexFormat.of().formatHex(digest.digest()));
        }
        synchronized Opened open(ObjectIdentity identity)throws IOException {
            verifyDirectory();if(identity==null||!identity.rootGeneration().equals(generation))throw new QuarantineNotReusable();
            Path name=Path.of(identity.serverObjectName());
            try {verifyIdentity(name,identity);}catch(java.nio.file.NoSuchFileException missing){throw new QuarantineNotReusable();}
            SeekableByteChannel channel=directory.newByteChannel(name,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
            if(!(channel instanceof FileChannel file)){channel.close();throw unavailable();}
            try{var opened=new Opened(this,name,file,identity);opened.verifyUnchanged();return opened;}catch(IOException|RuntimeException failure){file.close();throw failure;}
        }
        Opened openBaseline(QuarantinedObject source)throws IOException {
            WriterFence io=ioFence(AllocationIoKey.of(source),IoMode.READ);if(io==null)throw unavailable();
            try{Opened opened=open(source.identity());opened.io=io;return opened;}
            catch(IOException|RuntimeException failure){io.close();throw failure;}
        }
        synchronized void dispose(ObjectIdentity expected,Deadline deadline)throws IOException {
            verifyDirectory();if(expected==null||!expected.rootGeneration().equals(generation))throw unavailable();
            Path name=Path.of(expected.serverObjectName());
            try{verifyIdentity(name,expected);deadline.remainingNanos();directory.deleteFile(name);}
            catch(java.nio.file.NoSuchFileException alreadyAbsent){ /* Same never-reused name; recover deletion-before-checkpoint. */ }
            parentSync.force(true);verifyDirectory();
            try{directory.getFileAttributeView(name,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();throw unavailable();}
            catch(java.nio.file.NoSuchFileException confirmedAbsent){ }
        }
        private ObjectIdentity identity(String name,String version,long size)throws IOException {
            verifyRegular(Path.of(name),size,true);
            var attrs=directory.getFileAttributeView(Path.of(name),BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
            Path absolute=path.resolve(name);
            return new ObjectIdentity(generation,name,version,store,attrs.fileKey().toString(),number(absolute,"unix:dev"),number(absolute,"unix:ino"),number(absolute,"unix:nlink"),size,clock.instant().truncatedTo(ChronoUnit.MICROS));
        }
        private void verifyIdentity(Path name,ObjectIdentity expected)throws IOException {
            verifyRegular(name,expected.measuredBytes(),true);
            var actual=directory.getFileAttributeView(name,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();Path absolute=path.resolve(name);
            if(!actual.fileKey().toString().equals(expected.fileKey())||!store.equals(expected.fileStoreIdentity())
                    ||number(absolute,"unix:dev")!=expected.unixDevice()||number(absolute,"unix:ino")!=expected.unixInode()
                    ||number(absolute,"unix:nlink")!=expected.linkCount())throw new QuarantineNotReusable();
        }
        private void verifyRegular(Path name,long size,boolean readOnly)throws IOException {
            var attrs=directory.getFileAttributeView(name,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
            if(!attrs.isRegularFile()||attrs.isSymbolicLink()||attrs.fileKey()==null||attrs.size()!=size||!attrs.owner().equals(owner)
                    ||number(path.resolve(name),"unix:nlink")!=1)throw new QuarantineNotReusable();
            Set<PosixFilePermission> mode=readOnly?Set.of(PosixFilePermission.OWNER_READ):PosixFilePermissions.fromString("rw-------");
            if(!attrs.permissions().equals(mode))throw new QuarantineNotReusable();
            BasicFileAttributes throughPath=Files.readAttributes(path.resolve(name),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!Objects.equals(attrs.fileKey(),throughPath.fileKey()))throw new QuarantineNotReusable();
        }
        private void verifyDirectory()throws IOException {
            var pinned=directory.getFileAttributeView(PosixFileAttributeView.class).readAttributes();
            var named=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!pinned.isDirectory()||!pinned.owner().equals(owner)||!pinned.permissions().equals(PosixFilePermissions.fromString("rwx------"))
                    ||!Objects.equals(key,pinned.fileKey())||!Objects.equals(key,named.fileKey())||named.isSymbolicLink())throw unavailable();
        }
        /** Same fcntl lock primitive for writer and recovery; never mixed with native slot flock. */
        WriterFence writerFence(AllocationRequest allocation,boolean stop)throws IOException {return ioFence(AllocationIoKey.of(allocation),stop?IoMode.STOP:IoMode.WRITE);}
        private WriterFence ioFence(AllocationIoKey allocation,IoMode mode)throws IOException {
            verifyDirectory();if(!allocation.rootGeneration().equals(generation))throw unavailable();
            String ownershipKey=store+"|"+key+"|"+allocation.allocationId();Object ownership=new Object();
            if(WRITER_OWNERS.putIfAbsent(ownershipKey,ownership)!=null)return null;
            try{return writerFenceOwned(allocation,mode,ownershipKey,ownership);}
            catch(IOException|RuntimeException failure){WRITER_OWNERS.remove(ownershipKey,ownership);throw failure;}
        }
        void validateWriterControl()throws IOException {
            verifyDirectory();try(var controls=PrivateDirectory.open(path.resolve(".writer-control"),generation,clock)){controls.verifyDirectory();}
        }
        private WriterFence writerFenceOwned(AllocationIoKey allocation,IoMode mode,String ownershipKey,Object ownership)throws IOException {
            // Fixed preprovisioned private child, outside scanner mounts; never mkdir through an absolute path.
            try(var control=PrivateDirectory.open(path.resolve(".writer-control"),generation,clock)) {
                String stem=allocation.allocationId().toString();Path name=Path.of(stem+".lock");FileChannel channel;
                try{channel=control.channel(name,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),"rw-------");channel.force(true);control.parentSync.force(true);}
                catch(java.nio.file.FileAlreadyExistsException existing){channel=control.channel(name,Set.of(StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),null);}
                java.nio.channels.FileLock lock=null;
                try {
                    var before=control.directory.getFileAttributeView(name,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
                    if(!before.isRegularFile()||before.isSymbolicLink()||!before.owner().equals(owner)||before.size()!=0||before.fileKey()==null
                            ||!before.permissions().equals(PosixFilePermissions.fromString("rw-------"))||number(control.path.resolve(name),"unix:nlink")!=1)throw unavailable();
                    try{lock=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException busy){channel.close();WRITER_OWNERS.remove(ownershipKey,ownership);return null;}
                    if(lock==null){channel.close();WRITER_OWNERS.remove(ownershipKey,ownership);return null;}
                    var after=control.directory.getFileAttributeView(name,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
                    control.verifyDirectory();if(!Objects.equals(before.fileKey(),after.fileKey()))throw unavailable();
                    Path stopName=Path.of(stem+".stop");byte[] expected=("MSF_WRITE_STOP_V1\n"+generation+"\n"+allocation.tenantId()+"\n"+allocation.intentId()+"\n"+allocation.attemptId()+"\n"+allocation.allocationId()+"\n"+allocation.serverObjectName()+"\n"+allocation.storageVersion()+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    if(mode==IoMode.STOP)try(var out=control.channel(stopName,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),"r--------")) {
                        ByteBuffer bytes=ByteBuffer.wrap(expected);while(bytes.hasRemaining())out.write(bytes);out.force(true);control.parentSync.force(true);
                    }catch(java.nio.file.FileAlreadyExistsException permanent){ /* Presence is permanent even if a crashed STOP write was partial. */ }
                    java.nio.file.attribute.PosixFileAttributes attributes=null;
                    try{attributes=control.directory.getFileAttributeView(stopName,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();}
                    catch(java.nio.file.NoSuchFileException absent){ /* Only ENOENT is absence; all other I/O errors deny. */ }
                    String stopHash=null;
                    if(attributes!=null) {
                        if(mode==IoMode.WRITE)throw unavailable();
                        if(!attributes.isRegularFile()||attributes.isSymbolicLink()||!attributes.owner().equals(owner)||attributes.fileKey()==null
                                ||attributes.size()>expected.length||number(control.path.resolve(stopName),"unix:nlink")!=1
                                ||(!attributes.permissions().equals(Set.of(PosixFilePermission.OWNER_READ))&&!attributes.permissions().equals(PosixFilePermissions.fromString("rw-------"))))throw unavailable();
                        byte[] actual;try(var opened=control.channel(stopName,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS),null)){
                            actual=Channels.newInputStream(opened).readNBytes(expected.length+1);
                        }
                        if(actual.length!=attributes.size()||!MessageDigest.isEqual(actual,Arrays.copyOf(expected,actual.length)))throw unavailable();
                        if(actual.length<expected.length||!attributes.permissions().equals(Set.of(PosixFilePermission.OWNER_READ))) {
                            // Same inode, exact bound prefix, same allocation lock. Never clear/remove STOP.
                            var view=control.directory.getFileAttributeView(stopName,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                            view.setPermissions(PosixFilePermissions.fromString("rw-------"));
                            try(var out=control.channel(stopName,Set.of(StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),null)){
                                ByteBuffer bytes=ByteBuffer.wrap(expected);while(bytes.hasRemaining())out.write(bytes);out.force(true);view.setPermissions(Set.of(PosixFilePermission.OWNER_READ));out.force(true);
                            }
                            control.parentSync.force(true);actual=expected;
                        }
                        var verified=control.directory.getFileAttributeView(stopName,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
                        if(!Objects.equals(attributes.fileKey(),verified.fileKey())||verified.size()!=expected.length||!verified.permissions().equals(Set.of(PosixFilePermission.OWNER_READ)))throw unavailable();
                        stopHash=ScannerGenerationManifest.sha256(actual);
                    }
                    if(stopHash==null&&mode==IoMode.STOP)throw unavailable();control.verifyDirectory();verifyDirectory();
                    String proof=ScannerGenerationManifest.sha256(("MSF_WRITER_QUIESCENT_V1\n"+generation+"\n"+stem+"\n"+before.fileKey()+"\n"+number(control.path.resolve(name),"unix:dev")+"\n"+number(control.path.resolve(name),"unix:ino")+"\n"+stopHash).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    return new WriterFence(channel,lock,stopHash,proof,()->WRITER_OWNERS.remove(ownershipKey,ownership));
                }catch(IOException|RuntimeException failure){if(lock!=null)lock.release();channel.close();throw failure;}
            }
        }
        private FileChannel channel(Path name,Set<OpenOption> options,String mode)throws IOException {
            SeekableByteChannel opened=mode==null?directory.newByteChannel(name,options):directory.newByteChannel(name,options,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode)));
            if(opened instanceof FileChannel file)return file;opened.close();throw unavailable();
        }
        synchronized ObjectIdentity observePartial(AllocationRequest request,Deadline deadline)throws IOException {
            deadline.remainingNanos();verifyDirectory();Path name=Path.of(request.serverObjectName());
            java.nio.file.attribute.PosixFileAttributes a;
            try{a=directory.getFileAttributeView(name,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();}
            catch(java.nio.file.NoSuchFileException absent){parentSync.force(true);return null;}
            if(!a.isRegularFile()||a.isSymbolicLink()||!a.owner().equals(owner)||a.fileKey()==null||number(path.resolve(name),"unix:nlink")!=1
                    ||(!a.permissions().equals(PosixFilePermissions.fromString("rw-------"))&&!a.permissions().equals(Set.of(PosixFilePermission.OWNER_READ))))throw unavailable();
            BasicFileAttributes named=Files.readAttributes(path.resolve(name),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!Objects.equals(a.fileKey(),named.fileKey()))throw unavailable();
            return new ObjectIdentity(generation,request.serverObjectName(),request.storageVersion(),store,a.fileKey().toString(),number(path.resolve(name),"unix:dev"),number(path.resolve(name),"unix:ino"),1,a.size(),clock.instant().truncatedTo(ChronoUnit.MICROS));
        }
        synchronized void disposeObserved(AllocationRequest request,ObjectIdentity expected,Deadline deadline)throws IOException {
            verifyDirectory();Path name=Path.of(expected.serverObjectName());
            try {
                var actual=observePartial(request,deadline);
                if(actual!=null&&(!actual.fileKey().equals(expected.fileKey())||actual.unixDevice()!=expected.unixDevice()||actual.unixInode()!=expected.unixInode()||actual.measuredBytes()!=expected.measuredBytes()||!actual.fileStoreIdentity().equals(expected.fileStoreIdentity())))throw unavailable();
                if(actual!=null)directory.deleteFile(name);
            }catch(java.nio.file.NoSuchFileException absent){ /* Exact permanently fenced name already absent. */ }
            parentSync.force(true);verifyDirectory();
        }
        private static UserPrincipal currentOwner(Path path)throws IOException {
            // user.name may be absent or overridden, especially for a numeric container UID.
            byte[] bytes;try(var input=Files.newInputStream(Path.of("/proc/self/status"))){bytes=input.readNBytes(32_769);}
            if(bytes.length>32_768)throw unavailable();
            for(String line:new String(bytes,java.nio.charset.StandardCharsets.US_ASCII).split("\n"))if(line.startsWith("Uid:")){
                String[] ids=line.substring(4).strip().split("\\s+");if(ids.length!=4||!ids[0].matches("0|[1-9][0-9]{0,9}"))throw unavailable();
                for(String id:ids)if(!id.equals(ids[0]))throw unavailable();
                return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(ids[0]);
            }
            throw unavailable();
        }
        private static long number(Path path,String key)throws IOException {Object value=Files.getAttribute(path,key,LinkOption.NOFOLLOW_LINKS);if(!(value instanceof Number number)||number.longValue()<0)throw unavailable();return number.longValue();}
        @Override public void close()throws IOException {IOException failure=null;try{parentSync.close();}catch(IOException ex){failure=ex;}for(int i=chain.size()-1;i>=0;i--)try{chain.get(i).close();}catch(IOException ex){if(failure==null)failure=ex;else failure.addSuppressed(ex);}if(failure!=null)throw failure;}
    }
    static final class WriterFence implements AutoCloseable {
        final FileChannel channel;final java.nio.channels.FileLock lock;final String stopHash,proofHash;final Runnable releaseOwner;
        WriterFence(FileChannel channel,java.nio.channels.FileLock lock,String stopHash,String proofHash,Runnable releaseOwner){this.channel=channel;this.lock=lock;this.stopHash=stopHash;this.proofHash=proofHash;this.releaseOwner=releaseOwner;}
        @Override public void close()throws IOException{try{try{lock.release();}finally{channel.close();}}finally{releaseOwner.run();}}
    }
    private static final class Opened implements AutoCloseable {
        final PrivateDirectory directory;final Path name;final FileChannel channel;final ObjectIdentity identity;
        private WriterFence io;private final AtomicBoolean closed=new AtomicBoolean();
        private Opened(PrivateDirectory directory,Path name,FileChannel channel,ObjectIdentity identity){this.directory=directory;this.name=name;this.channel=channel;this.identity=identity;}
        void verifyUnchanged()throws IOException {directory.verifyDirectory();directory.verifyIdentity(name,identity);if(channel.size()!=identity.measuredBytes())throw new QuarantineNotReusable();}
        void hash(String expected,long size,Deadline deadline)throws IOException {
            channel.position(0);var digest=digest();long count=0;ByteBuffer buffer=ByteBuffer.allocate(32_768);int n;
            while((n=channel.read(buffer))>=0){deadline.remainingNanos();if(n==0)continue;count=Math.addExact(count,n);if(count>size)throw new QuarantineNotReusable();buffer.flip();digest.update(buffer);buffer.clear();}
            verifyUnchanged();if(count!=size||!HexFormat.of().formatHex(digest.digest()).equals(expected))throw new QuarantineNotReusable();channel.position(0);
        }
        @Override public void close()throws IOException {if(closed.compareAndSet(false,true)){try{channel.close();}finally{if(io!=null)io.close();}}}
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}

    /** Bounded structural policy, not a promise that arbitrary image rendering is safe. */
    static final class MediaPolicy {
        private static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};
        static String detect(FileChannel channel,long size,String declared,long maxPixels,Deadline deadline)throws IOException {
            channel.position(0);var input=new DataInputStream(Channels.newInputStream(channel));byte[] signature=input.readNBytes(8);channel.position(0);
            String type;
            if(Arrays.equals(signature,PNG)){png(input,size,maxPixels,deadline);type="image/png";}
            else if(signature.length>=2&&(signature[0]&255)==255&&(signature[1]&255)==216){jpeg(input,size,maxPixels,deadline);type="image/jpeg";}
            else throw new ContentRejected(UNSUPPORTED_MEDIA);
            if(!type.equals(declared))throw new ContentRejected(UNSUPPORTED_MEDIA);channel.position(0);return type;
        }
        private static void png(DataInputStream input,long size,long maxPixels,Deadline deadline)throws IOException {
            if(!Arrays.equals(input.readNBytes(8),PNG))bad();long consumed=8;boolean header=false,data=false,endedData=false,palette=false;long expanded=0,expected=0,row=0;
            var inflater=new Inflater();byte[] compressed=new byte[32_768],output=new byte[32_768];
            try {
                while(consumed<size) {
                    deadline.remainingNanos();long length=Integer.toUnsignedLong(input.readInt());int type=input.readInt();consumed=Math.addExact(consumed,12+length);
                    if(consumed>size)bad();
                    String name=new String(ByteBuffer.allocate(4).putInt(type).array(),java.nio.charset.StandardCharsets.US_ASCII);
                    if(!Set.of("IHDR","PLTE","IDAT","IEND").contains(name))bad();
                    var crc=new CRC32();crc.update(ByteBuffer.allocate(4).putInt(type).array());
                    if(name.equals("IHDR")) {
                        if(header||data||length!=13)bad();byte[] body=input.readNBytes(13);if(body.length!=13)bad();crc.update(body);
                        ByteBuffer dimensions=ByteBuffer.wrap(body);long width=Integer.toUnsignedLong(dimensions.getInt()),height=Integer.toUnsignedLong(dimensions.getInt());
                        int bits=body[8]&255,color=body[9]&255;
                        if(width<1||height<1||Math.multiplyExact(width,height)>maxPixels||bits!=8||(color!=2&&color!=6)||body[10]!=0||body[11]!=0||body[12]!=0)bad();
                        row=Math.addExact(Math.multiplyExact(width,color==2?3:4),1);expected=Math.multiplyExact(row,height);header=true;
                    } else if(name.equals("PLTE")) {
                        if(!header||data||palette||length<3||length>768||length%3!=0)bad();palette=true;byte[] body=input.readNBytes((int)length);if(body.length!=length)bad();crc.update(body);
                    } else if(name.equals("IDAT")) {
                        if(!header||endedData||length<1)bad();data=true;long left=length;
                        while(left>0){deadline.remainingNanos();int n=input.read(compressed,0,(int)Math.min(left,compressed.length));if(n<1)bad();left-=n;crc.update(compressed,0,n);if(inflater.finished())bad();inflater.setInput(compressed,0,n);
                            while(!inflater.needsInput()) {
                                deadline.remainingNanos();int count;try{count=inflater.inflate(output);}catch(java.util.zip.DataFormatException malformed){throw new ContentRejected(MALFORMED_CONTENT);}
                                if(count==0){if(inflater.finished())break;bad();}
                                for(int i=0;i<count;i++)if((expanded+i)%row==0&&(output[i]&255)>4)bad();
                                expanded=Math.addExact(expanded,count);if(expanded>expected)bad();
                            }
                            if(inflater.finished()&&inflater.getRemaining()!=0)bad();
                        }
                    } else {
                        endedData=true;if(!header||!data||length!=0||!inflater.finished()||expanded!=expected||consumed!=size)bad();
                    }
                    if(Integer.toUnsignedLong(input.readInt())!=crc.getValue())bad();
                    if(name.equals("IEND"))return;
                }
                bad();
            }catch(java.io.EOFException|ArithmeticException malformed){throw new ContentRejected(MALFORMED_CONTENT);}finally{inflater.end();}
        }
        private static void jpeg(DataInputStream input,long size,long maxPixels,Deadline deadline)throws IOException {
            if(input.readUnsignedShort()!=0xffd8)bad();long consumed=2,entropy=0;boolean frame=false,scan=false;int pending=-1,restart=0,interval=0;
            var components=new java.util.HashMap<Integer,Integer>();var quantization=new java.util.HashSet<Integer>();var huffman=new java.util.HashSet<Integer>();
            try {
                while(consumed<size||pending>=0) {
                    deadline.remainingNanos();int marker;
                    if(pending>=0){marker=pending;pending=-1;}else{if(input.readUnsignedByte()!=255)bad();consumed++;do{marker=input.readUnsignedByte();consumed++;}while(marker==255);}
                    if(marker==0xd9){if(!frame||!scan||entropy<1||consumed!=size)bad();return;}
                    if(marker==0xd8||marker==0||marker>=0xe0||marker==0xfe||marker>=0xd0&&marker<=0xd7)bad();
                    int length=input.readUnsignedShort();consumed+=2;if(length<2||length-2>size-consumed)bad();
                    byte[] body=input.readNBytes(length-2);consumed+=body.length;if(body.length!=length-2)bad();
                    if(marker==0xc0){
                        if(frame||scan||body.length<6||(body[0]&255)!=8)bad();int h=(body[1]&255)*256+(body[2]&255),w=(body[3]&255)*256+(body[4]&255),count=body[5]&255;
                        if(w<1||h<1||(long)w*h>maxPixels||(count!=1&&count!=3)||body.length!=6+3*count)bad();
                        for(int i=0;i<count;i++){int id=body[6+3*i]&255,sampling=body[7+3*i]&255,table=body[8+3*i]&255;
                            if(id==0||sampling/16<1||sampling/16>4||sampling%16<1||sampling%16>4||table>3||components.put(id,table)!=null)bad();}
                        frame=true;
                    } else if(marker==0xc2){throw new ContentRejected(UNSUPPORTED_MEDIA);
                    } else if(marker==0xdb){
                        if(scan||body.length<65)bad();int offset=0;
                        while(offset<body.length){int table=body[offset++]&255;if(table>3||body.length-offset<64)bad();
                            for(int i=0;i<64;i++)if(body[offset+i]==0)bad();offset+=64;quantization.add(table);}
                    } else if(marker==0xc4){
                        if(scan||body.length<18)bad();int offset=0;
                        while(offset<body.length){int table=body[offset++]&255;if((table>>4)>1||(table&15)>3||body.length-offset<16)bad();
                            int symbols=0,left=1;for(int i=0;i<16;i++){int count=body[offset+i]&255;symbols+=count;left=2*left-count;if(left<0)bad();}
                            offset+=16;if(symbols<1||symbols>256||left==0||body.length-offset<symbols)bad();var unique=new java.util.HashSet<Integer>();
                            for(int i=0;i<symbols;i++){int symbol=body[offset+i]&255;if(!unique.add(symbol))bad();if((table>>4)==0?symbol>11:(symbol&15)>10||((symbol&15)==0&&symbol!=0&&symbol!=240))bad();}
                            offset+=symbols;huffman.add(table);}
                    } else if(marker==0xdd){if(scan||body.length!=2)bad();interval=(body[0]&255)*256+(body[1]&255);
                    } else if(marker==0xda){
                        int count=body.length>0?body[0]&255:0;
                        if(!frame||scan||count!=components.size()||body.length!=4+2*count||body[body.length-3]!=0||body[body.length-2]!=63||body[body.length-1]!=0)bad();
                        var ids=new java.util.HashSet<Integer>();
                        for(int i=0;i<count;i++){int id=body[1+2*i]&255,tables=body[2+2*i]&255;
                            if(!ids.add(id)||!components.containsKey(id)||!quantization.contains(components.get(id))||!huffman.contains(tables>>4)||!huffman.contains(16+(tables&15)))bad();}
                        scan=true;
                        while(consumed<size){deadline.remainingNanos();int b=input.readUnsignedByte();consumed++;if(b!=255){entropy++;continue;}
                            do{b=input.readUnsignedByte();consumed++;}while(b==255);
                            if(b==0){entropy++;continue;}
                            if(b>=0xd0&&b<=0xd7){if(interval==0||b!=0xd0+restart)bad();restart=(restart+1)%8;continue;}
                            pending=b;break;}
                    } else bad();
                }bad();
            }catch(java.io.EOFException malformed){throw new ContentRejected(MALFORMED_CONTENT);}
        }
        private static void bad(){throw new ContentRejected(MALFORMED_CONTENT);}
    }
}
