package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;
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
        try {
            if (request.getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) {
                throw new InvalidLoginRequestException();
            }
            byte[] serializedBody = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            LoginRequest body;
            try {
                if (serializedBody.length > MAX_REQUEST_BYTES) {
                    throw new InvalidLoginRequestException();
                }
                body = objectMapper.readValue(serializedBody, LoginRequest.class);
            } finally {
                Arrays.fill(serializedBody, (byte) 0);
            }
            char[] password = body == null ? null : body.password();
            if (body == null || body.loginKey() == null || body.loginKey().isBlank()
                    || body.loginKey().length() > 200
                    || password == null || password.length == 0 || password.length > 1024
                    || body.tenantCode() == null || body.tenantCode().isBlank() || body.tenantCode().length() > 64) {
                if (password != null) {
                    Arrays.fill(password, '\0');
                }
                throw new InvalidLoginRequestException();
            }
            return new LocalPasswordAuthenticationToken(
                    body.loginKey().strip(), password, body.tenantCode().strip());
        } catch (InvalidLoginRequestException exception) {
            throw exception;
        } catch (IOException | InvalidMediaTypeException exception) {
            throw new InvalidLoginRequestException(exception);
        }
    }

    private record LoginRequest(String loginKey, char[] password, String tenantCode) {
    }
}
