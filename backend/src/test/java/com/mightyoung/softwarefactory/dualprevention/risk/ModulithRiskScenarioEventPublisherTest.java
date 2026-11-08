package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Thin delegation only. Real issuer/alias coverage lives in G014RiskPublicationBridgeTest. */
class ModulithRiskScenarioEventPublisherTest {
    private final G014JdbcTransactionManager manager=mock(G014JdbcTransactionManager.class);
    private final RiskScenarioDraftChangedEvent event=new RiskScenarioDraftChangedEvent(UUID.randomUUID(),UUID.randomUUID(),"risk_scenario",UUID.randomUUID(),"risk_scenario.create",1,Instant.parse("2026-11-04T00:00:00Z"),UUID.randomUUID(),"CREATE");
    @Test void delegatesOnlyTheExactEventToFixedManagerAction(){
        new ModulithRiskScenarioEventPublisher(manager).publish(event);
        verify(manager).publishRiskEvent(same(event));verifyNoMoreInteractions(manager);
    }
    @Test void constructionDoesNotConfigurePublishOrAcquire(){new ModulithRiskScenarioEventPublisher(manager);verifyNoInteractions(manager);}
    @Test void rejectsNullBeforeManagerAction(){
        assertThatThrownBy(()->new ModulithRiskScenarioEventPublisher(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->new ModulithRiskScenarioEventPublisher(manager).publish(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(manager);
    }
    @Test void preservesManagerIssuedFailure(){
        var failure=new IllegalStateException("G014 publication boundary is unavailable");doThrow(failure).when(manager).publishRiskEvent(event);
        assertThatThrownBy(()->new ModulithRiskScenarioEventPublisher(manager).publish(event)).isSameAs(failure);
    }
}
