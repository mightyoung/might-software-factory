package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FindingEvidenceIngestionWakeListenerTest {
    @Test void ignoresDisabledAndForeignScopeAndKeepsOnlyThirtyTwoHints(){
        UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID();
        var disabled=new FindingEvidenceIngestionWakeListener(new FindingEvidenceIngestionProperties(false,"","",1,2,""));
        disabled.wake(new FindingEvidenceQuarantinedEvent(tenant,UUID.randomUUID(),1));assertThat(disabled.drain(tenant,32)).isEmpty();
        var enabled=new FindingEvidenceIngestionWakeListener(new FindingEvidenceIngestionProperties(true,actor.toString(),tenant.toString(),1,2,""));
        for(int i=0;i<40;i++)enabled.wake(new FindingEvidenceQuarantinedEvent(tenant,UUID.randomUUID(),1));
        UUID foreign=UUID.randomUUID();enabled.wake(new FindingEvidenceQuarantinedEvent(foreign,UUID.randomUUID(),1));
        assertThat(enabled.drain(foreign,32)).isEmpty();assertThat(enabled.drain(tenant,32)).hasSize(32);
        assertThat(enabled.drain(tenant,32)).isEmpty();
    }
    @Test void workerEnablementNeedsExplicitServiceAndTenantScope(){
        assertThatThrownBy(()->new FindingEvidenceIngestionProperties(true,"","",1,2,""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new FindingEvidenceIngestionProperties(false,"","",3,2,""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
