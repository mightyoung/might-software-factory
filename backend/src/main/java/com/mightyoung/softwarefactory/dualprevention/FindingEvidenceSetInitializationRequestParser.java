package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationContracts.InitializeCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

/** Exactly one nonnegative decimal int64, in at most 1024 strictly decoded UTF-8 bytes. */
@Component
public final class FindingEvidenceSetInitializationRequestParser {
    private final ObjectMapper json;
    public FindingEvidenceSetInitializationRequestParser(ObjectMapper json) { this.json = json; }
    public InitializeCommand parse(HttpServletRequest request, UUID finding, int round, UUID key) {
        var types = Collections.list(request.getHeaders("Content-Type"));
        if (types.size() != 1 || !types.getFirst().matches(
                "(?i)application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?[ \\t]*")) throw failure(415);
        try {
            byte[] bytes = request.getInputStream().readNBytes(1025);
            if (bytes.length > 1024) throw failure(413);
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (body.startsWith("\uFEFF")) throw invalid();
            try (var parser = json.createParser(body)) {
                if (parser.nextToken() != JsonToken.START_OBJECT || parser.nextToken() != JsonToken.PROPERTY_NAME
                        || !"expectedFindingVersion".equals(parser.currentName())
                        || parser.nextToken() != JsonToken.VALUE_NUMBER_INT) throw invalid();
                String value = parser.getString();
                if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
                long version = Long.parseLong(value);
                if (parser.nextToken() != JsonToken.END_OBJECT || parser.nextToken() != null) throw invalid();
                return new InitializeCommand(key, finding, round, version);
            }
        } catch (tools.jackson.core.JacksonException | java.io.IOException | NumberFormatException malformed) {
            throw invalid();
        }
    }
    static UUID canonical(String value) {
        try { return FindingEvidenceUploadRequestParser.canonical(value); }
        catch (FindingEvidenceUploadRequestParser.RequestFailure malformed) { throw invalid(); }
    }
    static int round(String value) {
        try { return FindingEvidenceUploadRequestParser.round(value); }
        catch (FindingEvidenceUploadRequestParser.RequestFailure malformed) { throw invalid(); }
    }
    static RequestFailure invalid() { return failure(400); }
    private static RequestFailure failure(int status) { return new RequestFailure(status); }
    public static final class RequestFailure extends RuntimeException {
        private final int status;
        private RequestFailure(int status) { super("Invalid evidence set initialization request"); this.status = status; }
        public int status() { return status; }
    }
}
