package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class PasswordChangeRequestParserTest {
    private final PasswordChangeRequestParser parser=new PasswordChangeRequestParser(new ObjectMapper());
    @Test void exactFieldsPreserveRawSecretsAndRedactToString() {
        try(var result=parser.parse(request("{\"currentPassword\":\" old \u0020\",\"newPassword\":\"new password 12345\"}"))) {
            assertThat(result.currentPassword().toString()).isEqualTo(" old  ");
            assertThat(result.newPassword().toString()).isEqualTo("new password 12345");
            assertThat(result.toString()).doesNotContain("old","new password");
        }
    }
    @Test void rejectsUnknownDuplicateNestedMissingTrailingAndOversizedInput() {
        for(String body:new String[]{"{}","[]","{\"currentPassword\":\"x\",\"newPassword\":\"y\",\"extra\":1}",
                "{\"currentPassword\":\"x\",\"currentPassword\":\"x\",\"newPassword\":\"y\"}",
                "{\"currentPassword\":{},\"newPassword\":\"y\"}","{\"currentPassword\":\"x\",\"newPassword\":\"y\"} {}"," ".repeat(4097)}) {
            assertThatThrownBy(()->parser.parse(request(body))).isInstanceOf(PasswordChangeException.class)
                    .hasMessage("Password change could not be completed");
        }
    }
    @Test void rejectsMalformedUtf8AndNonJsonOrNonUtf8ContentTypes() {
        var malformed=request("{}");malformed.setContent(new byte[]{'{','"',(byte)0xc3,(byte)0x28,'"',':','1','}'});
        assertThatThrownBy(()->parser.parse(malformed)).isInstanceOf(PasswordChangeException.class);
        for(String type:new String[]{"text/plain","application/json;charset=UTF-16","application/*"}) {
            var request=request("{}");request.setContentType(type);
            assertThatThrownBy(()->parser.parse(request)).isInstanceOf(PasswordChangeException.class);
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"UTF-16LE","UTF-16BE","UTF-32LE","UTF-32BE"})
    void rejectsBomlessNonUtf8PayloadEvenWithUtf8ContentType(String charset) {
        var request=request("{}");
        request.setContentType("application/json;charset=UTF-8");
        request.setContent("{\"currentPassword\":\"current-password\",\"newPassword\":\"new password 123456\"}"
                .getBytes(java.nio.charset.Charset.forName(charset)));
        assertThatThrownBy(()->parser.parse(request)).isInstanceOf(PasswordChangeException.class);
    }
    @Test void ownedDecodedCharacterBufferIsWipedAfterSuccessAndFailure() throws Exception {
        var mapper=org.mockito.Mockito.spy(new ObjectMapper());
        var decoded=new java.util.concurrent.atomic.AtomicReference<char[]>();
        org.mockito.Mockito.doAnswer(call->{decoded.set(call.getArgument(0));return call.callRealMethod();})
                .when(mapper).createParser(org.mockito.ArgumentMatchers.any(char[].class),org.mockito.ArgumentMatchers.eq(0),org.mockito.ArgumentMatchers.anyInt());
        var strict=new PasswordChangeRequestParser(mapper);
        String valid="{\"currentPassword\":\"旧密码\",\"newPassword\":\""+"🌟".repeat(15)+"\"}";
        try(var value=strict.parse(request(valid))) {
            assertThat(value.currentPassword().toString()).isEqualTo("旧密码");
            assertThat(value.newPassword().toString()).isEqualTo("🌟".repeat(15));
        }
        assertThat(decoded.get()).containsOnly('\0');
        assertThatThrownBy(()->strict.parse(request(valid+" {}"))).isInstanceOf(PasswordChangeException.class);
        assertThat(decoded.get()).containsOnly('\0');
    }
    private static MockHttpServletRequest request(String body){var request=new MockHttpServletRequest();request.setContentType("application/json");request.setContent(body.getBytes(StandardCharsets.UTF_8));return request;}
}
