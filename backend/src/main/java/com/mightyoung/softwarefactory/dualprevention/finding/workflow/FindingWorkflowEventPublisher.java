package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

@FunctionalInterface
public interface FindingWorkflowEventPublisher {
    void publish(FindingWorkflowChangedEvent event);
}
