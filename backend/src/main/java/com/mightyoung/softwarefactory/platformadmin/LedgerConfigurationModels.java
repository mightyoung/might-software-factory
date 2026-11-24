package com.mightyoung.softwarefactory.platformadmin;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LedgerConfigurationModels {
    private LedgerConfigurationModels() {}
    public record Field(String key, String label, String type, boolean required) {}
    public record Definition(String name, List<Field> fields, List<String> listFields, List<String> queryFields,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Workflow workflow) {
        public Definition(String name, List<Field> fields, List<String> listFields, List<String> queryFields) {
            this(name, fields, listFields, queryFields, null);
        }
    }
    public record Workflow(List<Node> nodes, List<Edge> edges,
                           @JsonInclude(JsonInclude.Include.NON_NULL) List<String> states) {
        public Workflow(List<Node> nodes, List<Edge> edges) { this(nodes, edges, null); }
    }
    public record Node(String id, String kind, String label,
                       @JsonDeserialize(using = WorkflowCoordinateDeserializer.class) Integer x,
                       @JsonDeserialize(using = WorkflowCoordinateDeserializer.class) Integer y,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> transitions,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> fieldRules,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Approval approval) {
        public Node(String id, String kind, String label, Integer x, Integer y) {
            this(id, kind, label, x, y, null, null, null);
        }
    }
    public record Approval(String source, List<String> targets, String mode,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String fallbackMember) {}
    public record Edge(String from, String to) {}
    public record SaveRequest(UUID requestId, Long expectedVersion, Definition definition) {}
    public record RestoreRequest(UUID requestId, Long expectedVersion, Long sourceVersion) {}
    public record PublishRequest(Long version, Long expectedVersion, Long expectedPublishedVersion) {}
    public record Snapshot(String key, long version, Long publishedVersion, Definition definition,
                           Instant savedAt, Long sourceVersion, UUID savedBy) {}
    public record Item(String key, long latestVersion, Long publishedVersion, Definition definition,
                       Instant savedAt, Long sourceVersion, UUID savedBy) {}
    public record Items(List<Item> items) {}
    public record Versions(List<Snapshot> versions) {}
}
