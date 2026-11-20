package com.mightyoung.softwarefactory.platformadmin;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    @Test void coordinatesRejectFractionalNumbersTextAndOverflowAtJsonBoundary() {
        String valid = json.writeValueAsString(withWorkflow(linear()));
        for (String value : List.of("1.5", "\"1\"", "true", "2147483648", "{}", "[]")) {
            assertThatThrownBy(() -> json.readValue(valid.replaceFirst("\"x\":0", "\"x\":" + value), Definition.class))
                    .isInstanceOf(tools.jackson.core.JacksonException.class);
        }
    }
}
