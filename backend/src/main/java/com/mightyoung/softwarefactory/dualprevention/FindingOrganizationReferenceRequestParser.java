package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.reference.ReferenceQueryContract;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

/** The fixed organization-reference route accepts one bounded, strictly decoded JSON object. */
@Component
public final class FindingOrganizationReferenceRequestParser {
    private final ObjectMapper json;
    public FindingOrganizationReferenceRequestParser(ObjectMapper json) { this.json = json; }

    public ReferenceQueryContract.Request parse(HttpServletRequest request) {
        if (request.getQueryString() != null) throw failure(400);
        var contentTypes = Collections.list(request.getHeaders("Content-Type"));
        if (contentTypes.size() != 1 || !contentTypes.getFirst().matches(
                "(?i)application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?[ \\t]*")) throw failure(415);
        try {
            byte[] bytes = request.getInputStream().readNBytes(ReferenceQueryContract.MAX_BODY_BYTES + 1);
            if (bytes.length > ReferenceQueryContract.MAX_BODY_BYTES) throw failure(413);
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (body.startsWith("\uFEFF")) throw failure(400);
            String query = null;
            UUID after = null;
            Integer limit = null;
            var seen = new HashSet<String>();
            try (var parser = json.createParser(body)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw failure(400);
                for (JsonToken token = parser.nextToken(); token != JsonToken.END_OBJECT; token = parser.nextToken()) {
                    if (token != JsonToken.PROPERTY_NAME) throw failure(400);
                    String key = parser.currentName();
                    if (!seen.add(key)) throw failure(400);
                    token = parser.nextToken();
                    switch (key) {
                        case "query" -> {
                            if (token != JsonToken.VALUE_STRING) throw failure(400);
                            query = parser.getString();
                            requireUnicode(query);
                        }
                        case "afterId" -> {
                            if (token == JsonToken.VALUE_NULL) break;
                            if (token != JsonToken.VALUE_STRING) throw failure(400);
                            String value = parser.getString();
                            after = UUID.fromString(value);
                            if (!after.toString().equalsIgnoreCase(value)) throw failure(400);
                        }
                        case "limit" -> {
                            if (token != JsonToken.VALUE_NUMBER_INT || !parser.getString().matches("[1-9][0-9]?")) throw failure(400);
                            limit = Integer.valueOf(parser.getString());
                        }
                        default -> throw failure(400);
                    }
                }
                if (parser.nextToken() != null || !seen.contains("query")) throw failure(400);
            }
            return new ReferenceQueryContract.Request(query, after, limit);
        } catch (tools.jackson.core.JacksonException | java.io.IOException | IllegalArgumentException malformed) {
            throw failure(400);
        }
    }
    private static void requireUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw failure(400);
            } else if (Character.isLowSurrogate(current)) throw failure(400);
        }
    }
    static RequestFailure failure(int status) { return new RequestFailure(status); }
    public static final class RequestFailure extends RuntimeException {
        private final int status;
        private RequestFailure(int status) { super("Invalid organization reference request"); this.status = status; }
        public int status() { return status; }
    }
}
