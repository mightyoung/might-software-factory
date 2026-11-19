package com.mightyoung.softwarefactory.workflow;

import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import java.util.Map;

final class PackagedWorkflowDefinitions {
    private PackagedWorkflowDefinitions() {}
    static final Map<String, String> RESOURCES = Map.of(
            "findingRemediationV3", "processes/finding-remediation-v3.bpmn20.xml",
            "toolLoanV1", "processes/tool-loan-v1.bpmn20.xml");

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
            try (InputStream packaged = PackagedWorkflowDefinitions.class.getClassLoader()
                    .getResourceAsStream(RESOURCES.get(pin.expectedKey()));
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
        return new WorkflowEngineUnavailableException(new IllegalStateException("Reviewed workflow definition unavailable"));
    }
}
