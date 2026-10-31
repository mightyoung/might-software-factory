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
    void apiContractExposesAllCommandsAndSubmittedEvidenceReadRoute() throws Exception {
        Path contractPath = Path.of("..", "contracts", "openapi", "finding-workflow.yaml");
        Map<String, Object> document = new Yaml().load(Files.readString(contractPath));
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        assertThat(paths.keySet()).containsExactlyInAnyOrder(
                "/api/v1/findings",
                "/api/v1/findings/{id}/confirm/person",
                "/api/v1/findings/{id}/confirm/safety",
                "/api/v1/findings/{id}/dispatch",
                "/api/v1/findings/{id}/remediation-submissions",
                "/api/v1/findings/{id}/verifications",
                "/api/v1/findings/{id}/workflow-initialization",
                "/api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-items/{evidenceItemId}/content");

        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            Map<String, Object> path = (Map<String, Object>) entry.getValue();
            if (path.containsKey("get")) {
                Map<String, Object> get = (Map<String, Object>) path.get("get");
                assertThat(get.get("operationId")).isEqualTo("readSubmittedFindingEvidenceContent");
                assertThat(get.get("description").toString())
                        .contains("does not accept a file ID, object key, storage path, or storage version");
                continue;
            }
            Map<String, Object> post = (Map<String, Object>) path.get("post");
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
    void createContractUsesServerNumberingAndPreventsReporterRiskGrading() throws Exception {
        Map<String, Object> document = new Yaml().load(Files.readString(
                Path.of("..", "contracts", "openapi", "finding-workflow.yaml")));
        Map<String, Object> components = (Map<String, Object>) document.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> createRequest = (Map<String, Object>) schemas.get("CreateFindingRequest");
        Map<String, Object> createProperties = (Map<String, Object>) createRequest.get("properties");

        assertThat((List<String>) createRequest.get("required"))
                .contains("organizationId", "description", "discoveredAt", "grade")
                .doesNotContain("code");
        assertThat(createProperties).doesNotContainKey("code");
        assertThat((Map<String, Object>) createProperties.get("grade"))
                .containsEntry("enum", List.of("UNKNOWN"));

        Map<String, Object> response = (Map<String, Object>) schemas.get("CommandResponse");
        Map<String, Object> responseProperties = (Map<String, Object>) response.get("properties");
        Map<String, Object> data = (Map<String, Object>) responseProperties.get("data");
        assertThat((List<String>) data.get("required")).contains("code");
        assertThat((Map<String, Object>) data.get("properties")).containsKey("code");
    }

    @Test
    void errorContractDistinguishesConflictsAndDefinesSafeFieldErrorCodes() throws Exception {
        Map<String, Object> workflow = new Yaml().load(Files.readString(
                Path.of("..", "contracts", "openapi", "finding-workflow.yaml")));
        Map<String, Object> workflowComponents = (Map<String, Object>) workflow.get("components");
        Map<String, Object> workflowResponses = (Map<String, Object>) workflowComponents.get("responses");
        Map<String, Object> paths = (Map<String, Object>) workflow.get("paths");
        Map<String, Object> createPost = (Map<String, Object>) ((Map<String, Object>) paths
                .get("/api/v1/findings")).get("post");
        Map<String, Object> createResponses = (Map<String, Object>) createPost.get("responses");
        assertThat((Map<String, Object>) createResponses.get("409"))
                .containsEntry("$ref", "#/components/responses/IdempotencyConflict");

        Map<String, Object> personConfirmPost = (Map<String, Object>) ((Map<String, Object>) paths
                .get("/api/v1/findings/{id}/confirm/person")).get("post");
        Map<String, Object> personConfirmResponses = (Map<String, Object>) personConfirmPost.get("responses");
        assertThat((Map<String, Object>) personConfirmResponses.get("409"))
                .containsEntry("$ref", "#/components/responses/CommandConflict");

        Map<String, Object> commandConflict = (Map<String, Object>) workflowResponses.get("CommandConflict");
        Map<String, Object> conflictContent = (Map<String, Object>) commandConflict.get("content");
        Map<String, Object> conflictJson = (Map<String, Object>) conflictContent.get("application/json");
        Map<String, Object> conflictExamples = (Map<String, Object>) conflictJson.get("examples");
        assertThat(conflictExamples.keySet()).containsExactlyInAnyOrder(
                "versionConflict", "idempotencyConflict", "workflowStateConflict");
        assertThat(errorExampleCode(conflictExamples, "versionConflict")).isEqualTo("VERSION_CONFLICT");
        assertThat(errorExampleCode(conflictExamples, "idempotencyConflict")).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(errorExampleCode(conflictExamples, "workflowStateConflict")).isEqualTo("COMMAND_CONFLICT");

        Map<String, Object> invalidRequest = (Map<String, Object>) workflowResponses.get("InvalidRequest");
        Map<String, Object> invalidContent = (Map<String, Object>) invalidRequest.get("content");
        Map<String, Object> invalidJson = (Map<String, Object>) invalidContent.get("application/json");
        Map<String, Object> invalidExamples = (Map<String, Object>) invalidJson.get("examples");
        assertThat(invalidExamples.keySet()).containsExactlyInAnyOrder(
                "fieldValidation", "malformedJsonOrBinding", "unsupportedCreateField");
        Map<String, Object> fieldErrorExample = (Map<String, Object>) ((Map<String, Object>) invalidExamples
                .get("fieldValidation")).get("value");
        assertThat(fieldErrorExample.get("code")).isEqualTo("INVALID_REQUEST");
        assertThat((List<Map<String, Object>>) fieldErrorExample.get("fieldErrors"))
                .containsExactly(Map.of("field", "description", "reason", "Size"));
        for (String genericBadRequest : List.of("malformedJsonOrBinding", "unsupportedCreateField")) {
            Map<String, Object> example = (Map<String, Object>) ((Map<String, Object>) invalidExamples
                    .get(genericBadRequest)).get("value");
            assertThat(example.get("code")).isEqualTo("INVALID_QUERY");
            assertThat(example.get("fieldErrors")).isEqualTo(List.of());
        }

        Map<String, Object> workflowSchemas = (Map<String, Object>) workflowComponents.get("schemas");
        Map<String, Object> createSchema = (Map<String, Object>) workflowSchemas.get("CreateFindingRequest");
        assertThat(createSchema.get("additionalProperties")).isEqualTo(false);
        assertThat(createSchema.get("description").toString())
                .contains("rejects unknown properties at runtime");
        for (String lifecycleRequest : List.of("VersionedCommandRequest", "DispatchRequest",
                "RemediationSubmissionRequest", "VerificationRequest")) {
            Map<String, Object> lifecycleSchema = (Map<String, Object>) workflowSchemas.get(lifecycleRequest);
            assertThat(lifecycleSchema.get("additionalProperties")).isEqualTo(false);
            assertThat(lifecycleSchema.get("description").toString())
                    .contains("current runtime ignores unknown properties on lifecycle commands")
                    .contains("additionalProperties false describes the client contract");
        }

        Map<String, Object> shared = new Yaml().load(Files.readString(
                Path.of("..", "contracts", "openapi", "entity-query.yaml")));
        Map<String, Object> sharedComponents = (Map<String, Object>) shared.get("components");
        Map<String, Object> sharedSchemas = (Map<String, Object>) sharedComponents.get("schemas");
        Map<String, Object> fieldError = (Map<String, Object>) sharedSchemas.get("FieldError");
        Map<String, Object> fieldErrorProperties = (Map<String, Object>) fieldError.get("properties");
        assertThat(((Map<String, Object>) fieldErrorProperties.get("field")).get("description").toString())
                .contains("JSON request field path");
        assertThat(((Map<String, Object>) fieldErrorProperties.get("reason")).get("description").toString())
                .contains("Stable machine-readable validation code")
                .contains("never validation prose or a rejected value");
    }

    private static String errorExampleCode(Map<String, Object> examples, String key) {
        Map<String, Object> example = (Map<String, Object>) examples.get(key);
        Map<String, Object> value = (Map<String, Object>) example.get("value");
        return value.get("code").toString();
    }

    @Test
    void findingNumberMigrationCreatesTheRlsIsolatedTenantSequence() throws Exception {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V7__finding_business_number_sequence.sql"));
        assertThat(migration).contains(
                "create table public.finding_number_sequence",
                "primary key (tenant_id, business_day)",
                "enable row level security",
                "force row level security",
                "current_setting('app.tenant_id', true)")
                .doesNotContain("grant select", "grant insert", "grant update", "grant all");
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
                "foreign key (tenant_id, notice_id, finding_id, round_no)",
                "foreign key (tenant_id, submission_id, finding_id, round_no)",
                "constraint finding_transition_command_key unique (tenant_id, command_id)");
        String compactMigration = migration.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        assertThat(compactMigration).contains(
                "foreign key (tenant_id, evidence_set_id, finding_id, round_no, submitted_by)");
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
        assertThat(migration).contains("owner_member_id", "finding_id", "round_no", "verification_level")
                .doesNotContain("update public.finding");
        String listener = Files.readString(Path.of("src", "main", "java", "com", "mightyoung",
                "softwarefactory", "dualprevention", "FindingWorkflowEventListener.java"));
        assertThat(listener).contains("@ApplicationModuleListener");
        String originalFindingMigration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V2__dualprevention_finding_read.sql"));
        assertThat(originalFindingMigration).contains("'PERSON_CONFIRM_PENDING'", "'SAFETY_CONFIRM_PENDING'");
    }

    @Test
    void fileEvidenceMetadataMigrationAddsOnlyTenantScopedMetadataAndLinks() throws Exception {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V6__file_evidence_reference_metadata.sql"));
        String compactMigration = migration.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        assertThat(compactMigration).contains(
                "create table public.file_object",
                "owner_member_id uuid not null",
                "owner_actor_id uuid not null",
                "state in ( 'uploading', 'quarantined', 'ready', 'deleting', 'failed' )",
                "constraint file_object_ready_shape",
                "create table public.file_link",
                "constraint file_link_file_fkey",
                "alter table public.evidence_item",
                "foreign key (tenant_id, file_id)",
                "alter table public.file_object enable row level security",
                "alter table public.file_object force row level security",
                "create policy file_object_tenant_isolation",
                "alter table public.file_link enable row level security",
                "alter table public.file_link force row level security",
                "create policy file_link_tenant_isolation")
                .doesNotContain("grant ", "insert into public.file_object", "insert into public.file_link");
    }

    @Test
    void remediationSubmissionPinsImmutableEvidenceBytesAndTrustedReceiptContext() throws Exception {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V6__file_evidence_reference_metadata.sql"));
        String normalized = migration.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        assertThat(normalized).contains(
                "create table public.remediation_submission_evidence",
                "file_link_id uuid not null",
                "file_id uuid not null",
                "sha256 varchar(64) not null",
                "storage_version varchar(255) not null",
                "verification_receipt_id uuid not null",
                "verifier_id varchar(128) not null",
                "verifier_key_id varchar(128) not null",
                "verification_policy_version varchar(64) not null",
                "foreign key (tenant_id, submission_id, evidence_set_id, finding_id, round_no, submitted_by)",
                "foreign key (tenant_id, evidence_item_id, evidence_set_id)",
                "foreign key (tenant_id, file_link_id, file_id, resource_type, resource_id, purpose)",
                "for each row execute function public.reject_submission_evidence_mutation()",
                "create trigger evidence_item_submission_binding_immutable",
                "create trigger file_link_identity_immutable",
                "create trigger file_object_submission_version_immutable",
                "alter table public.remediation_submission_evidence force row level security")
                .doesNotContain("unique (tenant_id, submission_id, file_id)");
        assertThat(normalized).contains(
                "evidence_snapshot_count integer",
                "evidence_snapshot_count is not null",
                "evidence_snapshot_manifest uuid[]",
                "evidence_snapshots_sealed_at timestamptz",
                "alter table public.evidence_set add column evidence_revision bigint not null default 0",
                "add column submission_sealed_at timestamptz",
                "create function public.guard_evidence_set_submission_version",
                "create function public.advance_evidence_set_revision_for_item_change",
                "create function public.reject_evidence_item_hard_delete",
                "before delete on public.evidence_item",
                "before insert or update on public.evidence_item",
                "hard deletion of evidence items is prohibited",
                "evidence item tenant/id/set identity is immutable",
                "create function public.guard_remediation_submission_snapshot_seal",
                "set evidence_revision = es.evidence_revision + 1",
                "set evidence_revision = es.evidence_revision + 1, submission_sealed_at = transaction_timestamp()",
                "array_agg(snapshot.evidence_item_id order by snapshot.evidence_item_id)",
                "array_agg(item.id order by item.id)",
                "evidence snapshot manifest does not match the complete evidence set",
                "create trigger remediation_submission_evidence_seal_guard",
                "create constraint trigger remediation_submission_requires_evidence_seal",
                "deferrable initially deferred",
                "remediation submission cannot commit before its evidence snapshots are sealed",
                "create trigger remediation_submission_evidence_parent_lock",
                "create trigger evidence_item_submission_set_lock",
                "evidence_snapshots_sealed_at is not null",
                "remediation submission evidence snapshots are sealed",
                "evidence set is sealed by a remediation submission",
                "a sealed remediation submission is immutable",
                "and evidence_set.submission_sealed_at is not null");
        assertThat(normalized.indexOf("set evidence_revision = es.evidence_revision + 1, submission_sealed_at"))
                .isLessThan(normalized.indexOf("select array_agg(item.id order by item.id)"));
        int versionGuardStart = normalized.indexOf("create function public.guard_evidence_set_submission_version");
        int versionGuardEnd = normalized.indexOf("create trigger evidence_set_submission_version_guard");
        assertThat(normalized.substring(versionGuardStart, versionGuardEnd))
                .doesNotContain("update public.evidence_set");
        int itemMutationStart = normalized.indexOf("create function public.advance_evidence_set_revision_for_item_change");
        int itemMutationEnd = normalized.indexOf("create trigger evidence_item_submission_set_lock");
        String itemMutation = normalized.substring(itemMutationStart, itemMutationEnd);
        assertThat(itemMutation)
                .contains("update public.evidence_set", "set evidence_revision = es.evidence_revision + 1")
                .contains("evidence item tenant/id/set identity is immutable")
                .doesNotContain("for update", "delete");
        assertThat(itemMutation.indexOf("evidence item tenant/id/set identity is immutable"))
                .isLessThan(itemMutation.indexOf("update public.evidence_set es"));
        int hardDeleteStart = normalized.indexOf("create function public.reject_evidence_item_hard_delete");
        int hardDeleteEnd = normalized.indexOf("create trigger evidence_item_hard_delete_prohibited");
        String hardDeleteHandler = normalized.substring(hardDeleteStart, hardDeleteEnd);
        assertThat(hardDeleteHandler).contains("hard deletion of evidence items is prohibited")
                .doesNotContain("update public.evidence_set", "for update");

        String pg18Checklist = Files.readString(Path.of("..", "docs", "pg18-finding-evidence-integration-checklist.md"));
        assertThat(pg18Checklist).contains("may receive only its required `INSERT`/`UPDATE` privileges",
                "Do not grant runtime `DELETE` or `TRUNCATE`", "database trigger rejects row deletion")
                .doesNotContain("`INSERT`/`UPDATE`/`DELETE` operations");

        String repository = Files.readString(Path.of("src", "main", "java", "com", "mightyoung",
                "softwarefactory", "dualprevention", "finding", "workflow",
                "JooqFindingWorkflowRepository.java"));
        assertThat(repository).contains("insert into public.remediation_submission_evidence",
                "verification_receipt_id", "storage_version", "findSubmissionEvidenceSnapshots",
                "evidence_snapshots_sealed_at", "evidence_snapshot_manifest", "evidence_snapshot_count",
                "setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED)");
        String evidencePolicy = Files.readString(Path.of("src", "main", "java", "com", "mightyoung",
                "softwarefactory", "dualprevention", "finding", "workflow",
                "JooqFindingWorkflowEvidencePolicy.java"));
        assertThat(evidencePolicy).contains("ei.sha256", "snapshotMatches");
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
            var failureFlow = process.getElementsByTagNameNS(BPMN_NS, "sequenceFlow");
            boolean failureReturnsToSafetyDispatch = false;
            for (int index = 0; index < failureFlow.getLength(); index++) {
                var flow = failureFlow.item(index);
                if ("verificationFailure".equals(flow.getAttributes().getNamedItem("id").getNodeValue())) {
                    failureReturnsToSafetyDispatch = "safetyDispatch".equals(
                            flow.getAttributes().getNamedItem("targetRef").getNodeValue());
                }
            }
            assertThat(failureReturnsToSafetyDispatch).isTrue();

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
