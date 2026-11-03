package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationContracts.*;
import static org.assertj.core.api.Assertions.*;

class FindingEvidenceSetInitializationContractsTest {
    final UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
    @Test void initializationFingerprintHasIndependentStableNamespaceAndServerOwnership(){
        var command=new InitializeCommand(id,id,1,7);var owner=new CurrentTenantContext(id,id,id,id,0,0,1);
        String hash=command.fingerprint(owner,id);
        assertThat(hash).isEqualTo("f721b193505657398aacb8444c8813ac4b7e968093b70a24830c72fbad96f0e5");
        assertThat(new InitializeCommand(UUID.randomUUID(),id,1,7).fingerprint(owner,id)).isEqualTo(hash);
        assertThat(new InitializeCommand(id,id,1,8).fingerprint(owner,id)).isNotEqualTo(hash);
        assertThat(command.fingerprint(owner,UUID.randomUUID())).isNotEqualTo(hash);
        assertThat(command.fingerprint(new CurrentTenantContext(UUID.randomUUID(),id,id,id,0,0,1),id)).isNotEqualTo(hash);
        assertThat(OPERATION).isNotEqualTo(FindingEvidenceUploadContracts.OPERATION);
        assertThat(FINGERPRINT_VERSION).isNotEqualTo(FindingEvidenceUploadContracts.FINGERPRINT_VERSION);
    }
    @Test void countLimitsAreBoundedAndTenantCannotBeSmallerThanOwner(){
        assertThat(new Limits(20,2000).ownerOpenSets()).isEqualTo(20);
        assertThat(new Limits(1,1).tenantOpenSets()).isEqualTo(1);
        assertThat(new Limits(1000,10000).tenantOpenSets()).isEqualTo(10000);
        for(int[] candidate:new int[][]{{0,2000},{1001,2000},{20,19},{20,10001}}){
            assertThatIllegalArgumentException().isThrownBy(() -> new Limits(candidate[0],candidate[1]));
        }
    }
    @Test void commandRejectsMissingIdsInvalidRoundAndNegativeVersion(){
        assertThatNullPointerException().isThrownBy(() -> new InitializeCommand(null,id,1,0));
        assertThatNullPointerException().isThrownBy(() -> new InitializeCommand(id,null,1,0));
        assertThatIllegalArgumentException().isThrownBy(() -> new InitializeCommand(id,id,0,0));
        assertThatIllegalArgumentException().isThrownBy(() -> new InitializeCommand(id,id,1,-1));
    }
}
