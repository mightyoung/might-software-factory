package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRequestAuthenticationConverterTest {

    private static final String VALID =
            "{\"loginKey\":\"operator\",\"password\":\"synthetic-pass\",\"tenantCode\":\"plant\"}";
    private final LoginRequestAuthenticationConverter converter =
            new LoginRequestAuthenticationConverter(new ObjectMapper());

    @Test
    void acceptsOnlyTheThreeStringFieldsWithoutChangingPasswordOrProviderNormalization() {
        var token = (LocalPasswordAuthenticationToken) converter.convert(request("""
                {"tenantCode":"  Plant-A  ","password":" synthetic\\tpass ","loginKey":"  OPERATOR  "}
                """));

        assertThat(token.loginKey()).isEqualTo("OPERATOR");
        assertThat(token.tenantCode()).isEqualTo("Plant-A");
        assertThat((char[]) token.getCredentials()).containsExactly(" synthetic\tpass ".toCharArray());
        assertThat(token.isAuthenticated()).isFalse();
        assertThat(token.toString()).isEqualTo("LocalPasswordAuthenticationToken");
        token.eraseCredentials();
        assertThat(token.getCredentials()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsUnknownRepeatedMalformedAndNonStringInputWithOneSafeError(String body) {
        assertInvalid(request(body));
    }

    private static Stream<String> invalidBodies() {
        return Stream.of(
                "",
                "null",
                "[]",
                "[" + VALID + "]",
                "\"string\"",
                "42",
                VALID + " {}",
                VALID + " null",
                VALID + " trailing",
                VALID.substring(0, VALID.length() - 1),
                VALID.replace("\"plant\"", "\"plant\","),
                VALID.replace("\"loginKey\":\"operator\"", "\"loginKey\":\"operator\",\"loginKey\":\"other\""),
                VALID.replace("\"loginKey\":\"operator\"", "\"loginKey\":\"operator\",\"log\\u0069nKey\":\"other\""),
                VALID.replace("\"password\":\"synthetic-pass\"", "\"password\":\"synthetic-pass\",\"password\":\"other\""),
                VALID.replace("\"tenantCode\":\"plant\"", "\"tenantCode\":\"plant\",\"tenantCode\":\"other\""),
                VALID.replace("\"tenantCode\":\"plant\"", "\"tenantCode\":\"plant\",\"actorId\":\"other\""),
                "{\"password\":\"synthetic-pass\",\"loginKey\":\"operator\",\"unknown\":true}",
                "{\"loginKey\":\"operator\",\"password\":\"synthetic-pass\"}",
                "{\"loginKey\":\"operator\",\"tenantCode\":\"plant\"}",
                "{\"password\":\"synthetic-pass\",\"tenantCode\":\"plant\"}",
                VALID.replace("\"operator\"", "123"),
                VALID.replace("\"operator\"", "true"),
                VALID.replace("\"operator\"", "[\"operator\"]"),
                VALID.replace("\"synthetic-pass\"", "123"),
                VALID.replace("\"synthetic-pass\"", "true"),
                VALID.replace("\"synthetic-pass\"", "[\"s\",\"e\",\"c\"]"),
                VALID.replace("\"synthetic-pass\"", "{\"value\":\"synthetic-pass\"}"),
                VALID.replace("\"plant\"", "false"),
                VALID.replace("\"plant\"", "{}"),
                VALID.replace("\"plant\"", "null"),
                VALID.replace("\"operator\"", "null"),
                VALID.replace("\"synthetic-pass\"", "null"),
                VALID.replace("operator", " "),
                VALID.replace("plant", " "),
                VALID.replace("synthetic-pass", ""),
                VALID.replace("operator", "a".repeat(201)),
                VALID.replace("plant", "a".repeat(65)),
                VALID.replace("synthetic-pass", "a".repeat(1025)),
                "{\"loginKey\":\"operator\",\"password\":\"synthetic-pass\\q\",\"tenantCode\":\"plant\"}");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"text/plain", "application/xml", "application/*", "*/*", "not a media type"})
    void rejectsMissingNonJsonAndWildcardContentTypes(String contentType) {
        MockHttpServletRequest request = request(VALID);
        request.setContentType(contentType);
        assertInvalid(request);
    }

    @Test
    void acceptsJsonContentTypeParametersAndAnExactlySizedBodyButRejectsOneExtraByte() {
        MockHttpServletRequest exact = request(VALID + " ".repeat(4096 - VALID.length()));
        exact.setContentType("application/json;charset=UTF-8");
        var token = (LocalPasswordAuthenticationToken) converter.convert(exact);
        token.eraseCredentials();

        assertInvalid(request(VALID + " ".repeat(4097 - VALID.length())));
    }

    @Test
    void acceptsDeclaredMaximumStringLengthsWithinTheByteBudget() {
        String body = "{\"loginKey\":\"" + "a".repeat(200) + "\",\"password\":\""
                + "p".repeat(1024) + "\",\"tenantCode\":\"" + "t".repeat(64) + "\"}";

        var token = (LocalPasswordAuthenticationToken) converter.convert(request(body));

        assertThat(token.loginKey()).hasSize(200);
        assertThat(token.tenantCode()).hasSize(64);
        assertThat((char[]) token.getCredentials()).hasSize(1024);
        token.eraseCredentials();
    }

    @Test
    void wipesTheOwnedRequestBufferOnSuccessfulConversionAndOnAParsingFailure() {
        for (String body : new String[] {VALID, VALID + " {}"}) {
            AtomicReference<byte[]> readBuffer = new AtomicReference<>();
            MockHttpServletRequest request = trackingRequest(body, readBuffer, false);
            if (body.equals(VALID)) {
                var token = (LocalPasswordAuthenticationToken) converter.convert(request);
                assertThat((char[]) token.getCredentials()).containsExactly("synthetic-pass".toCharArray());
                token.eraseCredentials();
            } else {
                assertInvalid(request);
            }
            assertThat(readBuffer.get()).isNotNull().containsOnly((byte) 0);
        }
    }

    @Test
    void partialInputFailureStillWipesTheOwnedBufferAndDoesNotExposeItsCause() {
        AtomicReference<byte[]> readBuffer = new AtomicReference<>();

        assertInvalid(trackingRequest(VALID, readBuffer, true));

        assertThat(readBuffer.get()).isNotNull().containsOnly((byte) 0);
    }

    @ParameterizedTest
    @ValueSource(strings={"UTF-16LE","UTF-16BE","UTF-32LE","UTF-32BE"})
    void rejectsBomlessNonUtf8PayloadEvenWithUtf8ContentType(String charset) {
        var request=request("{}");
        request.setContentType("application/json;charset=UTF-8");
        request.setContent(VALID.getBytes(java.nio.charset.Charset.forName(charset)));
        assertInvalid(request);
    }

    @Test void ownedDecodedCharacterBufferIsWipedAfterSuccessAndFailure() throws Exception {
        var mapper=org.mockito.Mockito.spy(new ObjectMapper());
        var decoded=new AtomicReference<char[]>();
        org.mockito.Mockito.doAnswer(call->{decoded.set(call.getArgument(0));return call.callRealMethod();})
                .when(mapper).createParser(org.mockito.ArgumentMatchers.any(char[].class),org.mockito.ArgumentMatchers.eq(0),org.mockito.ArgumentMatchers.anyInt());
        var strict=new LoginRequestAuthenticationConverter(mapper);
        var token=(LocalPasswordAuthenticationToken)strict.convert(request(VALID.replace("synthetic-pass","synthetic-秘密")));
        assertThat((char[])token.getCredentials()).containsExactly("synthetic-秘密".toCharArray());
        token.eraseCredentials();assertThat(decoded.get()).containsOnly('\0');
        assertThatThrownBy(()->strict.convert(request(VALID+" {}"))).isInstanceOf(InvalidLoginRequestException.class);
        assertThat(decoded.get()).containsOnly('\0');
    }

    private void assertInvalid(MockHttpServletRequest request) {
        assertThatThrownBy(() -> converter.convert(request))
                .isInstanceOf(InvalidLoginRequestException.class)
                .hasMessage("The login request is invalid")
                .hasNoCause();
    }

    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest trackingRequest(
            String body, AtomicReference<byte[]> readBuffer, boolean failAfterFirstRead) {
        var source = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        var input = new ServletInputStream() {
            private boolean readOnce;

            @Override public boolean isFinished() { return source.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener readListener) { }
            @Override public int read() { return source.read(); }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                readBuffer.set(buffer);
                if (failAfterFirstRead && readOnce) {
                    throw new IOException("synthetic private read failure");
                }
                readOnce = true;
                return source.read(buffer, offset, failAfterFirstRead ? Math.min(length, 8) : length);
            }
        };
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override public ServletInputStream getInputStream() { return input; }
        };
        request.setContentType("application/json");
        return request;
    }
}
