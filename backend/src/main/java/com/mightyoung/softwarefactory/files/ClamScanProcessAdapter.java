package com.mightyoung.softwarefactory.files;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Actual subprocess controls and scan verdicts; fixture children are not accepted ClamAV resources. */
final class ClamScanProcessAdapter implements AutoCloseable {
    static final String MEDIA_POLICY_SHA256=ScannerGenerationManifest.sha256(
            "MSF_RESTRICTED_PNG_JPEG_V1:PNG-IHDR-PLTE-IDAT-IEND-CRC-EOF;JPEG-BASELINE-SOF0-DQT-DHT-DRI-SOS-EOI-noAPP-noCOM-noTrailing;no-rendering-guarantee".getBytes(StandardCharsets.US_ASCII));
    private static final byte[] CLEAN="MSF scanner clean generation control\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EICAR=("X5O!P%@AP[4"+"\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*").getBytes(StandardCharsets.US_ASCII);
    private final FileIngestionProperties properties;
    private final ImmutableContainmentProfile profile;
    private final ScannerGenerationManifest manifest;
    private final ScannerProcessLauncher launcher;
    private final Clock clock;

    ClamScanProcessAdapter(FileIngestionProperties properties,ImmutableContainmentProfile profile,
            ScannerGenerationManifest manifest,ScannerProcessLauncher launcher,Clock clock) {
        this.properties=properties;this.profile=profile;this.manifest=manifest;this.launcher=launcher;this.clock=clock;
    }
    /** This is the only promotion path. No public record, boolean, or imported launcher JSON can call it successfully. */
    AcceptedResources accept(Deadline deadline) throws IOException {
        manifest.verifyUnchanged(deadline,clock.instant());
        var controls=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(controls)) {
            out.writeUTF("MSF_SCANNER_CONTROLS_V1");out.writeUTF(manifest.resourceSha256());
            for(var slot:profile.slots()) {
                for(var probe:ImmutableContainmentProfile.Probe.values()) {
                    var result=launcher.probe(slot,probe,deadline);
                    if(result.resultCode()!=0||!result.policySha256().equals(effectivePolicySha256(properties)))throw unavailable();
                    if(probe==ImmutableContainmentProfile.Probe.CLEAN_CONTROL) {
                        if(result.exitCode()!=0||result.bytes()!=CLEAN.length||!result.inputSha256().equals(ScannerGenerationManifest.sha256(CLEAN)))throw unavailable();
                    } else if(probe==ImmutableContainmentProfile.Probe.EICAR_CONTROL) {
                        if(result.exitCode()!=1||result.bytes()!=EICAR.length||!result.inputSha256().equals(ScannerGenerationManifest.sha256(EICAR)))throw unavailable();
                    } else if(result.exitCode()!=0||result.bytes()!=0||!result.inputSha256().equals(ScannerGenerationManifest.sha256(new byte[0])))throw unavailable();
                    out.writeUTF(slot.id());out.writeUTF(probe.name());out.writeInt(result.exitCode());out.writeLong(result.bytes());out.writeUTF(result.inputSha256());out.writeUTF(result.controlSha256());
                }
            }
        }
        manifest.verifyUnchanged(deadline,clock.instant());
        var canonical=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(canonical)) {
            out.writeUTF("MSF_ACCEPTED_SCANNER_GENERATION_V1");
            byte[] resource=manifest.resourcePayload();out.writeInt(resource.length);out.write(resource);
            out.writeUTF(ScannerGenerationManifest.sha256(controls.toByteArray()));
        }
        if(canonical.size()>MAX_CANONICAL_BYTES)throw unavailable();
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant until=now.plus(Duration.ofHours(2));
        if(manifest.dailyFreshUntil().isBefore(until))until=manifest.dailyFreshUntil().truncatedTo(ChronoUnit.MICROS);
        byte[] bytes=canonical.toByteArray();String hash=ScannerGenerationManifest.sha256(bytes);
        AcceptedGeneration generation=new AcceptedGeneration(UUID.randomUUID(),hash,hash,bytes,now,until);
        var limits=properties.limits();
        var capability=new UploadProducerReadiness.Capability(hash,properties.policyVersion(),hash,properties.allowedMediaTypes(),
                limits.maxBytes(),limits.maxItemsPerSet(),limits.ownerPendingCount(),limits.ownerPendingBytes(),
                limits.tenantPendingCount(),limits.tenantPendingBytes(),limits.intentTtl(),limits.streamTimeout(),limits.concurrency());
        return new AcceptedResources(this,generation,capability);
    }
    ScanResult scan(AcceptedResources accepted,QuarantinedObject source,ScanAllocation allocation,
            RecordedExecution execution,PreparedSlotLease lease,InputStream bytes,Deadline deadline) throws IOException {
        requireAccepted(accepted);manifest.verifyUnchanged(deadline,clock.instant());
        var slot=profile.slots().stream().filter(value->value.id().equals(allocation.slotDescriptorId())).findFirst().orElseThrow(ClamScanProcessAdapter::unavailable);
        if(allocation.hardCapacityBytes()!=profile.scratchBytes()||allocation.hardCapacityBytes()>allocation.scratch().request().reservedBytes())throw unavailable();
        Instant started=clock.instant().truncatedTo(ChronoUnit.MICROS);
        var result=launcher.scan(execution,lease,bytes,source.sizeBytes(),source.sha256(),deadline);
        if(result.exitCode()==1)throw new RejectedContent();
        if(result.exitCode()!=0||result.stderrBytes()!=0||result.stdoutBytes()!=0
                ||result.bytes()!=source.sizeBytes()||!result.inputSha256().equals(source.sha256()))throw unavailable();
        manifest.verifyUnchanged(deadline,clock.instant());requireAccepted(accepted);
        return new ScanResult(started,clock.instant().truncatedTo(ChronoUnit.MICROS),result.kernelFacts());
    }
    PreparedSlotLease prepareSlot(AcceptedResources accepted,ScanPlan plan,Deadline deadline) {
        try{requireAccepted(accepted);}catch(IOException failure){throw unavailable();}
        return launcher.prepareSlot(plan,deadline);
    }
    void requireAccepted(AcceptedResources accepted)throws IOException {
        if(accepted==null||accepted.owner!=this||launcher.hasUnknownResources()||!clock.instant().isBefore(accepted.generation.validUntil()))throw unavailable();
        manifest.verifyMetadata(clock.instant());
    }
    boolean isLocallyCurrent(AcceptedResources accepted) {
        return accepted!=null&&accepted.owner==this&&!launcher.hasUnknownResources()&&clock.instant().isBefore(accepted.generation.validUntil());
    }
    static List<String> policyArguments(FileIngestionProperties properties) {
        long max=properties.limits().maxBytes();
        // This exact argv is part of the accepted generation and controls. There are no caller options.
        return List.of("--suppress-ok-results","--stdout","--no-summary","--official-db-only=yes","--bytecode=yes",
                "--bytecode-unsigned=no","--bytecode-timeout=10000","--max-filesize="+max,
                "--max-scansize="+max,"--max-files=1","--max-recursion=1","--alert-exceeds-max=yes",
                "--alert-encrypted=yes","--alert-macros=yes","--scan-archive=no","--scan-pdf=no",
                "--scan-html=no","--scan-mail=no","--scan-ole2=no","--scan-pe=no","--scan-elf=no",
                "--database=/definitions","--tempdir=/scratch","-");
    }
    @Override public void close(){launcher.close();}
    static final class AcceptedResources {
        private final ClamScanProcessAdapter owner;private final AcceptedGeneration generation;private final UploadProducerReadiness.Capability capability;
        private AcceptedResources(ClamScanProcessAdapter owner,AcceptedGeneration generation,UploadProducerReadiness.Capability capability){this.owner=owner;this.generation=generation;this.capability=capability;}
        AcceptedGeneration generation(){return generation;}UploadProducerReadiness.Capability capability(){return capability;}
        @Override public String toString(){return "AcceptedScannerResources[generationId="+generation.generationId()+"]";}
    }
    record ScanResult(Instant startedAt,Instant completedAt,KernelExecutionFacts kernelFacts){}
    static final class RejectedContent extends RuntimeException {RejectedContent(){super("Uploaded content was rejected");}}
    static String effectivePolicySha256(FileIngestionProperties properties){
        String canonical="MSF_CLAMSCAN_CONTAINMENT_V2\n/scanner/clamscan\n"+String.join("\n",policyArguments(properties))+"\nLC_ALL=C\nTZ=UTC\nmemory.swap.max=0\nparent-death=SIGKILL/ARMED_GO_V2\nsupervisor-effective-cap=CAP_KILL\n";
        return ScannerGenerationManifest.sha256(canonical.getBytes(StandardCharsets.US_ASCII));
    }
    private static FileEvidenceTrustUnavailableException unavailable(){return new FileEvidenceTrustUnavailableException();}
}
