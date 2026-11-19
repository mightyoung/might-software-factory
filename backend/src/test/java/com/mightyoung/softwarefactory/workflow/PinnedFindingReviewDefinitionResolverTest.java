package com.mightyoung.softwarefactory.workflow;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PinnedFindingReviewDefinitionResolverTest {
    private final UUID tenant = UUID.randomUUID();
    private final RepositoryService repository = mock(RepositoryService.class);
    private final ProcessDefinition definition = mock(ProcessDefinition.class);
    private byte[] bytes;
    private String digest;
    @BeforeEach void setup() throws Exception {
        try (var stream = getClass().getResourceAsStream("/" + PackagedWorkflowDefinitions.RESOURCES.get("findingRemediationV3"))) {
            bytes = stream.readAllBytes();
        }
        digest = PackagedWorkflowDefinitions.sha256(bytes);
        var query = mock(ProcessDefinitionQuery.class, RETURNS_SELF);
        when(repository.createProcessDefinitionQuery()).thenReturn(query);
        when(query.singleResult()).thenReturn(definition);
        when(definition.getId()).thenReturn("reviewed-id");
        when(definition.getKey()).thenReturn("findingRemediationV3");
        when(definition.getTenantId()).thenReturn(tenant.toString());
        when(definition.getDeploymentId()).thenReturn("deployment");
        when(definition.getResourceName()).thenReturn("reviewed.bpmn20.xml");
        when(repository.getResourceAsStream("deployment", "reviewed.bpmn20.xml"))
                .thenAnswer(call -> new ByteArrayInputStream(bytes));
    }
    @Test void resolvesOnlyConfiguredExactDefinitionAndNeverDeploys() {
        var pin = resolver(true, digest).resolve(tenant);
        assertThat(pin).isEqualTo(new WorkflowDefinitionPin(tenant, "reviewed-id", "findingRemediationV3", digest));
        verify(repository.createProcessDefinitionQuery()).processDefinitionId("reviewed-id");
        verify(repository, never()).createDeployment();
    }
    @Test void disabledOrMissingTenantFailsBeforeEngineLookup() {
        assertThatThrownBy(() -> resolver(false, digest).resolve(tenant))
                .isInstanceOf(WorkflowEngineUnavailableException.class);
        assertThatThrownBy(() -> resolver(true, digest).resolve(UUID.randomUUID()))
                .isInstanceOf(WorkflowEngineUnavailableException.class);
        verify(repository, never()).createProcessDefinitionQuery();
    }
    @Test void exactIdTenantKeyAndSuspensionAreMandatory() {
        when(definition.getId()).thenReturn("newer-id"); rejects();
        when(definition.getId()).thenReturn("reviewed-id");
        when(definition.getTenantId()).thenReturn(UUID.randomUUID().toString()); rejects();
        when(definition.getTenantId()).thenReturn(tenant.toString());
        when(definition.getKey()).thenReturn("findingRemediationV2"); rejects();
        when(definition.getKey()).thenReturn("findingRemediationV3");
        when(definition.isSuspended()).thenReturn(true); rejects();
    }
    @Test void missingDefinitionMissingResourceAndBothDigestMismatchesFailClosed() {
        var query = repository.createProcessDefinitionQuery();
        when(query.singleResult()).thenReturn(null); rejects();
        when(query.singleResult()).thenReturn(definition);
        when(repository.getResourceAsStream("deployment", "reviewed.bpmn20.xml")).thenReturn(null); rejects();
        when(repository.getResourceAsStream("deployment", "reviewed.bpmn20.xml"))
                .thenAnswer(call -> new ByteArrayInputStream(new byte[]{1, 2})); rejects();
        assertThatThrownBy(() -> resolver(true, "0".repeat(64)).resolve(tenant))
                .isInstanceOf(WorkflowEngineUnavailableException.class);
    }
    private void rejects() {
        assertThatThrownBy(() -> resolver(true, digest).resolve(tenant))
                .isInstanceOf(WorkflowEngineUnavailableException.class);
    }
    private PinnedFindingReviewDefinitionResolver resolver(boolean enabled, String sha) {
        return new PinnedFindingReviewDefinitionResolver(new FindingReviewDefinitionProperties(enabled,
                Map.of(tenant, new FindingReviewDefinitionProperties.Pin("reviewed-id", sha))), repository);
    }
}
