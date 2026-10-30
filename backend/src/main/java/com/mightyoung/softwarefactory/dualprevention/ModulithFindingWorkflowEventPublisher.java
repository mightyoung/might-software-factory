package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Publishes workflow events into Spring Modulith's transactional JDBC publication registry. */
@Component
public final class ModulithFindingWorkflowEventPublisher implements FindingWorkflowEventPublisher {

    private final ApplicationEventPublisher applicationEvents;

    public ModulithFindingWorkflowEventPublisher(ApplicationEventPublisher applicationEvents) {
        this.applicationEvents = Objects.requireNonNull(applicationEvents, "applicationEvents");
    }

    @Override
    public void publish(FindingWorkflowChangedEvent event) {
        applicationEvents.publishEvent(Objects.requireNonNull(event, "event"));
    }
}
