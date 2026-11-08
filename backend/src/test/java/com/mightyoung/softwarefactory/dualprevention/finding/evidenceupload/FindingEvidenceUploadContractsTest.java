package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.files.UploadProducerReadiness;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;
import static org.assertj.core.api.Assertions.*;

class FindingEvidenceUploadContractsTest {
    final UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
    ReserveCommand command(String filename,String media,long size) { return new ReserveCommand(id,id,1,id,0,0,filename,media,size); }
    @Test void validatesUnicodeFilenameMediaAndReaderCompatibleSizeWithoutNormalization() {
        assertThat(command("😀".repeat(255),"application/pdf",67_108_864).filename()).hasSize(510);
        for (String filename : new String[]{""," ",".","..","../evidence","a\\b","a\nb","\u0000","\uD800","😀".repeat(256)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> command(filename,"application/pdf",1));
        }
        for (String media : new String[]{"application/pdf;charset=utf8","text","A/B","text/*"," text/plain","text/é"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> command("a",media,1));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> command("a","application/pdf",0));
        assertThatIllegalArgumentException().isThrownBy(() -> command("a","application/pdf",67_108_865));
    }
    @Test void fingerprintHasStableUtf8LengthPrefixAndOwnerNamespace() {
        var owner=new CurrentTenantContext(id,id,id,id,0,0,1);
        String hash=command("证据.pdf","application/pdf",123).fingerprint(owner,id);
        assertThat(hash).isEqualTo("8bb3e4bd1ebae9a02f9faf6e4a5df09f89de6ad6599bd765f074be93626fa85b");
        assertThat(command("证据.pdf ","application/pdf",123).fingerprint(owner,id)).isNotEqualTo(hash);
        var changedOwner=new CurrentTenantContext(UUID.randomUUID(),id,id,id,0,0,1);
        assertThat(command("证据.pdf","application/pdf",123).fingerprint(changedOwner,id)).isNotEqualTo(hash);
    }
    @Test void capabilityRequiresPinnedBoundedActualProducerPolicy() {
        assertThat(FindingEvidenceUploadServiceTest.capability().intentTtl()).isEqualTo(Duration.ofMinutes(15));
        for (long size : new long[]{0,67_108_865}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new UploadProducerReadiness.Capability("producer-v1","policy-v1","definitions-v1",
                    Set.of("application/pdf"),size,20,20,134_217_728,200,1_342_177_280,Duration.ofMinutes(15),Duration.ofMinutes(2),4));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new UploadProducerReadiness.Capability("producer-v1","policy-v1","definitions-v1",
                Set.of(),1,1,1,1,1,1,Duration.ofMinutes(15),Duration.ofMinutes(2),1));
        assertThatIllegalArgumentException().isThrownBy(() -> new UploadProducerReadiness.Capability("producer-v1","policy-v1","definitions-v1",
                Set.of("application/pdf"),1,1,1,1,1,1,Duration.ofSeconds(1),Duration.ofMinutes(2),1));
    }
}
