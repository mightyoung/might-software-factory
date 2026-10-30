package com.mightyoung.softwarefactory.dualprevention;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FindingWorkflowContractTest {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";

    @Test
    void apiContractExposesAllSixCommandsWithVersionAndIdempotencyHeaders() throws Exception {
        Path contractPath = Path.of("..", "contracts", "openapi", "finding-workflow.yaml");
        Map<String, Object> document = new Yaml().load(Files.readString(contractPath));
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        assertThat(paths.keySet()).containsExactlyInAnyOrder(
                "/api/v1/findings",
                "/api/v1/findings/{id}/confirm/person",
                "/api/v1/findings/{id}/confirm/safety",
                "/api/v1/findings/{id}/dispatch",
                "/api/v1/findings/{id}/remediation-submissions",
                "/api/v1/findings/{id}/verifications");

        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) entry.getValue()).get("post");
            List<Map<String, Object>> parameters = (List<Map<String, Object>>) post.get("parameters");
            assertThat(parameters).extracting(parameter -> parameter.get("$ref"))
                    .contains("#/components/parameters/IdempotencyKey",
                            "#/components/parameters/CsrfToken");
        }

        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) document.get("components"))
                .get("schemas");
        for (String requestName : List.of("CreateFindingRequest", "VersionedCommandRequest", "DispatchRequest",
                "RemediationSubmissionRequest", "VerificationRequest")) {
            Map<String, Object> request = (Map<String, Object>) schemas.get(requestName);
            Map<String, Object> properties = (Map<String, Object>) request.get("properties");
            assertThat(properties.keySet()).doesNotContain("actorId", "tenantId", "memberId");
            assertThat(request.get("additionalProperties")).isEqualTo(false);
        }
    }

    @Test
    void newMigrationAddsTenantIsolatedAppendOnlyWorkflowRecordsWithoutChangingV2() throws Exception {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V5__finding_workflow_commands.sql"));
        assertThat(migration).contains(
                "insert into public.permission",
                "public.workflow_binding",
                "public.finding_command_receipt",
                "public.finding_confirmation",
                "public.remediation_round",
                "public.remediation_notice",
                "public.remediation_submission",
                "public.verification",
                "public.finding_transition",
                "public.finding_stage_assignment",
                "public.evidence_set",
                "public.evidence_item",
                "public.audit_event",
                "public.event_publication",
                "constraint finding_transition_command_key unique (tenant_id, command_id)");
        String repository = Files.readString(Path.of("src", "main", "java", "com", "mightyoung",
                "softwarefactory", "dualprevention", "finding", "workflow",
                "JooqFindingWorkflowRepository.java"));
        assertThat(repository).contains("on conflict (tenant_id, command_id) do nothing",
                "request_hash", "result_finding_id", "insert into public.audit_event");

        String application = Files.readString(Path.of("src", "main", "resources", "application.yml"));
        assertThat(application).contains("modulith:", "events:", "schema-initialization:", "enabled: false");
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("spring-modulith-starter-jdbc");

        for (String table : List.of("workflow_binding", "finding_command_receipt", "finding_transition",
                "finding_confirmation", "remediation_round", "remediation_notice", "evidence_set",
                "evidence_item", "remediation_submission", "verification", "finding_stage_assignment")) {
            assertThat(migration).contains("alter table public." + table + " enable row level security")
                    .contains("alter table public." + table + " force row level security")
                    .contains("create policy " + table + "_tenant_isolation on public." + table);
        }
        assertThat(migration).contains("alter table public.audit_event enable row level security")
                .contains("alter table public.audit_event force row level security")
                .contains("create policy audit_event_tenant_isolation on public.audit_event")
                .contains("create trigger audit_event_append_only")
                .contains("create table public.event_publication");
        String listener = Files.readString(Path.of("src", "main", "java", "com", "mightyoung",
                "softwarefactory", "dualprevention", "FindingWorkflowEventListener.java"));
        assertThat(listener).contains("@ApplicationModuleListener");
        String originalFindingMigration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V2__dualprevention_finding_read.sql"));
        assertThat(originalFindingMigration).contains("'PERSON_CONFIRM_PENDING'", "'SAFETY_CONFIRM_PENDING'");
    }

    @Test
    void flowableDefinitionContainsBothConfirmationsAndLoopsOnlyFailedAcceptance() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/processes/finding-remediation-v1.bpmn20.xml")) {
            assertThat(stream).isNotNull();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document process = factory.newDocumentBuilder().parse(stream);

            assertThat(process.getElementsByTagNameNS(BPMN_NS, "process").item(0)
                    .getAttributes().getNamedItem("id").getNodeValue()).isEqualTo("findingRemediationV1");
            assertThat(process.getElementsByTagNameNS(BPMN_NS, "userTask").getLength()).isEqualTo(5);
            assertThat(process.getElementsByTagNameNS(BPMN_NS, "serviceTask").getLength()).isZero();
            assertThat(process.getElementsByTagNameNS(BPMN_NS, "scriptTask").getLength()).isZero();
            assertThat(process.getElementsByTagNameNS(BPMN_NS, "sequenceFlow").getLength()).isEqualTo(8);

            Set<String> taskIds = Set.of("personConfirm", "safetyConfirm", "safetyDispatch", "remediation",
                    "safetyVerification");
            for (String taskId : taskIds) {
                var task = process.getElementsByTagNameNS(BPMN_NS, "userTask");
                boolean found = false;
                for (int index = 0; index < task.getLength(); index++) {
                    if (taskId.equals(task.item(index).getAttributes().getNamedItem("id").getNodeValue())) {
                        assertThat(task.item(index).getAttributes().getNamedItemNS(FLOWABLE_NS, "assignee"))
                                .isNotNull();
                        found = true;
                    }
                }
                assertThat(found).isTrue();
            }
        }
    }
}
