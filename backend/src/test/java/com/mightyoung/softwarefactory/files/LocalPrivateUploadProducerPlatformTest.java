package com.mightyoung.softwarefactory.files;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static org.assertj.core.api.Assertions.*;

/** Actual filesystem tests; none claims scanner, containment, or resource-generation acceptance. */
class LocalPrivateUploadProducerPlatformTest {
    @TempDir Path temporary;
    @Test void createNewWritesDurableReadOnlyIdentityAndNeverOverwrites()throws Exception {
        Path root=root("quarantine");byte[] content=png(1,1,new byte[]{0,1,2,3});
        try(var directory=LocalPrivateUploadProducer.PrivateDirectory.open(root,"a".repeat(64),Clock.systemUTC())) {
            var allocation=allocation("object-one",content.length);
            var written=directory.write(allocation,new ByteArrayInputStream(content),content.length,deadline());
            assertThat(written.identity().measuredBytes()).isEqualTo(content.length);assertThat(written.identity().linkCount()).isEqualTo(1);
            assertThat(written.sha256()).isEqualTo(ScannerGenerationManifest.sha256(content));
            assertThat(Files.getPosixFilePermissions(root.resolve("object-one"))).isEqualTo(PosixFilePermissions.fromString("r--------"));
            assertThatThrownBy(()->directory.write(allocation,new ByteArrayInputStream(new byte[content.length]),content.length,deadline())).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
            assertThat(Files.readAllBytes(root.resolve("object-one"))).containsExactly(content);
            directory.dispose(written.identity(),deadline());assertThat(Files.exists(root.resolve("object-one"))).isFalse();
        }
    }
    @Test void shortAndExtraStreamsRemainUntrustedAndRetained()throws Exception {
        Path root=root("quarantine");
        try(var directory=LocalPrivateUploadProducer.PrivateDirectory.open(root,"a".repeat(64),Clock.systemUTC())) {
            assertThatThrownBy(()->directory.write(allocation("short",3),new ByteArrayInputStream(new byte[]{1,2}),3,deadline()))
                    .isInstanceOfSatisfying(FileIngestionPort.ContentRejected.class,e->assertThat(e.reason()).isEqualTo(FileIngestionPort.RejectionReason.INVALID_LENGTH));
            assertThat(Files.readAllBytes(root.resolve("short"))).containsExactly(1,2);
            assertThat(Files.getPosixFilePermissions(root.resolve("short"))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThatThrownBy(()->directory.write(allocation("long",3),new ByteArrayInputStream(new byte[]{1,2,3,4}),3,deadline())).isInstanceOf(FileIngestionPort.ContentRejected.class);
            assertThat(Files.exists(root.resolve("long"))).isTrue();
        }
    }
    @Test void symlinkAndNamedRootReplacementCannotRedirectWrites()throws Exception {
        Path root=root("quarantine"),other=root("other");Path link=temporary.resolve("link");Files.createSymbolicLink(link,root);
        assertThatThrownBy(()->LocalPrivateUploadProducer.PrivateDirectory.open(link,"a".repeat(64),Clock.systemUTC())).isInstanceOfAny(java.io.IOException.class,FileEvidenceTrustUnavailableException.class);
        try(var directory=LocalPrivateUploadProducer.PrivateDirectory.open(root,"a".repeat(64),Clock.systemUTC())) {
            Files.move(root,temporary.resolve("old"));Files.createDirectory(root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            assertThatThrownBy(()->directory.write(allocation("redirect",3),new ByteArrayInputStream(new byte[]{1,2,3}),3,deadline())).isInstanceOf(FileEvidenceTrustUnavailableException.class);
            assertThat(Files.exists(root.resolve("redirect"))).isFalse();assertThat(Files.exists(temporary.resolve("old/redirect"))).isFalse();
        }
    }
    @Test void hardlinkAndIdentityMismatchDoNotDeleteOrReuseOriginal()throws Exception {
        Path root=root("quarantine");
        try(var directory=LocalPrivateUploadProducer.PrivateDirectory.open(root,"a".repeat(64),Clock.systemUTC())) {
            var written=directory.write(allocation("original",3),new ByteArrayInputStream(new byte[]{1,2,3}),3,deadline());
            Files.createLink(root.resolve("alias"),root.resolve("original"));
            assertThatThrownBy(()->directory.dispose(written.identity(),deadline())).isInstanceOf(FileIngestionPort.QuarantineNotReusable.class);
            assertThat(Files.exists(root.resolve("original"))).isTrue();assertThat(Files.exists(root.resolve("alias"))).isTrue();
        }
    }
    @Test void structuralPngPolicyRejectsTrailingBytesBadCrcAndExpandedSizeMismatch()throws Exception {
        Path root=root("media");byte[] valid=png(1,1,new byte[]{0,1,2,3});
        assertThat(detect(root,valid,"image/png")).isEqualTo("image/png");
        assertThatThrownBy(()->detect(root,java.util.Arrays.copyOf(valid,valid.length+1),"image/png")).isInstanceOf(FileIngestionPort.ContentRejected.class);
        byte[] bad=valid.clone();bad[bad.length-1]^=1;
        assertThatThrownBy(()->detect(root,bad,"image/png")).isInstanceOf(FileIngestionPort.ContentRejected.class);
        assertThatThrownBy(()->detect(root,png(1,1,new byte[]{0,1,2,3,4}),"image/png")).isInstanceOf(FileIngestionPort.ContentRejected.class);
        assertThatThrownBy(()->detect(root,valid,"image/jpeg")).isInstanceOf(FileIngestionPort.ContentRejected.class);
    }
    @Test void baselineJpegRequiresActualTablesAndRejectsMetadataAndTrailingBytes()throws Exception {
        Path root=root("jpeg");var bytes=new ByteArrayOutputStream();
        var image=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB);
        assertThat(javax.imageio.ImageIO.write(image,"jpeg",bytes)).isTrue();byte[] original=bytes.toByteArray();
        assertThatThrownBy(()->detect(root,original,"image/jpeg")).isInstanceOf(FileIngestionPort.ContentRejected.class);
        // Strip the known generated APP0 metadata segment, retaining real JDK-generated tables/entropy.
        int segment=((original[4]&255)<<8)+(original[5]&255);var restricted=new ByteArrayOutputStream();restricted.write(original,0,2);restricted.write(original,4+segment,original.length-4-segment);
        byte[] valid=restricted.toByteArray();assertThat(detect(root,valid,"image/jpeg")).isEqualTo("image/jpeg");
        assertThatThrownBy(()->detect(root,java.util.Arrays.copyOf(valid,valid.length+1),"image/jpeg")).isInstanceOf(FileIngestionPort.ContentRejected.class);
        byte[] noTables={(byte)255,(byte)216,(byte)255,(byte)192,0,11,8,0,1,0,1,1,1,17,0,(byte)255,(byte)218,0,8,1,1,0,0,63,0,1,(byte)255,(byte)217};
        assertThatThrownBy(()->detect(root,noTables,"image/jpeg")).isInstanceOf(FileIngestionPort.ContentRejected.class);
    }
    @Test void externalBoundaryRejectsSynchronizationAndBoundConnectionBeforeEffects() {
        TransactionSynchronizationManager.initSynchronization();try{
            assertThatThrownBy(LocalPrivateUploadProducer::noTransaction).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        }finally{TransactionSynchronizationManager.clearSynchronization();}
        var datasource=new DriverManagerDataSource();TransactionSynchronizationManager.bindResource(datasource,new Object());try{
            assertThatThrownBy(LocalPrivateUploadProducer::noTransaction).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        }finally{TransactionSynchronizationManager.unbindResource(datasource);}
    }
    @Test void recoveryFencesNeverCreatedNameBeforeLateWriterAndDoesNotNeedScannerOrKeys()throws Exception {
        Path root=root("fenced-absent");var request=allocation("never-created",3);
        try(var directory=LocalPrivateUploadProducer.PrivateDirectory.open(root,"a".repeat(64),Clock.systemUTC())) {
            try(var fence=directory.writerFence(request,true)){assertThat(fence).isNotNull();assertThat(fence.stopHash).hasSize(64);assertThat(directory.observePartial(request,deadline())).isNull();}
            assertThatThrownBy(()->directory.write(request,new ByteArrayInputStream(new byte[]{1,2,3}),3,deadline())).isInstanceOf(FileEvidenceTrustUnavailableException.class);
            assertThat(Files.exists(root.resolve("never-created"))).isFalse();
        }
    }
    @Test void liveWriterLockPreventsRecoveryAndPartialBecomesOnlyUntrustedTargetAfterClose()throws Exception {
        Path root=root("partial-fence");var request=allocation("partial",3);
        try(var directory=LocalPrivateUploadProducer.PrivateDirectory.open(root,"a".repeat(64),Clock.systemUTC())) {
            try(var live=directory.writerFence(request,false)){assertThat(live).isNotNull();assertThat(directory.writerFence(request,true)).isNull();assertThat(Files.exists(root.resolve(".writer-control/"+request.allocationId()+".stop"))).isFalse();}
            assertThatThrownBy(()->directory.write(request,new ByteArrayInputStream(new byte[]{1,2}),3,deadline())).isInstanceOf(FileIngestionPort.ContentRejected.class);
            try(var fence=directory.writerFence(request,true)){var partial=directory.observePartial(request,deadline());assertThat(partial.measuredBytes()).isEqualTo(2);directory.disposeObserved(request,partial,deadline());assertThat(directory.observePartial(request,deadline())).isNull();}
        }
    }
    @Test void tamperedPermanentFenceOrLockInodeLinkCannotAuthorizeDeletion()throws Exception {
        Path root=root("tampered-fence");var request=allocation("not-created",3);
        try(var directory=LocalPrivateUploadProducer.PrivateDirectory.open(root,"a".repeat(64),Clock.systemUTC())) {
            try(var fence=directory.writerFence(request,true)){assertThat(fence).isNotNull();}
            Path stop=root.resolve(".writer-control/"+request.allocationId()+".stop");Files.setPosixFilePermissions(stop,PosixFilePermissions.fromString("rw-------"));Files.writeString(stop,"forged");Files.setPosixFilePermissions(stop,PosixFilePermissions.fromString("r--------"));
            assertThatThrownBy(()->directory.writerFence(request,true)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        }
    }
    @Test void secondJvmSeesLiveOsLockAfterLocalBusyRecoveryAttempt()throws Exception {
        Path root=root("cross-jvm-fence");var request=allocation("not-created",3);
        try(var directory=LocalPrivateUploadProducer.PrivateDirectory.open(root,"a".repeat(64),Clock.systemUTC());var live=directory.writerFence(request,false)) {
            assertThat(live).isNotNull();assertThat(directory.writerFence(request,true)).isNull();
            String javaBinary=Path.of(System.getProperty("java.home"),"bin","java").toString();String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
            Process child=new ProcessBuilder(javaBinary,"-cp",cp,WriterLockChild.class.getName(),root.resolve(".writer-control/"+request.allocationId()+".lock").toString()).redirectErrorStream(true).start();
            try{assertThat(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();assertThat(new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("BUSY\n");}
            finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
        }
    }
    public static final class WriterLockChild {
        public static void main(String[] args)throws Exception {
            try(var channel=FileChannel.open(Path.of(args[0]),StandardOpenOption.READ,StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
                var lock=channel.tryLock();if(lock==null){System.out.println("BUSY");return;}try(lock){System.out.println("UNSAFE_UNLOCKED");System.exit(1);}
            }
        }
    }
    @Test void interruptedStopPrefixRepairsSameInodeAndNeverLetsLateWriterCreate()throws Exception {
        Path root=root("partial-stop");var request=allocation("not-created",3);Path stop=root.resolve(".writer-control/"+request.allocationId()+".stop");
        try(var directory=LocalPrivateUploadProducer.PrivateDirectory.open(root,"a".repeat(64),Clock.systemUTC())){
            try(var fence=directory.writerFence(request,true)){assertThat(fence).isNotNull();}
            Object inode=Files.readAttributes(stop,java.nio.file.attribute.BasicFileAttributes.class).fileKey();byte[] original=Files.readAllBytes(stop);
            Files.setPosixFilePermissions(stop,PosixFilePermissions.fromString("rw-------"));Files.write(stop,java.util.Arrays.copyOf(original,17));Files.setPosixFilePermissions(stop,PosixFilePermissions.fromString("r--------"));
            assertThatThrownBy(()->directory.write(request,new ByteArrayInputStream(new byte[]{1,2,3}),3,deadline())).isInstanceOf(FileEvidenceTrustUnavailableException.class);
            try(var repaired=directory.writerFence(request,true)){assertThat(repaired.stopHash).isEqualTo(ScannerGenerationManifest.sha256(original));assertThat(Files.readAllBytes(stop)).containsExactly(original);assertThat(Files.readAttributes(stop,java.nio.file.attribute.BasicFileAttributes.class).fileKey()).isEqualTo(inode);}
        }
    }
    @Test void directRecoveryOfNeverCreatedObjectWorksWithoutSigningOrCleanGeneration()throws Exception {
        Path quarantine=root("direct-quarantine"),immutable=root("direct-immutable"),receipts=root("direct-receipts");var request=allocation("never-created",3);
        var allocation=new RecordedAllocation(request,0,null,null,3,null,null,null);
        var binding=new RecoveryBinding(request.tenantId(),request.intentId(),request.allocationId(),UUID.randomUUID(),0,1,0,null,null,null,RecoveryReason.EXPIRED_WORK,"d".repeat(64));
        try(var producer=producer(quarantine,immutable,receipts)){
            assertThatThrownBy(producer::currentResources).isInstanceOf(FileEvidenceTrustUnavailableException.class);
            var observed=producer.observeRecovery(allocation,java.util.Optional.empty(),binding,deadline());
            assertThat(observed.presence()).isEqualTo(RecoveryPresence.ABSENT);assertThat(observed.identity()).isNull();assertThat(observed.quiescent()).isTrue();assertThat(allocation.retainedBytes()).isEqualTo(3);
        }
    }
    @Test void missingRetainedProfileReturnsStructuredUnknownWithoutSigningOrChargeReduction()throws Exception {
        Path quarantine=root("missing-quarantine"),immutable=root("missing-immutable"),receipts=root("missing-receipts");
        var plan=new ScanPlan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),"scan-0",0,1,"b".repeat(64),"c".repeat(64));
        var fence=new TechnicalFence(plan.tenantId(),plan.intentId(),plan.scanAttemptId(),UUID.randomUUID(),1,1);var execution=new RecordedExecution(ExecutionDescriptor.forClaim(plan,fence,Instant.now()));
        var request=new AllocationRequest(plan.tenantId(),plan.intentId(),plan.fileId(),UUID.randomUUID(),plan.scanAttemptId(),plan.scratchAllocationId(),AllocationKind.SCANNER_SCRATCH,"a".repeat(64),"scratch-test","version-one",1024,1,Instant.now());
        var allocation=new RecordedAllocation(request,0,null,null,1024,null,null,null);var binding=new RecoveryBinding(request.tenantId(),request.intentId(),request.allocationId(),UUID.randomUUID(),2,1,0,null,plan.plannedExecutionId(),execution.descriptor().descriptorSha256(),RecoveryReason.EXPIRED_WORK,"d".repeat(64));
        try(var producer=producer(quarantine,immutable,receipts)){
            var observed=producer.observeRecovery(allocation,java.util.Optional.of(execution),binding,deadline());
            assertThat(observed.presence()).isEqualTo(RecoveryPresence.UNKNOWN);assertThat(observed.processState()).isEqualTo(ProcessState.UNKNOWN);assertThat(observed.quiescent()).isFalse();assertThat(observed.identity()).isNull();assertThat(allocation.retainedBytes()).isEqualTo(1024);
        }
    }
    @Test void actualQuarantineReaderPinsOsAllocationLockUntilFdCloseAndStillAllowsStoppedReadReuse()throws Exception {
        Path quarantine=root("reader-quarantine"),immutable=root("reader-immutable"),receipts=root("reader-receipts");byte[] content=png(1,1,new byte[]{0,1,2,3});var request=allocation("reader-source",content.length);
        var allocation=new RecordedAllocation(request,0,null,null,content.length,null,null,null);
        var binding=new RecoveryBinding(request.tenantId(),request.intentId(),request.allocationId(),UUID.randomUUID(),0,1,0,null,null,null,RecoveryReason.EXPIRED_WORK,"d".repeat(64));
        try(var directory=LocalPrivateUploadProducer.PrivateDirectory.open(quarantine,"a".repeat(64),Clock.systemUTC());var producer=producer(quarantine,immutable,receipts)){
            var written=directory.write(request,new ByteArrayInputStream(content),content.length,deadline());String media;
            try(var channel=FileChannel.open(quarantine.resolve(request.serverObjectName()),StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)){media=LocalPrivateUploadProducer.MediaPolicy.detect(channel,content.length,"image/png",1000,deadline());}
            // Actual durable file facts only; no scanner verdict, signing key or database success is invented.
            var observation=IngestionProofCodec.unavailable().observe(request,ObservationKind.COMPLETE_QUARANTINE,written.identity(),written.sha256(),(long)content.length,media);
            var source=new QuarantinedObject(request.tenantId(),request.intentId(),request.fileId(),request.allocationId(),request.attemptId(),written.identity(),written.sha256(),media,content.length,observation.observationSha256());
            try(AutoCloseable reader=directory.openBaseline(source)){
                String javaBinary=Path.of(System.getProperty("java.home"),"bin","java").toString();String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
                Process child=new ProcessBuilder(javaBinary,"-cp",cp,WriterLockChild.class.getName(),quarantine.resolve(".writer-control/"+request.allocationId()+".lock").toString()).redirectErrorStream(true).start();
                try{assertThat(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();byte[] diagnostic=child.getInputStream().readNBytes(65537);if(diagnostic.length>65536)throw new AssertionError("HELPER_OUTPUT_LIMIT");String text=new String(diagnostic,java.nio.charset.StandardCharsets.US_ASCII);String marker=text.equals("BUSY\n")?"BUSY":text.equals("UNSAFE_UNLOCKED\n")?"UNSAFE_UNLOCKED":"HELPER_ERROR";System.out.println("reader-pin-child="+marker);System.out.println("reader-pin-classpath-sha256="+ScannerGenerationManifest.sha256(cp.getBytes(java.nio.charset.StandardCharsets.UTF_8)));assertThat(child.exitValue()).as("child marker=%s bytes=%s",marker,diagnostic.length).isZero();assertThat(marker).isEqualTo("BUSY");}
                finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
                var observed=java.util.concurrent.CompletableFuture.supplyAsync(()->producer.observeRecovery(allocation,java.util.Optional.empty(),binding,deadline())).get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(observed.presence()).isEqualTo(RecoveryPresence.UNKNOWN);assertThat(observed.quiescent()).isFalse();assertThat(observed.identity()).isNull();assertThat(Files.exists(quarantine.resolve(request.serverObjectName()))).isTrue();assertThat(allocation.retainedBytes()).isEqualTo(content.length);
            }
            var observed=producer.observeRecovery(allocation,java.util.Optional.empty(),binding,deadline());
            assertThat(observed.presence()).isEqualTo(RecoveryPresence.PRESENT_UNTRUSTED_PARTIAL);assertThat(observed.quiescent()).isTrue();
            // WRITE_STOP prevents old writes, while a valid immutable source remains legitimately readable.
            try(AutoCloseable reused=directory.openBaseline(source)){
                assertThat(directory.writerFence(request,true)).isNull();assertThat(Files.exists(quarantine.resolve(request.serverObjectName()))).isTrue();
            }
            try(var stopped=directory.writerFence(request,true)){assertThat(stopped).isNotNull();directory.disposeObserved(request,observed.identity(),deadline());assertThat(directory.observePartial(request,deadline())).isNull();}
            assertThatThrownBy(()->directory.openBaseline(source)).isInstanceOf(FileIngestionPort.QuarantineNotReusable.class);
        }
    }
    private static LocalPrivateUploadProducer producer(Path quarantine,Path immutable,Path receipts){
        var base=ClamScanProcessAdapterTest.properties();var properties=new FileIngestionProperties(true,new FileIngestionProperties.Storage(quarantine,immutable,receipts,"a".repeat(64)),base.scanner(),base.limits(),base.allowedMediaTypes(),base.policyVersion(),base.verifierId());
        return new LocalPrivateUploadProducer(properties,IngestionProofCodec.unavailable());
    }
    private Path root(String name)throws Exception {
        SecureLocalStorageTestSupport.requireOrAssumeSecureStorage(temporary);
        Path root=Files.createDirectory(temporary.resolve(name),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createDirectory(root.resolve(".writer-control"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));return root;
    }
    private static String detect(Path root,byte[] bytes,String type)throws Exception {
        Path file=root.resolve(UUID.randomUUID().toString());Files.write(file,bytes);
        try(var channel=FileChannel.open(file,StandardOpenOption.READ)){return LocalPrivateUploadProducer.MediaPolicy.detect(channel,bytes.length,type,1000,deadline());}
    }
    private static AllocationRequest allocation(String name,long bytes){return new AllocationRequest(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),AllocationKind.QUARANTINE,"a".repeat(64),name,"version-one",bytes,1,Instant.now());}
    private static Deadline deadline(){return Deadline.after(Duration.ofSeconds(10));}
    static byte[] png(int width,int height,byte[] pixels)throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        chunk(out,"IHDR",ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte)8).put((byte)2).put((byte)0).put((byte)0).put((byte)0).array());
        var compressed=new ByteArrayOutputStream();try(var deflater=new DeflaterOutputStream(compressed)){deflater.write(pixels);}chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);return bytes.toByteArray();
    }
    private static void chunk(DataOutputStream out,String type,byte[] body)throws Exception {byte[] name=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);var crc=new CRC32();crc.update(name);crc.update(body);out.writeInt(body.length);out.write(name);out.write(body);out.writeInt((int)crc.getValue());}
}
