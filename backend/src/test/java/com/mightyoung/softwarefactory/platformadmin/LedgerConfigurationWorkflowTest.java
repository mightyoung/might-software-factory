package com.mightyoung.softwarefactory.platformadmin;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Collections;

import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.*;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationServiceTest.complete;
import static org.assertj.core.api.Assertions.*;

class LedgerConfigurationWorkflowTest {
    private final JsonMapper json = JsonMapper.builder().build();

    static Workflow linear() {
        return new Workflow(List.of(new Node("start", "start", "登记", 0, 0),
                new Node("review", "step", "审核", 500, 500), new Node("end", "end", "完成", 1000, 1000)),
                List.of(new Edge("start", "review"), new Edge("review", "end")));
    }

    static Definition withWorkflow(Workflow workflow) {
        Definition base = complete();
        return new Definition(base.name(), base.fields(), base.listFields(), base.queryFields(), workflow);
    }

    @Test void legacyDefinitionRetainsItsOriginalJsonAndOptionalWorkflowRoundTrips() {
        String oldJson = "{\"name\":\"采购\",\"fields\":[{\"key\":\"quantity\",\"label\":\"数量\",\"type\":\"number\",\"required\":true}],\"listFields\":[\"quantity\"],\"queryFields\":[\"quantity\"]}";
        assertThat(json.readValue(oldJson, Definition.class)).isEqualTo(complete());
        assertThat(json.readTree(json.writeValueAsString(complete()))).isEqualTo(json.readTree(oldJson));
        Definition graph = withWorkflow(linear());
        assertThat(json.readValue(json.writeValueAsString(graph), Definition.class)).isEqualTo(graph);
        assertThatCode(() -> LedgerConfigurationService.validDefinition(complete(), true)).doesNotThrowAnyException();
        assertThatCode(() -> LedgerConfigurationService.validDefinition(graph, true)).doesNotThrowAnyException();
    }

    @Test void disconnectedAndUnlabelledGraphsRemainDraftsButCannotBePublished() {
        List<Workflow> drafts = List.of(new Workflow(List.of(), List.of()),
                new Workflow(linear().nodes(), List.of()),
                new Workflow(List.of(new Node("start", "start", "", 0, 0), new Node("end", "end", "完成", 1000, 0)),
                        List.of(new Edge("start", "end"))));
        for (Workflow workflow : drafts) {
            assertThatCode(() -> LedgerConfigurationService.validDefinition(withWorkflow(workflow), false)).doesNotThrowAnyException();
            assertThatThrownBy(() -> LedgerConfigurationService.validDefinition(withWorkflow(workflow), true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void invalidNodesCoordinatesAndEdgesAreRejectedBeforeSave() {
        List<Workflow> malformed = new ArrayList<>(List.of(
                new Workflow(null, List.of()), new Workflow(List.of(), null),
                new Workflow(Arrays.asList((Node) null), List.of()),
                new Workflow(List.of(linear().nodes().getFirst(), linear().nodes().getFirst()), List.of()),
                new Workflow(linear().nodes(), List.of(new Edge("missing", "end"))),
                new Workflow(linear().nodes(), List.of(new Edge("start", "start"))),
                new Workflow(linear().nodes(), List.of(new Edge("start", "end"), new Edge("start", "end"))),
                new Workflow(linear().nodes(), Arrays.asList((Edge) null))));
        for (Node node : List.of(new Node(null, "step", "审核", 1, 1), new Node("bad-id", "step", "审核", 1, 1),
                new Node("a", null, "审核", 1, 1), new Node("a", "script", "审核", 1, 1),
                new Node("a", "step", null, 1, 1), new Node("a", "step", "a".repeat(121), 1, 1),
                new Node("a", "step", "审核", null, 1), new Node("a", "step", "审核", 1, null),
                new Node("a", "step", "审核", -1, 1), new Node("a", "step", "审核", 1, 1001))) {
            malformed.add(new Workflow(List.of(node), List.of()));
        }
        malformed.add(new Workflow(java.util.stream.IntStream.range(0, 33)
                .mapToObj(i -> new Node("n" + i, "step", "节点", 1, 1)).toList(), List.of()));
        malformed.add(new Workflow(linear().nodes(), java.util.Collections.nCopies(32, new Edge("start", "end"))));
        for (Workflow workflow : malformed) {
            assertThatThrownBy(() -> LedgerConfigurationService.validDefinition(withWorkflow(workflow), false)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void publicationRejectsBranchesCyclesAndDisconnectedComponents() {
        List<Node> nodes = List.of(new Node("start", "start", "登记", 0, 0), new Node("a", "step", "甲", 1, 1),
                new Node("b", "step", "乙", 2, 2), new Node("end", "end", "完成", 3, 3));
        for (Workflow workflow : List.of(
                new Workflow(nodes, List.of(new Edge("start", "a"), new Edge("start", "b"), new Edge("a", "end"), new Edge("b", "end"))),
                new Workflow(nodes, List.of(new Edge("start", "end"), new Edge("a", "b"), new Edge("b", "a"))),
                new Workflow(List.of(new Node("start", "start", "登记", 0, 0), new Node("other", "start", "登记二", 1, 1), new Node("end", "end", "完成", 2, 2)), List.of(new Edge("start", "end"))))) {
            assertThatCode(() -> LedgerConfigurationService.validDefinition(withWorkflow(workflow), false)).doesNotThrowAnyException();
            assertThatThrownBy(() -> LedgerConfigurationService.validDefinition(withWorkflow(workflow), true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    static Definition configured() {
        Definition base = complete();
        var nodes = new ArrayList<>(linear().nodes());
        nodes.set(1, new Node("review", "step", "审核", 500, 500,
                Map.of("enter", "待审核", "approve", "已通过", "return", "待补正"),
                Map.of("quantity", "readonly", "owner", "required"),
                new Approval("field", List.of("owner"), "sequential", "11111111-1111-4111-8111-111111111111")));
        var fields = new ArrayList<>(base.fields());
        fields.add(new Field("owner", "责任人", "text", false));
        return new Definition(base.name(), fields, base.listFields(), base.queryFields(),
                new Workflow(nodes, linear().edges(), List.of("待审核", "已通过", "待补正")));
    }

    static Definition rules(Map<String, String> transitions, Map<String, String> fieldRules, Approval approval) {
        var nodes = new ArrayList<>(linear().nodes());
        nodes.set(1, new Node("review", "step", "审核", 500, 500, transitions, fieldRules, approval));
        return withWorkflow(new Workflow(nodes, linear().edges(), List.of("待审核")));
    }

    @Test void configuredNodeRulesRoundTripWithoutChangingLegacyGraphJson() {
        assertThat(json.readValue(json.writeValueAsString(configured()), Definition.class)).isEqualTo(configured());
        assertThatCode(() -> LedgerConfigurationService.validDefinition(configured(), true)).doesNotThrowAnyException();
        String legacyGraph = json.writeValueAsString(withWorkflow(linear()));
        assertThat(legacyGraph).doesNotContain("states", "transitions", "fieldRules", "approval");
        assertThat(json.readValue(legacyGraph, Definition.class)).isEqualTo(withWorkflow(linear()));
    }

    @Test void incompleteReferencesCanBeSavedButMustResolveBeforePublication() {
        for (Definition draft : List.of(
                rules(Map.of("enter", "未知状态"), null, null),
                rules(null, Map.of("missing", "required"), null),
                rules(null, null, new Approval("members", List.of(), "any", null)),
                rules(null, null, new Approval("members", List.of("unfinished"), "all", null)),
                rules(null, null, new Approval("field", List.of("quantity"), "any", null)),
                rules(null, null, new Approval("field", List.of("missing"), "any", null)),
                rules(null, null, new Approval("departmentManager", List.of("extra"), "any", null)),
                rules(null, null, new Approval("departmentManager", List.of(), "any", "unfinished")))) {
            assertThatCode(() -> LedgerConfigurationService.validDefinition(draft, false)).doesNotThrowAnyException();
            assertThatThrownBy(() -> LedgerConfigurationService.validDefinition(draft, true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void invalidRuleShapesAndBoundsAreRejectedBeforeSaving() {
        for (Definition malformed : List.of(
                rules(Map.of("unknownAction", "待审核"), null, null),
                rules(Collections.singletonMap("enter", null), null, null),
                rules(null, Map.of("bad-key", "required"), null),
                rules(null, Map.of("quantity", "writeAnything"), null),
                rules(null, null, new Approval("unknown", List.of(), "any", null)),
                rules(null, null, new Approval("members", null, "any", null)),
                rules(null, null, new Approval("members", List.of("duplicate", "duplicate"), "any", null)),
                rules(null, null, new Approval("members", List.of(), "unknown", null)),
                withWorkflow(new Workflow(linear().nodes(), linear().edges(), List.of("重复", "重复"))),
                withWorkflow(new Workflow(linear().nodes(), linear().edges(), List.of(" "))),
                withWorkflow(new Workflow(linear().nodes(), linear().edges(), List.of("长".repeat(121)))))) {
            assertThatThrownBy(() -> LedgerConfigurationService.validDefinition(malformed, false)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void directorySourcesAndAllFieldRulesCanBePublished() {
        for (String source : List.of("members", "roles", "positions", "departmentManager")) {
            for (String mode : List.of("any", "all", "sequential")) {
                var targets = source.equals("departmentManager") ? List.<String>of()
                        : List.of("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222");
                Definition definition = rules(Map.of("enter", "待审核", "submit", "待审核"), Map.of("quantity", "hidden"),
                        new Approval(source, targets, mode, null));
                assertThatCode(() -> LedgerConfigurationService.validDefinition(definition, true)).doesNotThrowAnyException();
            }
        }
        for (String rule : List.of("hidden", "readonly", "editable", "required")) {
            assertThatCode(() -> LedgerConfigurationService.validDefinition(rules(null, Map.of("quantity", rule), null), true))
                    .doesNotThrowAnyException();
        }
    }

    @Test void publicationRejectsDuplicateDirectoryIdentitiesRegardlessOfLetterCase() {
        for (String source : List.of("members", "roles", "positions")) {
            Definition draft = rules(null, null, new Approval(source,
                    List.of("aaaaaaaa-1111-4111-8111-111111111111", "AAAAAAAA-1111-4111-8111-111111111111"), "sequential", null));
            assertThatCode(() -> LedgerConfigurationService.validDefinition(draft, false)).doesNotThrowAnyException();
            assertThatThrownBy(() -> LedgerConfigurationService.validDefinition(draft, true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void removingBusinessFieldsLeavesUnresolvedNodeOverridesAsDraftsUntilRepaired() {
        Definition configured = configured();
        for (Node review : List.of(
                new Node("review", "step", "审核", 500, 500, null, Map.of("owner", "required"), null),
                new Node("review", "step", "审核", 500, 500, null, null, configured.workflow().nodes().get(1).approval()))) {
            var nodes = new ArrayList<>(linear().nodes());
            nodes.set(1, review);
            Definition draft = withWorkflow(new Workflow(nodes, linear().edges()));
            assertThatCode(() -> LedgerConfigurationService.validDefinition(draft, false)).doesNotThrowAnyException();
            assertThatThrownBy(() -> LedgerConfigurationService.validDefinition(draft, true)).isInstanceOf(IllegalArgumentException.class);
            assertThat(json.readValue(json.writeValueAsString(draft), Definition.class)).isEqualTo(draft);
        }
        assertThatCode(() -> LedgerConfigurationService.validDefinition(rules(null, Map.of(), null), true))
                .doesNotThrowAnyException();
    }

    @Test void coordinatesRejectFractionalNumbersTextAndOverflowAtJsonBoundary() {
        String valid = json.writeValueAsString(withWorkflow(linear()));
        for (String value : List.of("1.5", "\"1\"", "true", "2147483648", "{}", "[]")) {
            assertThatThrownBy(() -> json.readValue(valid.replaceFirst("\"x\":0", "\"x\":" + value), Definition.class))
                    .isInstanceOf(tools.jackson.core.JacksonException.class);
        }
    }
}
