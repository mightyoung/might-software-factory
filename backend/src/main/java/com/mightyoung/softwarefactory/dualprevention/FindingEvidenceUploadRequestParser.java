package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.ReserveCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

/** Token-level declaration parser. Neither charset autodetection nor DTO coercion is permitted. */
@Component
public final class FindingEvidenceUploadRequestParser {
    private static final Set<String> STRINGS = Set.of("evidenceSetId", "filename", "declaredMediaType");
    private static final Set<String> NUMBERS = Set.of("expectedFindingVersion", "expectedEvidenceRevision", "declaredSizeBytes");
    private final ObjectMapper json;
    public FindingEvidenceUploadRequestParser(ObjectMapper json) { this.json = json; }

    public ReserveCommand parse(HttpServletRequest request, UUID finding, int round, UUID key) {
        try {
            var headers = Collections.list(request.getHeaders("Content-Type"));
            if (headers.size() != 1 || !headers.getFirst().matches("(?i)application/json(?:[ \t]*;[ \t]*charset[ \t]*=[ \t]*(?:UTF-8|\"UTF-8\"))?[ \t]*")) throw media();
            MediaType type;
            try { type = MediaType.parseMediaType(headers.getFirst()); }
            catch (IllegalArgumentException failure) { throw media(); }
            if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())
                    || !Set.of("charset").containsAll(type.getParameters().keySet())
                    || type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset())) throw media();
            byte[] bytes = request.getInputStream().readNBytes(4097);
            if (bytes.length > 4096) throw tooLarge();
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (body.startsWith("\uFEFF")) throw invalid();
            Map<String, String> fields = new HashMap<>();
            try (var parser = json.createParser(body)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw invalid();
                    String name = parser.currentName();
                    if ((!STRINGS.contains(name) && !NUMBERS.contains(name)) || fields.containsKey(name)) throw invalid();
                    var token = parser.nextToken();
                    if (STRINGS.contains(name)) {
                        if (token != JsonToken.VALUE_STRING) throw invalid();
                        String value = parser.getString();
                        requireUnicode(value);
                        fields.put(name, value);
                    } else {
                        if (token != JsonToken.VALUE_NUMBER_INT) throw invalid();
                        String value = parser.getString();
                        if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
                        Long.parseLong(value);
                        fields.put(name, value);
                    }
                }
                if (fields.size() != 6 || parser.nextToken() != null) throw invalid();
            }
            long size = Long.parseLong(fields.get("declaredSizeBytes"));
            if (size > 67_108_864L) throw tooLarge();
            try {
                return new ReserveCommand(key, finding, round, canonical(fields.get("evidenceSetId")),
                        Long.parseLong(fields.get("expectedFindingVersion")), Long.parseLong(fields.get("expectedEvidenceRevision")),
                        fields.get("filename"), fields.get("declaredMediaType"), size);
            } catch (IllegalArgumentException invalidDeclaration) { throw invalid(); }
        } catch (RequestFailure failure) { throw failure; }
        catch (tools.jackson.core.JacksonException | java.io.IOException | NumberFormatException failure) { throw invalid(); }
    }
    static UUID canonical(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw invalid();
            return id;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    static int round(String value) {
        if (!value.matches("[1-9][0-9]{0,9}")) throw invalid();
        try { return Integer.parseInt(value); } catch (NumberFormatException failure) { throw invalid(); }
    }
    private static void requireUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
    }
    static RequestFailure invalid() { return new RequestFailure(400); }
    static RequestFailure tooLarge() { return new RequestFailure(413); }
    static RequestFailure media() { return new RequestFailure(415); }
    public static final class RequestFailure extends RuntimeException {
        private final int status;
        private RequestFailure(int status) { super("Invalid upload request"); this.status = status; }
        public int status() { return status; }
    }
}
