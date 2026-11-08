package com.mightyoung.softwarefactory.workflow;

import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Resolves only explicit reviewed tenant pins. This component never deploys definitions. */
@Component
@EnableConfigurationProperties(FindingReviewDefinitionProperties.class)
public final class PinnedFindingReviewDefinitionResolver {
    public static final String DEFINITION_KEY = "findingRemediationV3";
    static final String RESOURCE = "processes/finding-remediation-v3.bpmn20.xml";
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
        verify(repository, pin);
        return pin;
    }

    static ProcessDefinition verify(RepositoryService repository, WorkflowDefinitionPin pin) {
        try {
            ProcessDefinition definition = repository.createProcessDefinitionQuery()
                    .processDefinitionId(pin.definitionId()).singleResult();
            if (definition == null || !pin.definitionId().equals(definition.getId())
                    || !pin.tenantId().toString().equals(definition.getTenantId())
                    || !pin.expectedKey().equals(definition.getKey()) || definition.isSuspended()
                    || definition.getDeploymentId() == null || definition.getResourceName() == null) {
                throw unavailable();
            }
            try (InputStream packaged = PinnedFindingReviewDefinitionResolver.class.getClassLoader()
                    .getResourceAsStream(RESOURCE);
                 InputStream deployed = repository.getResourceAsStream(definition.getDeploymentId(),
                         definition.getResourceName())) {
                if (packaged == null || deployed == null
                        || !pin.resourceSha256().equals(sha256(packaged.readAllBytes()))
                        || !pin.resourceSha256().equals(sha256(deployed.readAllBytes()))) throw unavailable();
            }
            return definition;
        } catch (FlowableException | IOException failure) {
            throw new WorkflowEngineUnavailableException(failure);
        }
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static WorkflowEngineUnavailableException unavailable() {
        return new WorkflowEngineUnavailableException(new IllegalStateException("Reviewed finding definition unavailable"));
    }
}
