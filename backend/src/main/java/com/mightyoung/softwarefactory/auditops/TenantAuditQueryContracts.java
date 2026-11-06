package com.mightyoung.softwarefactory.auditops;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Closed audit metadata wire values. These values confer no authority and do not encode a response. */
public final class TenantAuditQueryContracts {
    public static final int REQUEST_BYTES = 8192;
    public static final int RESPONSE_BYTES = 128 * 1024;
    public static final int ITEM_BYTES = 4096;
    public static final int ERROR_BYTES = 8192;
    public static final int MAX_PAGE_SIZE = 50;
    public static final String SCHEMA_VERSION = "1";
    private TenantAuditQueryContracts() { }

    public enum Result { SUCCESS, DENIED, FAILED }
    public enum Code {
        BAD_REQUEST(400), REQUEST_TOO_LARGE(413), UNSUPPORTED_MEDIA_TYPE(415);
        private final int status;
        Code(int status) { this.status = status; }
        public int status() { return status; }
    }
    public static final class Failure extends IllegalArgumentException {
        private final Code code;
        public Failure(Code code) {
            super("Audit query request was rejected");
            if (code == null) throw new IllegalArgumentException("Audit query failure code is required");
            this.code = code;
        }
        public Code code() { return code; }
        public int status() { return code.status(); }
    }
    static Failure invalid() { return new Failure(Code.BAD_REQUEST); }

    public record Cursor(Instant occurredAt, UUID id, String queryFingerprint) {
        public Cursor {
            time(occurredAt); required(id);
            if (queryFingerprint == null || !queryFingerprint.matches("[0-9a-f]{64}")) throw invalid();
        }
    }
    public record Window(Instant from, Instant to) {
        public Window {
            time(from); time(to);
            if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) throw invalid();
        }
        public boolean contains(Instant value) { return value != null && !value.isBefore(from) && value.isBefore(to); }
    }
    public record Query(Instant from, Instant to, UUID actorId, String resourceType, UUID resourceId,
                        String action, Result result, int pageSize, Cursor cursor) {
        public Query {
            if ((from == null) != (to == null) || pageSize < 1 || pageSize > MAX_PAGE_SIZE) throw invalid();
            if (resourceType != null) identifier(resourceType, 64);
            if (action != null) identifier(action, 128);
            if (resourceId != null && resourceType == null) throw invalid();
            Window window = from == null ? null : new Window(from, to);
            if (cursor != null && (window == null || !window.contains(cursor.occurredAt()))) throw invalid();
        }
    }
    public record Item(UUID id, UUID actorId, UUID delegatorId, String action, String resourceType,
                       UUID resourceId, Result result, UUID traceId, String policyVersion, Instant occurredAt) {
        public Item {
            required(id); required(actorId); required(result); time(occurredAt);
            identifier(action, 128); identifier(resourceType, 64); identifier(policyVersion, 64);
        }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Data(List<Item> items, Window window, boolean hasMore, Cursor nextCursor) {
        public Data {
            required(items); required(window);
            if (items.size() > MAX_PAGE_SIZE) throw invalid();
            var owned = new ArrayList<Item>();
            for (Item item : items) {
                if (item == null || owned.size() == MAX_PAGE_SIZE) throw invalid();
                owned.add(item);
            }
            items = List.copyOf(owned);
            if (hasMore != (nextCursor != null) || hasMore && items.isEmpty()) throw invalid();
            var ids = new HashSet<UUID>(); Item previous = null;
            for (Item item : items) {
                required(item);
                if (!window.contains(item.occurredAt()) || !ids.add(item.id()) || previous != null && compare(previous, item) <= 0) throw invalid();
                previous = item;
            }
            if (nextCursor != null && (!previous.id().equals(nextCursor.id()) || !previous.occurredAt().equals(nextCursor.occurredAt()))) throw invalid();
        }
    }
    public record Meta(UUID requestId, UUID traceId, String schemaVersion) {
        public Meta { required(requestId); required(traceId); if (!SCHEMA_VERSION.equals(schemaVersion)) throw invalid(); }
        public Meta(UUID requestId, UUID traceId) { this(requestId, traceId, SCHEMA_VERSION); }
    }
    public record Response(Data data, Meta meta) { public Response { required(data); required(meta); } }

    private static int compare(Item left, Item right) {
        int time = left.occurredAt().compareTo(right.occurredAt());
        if (time != 0) return time;
        int high = Long.compareUnsigned(left.id().getMostSignificantBits(), right.id().getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.id().getLeastSignificantBits(), right.id().getLeastSignificantBits());
    }
    static String identifier(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || "._:-".indexOf(c) >= 0)) throw invalid();
        }
        return value;
    }
    static Instant time(Instant value) {
        required(value);
        try {
            int year = value.atOffset(ZoneOffset.UTC).getYear();
            if (year < 1 || year > 9999 || value.getNano() % 1000 != 0) throw invalid();
        } catch (java.time.DateTimeException failure) { throw invalid(); }
        return value;
    }
    private static void required(Object value) { if (value == null) throw invalid(); }
}
