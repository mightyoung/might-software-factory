package com.mightyoung.softwarefactory.dualprevention.asset;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationSelectorContracts.*;

/** Selectors have a separate 2048-byte flat token contract; command coercions are not reused. */
public final class AssetFoundationSelectorParser {
    private final ObjectMapper json;
    public AssetFoundationSelectorParser(ObjectMapper json) { this.json = Objects.requireNonNull(json); }
    public Request reference(HttpServletRequest request) { return parse(request, null); }
    public Request anchor(HttpServletRequest request) { return parse(request, "organizationId"); }
    public Request position(HttpServletRequest request) { return parse(request, "orgId"); }

    private Request parse(HttpServletRequest request, String anchor) {
        if (request.getQueryString() != null) invalid();
        var types = Collections.list(request.getHeaders("Content-Type"));
        if (types.size() != 1 || !types.getFirst().matches("(?i)application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?[ \\t]*"))
            throw new AssetFoundationFailure(415);
        try {
            byte[] bytes = request.getInputStream().readNBytes(2049);
            if (bytes.length > 2048) throw new AssetFoundationFailure(413);
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (body.startsWith("\uFEFF")) invalid();
            Set<String> seen = new HashSet<>();
            Set<String> allowed = anchor == null ? Set.of("query", "afterId", "limit") : Set.of("query", "afterId", "limit", anchor);
            String query = null;
            UUID after = null, organization = null;
            int limit = 20;
            try (var parser = json.createParser(body)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) invalid();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) invalid();
                    String key = parser.currentName();
                    if (!allowed.contains(key) || !seen.add(key)) invalid();
                    JsonToken token = parser.nextToken();
                    if (key.equals("limit")) {
                        if (token != JsonToken.VALUE_NUMBER_INT || !parser.getString().matches("[1-9][0-9]?")) invalid();
                        limit = Integer.parseInt(parser.getString());
                    } else if (key.equals("afterId") && token == JsonToken.VALUE_NULL) {
                        after = null;
                    } else {
                        if (token != JsonToken.VALUE_STRING) invalid();
                        String value = parser.getString();
                        unicode(value);
                        if (key.equals("query")) query = value;
                        else if (key.equals("afterId")) after = AssetFoundationRequestParser.uuid(value);
                        else organization = AssetFoundationRequestParser.uuid(value);
                    }
                }
                if (parser.nextToken() != null || !seen.contains("query") || anchor != null && !seen.contains(anchor)) invalid();
            }
            return new Request(query, after, limit, organization);
        } catch (tools.jackson.core.JacksonException | java.io.IOException | NumberFormatException error) {
            throw new AssetFoundationFailure(400);
        }
    }
}
