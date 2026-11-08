package com.mightyoung.softwarefactory.applicationregistry.reference;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Fixed organization-reference transport. A UUID cursor is a position, never authority. */
public final class ReferenceQueryContract {
    public static final int MAX_BODY_BYTES = 2048;
    public static final int MAX_QUERY_CODE_POINTS = 100;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    private ReferenceQueryContract() { }

    public record Request(String query, UUID afterId, Integer limit) {
        public Request {
            Objects.requireNonNull(query, "query");
            if (query.codePointCount(0, query.length()) > MAX_QUERY_CODE_POINTS || query.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid reference query");
            limit = limit == null ? DEFAULT_LIMIT : limit;
            if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("Invalid reference limit");
        }
    }
    public record Item(UUID id, String code, String name) {
        public Item {
            Objects.requireNonNull(id); Objects.requireNonNull(code); Objects.requireNonNull(name);
        }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(List<Item> items, UUID nextAfterId) {
        public Page {
            items = List.copyOf(items);
            if (items.size() > MAX_LIMIT || new HashSet<>(items.stream().map(Item::id).toList()).size() != items.size())
                throw new IllegalArgumentException("Invalid reference page");
            if (nextAfterId != null && (items.isEmpty() || !nextAfterId.equals(items.getLast().id())))
                throw new IllegalArgumentException("Invalid next reference position");
        }
    }
}
