package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Persisted publication consumer only queues bounded hints. Polling the intent remains authoritative. */
@Component
public class FindingEvidenceIngestionWakeListener {
    private final FindingEvidenceIngestionProperties properties;
    private final ConcurrentHashMap<UUID,LinkedHashSet<UUID>> hints=new ConcurrentHashMap<>();
    public FindingEvidenceIngestionWakeListener(FindingEvidenceIngestionProperties properties){this.properties=properties;}
    @ApplicationModuleListener
    public void wake(FindingEvidenceQuarantinedEvent event){
        if(!properties.enabled()||!properties.tenants().contains(event.tenantId()))return;
        var pending=hints.computeIfAbsent(event.tenantId(),ignored->new LinkedHashSet<>());
        synchronized(pending){if(pending.size()<32)pending.add(event.intentId());}
    }
    List<UUID> drain(UUID tenant,int limit){
        if(limit<1||limit>32)throw new IllegalArgumentException("Ingestion wake drain must be bounded");
        var pending=hints.get(tenant);if(pending==null)return List.of();
        synchronized(pending){
            var result=new ArrayList<UUID>();var it=pending.iterator();
            while(it.hasNext()&&result.size()<limit){result.add(it.next());it.remove();}
            return List.copyOf(result);
        }
    }
}
