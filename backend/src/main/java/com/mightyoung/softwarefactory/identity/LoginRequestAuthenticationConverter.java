package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Arrays;

/** Converts only the local login JSON fields into a one-use authentication request. */
final class LoginRequestAuthenticationConverter implements AuthenticationConverter {

    private final ObjectMapper objectMapper;
    private static final int MAX_REQUEST_BYTES = 4096;

    LoginRequestAuthenticationConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        byte[] serializedBody = null;
        char[] decodedCharacters = null;
        char[] password = null;
        boolean passwordTransferred = false;
        try {
            if (request.getContentType() == null) {
                throw new InvalidLoginRequestException();
            }
            MediaType contentType = MediaType.parseMediaType(request.getContentType());
            if (contentType.isWildcardType() || contentType.isWildcardSubtype()
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                    || (contentType.getCharset() != null
                        && !java.nio.charset.StandardCharsets.UTF_8.equals(contentType.getCharset()))) {
                throw new InvalidLoginRequestException();
            }
            // Own the buffer before reading so even a partial I/O failure can wipe it.
            serializedBody = new byte[MAX_REQUEST_BYTES + 1];
            int bodyLength = request.getInputStream().readNBytes(serializedBody, 0, serializedBody.length);
            if (bodyLength > MAX_REQUEST_BYTES) {
                throw new InvalidLoginRequestException();
            }
            decodedCharacters = new char[MAX_REQUEST_BYTES + 1];
            var characters = java.nio.CharBuffer.wrap(decodedCharacters);
            var decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            var decoded = decoder.decode(java.nio.ByteBuffer.wrap(serializedBody, 0, bodyLength), characters, true);
            if (decoded.isError()) decoded.throwException();
            if (decoded.isOverflow()) throw new InvalidLoginRequestException();
            var flushed = decoder.flush(characters);
            if (flushed.isError()) flushed.throwException();
            if (flushed.isOverflow()) throw new InvalidLoginRequestException();
            String loginKey = null;
            String tenantCode = null;
            int fieldsSeen = 0;
            try (JsonParser parser = objectMapper.createParser(decodedCharacters, 0, characters.position())) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new InvalidLoginRequestException();
                }
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                        throw new InvalidLoginRequestException();
                    }
                    int field = switch (parser.currentName()) {
                        case "loginKey" -> 1;
                        case "password" -> 2;
                        case "tenantCode" -> 4;
                        default -> throw new InvalidLoginRequestException();
                    };
                    if ((fieldsSeen & field) != 0 || parser.nextToken() != JsonToken.VALUE_STRING) {
                        throw new InvalidLoginRequestException();
                    }
                    fieldsSeen |= field;
                    switch (field) {
                        case 1 -> loginKey = parser.getString();
                        case 2 -> password = readPassword(parser);
                        case 4 -> tenantCode = parser.getString();
                        default -> throw new IllegalStateException("Unexpected login field");
                    }
                }
                if (fieldsSeen != 7 || parser.nextToken() != null) {
                    throw new InvalidLoginRequestException();
                }
            }
            if (loginKey == null || loginKey.isBlank() || loginKey.length() > 200
                    || password == null || password.length == 0 || password.length > 1024
                    || tenantCode == null || tenantCode.isBlank() || tenantCode.length() > 64) {
                throw new InvalidLoginRequestException();
            }
            LocalPasswordAuthenticationToken token = new LocalPasswordAuthenticationToken(
                    loginKey.strip(), password, tenantCode.strip());
            passwordTransferred = true;
            return token;
        } catch (InvalidLoginRequestException exception) {
            throw exception;
        } catch (JacksonException | IOException | InvalidMediaTypeException exception) {
            throw new InvalidLoginRequestException();
        } finally {
            if (serializedBody != null) {
                Arrays.fill(serializedBody, (byte) 0);
            }
            if (decodedCharacters != null) {
                Arrays.fill(decodedCharacters, '\0');
            }
            if (!passwordTransferred && password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private static char[] readPassword(JsonParser parser) {
        char[] characters = parser.getStringCharacters();
        int offset = parser.getStringOffset();
        int length = parser.getStringLength();
        try {
            if (length == 0 || length > 1024) {
                throw new InvalidLoginRequestException();
            }
            return Arrays.copyOfRange(characters, offset, offset + length);
        } finally {
            Arrays.fill(characters, offset, offset + length, '\0');
        }
    }
}
