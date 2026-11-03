package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit worker scope and conservative business storage budgets; empty scope never discovers tenants. */
@Component
public final class FindingEvidenceIngestionProperties {
    private final boolean enabled;
    private final UUID technicalActorId;
    private final Set<UUID> tenants;
    private final long ownerBytes,tenantBytes;
    private final String retentionPolicySha256;
    public FindingEvidenceIngestionProperties(
            @Value("${software-factory.findings.evidence-ingestion.enabled:false}") boolean enabled,
            @Value("${software-factory.findings.evidence-ingestion.technical-actor-id:}") String actor,
            @Value("${software-factory.findings.evidence-ingestion.tenant-ids:}") String tenants,
            @Value("${software-factory.findings.evidence-ingestion.owner-physical-bytes:1073741824}") long ownerBytes,
            @Value("${software-factory.findings.evidence-ingestion.tenant-physical-bytes:8589934592}") long tenantBytes,
            @Value("${software-factory.findings.evidence-ingestion.retention-policy-sha256:}") String retentionPolicySha256) {
        this.enabled=enabled;technicalActorId=actor.isBlank()?null:UUID.fromString(actor);
        this.tenants=tenants.isBlank()?Set.of():Arrays.stream(tenants.split(",",-1)).map(String::strip)
                .map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
        if(this.tenants.size()>128||ownerBytes<1||tenantBytes<ownerBytes||tenantBytes>1_099_511_627_776L
                ||!retentionPolicySha256.isEmpty()&&!retentionPolicySha256.matches("[0-9a-f]{64}")
                ||enabled&&(technicalActorId==null||this.tenants.isEmpty()))throw new IllegalArgumentException("Invalid ingestion policy");
        this.ownerBytes=ownerBytes;this.tenantBytes=tenantBytes;this.retentionPolicySha256=retentionPolicySha256;
    }
    public boolean enabled(){return enabled;}
    public UUID technicalActorId(){return technicalActorId;}
    public Set<UUID> tenants(){return tenants;}
    public long ownerBytes(){return ownerBytes;}
    public long tenantBytes(){return tenantBytes;}
    public String retentionPolicySha256(){return retentionPolicySha256;}
    public Duration lease(){return Duration.ofSeconds(120);}
    public Duration heartbeat(){return Duration.ofSeconds(15);}
    public Duration scanDeadline(){return Duration.ofSeconds(90);}
    public Duration orphanGrace(){return Duration.ofHours(1);}
}
