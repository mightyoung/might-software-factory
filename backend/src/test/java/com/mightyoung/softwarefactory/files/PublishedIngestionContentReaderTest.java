package com.mightyoung.softwarefactory.files;

import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishedIngestionContentReaderTest {
    @TempDir Path root;
    final byte[] bytes={1,2,3};
    final IngestionProofCodecTest f=new IngestionProofCodecTest(){
        @Override QuarantinedObject quarantine(){var q=super.quarantine();return new QuarantinedObject(q.tenantId(),q.intentId(),q.fileId(),q.allocationId(),q.sourceAttemptId(),q.identity(),IngestionProofCodec.sha256(bytes),q.detectedMediaType(),q.sizeBytes(),q.observationSha256());}
    };
    final StorageContentVerificationPort legacy=mock(StorageContentVerificationPort.class);
    final VerifiedStorageVersionReadPort legacyRead=mock(VerifiedStorageVersionReadPort.class);
    FileIngestionProperties.Storage storage(){return new FileIngestionProperties.Storage(root.resolve("quarantine"),root.resolve("immutable"),root.resolve("receipts"),"a".repeat(64));}
    PublishedIngestionContentReader.Publication publication(boolean physical)throws Exception {
        var a=f.allocation(AllocationKind.IMMUTABLE,f.scanAttempt,"immutable");
        ObjectIdentity identity=f.identity(a,3);
        if(physical){
            Files.createDirectory(storage().immutableRoot(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.createDirectory(storage().immutableRoot().resolve(".writer-control"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try(var directory=LocalPrivateUploadProducer.PrivateDirectory.open(storage().immutableRoot(),storage().rootGeneration(),Clock.systemUTC())){
                identity=directory.write(a,new ByteArrayInputStream(bytes),bytes.length,Deadline.after(Duration.ofSeconds(5))).identity();
            }
        }
        var proof=f.proof();var hash=IngestionProofCodec.sha256(bytes);
        var immutable=new ImmutableObject(f.tenant,f.intent,f.file,a.allocationId(),f.sourceAttempt,f.scanAttempt,identity,hash,"image/png",3,proof.quarantineObservationSha256());
        var baseline=f.codec.observe(a,ObservationKind.COMPLETE_IMMUTABLE,identity,hash,3L,"image/png");
        var receipt=f.codec.issueReceipt(IngestionProofCodecTest.id(9),immutable,proof,baseline.observationSha256(),new IngestionProofCodec.ReceiptTimes(IngestionProofCodecTest.NOW,IngestionProofCodecTest.NOW,IngestionProofCodecTest.NOW.plusSeconds(600)));
        var stored=new RecordedAllocation(a,1,IngestionProofCodecTest.id(70),"a".repeat(64),3,baseline,IngestionProofCodecTest.id(70),null,f.file,null);
        var ra=f.allocation(AllocationKind.RECEIPT,f.scanAttempt,"receipt");
        byte[] rb=IngestionSignedCheckpointReader.receiptFileBytes(receipt);
        var observation=f.codec.observe(ra,ObservationKind.RECEIPT,f.identity(ra,rb.length),IngestionProofCodec.sha256(rb),(long)rb.length,"application/vnd.msf.scanned-content-receipt");
        var receiptAllocation=new RecordedAllocation(ra,1,IngestionProofCodecTest.id(71),"a".repeat(64),rb.length,observation,IngestionProofCodecTest.id(71),null,f.file,null);
        return new PublishedIngestionContentReader.Publication(f.tenant,f.file,IngestionProofCodecTest.id(20),IngestionProofCodecTest.id(21),IngestionProofCodecTest.id(22),1,IngestionProofCodecTest.id(23),IngestionProofCodecTest.id(24),IngestionProofCodecTest.id(25),true,new VerifiedContentCheckpoint(proof,receipt,stored,receiptAllocation));
    }
    PublishedIngestionContentReader reader(PublishedIngestionContentReader.Publication publication){
        // Historical reads deliberately occur long after scan/receipt expiry.
        var historyCodec=new IngestionProofCodec(Map.of("key1",f.key),"key1",Clock.fixed(IngestionProofCodecTest.NOW.plusSeconds(86400),ZoneOffset.UTC));
        return new PublishedIngestionContentReader((t,file,r)->Optional.of(publication),storage(),historyCodec,legacy,legacyRead);
    }
    VerifiedFileEvidenceSnapshot snapshot(PublishedIngestionContentReader.Publication p){var r=p.checkpoint().receipt();return new VerifiedFileEvidenceSnapshot(p.tenantId(),p.evidenceItemId(),p.fileId(),p.fileLinkId(),p.ownerMemberId(),p.ownerActorId(),r.sha256(),r.immutableStorageVersion(),r.receiptId(),r.verifierId(),r.keyId(),r.policyVersion(),FileEvidenceVerificationLevel.SCANNED_CONTENT_V2,r.issuedAt().atOffset(ZoneOffset.UTC));}
    EvidenceFileUse use(PublishedIngestionContentReader.Publication p){return new EvidenceFileUse(p.tenantId(),p.ownerActorId(),p.ownerMemberId(),p.findingId(),1,p.evidenceSetId(),p.ownerMemberId(),p.evidenceItemId(),p.fileId(),"SCANNED_CONTENT_V2",p.checkpoint().receipt().sha256());}
    FileObjectMetadata metadata(PublishedIngestionContentReader.Publication p){var r=p.checkpoint().receipt();return new FileObjectMetadata(p.tenantId(),p.fileId(),p.ownerMemberId(),p.ownerActorId(),FileObjectState.READY,3,"image/png",r.sha256(),r.immutableStorageVersion(),r.issuedAt().atOffset(ZoneOffset.UTC));}
    @Test void historicalSignatureSurvivesExpiryAndKeepsV2Protocol()throws Exception {
        var p=publication(false);var r=reader(p);
        assertThat(r.findByReceiptId(f.tenant,p.checkpoint().receipt().receiptId())).get().extracting(ContentVerificationReceipt::verificationLevel).isEqualTo(FileEvidenceVerificationLevel.SCANNED_CONTENT_V2);
        assertThat(r.findVerifiedContent(use(p),metadata(p))).isPresent();verifyNoInteractions(legacy);
    }
    @Test void crossTenantAndReceiptSubstitutionDeny()throws Exception {
        var p=publication(false);var r=reader(p);
        when(legacy.findByReceiptId(any(),any())).thenReturn(Optional.empty());
        assertThat(r.findByReceiptId(UUID.randomUUID(),p.checkpoint().receipt().receiptId())).isEmpty();
        assertThat(r.findByReceiptId(f.tenant,UUID.randomUUID())).isEmpty();
    }
    @Test void currentUseMustMatchPublicationAndActualOwner()throws Exception {
        var p=publication(false);var r=reader(p);var m=metadata(p);
        var changed=new FileObjectMetadata(m.tenantId(),m.fileId(),UUID.randomUUID(),m.ownerActorId(),m.state(),m.sizeBytes(),m.mimeType(),m.sha256(),m.storageVersion(),m.verifiedAt());
        assertThat(r.findVerifiedContent(use(p),changed)).isEmpty();
        var u=use(p);var wrongFinding=new EvidenceFileUse(u.tenantId(),u.actorId(),u.memberId(),UUID.randomUUID(),u.roundNo(),u.evidenceSetId(),u.evidenceSetOwnerMemberId(),u.evidenceItemId(),u.fileId(),u.recordedVerificationLevel(),u.recordedSha256());
        assertThat(r.findVerifiedContent(wrongFinding,m)).isEmpty();
    }
    @Test void revokedCurrentLinkDoesNotInvalidateHistoricalReceipt()throws Exception {
        var p=publication(false);
        var revoked=new PublishedIngestionContentReader.Publication(p.tenantId(),p.fileId(),p.fileLinkId(),p.evidenceItemId(),p.findingId(),p.roundNo(),p.evidenceSetId(),p.ownerMemberId(),p.ownerActorId(),false,p.checkpoint());
        assertThat(reader(revoked).findVerifiedContent(use(p),metadata(p))).isEmpty();
        assertThat(reader(revoked).findByReceiptId(p.tenantId(),p.checkpoint().receipt().receiptId())).isPresent();
    }
    @Test void corruptedSignatureFailsClosed()throws Exception {
        var p=publication(false);var proof=p.checkpoint().proof();
        var tampered=IngestionProofCodecTest.<SignedScanProofV1>copy(proof,"signature",new byte[32]);
        var corrupt=new PublishedIngestionContentReader.Publication(p.tenantId(),p.fileId(),p.fileLinkId(),p.evidenceItemId(),p.findingId(),p.roundNo(),p.evidenceSetId(),p.ownerMemberId(),p.ownerActorId(),true,new VerifiedContentCheckpoint(tampered,p.checkpoint().receipt(),p.checkpoint().immutableAllocation(),p.checkpoint().receiptAllocation()));
        assertThatThrownBy(()->reader(corrupt).findByReceiptId(f.tenant,p.checkpoint().receipt().receiptId())).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        verifyNoInteractions(legacy);
    }
    @Test void v1UsesLegacyReaderWithoutUpgradingItsProtocol()throws Exception {
        var p=publication(false);var s=snapshot(p);
        var v1=new VerifiedFileEvidenceSnapshot(s.tenantId(),s.evidenceItemId(),s.fileId(),s.fileLinkId(),s.ownerMemberId(),s.ownerActorId(),s.sha256(),s.storageVersion(),s.verificationReceiptId(),s.verifierId(),s.verifierKeyId(),s.verificationPolicyVersion(),FileEvidenceVerificationLevel.CONTENT,s.verifiedAt());
        reader(p).openVerifiedVersion(v1);verify(legacyRead).openVerifiedVersion(v1);
    }
    @Test void snapshotCannotSubstituteVersionLinkOrDigest()throws Exception {
        var p=publication(false);var r=reader(p);var s=snapshot(p);
        for(var field:java.util.List.of("fileLinkId","evidenceItemId","ownerMemberId","storageVersion","sha256")) {
            Object changed=switch(field){case "storageVersion"->"other-version";case "sha256"->"0".repeat(64);default->UUID.randomUUID();};
            var tampered=IngestionProofCodecTest.<VerifiedFileEvidenceSnapshot>copy(s,field,changed);
            assertThatThrownBy(()->r.openVerifiedVersion(tampered)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        }
    }
    @Test void exactImmutableVersionStreamsAndRejectsReplacement()throws Exception {
        SecureLocalStorageTestSupport.requireOrAssumeSecureStorage(root);
        var p=publication(true);var r=reader(p);var s=snapshot(p);
        try(var content=r.openVerifiedVersion(s)){var output=new ByteArrayOutputStream();content.transferTo(output);assertThat(output.toByteArray()).isEqualTo(bytes);}
        Path immutable=storage().immutableRoot().resolve("immutable");Files.move(immutable,root.resolve("retired-immutable"));
        Files.write(immutable,bytes);Files.setPosixFilePermissions(immutable,PosixFilePermissions.fromString("r--------"));
        assertThatThrownBy(()->r.openVerifiedVersion(s)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void hardlinkAndWritableModeDeny()throws Exception {
        SecureLocalStorageTestSupport.requireOrAssumeSecureStorage(root);
        var p=publication(true);var r=reader(p);var s=snapshot(p);Path immutable=storage().immutableRoot().resolve("immutable");
        Files.createLink(root.resolve("alias"),immutable);
        assertThatThrownBy(()->r.openVerifiedVersion(s)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        Files.delete(root.resolve("alias"));Files.setPosixFilePermissions(immutable,PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(()->r.openVerifiedVersion(s)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void changedContentAfterOpenReleasesNoUnverifiedBytes()throws Exception {
        SecureLocalStorageTestSupport.requireOrAssumeSecureStorage(root);
        var p=publication(true);var r=reader(p);Path immutable=storage().immutableRoot().resolve("immutable");
        try(var content=r.openVerifiedVersion(snapshot(p))){
            Files.setPosixFilePermissions(immutable,PosixFilePermissions.fromString("rw-------"));Files.write(immutable,new byte[]{3,2,1});Files.setPosixFilePermissions(immutable,PosixFilePermissions.fromString("r--------"));
            var output=new ByteArrayOutputStream();assertThatThrownBy(()->content.transferTo(output)).isInstanceOf(java.io.IOException.class);assertThat(output.size()).isZero();
        }
    }
}
