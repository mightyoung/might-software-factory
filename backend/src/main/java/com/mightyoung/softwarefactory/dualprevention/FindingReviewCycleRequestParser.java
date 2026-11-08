package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingReviewCycleContracts.ReportSupplementCommand;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingReviewCycleContracts.ReviewDecision;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingReviewCycleContracts.ReviewDecisionCommand;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.ConfirmationStage;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

/** Bounded raw JSON token parsing, isolated from all legacy request binding. */
@Component
public final class FindingReviewCycleRequestParser {
    static final int MAX_BODY_BYTES = 32768;
    private final ObjectMapper json;
    public FindingReviewCycleRequestParser(ObjectMapper json) { this.json = json; }

    public ReviewDecisionCommand review(HttpServletRequest request) {
        var fields = fields(request, Set.of("expectedVersion", "reviewCycle", "stage", "decision", "reason", "grade"));
        try {
            var stage = ConfirmationStage.valueOf(required(fields, "stage"));
            var decision = ReviewDecision.valueOf(required(fields, "decision"));
            boolean graded = stage == ConfirmationStage.SAFETY && decision == ReviewDecision.APPROVE;
            if (!graded && fields.containsKey("grade")) throw invalid();
            return new ReviewDecisionCommand(version(fields), cycle(fields), stage, decision,
                    required(fields, "reason"), graded ? required(fields, "grade") : null);
        } catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    public ReportSupplementCommand supplement(HttpServletRequest request) {
        var fields = fields(request, Set.of("expectedVersion", "reviewCycle", "statement"));
        try { return new ReportSupplementCommand(version(fields), cycle(fields), required(fields, "statement")); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    private Map<String, String> fields(HttpServletRequest request, Set<String> allowed) {
        var types = Collections.list(request.getHeaders("Content-Type"));
        if (types.size() != 1 || !types.getFirst().matches(
                "(?i)application/json(?:[ \t]*;[ \t]*charset[ \t]*=[ \t]*(?:UTF-8|\"UTF-8\"))?[ \t]*")) throw failure(415);
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw failure(413);
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (body.startsWith("\uFEFF")) throw invalid();
            try (var parser = json.createParser(body)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
                Map<String, String> fields = new HashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw invalid();
                    String name = parser.currentName();
                    if (!allowed.contains(name) || fields.containsKey(name)) throw invalid();
                    var token = parser.nextToken();
                    boolean number = name.equals("expectedVersion") || name.equals("reviewCycle");
                    if (token != (number ? JsonToken.VALUE_NUMBER_INT : JsonToken.VALUE_STRING)) throw invalid();
                    String value = parser.getString();
                    if (number ? !value.matches("[1-9][0-9]{0,18}") : !pairedSurrogates(value)) throw invalid();
                    fields.put(name, value);
                }
                if (parser.nextToken() != null) throw invalid();
                return fields;
            }
        } catch (tools.jackson.core.JacksonException | java.io.IOException malformed) { throw invalid(); }
    }

    private static boolean pairedSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) throw invalid();
        return value;
    }
    private static long version(Map<String, String> fields) { return Long.parseLong(required(fields, "expectedVersion")); }
    private static int cycle(Map<String, String> fields) { return Integer.parseInt(required(fields, "reviewCycle")); }
    static UUID canonical(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw invalid();
            return parsed;
        } catch (IllegalArgumentException malformed) { throw invalid(); }
    }
    static RequestFailure invalid() { return failure(400); }
    private static RequestFailure failure(int status) { return new RequestFailure(status); }
    public static final class RequestFailure extends RuntimeException {
        private final int status;
        private RequestFailure(int status) { super("Invalid finding review cycle request"); this.status = status; }
        public int status() { return status; }
    }
}
