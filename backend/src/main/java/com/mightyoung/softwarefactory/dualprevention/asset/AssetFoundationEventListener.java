package com.mightyoung.softwarefactory.dualprevention.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;

/** At-least-once committed tracing only. This consumer does not perform risk reassessment or notify users. */
public class AssetFoundationEventListener {
    public static final String ID="asset-foundation-change-trace";
    private static final Logger LOG=LoggerFactory.getLogger(AssetFoundationEventListener.class);
    @ApplicationModuleListener(id=ID)
    public void record(AssetFoundationChangedEvent event) {
        LOG.atInfo().addKeyValue("tenantId",event.tenantId()).addKeyValue("commandId",event.commandId())
                .addKeyValue("resourceType",event.resourceType()).addKeyValue("resourceId",event.resourceId())
                .addKeyValue("action",event.action()).addKeyValue("version",event.version())
                .addKeyValue("changeKind",event.changeKind()).addKeyValue("traceId",event.traceId())
                .log("Asset foundation committed change traced");
    }
}
