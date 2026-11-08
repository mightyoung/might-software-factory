package com.mightyoung.softwarefactory.dualprevention.risk;

import java.util.Objects;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager;

/** Construct before service composition; configure the manager's fixed native bridge after its gate exists. */
public final class ModulithRiskScenarioEventPublisher implements RiskScenarioEventPublisher {
    private final G014JdbcTransactionManager manager;
    public ModulithRiskScenarioEventPublisher(G014JdbcTransactionManager manager) {
        this.manager=Objects.requireNonNull(manager);
    }
    @Override public void publish(RiskScenarioDraftChangedEvent event) {
        manager.publishRiskEvent(Objects.requireNonNull(event));
    }
}
