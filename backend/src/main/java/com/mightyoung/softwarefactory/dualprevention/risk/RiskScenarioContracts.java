package com.mightyoung.softwarefactory.dualprevention.risk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationFailure;
import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts.ExistenceState;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Bounded domain and wire values. None of these values grants authority or computes risk. */
public final class RiskScenarioContracts {
    public static final String ENTITY_KEY = "risk_scenario";
    public static final String SCHEMA_VERSION = "1";
    public static final long MAX_VERSION = 9_007_199_254_740_991L;
    public static final int MAX_NARRATIVE_CODE_POINTS = 20_000;
    public static final int MAX_HAZARDS = 100;
    public static final int MAX_PREVIEW_CODE_POINTS = 200;
    public static final int COMMAND_BODY_BYTES = 128 * 1024;
    public static final int QUERY_BODY_BYTES = 32 * 1024;
    public static final int SELECTOR_BODY_BYTES = 2048;
    public static final int RESPONSE_HEADERS_BYTES = 16 * 1024;
    private static final JsonMapper WIRE_JSON = JsonMapper.builder()
            .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private RiskScenarioContracts() { }
    public enum AssessmentStatus { UNASSESSED }
    public enum Visibility { COMPLETE, RESTRICTED }
    public enum SelectorUse { CREATE, UPDATE }
    public enum ResponseBudget {
        COMMAND(16 * 1024), DETAIL(1024 * 1024), QUERY(1024 * 1024), HISTORY(1024 * 1024),
        SELECTOR(128 * 1024), METADATA(256 * 1024), HISTORICAL_SNAPSHOT(256 * 1024),
        ERROR(8 * 1024), ITEM(768 * 1024);
        private final int bytes;
        ResponseBudget(int bytes) { this.bytes = bytes; }
        public int bytes() { return bytes; }
    }

    public record Create(String code, UUID organizationId, String description, String triggeringConditions,
                         String exposureDescription, String consequence, List<UUID> hazardIds) {
        public Create {
            code = RiskScenarioContracts.code(code); Objects.requireNonNull(organizationId);
            narrative(description); narrative(triggeringConditions); narrative(exposureDescription); narrative(consequence);
            hazardIds = canonicalHazards(hazardIds);
        }
    }
    public record Update(long expectedVersion, String description, String triggeringConditions,
                         String exposureDescription, String consequence, List<UUID> hazardIds) {
        public Update {
            RiskScenarioContracts.version(expectedVersion); narrative(description); narrative(triggeringConditions);
            narrative(exposureDescription); narrative(consequence); hazardIds = canonicalHazards(hazardIds);
        }
    }
    public record CommandResult(String resourceType, UUID id, String code, long version, Instant occurredAt) {
        public CommandResult {
            if (!ENTITY_KEY.equals(resourceType)) throw invalid();
            Objects.requireNonNull(id); code = RiskScenarioContracts.code(code); RiskScenarioContracts.version(version); instant(occurredAt);
        }
        public CommandResult(UUID id, String code, long version, Instant occurredAt) {
            this(ENTITY_KEY, id, code, version, occurredAt);
        }
    }
    public sealed interface Hazards permits CompleteHazards, RestrictedHazards {
        Visibility visibility();
    }
    public record CompleteHazards(Visibility visibility, List<HazardSummary> items) implements Hazards {
        public CompleteHazards {
            if (visibility != Visibility.COMPLETE || items == null || items.isEmpty() || items.size() > MAX_HAZARDS)
                throw invalid();
            items = List.copyOf(items);
            if (items.stream().map(HazardSummary::id).distinct().count() != items.size()
                    || items.stream().map(HazardSummary::linkId).distinct().count() != items.size()) throw invalid();
        }
        public CompleteHazards(List<HazardSummary> items) { this(Visibility.COMPLETE, items); }
    }
    public record RestrictedHazards(Visibility visibility) implements Hazards {
        public RestrictedHazards { if (visibility != Visibility.RESTRICTED) throw invalid(); }
        public RestrictedHazards() { this(Visibility.RESTRICTED); }
    }
    public record HazardSummary(UUID id, UUID linkId, long linkVersion, String code,
                                String descriptionPreview, boolean descriptionTruncated, ExistenceState existence,
                                long hazardVersionAtLink, long hazardVersionAtScenarioRevision) {
        public HazardSummary {
            Objects.requireNonNull(id); Objects.requireNonNull(linkId); RiskScenarioContracts.version(linkVersion);
            code = RiskScenarioContracts.code(code); text(descriptionPreview, MAX_PREVIEW_CODE_POINTS);
            if (descriptionTruncated && descriptionPreview.codePointCount(0, descriptionPreview.length()) != MAX_PREVIEW_CODE_POINTS)
                throw invalid();
            Objects.requireNonNull(existence); RiskScenarioContracts.version(hazardVersionAtLink); RiskScenarioContracts.version(hazardVersionAtScenarioRevision);
        }
    }
    public record HistoricalHazardSnapshot(UUID id, UUID linkId, long linkVersion, String code,
                                          String fullDescription, ExistenceState existence,
                                          long hazardVersionAtLink, long hazardVersionAtScenarioRevision) {
        public HistoricalHazardSnapshot {
            Objects.requireNonNull(id); Objects.requireNonNull(linkId); RiskScenarioContracts.version(linkVersion);
            code = RiskScenarioContracts.code(code); narrative(fullDescription); Objects.requireNonNull(existence);
            RiskScenarioContracts.version(hazardVersionAtLink); RiskScenarioContracts.version(hazardVersionAtScenarioRevision);
        }
        public HazardSummary summary() {
            int count = fullDescription.codePointCount(0, fullDescription.length());
            String preview = fullDescription.substring(0, fullDescription.offsetByCodePoints(0, Math.min(count, MAX_PREVIEW_CODE_POINTS)));
            return new HazardSummary(id, linkId, linkVersion, code, preview, count > MAX_PREVIEW_CODE_POINTS,
                    existence, hazardVersionAtLink, hazardVersionAtScenarioRevision);
        }
    }
    public record Detail(UUID id, UUID organizationId, long version, String code, String description,
                         String triggeringConditions, String exposureDescription, String consequence,
                         Instant createdAt, Instant updatedAt, AssessmentStatus assessmentStatus, Void currentAssessment,
                         String accidentCodeRef, String accidentVersion, Hazards hazards) {
        public Detail {
            Objects.requireNonNull(id); Objects.requireNonNull(organizationId); RiskScenarioContracts.version(version);
            code = RiskScenarioContracts.code(code); narrative(description); narrative(triggeringConditions);
            narrative(exposureDescription); narrative(consequence); instant(createdAt); instant(updatedAt);
            if (assessmentStatus != AssessmentStatus.UNASSESSED || currentAssessment != null
                    || accidentCodeRef != null || accidentVersion != null) throw invalid();
            Objects.requireNonNull(hazards);
        }
    }
    public record HistoryEntry(long version, UUID commandId, Instant occurredAt, UUID organizationId,
                               String code, String description, String triggeringConditions, String exposureDescription,
                               String consequence, AssessmentStatus assessmentStatus, Void currentAssessment, Hazards hazards) {
        public HistoryEntry {
            RiskScenarioContracts.version(version); Objects.requireNonNull(commandId); instant(occurredAt); Objects.requireNonNull(organizationId);
            code = RiskScenarioContracts.code(code); narrative(description); narrative(triggeringConditions);
            narrative(exposureDescription); narrative(consequence);
            if (assessmentStatus != AssessmentStatus.UNASSESSED || currentAssessment != null) throw invalid();
            Objects.requireNonNull(hazards);
        }
    }
    public record HistoryCursor(Instant occurredAt, UUID commandId) {
        public HistoryCursor { instant(occurredAt); Objects.requireNonNull(commandId); }
    }
    public record HistoryRequest(HistoryCursor after, int pageSize) {
        public HistoryRequest { if (pageSize < 1 || pageSize > 50) throw invalid(); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryPage(UUID scenarioId, List<HistoryEntry> items, boolean hasMore, HistoryCursor nextCursor) {
        public HistoryPage {
            Objects.requireNonNull(scenarioId); items = List.copyOf(items);
            if (items.size() > 50 || hasMore != (nextCursor != null) || hasMore && items.isEmpty()) throw invalid();
        }
    }
    public record Filter(String field, FilterOperator operator, List<Object> values) {
        public Filter { Objects.requireNonNull(field); Objects.requireNonNull(operator); values = List.copyOf(values); }
    }
    public record Sort(String field, Direction direction) {
        public Sort { Objects.requireNonNull(field); Objects.requireNonNull(direction); }
    }
    public record QueryRequest(List<Filter> filters, List<Sort> sort, List<String> fields,
                               int size, Integer number, String cursor) {
        public QueryRequest {
            filters = List.copyOf(filters); sort = List.copyOf(sort); fields = List.copyOf(fields);
            if (size != 20 && size != 50 && size != 100) throw invalid();
            if ((number == null) == (cursor == null)) throw invalid();
            if (number != null && (number < 1 || (long) (number - 1) * size > 10_000)) throw invalid();
            if (cursor != null && (cursor.isBlank() || cursor.codePointCount(0, cursor.length()) > 2048)) throw invalid();
        }
        public int offset() { return number == null ? 0 : (number - 1) * size; }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueryPageInfo(int size, Integer number, boolean hasMore, String nextCursor, Long total) {
        public QueryPageInfo {
            if (size != 20 && size != 50 && size != 100 || hasMore != (nextCursor != null)
                    || total != null && total < 0) throw invalid();
        }
    }
    public record QueryPage(List<Map<String, Object>> items, QueryPageInfo page) {
        public QueryPage {
            items = items.stream().map(item -> {
                if (!RiskScenarioEntitySchema.schema().fields().keySet().containsAll(item.keySet())) throw invalid();
                if (item.containsKey("assessmentStatus") && !AssessmentStatus.UNASSESSED.name().equals(item.get("assessmentStatus"))
                        && item.get("assessmentStatus") != AssessmentStatus.UNASSESSED) throw invalid();
                return Collections.unmodifiableMap(new LinkedHashMap<>(item));
            }).toList();
            Objects.requireNonNull(page);
            if (items.size() > page.size() || page.hasMore() && items.isEmpty()) throw invalid();
        }
    }
    public record SelectorRequest(SelectorUse use, UUID organizationId, UUID scenarioId,
                                  String query, UUID afterId, int limit) {
        public SelectorRequest {
            Objects.requireNonNull(use); unicode(query);
            if (query.codePointCount(0, query.length()) > 100 || limit < 1 || limit > 50) throw invalid();
            if (use == SelectorUse.CREATE ? organizationId == null || scenarioId != null : scenarioId == null || organizationId != null)
                throw invalid();
        }
    }
    public record HazardCandidate(UUID id, UUID organizationId, String code, long version,
                                  String descriptionPreview, boolean descriptionTruncated, ExistenceState existence) {
        public HazardCandidate {
            Objects.requireNonNull(id); Objects.requireNonNull(organizationId); code = RiskScenarioContracts.code(code);
            RiskScenarioContracts.version(version); text(descriptionPreview, MAX_PREVIEW_CODE_POINTS); Objects.requireNonNull(existence);
            if (descriptionTruncated && descriptionPreview.codePointCount(0, descriptionPreview.length()) != MAX_PREVIEW_CODE_POINTS)
                throw invalid();
        }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SelectorPage(List<HazardCandidate> items, boolean hasMore, UUID nextAfter) {
        public SelectorPage {
            items = List.copyOf(items);
            if (items.size() > 50 || hasMore != (nextAfter != null) || hasMore && items.isEmpty()) throw invalid();
        }
    }
    public record Response<T>(T data, Metadata meta) {
        public Response { Objects.requireNonNull(data); Objects.requireNonNull(meta); }
    }

    public static List<UUID> canonicalHazards(List<UUID> input) {
        if (input == null || input.isEmpty() || input.size() > MAX_HAZARDS) throw invalid();
        var result = new ArrayList<>(input);
        if (result.contains(null) || new HashSet<>(result).size() != result.size()) throw invalid();
        result.sort(RiskScenarioContracts::compareUuid);
        return List.copyOf(result);
    }
    public static int compareUuid(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
    public static long version(long value) { if (value < 1 || value > MAX_VERSION) throw invalid(); return value; }
    public static long nextVersion(long value) {
        version(value); if (value == MAX_VERSION) throw new Failure(409); return value + 1;
    }
    public static String code(String value) {
        try { return AssetContracts.code(value); } catch (AssetFoundationFailure invalid) { throw invalid(); }
    }
    public static String narrative(String value) { return text(value, MAX_NARRATIVE_CODE_POINTS); }
    public static String text(String value, int maximum) {
        try { return AssetContracts.text(value, maximum, false); } catch (AssetFoundationFailure invalid) { throw invalid(); }
    }
    public static Instant instant(Instant value) {
        try { return AssetContracts.instant(value); } catch (AssetFoundationFailure invalid) { throw invalid(); }
    }
    public static String unicode(String value) {
        if (value == null) throw invalid();
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == 0 || Character.isLowSurrogate(c)) throw invalid();
            if (Character.isHighSurrogate(c) && (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))))
                throw invalid();
        }
        return value;
    }

    /** Compatibility API: returns an independent exact-length copy of the bounded encoded output. */
    public static byte[] encodeResponse(Object value, ResponseBudget budget) {
        return encodeOwnedResponse(value, budget).copyBytes();
    }
    /** Serializes once into one fixed budget-sized buffer and transfers its private ownership without a payload copy.
     * Page selection and the separate late framework-header budget remain the HTTP/read owner's responsibility. */
    public static EncodedOutput encodeOwnedResponse(Object value, ResponseBudget budget) {
        Objects.requireNonNull(value); Objects.requireNonNull(budget);
        var output = new LimitedOutput(budget.bytes());
        try { WIRE_JSON.writeValue(output, value); return output.seal(); }
        catch (tools.jackson.core.JacksonException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof BudgetExceeded) throw Failure.responseBudgetExceeded();
            throw new Failure(503);
        }
    }
    /** Immutable handle to a privately owned encoded range. No method exposes its mutable backing array. */
    public static final class EncodedOutput {
        private final byte[] buffer;
        private final int length;
        private EncodedOutput(byte[] owned, int length) { this.buffer = owned; this.length = length; }
        public int size() { return length; }
        /** Copies directly into the caller-owned page, with all bounds checked before any mutation. */
        public int appendTo(byte[] destination, int offset) {
            Objects.requireNonNull(destination); Objects.checkFromIndexSize(offset, length, destination.length);
            System.arraycopy(buffer, 0, destination, offset, length); return offset + length;
        }
        /** Does not flush or close the caller's stream. At most 8KiB scratch prevents a hostile stream from
         * retaining or mutating the private buffer passed to the serializer; there is no full-payload output copy. */
        public void writeTo(OutputStream destination) throws IOException {
            Objects.requireNonNull(destination); byte[] scratch = new byte[Math.min(8192, length)];
            for (int offset = 0; offset < length;) {
                int amount = Math.min(scratch.length, length - offset);
                System.arraycopy(buffer, offset, scratch, 0, amount); destination.write(scratch, 0, amount); offset += amount;
            }
        }
        private byte[] copyBytes() { return java.util.Arrays.copyOf(buffer, length); }
    }
    private static final class LimitedOutput extends OutputStream {
        private final int maximum;
        private final byte[] output;
        private int count;
        private boolean sealed;
        LimitedOutput(int maximum) { this.maximum = maximum; this.output = new byte[maximum]; }
        @Override public void write(int value) throws IOException {
            requireWritable(); if (count == maximum) throw new BudgetExceeded(); output[count++] = (byte) value;
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            requireWritable(); if (length > maximum - count) throw new BudgetExceeded();
            System.arraycopy(bytes, offset, output, count, length); count += length;
        }
        private void requireWritable() throws IOException { if (sealed) throw new IOException("Risk output is sealed"); }
        EncodedOutput seal() { if (sealed) throw new IllegalStateException("Risk output is sealed"); sealed = true; return new EncodedOutput(output, count); }
    }
    private static final class BudgetExceeded extends IOException { }
    public static final class Failure extends RuntimeException {
        private final int status;
        private final String code;
        public Failure(int status) { this(status, switch (status) {
            case 400 -> "BAD_REQUEST"; case 403 -> "FORBIDDEN"; case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT"; case 413 -> "REQUEST_TOO_LARGE"; case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "UNAVAILABLE"; default -> throw new IllegalArgumentException("Unsupported risk response status");
        }); }
        private Failure(int status, String code) { super("Risk scenario request cannot be processed"); this.status = status; this.code = code; }
        public int status() { return status; }
        public String code() { return code; }
        static Failure responseBudgetExceeded() { return new Failure(503, "RESPONSE_BUDGET_EXCEEDED"); }
    }
    static Failure invalid() { return new Failure(400); }
}
