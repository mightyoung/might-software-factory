package com.mightyoung.softwarefactory.dualprevention.risk;

/** Publication must persist on the caller's guarded command connection before commit. */
public interface RiskScenarioEventPublisher {
    void publish(RiskScenarioDraftChangedEvent event);
}
