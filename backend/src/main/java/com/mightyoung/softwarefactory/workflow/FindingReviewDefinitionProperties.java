package com.mightyoung.softwarefactory.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;
import java.util.UUID;

@ConfigurationProperties(prefix = "software-factory.workflow.finding-review")
public record FindingReviewDefinitionProperties(boolean initiationEnabled, Map<UUID, Pin> tenantPins) {
    public FindingReviewDefinitionProperties {
        tenantPins = tenantPins == null ? Map.of() : Map.copyOf(tenantPins);
        if (tenantPins.size() > 256 || tenantPins.keySet().contains(new UUID(0, 0))) {
            throw new IllegalArgumentException("Invalid tenant finding review pins");
        }
    }
    public record Pin(String definitionId, String resourceSha256) {
        public Pin {
            if (definitionId == null || definitionId.isBlank() || resourceSha256 == null
                    || !resourceSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid finding review pin");
            }
        }
    }
}
