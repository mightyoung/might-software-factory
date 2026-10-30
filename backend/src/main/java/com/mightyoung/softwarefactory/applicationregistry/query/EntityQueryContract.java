package com.mightyoung.softwarefactory.applicationregistry.query;

import tools.jackson.core.JacksonException;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared DTOs for the generic, schema-driven entity query contract.
 *
 * <p>These records describe wire data only. They do not authorize a query or execute it.
 * Callers must resolve tenant, permission, and field visibility before validating a request
 * against an effective schema.</p>
 */
public final class EntityQueryContract {

    private static final JsonMapper STRICT_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EntityQueryContract() {
    }

    public static Request readRequest(String json) {
        try {
            return STRICT_JSON.readValue(json, Request.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid entity query JSON", exception);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            FilterGroup filterAST,
            List<SortField> sort,
            PageRequest page,
            Integer size,
            List<String> fields) {
    }

    public record FilterGroup(LogicalOperator operator, List<FilterCondition> conditions) {
    }

    public record FilterCondition(String field, FilterOperator operator, JsonNode value) {
    }

    public record SortField(String field, Direction direction) {
    }

    /** Exactly one of {@code number} (one-based page) or {@code cursor} must be supplied. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PageRequest(Integer number, String cursor) {
    }

    public record EntitySchema(
            String key,
            String schemaVersion,
            String idField,
            List<String> defaultFields,
            Map<String, FieldSchema> fields) {
    }

    public record FieldSchema(
            FieldType type,
            boolean nullable,
            boolean selectable,
            boolean sortable,
            Set<FilterOperator> operators,
            int maxLength,
            Set<String> enumValues) {
    }

    public enum LogicalOperator {
        AND
    }

    public enum Direction {
        ASC,
        DESC
    }

    public enum FilterOperator {
        EQ,
        GT,
        GTE,
        LT,
        LTE,
        BETWEEN,
        IN,
        CONTAINS,
        IS_EMPTY,
        IS_NOT_EMPTY,
        IS_NULL,
        IS_NOT_NULL,
        IN_SUBTREE
    }

    public enum FieldType {
        TEXT,
        NUMBER,
        MONEY,
        DATE,
        DATETIME,
        BOOLEAN,
        ENUM,
        UUID,
        USER,
        ORGANIZATION,
        REFERENCE,
        IMAGE,
        ATTACHMENT
    }

    public record EntityQueryResponse<T>(ListData<T> data, Metadata meta) {
    }

    public record ListData<T>(List<T> items, PageMetadata page) {
    }

    /** A missing total is represented by {@code null} and omitted by the JSON serializer. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PageMetadata(Integer size, Integer number, String nextCursor, Long total) {
    }

    public record Metadata(String requestId, String traceId, String schemaVersion) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(
            String code,
            String message,
            String traceId,
            boolean retryable,
            List<FieldError> fieldErrors,
            Long currentVersion) {
    }

    public record FieldError(String field, String reason) {
    }

    public static final class ValidationException extends IllegalArgumentException {

        private final String field;
        private final String code;

        public ValidationException(String field, String code, String message) {
            super(message);
            this.field = field;
            this.code = code;
        }

        public String field() {
            return field;
        }

        public String code() {
            return code;
        }
    }
}
