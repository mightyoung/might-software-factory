package com.mightyoung.softwarefactory.workflow;

import org.flowable.engine.RepositoryService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Resolves only explicit reviewed tenant pins. This component never deploys definitions. */
@Component
@EnableConfigurationProperties(FindingReviewDefinitionProperties.class)
public final class PinnedFindingReviewDefinitionResolver {
    public static final String DEFINITION_KEY = "findingRemediationV3";
    private final FindingReviewDefinitionProperties properties;
    private final RepositoryService repository;

    public PinnedFindingReviewDefinitionResolver(FindingReviewDefinitionProperties properties,
                                                RepositoryService repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public WorkflowDefinitionPin resolve(UUID tenantId) {
        var configured = properties.tenantPins().get(tenantId);
        if (!properties.initiationEnabled() || configured == null) throw unavailable();
        var pin = new WorkflowDefinitionPin(tenantId, configured.definitionId(), DEFINITION_KEY,
                configured.resourceSha256());
        PackagedWorkflowDefinitions.verify(repository, pin);
        return pin;
    }

    private static WorkflowEngineUnavailableException unavailable() {
        return new WorkflowEngineUnavailableException(new IllegalStateException("Reviewed finding definition unavailable"));
    }
}
