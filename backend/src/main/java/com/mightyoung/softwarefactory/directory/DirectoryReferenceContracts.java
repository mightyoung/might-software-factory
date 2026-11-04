package com.mightyoung.softwarefactory.directory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Directory-owned bounded reference values; observation time is not a fictitious directory version. */
public final class DirectoryReferenceContracts {
    private DirectoryReferenceContracts() { }
    public record Query(String query, UUID afterId, Integer limit) {
        public Query {
            Objects.requireNonNull(query, "query");
            if (query.codePointCount(0, query.length()) > 100 || query.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid directory query");
            limit = limit == null ? 20 : limit;
            if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid directory page size");
        }
    }
    public record Organization(UUID id, String code, String name, boolean active, Instant observedAt) {
        public Organization {
            Objects.requireNonNull(id); Objects.requireNonNull(code);
            Objects.requireNonNull(name); Objects.requireNonNull(observedAt);
        }
    }
    public record Position(UUID id, String code, String name, UUID organizationId,
                           boolean active, Instant observedAt) {
        public Position {
            Objects.requireNonNull(id); Objects.requireNonNull(code);
            Objects.requireNonNull(name); Objects.requireNonNull(observedAt);
        }
    }
    public record Item(UUID id, String code, String name) {
        public Item { Objects.requireNonNull(id); Objects.requireNonNull(code); Objects.requireNonNull(name); }
    }
    public record Page(List<Item> items, UUID nextAfterId) {
        public Page {
            items = List.copyOf(items);
            if (items.size() > 50 || new HashSet<>(items.stream().map(Item::id).toList()).size() != items.size()
                    || nextAfterId != null && (items.isEmpty() || !nextAfterId.equals(items.getLast().id())))
                throw new IllegalArgumentException("Invalid directory page");
        }
    }
}
