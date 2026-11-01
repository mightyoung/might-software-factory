package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Records a structured post-commit trace; future notification adapters can subscribe independently.
 * Must remain non-final so Spring can proxy the async, transactional Modulith listener.
 */
@Component
public class FindingWorkflowEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(FindingWorkflowEventListener.class);

    @ApplicationModuleListener
    public void record(FindingWorkflowChangedEvent event) {
        LOG.atInfo()
                .addKeyValue("tenantId", event.tenantId())
                .addKeyValue("findingId", event.findingId())
                .addKeyValue("commandId", event.commandId())
                .addKeyValue("action", event.action())
                .addKeyValue("toState", event.toState())
                .addKeyValue("roundNo", event.roundNo())
                .addKeyValue("version", event.version())
                .log("Finding workflow command committed");
    }
}
