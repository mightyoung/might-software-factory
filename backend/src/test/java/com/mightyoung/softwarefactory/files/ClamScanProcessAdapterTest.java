package com.mightyoung.softwarefactory.files;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static com.mightyoung.softwarefactory.files.ScannerBrokerProtocol.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic wire fixtures test parsing only, never kernel/scanner/profile acceptance. */
class ClamScanProcessAdapterTest {
    @Test void exactFullChallengeAndBindingsRoundTripWithoutTruncation()throws Exception {
        var binding=binding();var writer=new ScannerBrokerProtocol(binding);var bytes=new ByteArrayOutputStream();
        writer.write(bytes,Type.PREPARE,NO_DESCRIPTOR,new byte[]{1,2,3});
        var reader=new ScannerBrokerProtocol(binding);var frame=reader.read(new ByteArrayInputStream(bytes.toByteArray()),Type.PREPARE,NO_DESCRIPTOR);
        assertThat(frame.body()).containsExactly(1,2,3);assertThat(binding.challengeHex()).hasSize(64);
        byte[] nonce=binding.challenge();nonce[31]^=1;
        var wrong=new Binding(nonce,binding.execution(),binding.realm(),binding.profileSha256(),binding.slotId());
        assertThatThrownBy(()->new ScannerBrokerProtocol(wrong).read(new ByteArrayInputStream(bytes.toByteArray()),Type.PREPARE,NO_DESCRIPTOR)).isInstanceOf(IOException.class);
    }
    @Test void duplicateSequenceUnknownTypeVersionAndTruncatedFramesAreRejected()throws Exception {
        var binding=binding();var bytes=new ByteArrayOutputStream();new ScannerBrokerProtocol(binding).write(bytes,Type.PREPARE,NO_DESCRIPTOR,new byte[0]);byte[] valid=bytes.toByteArray();
        var twice=new ByteArrayOutputStream();twice.write(valid);twice.write(valid);var reader=new ScannerBrokerProtocol(binding);var input=new ByteArrayInputStream(twice.toByteArray());reader.read(input,Type.PREPARE,NO_DESCRIPTOR);
        assertThatThrownBy(()->reader.read(input,Type.PREPARE,NO_DESCRIPTOR)).isInstanceOf(IOException.class);
        for(int offset:new int[]{5,7,8,12,44,60,76,110,valid.length-1}){byte[] bad=valid.clone();bad[offset]^=1;
            assertThatThrownBy(()->new ScannerBrokerProtocol(binding).read(new ByteArrayInputStream(bad),Type.PREPARE,NO_DESCRIPTOR)).isInstanceOf(IOException.class);}
        for(int size:new int[]{0,1,3,4,valid.length-1})assertThatThrownBy(()->new ScannerBrokerProtocol(binding).read(new ByteArrayInputStream(Arrays.copyOf(valid,size)),Type.PREPARE,NO_DESCRIPTOR)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->new ScannerBrokerProtocol(binding).read(new ByteArrayInputStream("MSF_SCANNER_READY_V1\n".getBytes(StandardCharsets.US_ASCII)),Type.PREPARED,NO_DESCRIPTOR)).isInstanceOf(IOException.class);
    }
    @Test void wrongPhaseOversizedPayloadAndControlFloodAreRejected()throws Exception {
        var binding=binding();var bytes=new ByteArrayOutputStream();new ScannerBrokerProtocol(binding).write(bytes,Type.GO,"b".repeat(64),new byte[0]);
        assertThatThrownBy(()->new ScannerBrokerProtocol(binding).read(new ByteArrayInputStream(bytes.toByteArray()),Type.IMAGE,"b".repeat(64))).isInstanceOf(IOException.class);
        assertThatThrownBy(()->new ScannerBrokerProtocol(binding).write(new ByteArrayOutputStream(),Type.DATA,NO_DESCRIPTOR,new byte[MAX_DATA+1])).isInstanceOf(IOException.class);
        byte[] oversized=ByteBuffer.allocate(4).putInt(MAX_FRAME+1).array();assertThatThrownBy(()->new ScannerBrokerProtocol(binding).read(new ByteArrayInputStream(oversized),Type.PREPARE,NO_DESCRIPTOR)).isInstanceOf(IOException.class);
        var flooded=new ScannerBrokerProtocol(binding);assertThatThrownBy(()->{for(int i=0;i<32;i++)flooded.write(new ByteArrayOutputStream(),Type.CHILD,NO_DESCRIPTOR,new byte[16000]);}).isInstanceOf(IOException.class);
    }
    @Test void oneAndTwoSlotRetainedProfilesHaveDistinctFixedRootsAndRejectAliases() {
        UUID realm=UUID.randomUUID();String content=profile(realm,2);String hash=ScannerGenerationManifest.sha256(content.getBytes(StandardCharsets.US_ASCII));
        var parsed=ImmutableContainmentProfile.parseProfile(ImmutableContainmentProfile.retainedPath(hash),hash,realm,null,content.getBytes(StandardCharsets.US_ASCII));
        assertThat(parsed.slots()).hasSize(2);assertThat(parsed.slots().get(0).hostScratch()).isNotEqualTo(parsed.slots().get(1).hostScratch());
        assertThat(parsed.id()).isEqualTo(hash);assertThat(ImmutableContainmentProfile.retainedPath(hash)).isEqualTo(Path.of("/etc/msf-scanner/profiles",hash));
        String traversal=content.replace("usr/lib/libclamav.so.12","usr/lib/../libclamav.so.12");assertThatThrownBy(()->ImmutableContainmentProfile.parseProfile(Path.of("/unused"),hash,realm,null,traversal.getBytes(StandardCharsets.US_ASCII))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        String missing=content.replace("definitions/daily.cvd","definitions/unapproved.cvd");assertThatThrownBy(()->ImmutableContainmentProfile.parseProfile(Path.of("/unused"),hash,realm,null,missing.getBytes(StandardCharsets.US_ASCII))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        String duplicate=content.replace("slot.1.id=scan-1","slot.1.id=scan-0");assertThatThrownBy(()->ImmutableContainmentProfile.parseProfile(Path.of("/unused"),hash,realm,null,duplicate.getBytes(StandardCharsets.US_ASCII))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->ImmutableContainmentProfile.parseProfile(Path.of("/unused"),hash,UUID.randomUUID(),null,content.getBytes(StandardCharsets.US_ASCII))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        String refreshed=profile(realm,1);String newHash=ScannerGenerationManifest.sha256(refreshed.getBytes(StandardCharsets.US_ASCII));
        assertThat(newHash).isNotEqualTo(hash);assertThat(ImmutableContainmentProfile.retainedPath(hash)).isNotEqualTo(ImmutableContainmentProfile.retainedPath(newHash));
        assertThatThrownBy(()->ImmutableContainmentProfile.retainedPath("../profile")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void fullProfilesAcceptTwoGiBButRejectAnythingAboveTheHardBound() {
        UUID realm=UUID.randomUUID();
        for(String memory:List.of("536870912","2147483648")) {
            String content=profile(realm,1).replace("memory-bytes=536870912","memory-bytes="+memory);
            String hash=ScannerGenerationManifest.sha256(content.getBytes(StandardCharsets.US_ASCII));
            assertThat(ImmutableContainmentProfile.parseProfile(ImmutableContainmentProfile.retainedPath(hash),hash,realm,null,
                    content.getBytes(StandardCharsets.US_ASCII)).slots()).hasSize(1);
        }
        for(String memory:List.of("2147483649","9223372036854775807","0")) {
            String content=profile(realm,1).replace("memory-bytes=536870912","memory-bytes="+memory);
            String hash=ScannerGenerationManifest.sha256(content.getBytes(StandardCharsets.US_ASCII));
            assertThatThrownBy(()->ImmutableContainmentProfile.parseProfile(ImmutableContainmentProfile.retainedPath(hash),hash,realm,null,
                    content.getBytes(StandardCharsets.US_ASCII))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        }
    }
    @Test void leasePureValidityBindsEntirePlanAndCloseIsIrrevocable() {
        var plan=plan();var live=new java.util.concurrent.atomic.AtomicBoolean(true);var stops=new java.util.concurrent.atomic.AtomicInteger();
        var lease=new PreparedSlotLease(this,plan,new PreparedSlotLease.Session(){public boolean locallyLive(){return live.get();}public void stop(){stops.incrementAndGet();live.set(false);}});
        assertThat(lease.isValidFor(plan)).isTrue();assertThat(lease.isValidFor(plan())).isFalse();lease.close();lease.close();assertThat(lease.isValidFor(plan)).isFalse();assertThat(stops.get()).isEqualTo(1);
    }
    @Test void policyHasOneStdinOperandAndExactBuiltInGenerationControls() {
        var args=ClamScanProcessAdapter.policyArguments(properties());assertThat(args.stream().filter("-"::equals).count()).isEqualTo(1);
        // Independent canonical V2 vector; the native unprivileged gate compares its actual C encoding.
        assertThat(ClamScanProcessAdapter.effectivePolicySha256(properties())).isEqualTo("0a570d8ff38703e1ac1da5d89c6d856f71977107a577d821cdc0cb89e84b5013");
        assertThat(args).contains("--official-db-only=yes","--bytecode-unsigned=no","--database=/definitions","--tempdir=/scratch");
        assertThat(ImmutableContainmentProfile.Probe.values()).contains(ImmutableContainmentProfile.Probe.CLEAN_CONTROL,ImmutableContainmentProfile.Probe.EICAR_CONTROL,ImmutableContainmentProfile.Probe.PIDS_RELEASE_ACCOUNTING,ImmutableContainmentProfile.Probe.ORPHAN_REAPER);
    }
    @Test void cancelledPrepareStopsProcessThatReturnsAfterItsDeadline()throws Exception {
        var plan=plan();var profile=org.mockito.Mockito.mock(ImmutableContainmentProfile.class);
        org.mockito.Mockito.when(profile.realm()).thenReturn(plan.physicalResourceRealmId());org.mockito.Mockito.when(profile.sha256()).thenReturn(plan.profileSha256());org.mockito.Mockito.when(profile.id()).thenReturn(plan.profileSha256());
        org.mockito.Mockito.when(profile.slots()).thenReturn(List.of(new ImmutableContainmentProfile.Slot(plan.slotId(),Path.of("/unused/scratch"),Path.of("/unused/cgroup"))));
        var release=new java.util.concurrent.CountDownLatch(1);var entered=new java.util.concurrent.CountDownLatch(1);var actual=new java.util.concurrent.atomic.AtomicReference<Process>();
        try(var launcher=new ScannerProcessLauncher(properties(),profile,builder->{entered.countDown();for(;;)try{release.await();break;}catch(InterruptedException ignored){}Thread.interrupted();
            String javaBinary=Path.of(System.getProperty("java.home"),"bin","java").toString();builder.command(javaBinary,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),LateProcessChild.class.getName());Process child=builder.start();actual.set(child);return child;})){
            assertThatThrownBy(()->launcher.prepareSlot(plan,Deadline.after(Duration.ofMillis(250)))).isInstanceOf(FileEvidenceTrustUnavailableException.class);assertThat(entered.getCount()).isZero();release.countDown();
            long stop=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<stop&&(actual.get()==null||actual.get().isAlive()))Thread.sleep(10);
            assertThat(actual.get()).isNotNull();assertThat(actual.get().isAlive()).isFalse();
        }finally{release.countDown();Process child=actual.get();if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
    }
    /** No scanner/kernel attestation: this fixture only models an uninterruptible late ProcessBuilder return. */
    public static final class LateProcessChild {public static void main(String[] args)throws Exception {Thread.sleep(60000);}}
    private static Binding binding(){byte[] nonce=new byte[32];for(int i=0;i<32;i++)nonce[i]=(byte)i;return new Binding(nonce,UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),"scan-0");}
    private static ScanPlan plan(){return new ScanPlan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),"scan-0",0,1,"b".repeat(64),"c".repeat(64));}
    private static String profile(UUID realm,int count){return "format=MSF_SCANNER_BROKER_PROFILE_V1\nrealm-id="+realm+"\nslot-count="+count+"\ncaller-uid=1000\ncaller-gid=1000\nscanner-uid=2000\nscanner-gid=2000\nmemory-bytes=536870912\nscratch-bytes=134217728\nmax-bytes=1024\ncpu-seconds=3\nmax-pids=16\nmax-fds=64\nscanner-sha256="+"a".repeat(64)+"\nslot.0.id=scan-0\n"+(count==2?"slot.1.id=scan-1\n":"")+resourceProfile();}
    private static String resourceProfile(){StringBuilder value=new StringBuilder("resource-count=6\n");String[] paths={"scanner/clamscan","definitions/main.cvd","definitions/daily.cvd","definitions/bytecode.cvd","usr/lib/libclamav.so.12","scanner/clamav-package.manifest"};for(int i=0;i<paths.length;i++)value.append("resource.").append(i).append(".path=").append(paths[i]).append("\nresource.").append(i).append(".sha256=").append("a".repeat(64)).append("\n");return value.toString();}
    static FileIngestionProperties properties() {
        String hash="a".repeat(64);
        return new FileIngestionProperties(true,new FileIngestionProperties.Storage(Path.of("/not-provisioned/quarantine"),Path.of("/not-provisioned/immutable"),Path.of("/not-provisioned/receipts"),hash),
                new FileIngestionProperties.Scanner(Path.of("/not-provisioned/launcher"),hash,Path.of("/not-provisioned/profile"),hash,Path.of("/not-provisioned/clamscan"),hash,Path.of("/not-provisioned/definitions"),"1.4.6",
                        Map.of("/not-provisioned/libclamav.so",hash,"/not-provisioned/clamav-package.manifest",hash),Map.of("main.cvd",hash,"daily.cvd",hash,"bytecode.cvd",hash),Map.of("main.cvd",1,"daily.cvd",1,"bytecode.cvd",1),230),
                new FileIngestionProperties.Limits(1024,20,8,131072,128,1048576,Duration.ofMinutes(30),Duration.ofSeconds(5),1,134217728,Duration.ofSeconds(3),Duration.ofMinutes(15),1000),
                Set.of("image/png"),"restricted-image-v1","local-private-ingestion");
    }
}
