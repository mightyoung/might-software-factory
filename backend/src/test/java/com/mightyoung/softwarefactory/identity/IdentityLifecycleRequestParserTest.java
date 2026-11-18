package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

class IdentityLifecycleRequestParserTest {
    private final IdentityLifecycleRequestParser parser=new IdentityLifecycleRequestParser(new ObjectMapper());
    private static final String ID="10000000-0000-0000-0000-000000000001";
    private static final String KEY="20000000-0000-0000-0000-000000000001";
    private static final String TOKEN="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String PASSWORD="a sufficiently long password";
    private static final String CONTACT="{\"channel\":\"EMAIL\",\"value\":\"synthetic@example.invalid\"}";

    @Test void parsesBothExactInvitationShapesWithoutAuthoritySelectors() {
        var created=parser.invitation(request("{\"kind\":\"NEW_ACCOUNT\",\"loginKey\":\" Person \",\"displayName\":\"Person\",\"destination\":"+CONTACT+"}"));
        assertThat(created.purpose()).isEqualTo(Purpose.NEW_ACCOUNT);assertThat(created.loginKey()).isEqualTo("person");
        assertThat(created.targetAccountId()).isNull();assertThat(created.commandKey()).isEqualTo(UUID.fromString(KEY));
        var existing=parser.invitation(request("{\"kind\":\"EXISTING_ACCOUNT_MEMBERSHIP\",\"targetAccountId\":\""+ID+"\",\"destination\":"+CONTACT+"}"));
        assertThat(existing.purpose()).isEqualTo(Purpose.EXISTING_ACCOUNT_MEMBERSHIP);assertThat(existing.targetAccountId()).isEqualTo(UUID.fromString(ID));
        assertThat(existing.loginKey()).isNull();assertThat(existing.toString()).doesNotContain("synthetic@",TOKEN);
    }
    @Test void consumesExactRouteSpecificIdentifiersAndOptionalInvitationPassword() {
        try(var reset=parser.resetConsume(request(resetBody()));var join=parser.invitationConsume(request("{\"invitationId\":\""+ID+"\",\"token\":\""+TOKEN+"\"}"))) {
            assertThat(reset.challengeId()).isEqualTo(UUID.fromString(ID));assertThat(reset.password()).containsExactly(PASSWORD.toCharArray());
            assertThat(join.password()).isNull();assertThat(reset.toString()).doesNotContain(PASSWORD,TOKEN);
        }
        assertThatThrownBy(()->parser.resetConsume(request("{\"resetId\":\""+ID+"\",\"token\":\""+TOKEN+"\"}"))).isInstanceOf(IdentityLifecycleException.class);
        assertThatThrownBy(()->parser.invitationConsume(request(resetBody()))).isInstanceOf(IdentityLifecycleException.class);
    }
    @Test void rejectsDuplicatesUnknownFieldsWrongRootsNestedValuesAndTrailingData() {
        for(String body:new String[]{"{}","[]","null","{\"loginKey\":\"a\",\"loginKey\":\"b\"}","{\"loginKey\":\"a\",\"accountId\":\""+ID+"\"}",
                "{\"loginKey\":{}}","{\"loginKey\":null}","{\"loginKey\":true}","{\"loginKey\":\"a\"} {}","{\"loginKey\":\"a\""}) {
            assertThatThrownBy(()->parser.resetRequest(request(body))).isInstanceOf(IdentityLifecycleException.class).hasMessage("Identity lifecycle operation could not be completed");
        }
    }
    @Test void rejectsPurposeDowngradesExtraExistingIdentityFieldsAndDestinationAmbiguity() {
        for(String body:new String[]{
                "{\"kind\":\"PASSWORD_RESET\",\"destination\":"+CONTACT+"}",
                "{\"kind\":\"EXISTING_ACCOUNT_MEMBERSHIP\",\"targetAccountId\":\""+ID+"\",\"loginKey\":\"person\",\"destination\":"+CONTACT+"}",
                "{\"kind\":\"NEW_ACCOUNT\",\"loginKey\":\"person\",\"displayName\":\"Person\",\"destination\":{\"channel\":\"EMAIL\",\"value\":\"a\",\"value\":\"b\"}}",
                "{\"kind\":\"NEW_ACCOUNT\",\"loginKey\":\"person\",\"displayName\":\"Person\",\"destination\":{\"channel\":\"EMAIL\",\"value\":\"a\",\"verified\":true}}"}) {
            assertThatThrownBy(()->parser.invitation(request(body))).isInstanceOf(IdentityLifecycleException.class);
        }
    }
    @Test void enforcesCanonicalSingleKeyIdentifiersAndPositiveIntegralVersions() {
        for(String key:new String[]{null,"1-1-1-1-1",KEY.toUpperCase()+" ",""}) {
            var request=request("{\"expectedVersion\":1}");request.removeHeader("Idempotency-Key");if(key!=null)request.addHeader("Idempotency-Key",key);
            assertThatThrownBy(()->parser.mutation(request,ID)).isInstanceOf(IdentityLifecycleException.class);
        }
        var duplicate=request("{\"expectedVersion\":1}");duplicate.addHeader("Idempotency-Key",KEY);
        assertThatThrownBy(()->parser.mutation(duplicate,ID)).isInstanceOf(IdentityLifecycleException.class);
        for(String version:new String[]{"0","-1","1.5","\"1\"","9223372036854775808"})
            assertThatThrownBy(()->parser.mutation(request("{\"expectedVersion\":"+version+"}"),ID)).isInstanceOf(IdentityLifecycleException.class);
        assertThat(parser.mutation(request("{\"expectedVersion\":2}"),ID).expectedVersion()).isEqualTo(2);
        assertThatThrownBy(()->parser.mutation(request("{\"expectedVersion\":1}"),"1-1-1-1-1")).isInstanceOf(IdentityLifecycleException.class);
    }
    @Test void rejectsQueryEncodingUnsupportedMediaAndOversizeWithoutReadingUnboundedBytes() {
        for(String type:new String[]{"text/plain","application/*","application/json;charset=UTF-16","application/json;profile=other"}) {
            var request=request("{\"loginKey\":\"person\"}");request.setContentType(type);
            assertThatThrownBy(()->parser.resetRequest(request)).isInstanceOf(IdentityLifecycleException.class);
        }
        var query=request("{\"loginKey\":\"person\"}");query.setQueryString("token=do-not-read");
        assertThatThrownBy(()->parser.resetRequest(query)).isInstanceOf(IdentityLifecycleException.class);
        var encoded=request("{\"loginKey\":\"person\"}");encoded.addHeader("Content-Encoding","gzip");
        assertThatThrownBy(()->parser.resetRequest(encoded)).isInstanceOf(IdentityLifecycleException.class);
        assertThatThrownBy(()->parser.resetRequest(request(" ".repeat(8193)))).isInstanceOf(IdentityLifecycleException.class);
    }
    @Test void rejectsNonUtf8MalformedSurrogatesAndPasswordByteOrTokenBoundaries() {
        for(String charset:new String[]{"UTF-16LE","UTF-16BE","UTF-32LE","UTF-32BE"}) {
            var request=request("{}");request.setContent("{\"loginKey\":\"person\"}".getBytes(java.nio.charset.Charset.forName(charset)));
            assertThatThrownBy(()->parser.resetRequest(request)).isInstanceOf(IdentityLifecycleException.class);
        }
        var malformed=request("{}");malformed.setContent(new byte[]{'{','"',(byte)0xc3,(byte)0x28,'"',':','1','}'});
        assertThatThrownBy(()->parser.resetRequest(malformed)).isInstanceOf(IdentityLifecycleException.class);
        for(String password:new String[]{"too short","a".repeat(73),"🌟".repeat(19),"\\uD800".repeat(15)})
            assertThatThrownBy(()->parser.resetConsume(request(resetBody().replace(PASSWORD,password)))).isInstanceOf(IdentityLifecycleException.class);
        for(String token:new String[]{"A".repeat(42),"A".repeat(44),"A".repeat(42)+"B"})
            assertThatThrownBy(()->parser.resetConsume(request(resetBody().replace(TOKEN,token)))).isInstanceOf(IdentityLifecycleException.class);
    }
    @Test void decodedSecretBuffersAreWipedOnSuccessAndFailure() {
        var mapper=org.mockito.Mockito.spy(new ObjectMapper());var captured=new java.util.concurrent.atomic.AtomicReference<char[]>();
        org.mockito.Mockito.doAnswer(call->{captured.set(call.getArgument(0));return call.callRealMethod();}).when(mapper).createParser(org.mockito.ArgumentMatchers.any(char[].class),org.mockito.ArgumentMatchers.eq(0),org.mockito.ArgumentMatchers.anyInt());
        var strict=new IdentityLifecycleRequestParser(mapper);
        try(var command=strict.resetConsume(request(resetBody()))){assertThat(command.password()).containsExactly(PASSWORD.toCharArray());}
        assertThat(captured.get()).containsOnly('\0');
        assertThatThrownBy(()->strict.resetConsume(request(resetBody()+" {}"))).isInstanceOf(IdentityLifecycleException.class);
        assertThat(captured.get()).containsOnly('\0');
    }
    @Test void unknownLengthStreamCannotReadBeyondTheSingleBoundedProbe() {
        var read=new java.util.concurrent.atomic.AtomicInteger();
        var input=new java.io.ByteArrayInputStream(new byte[100_000]) {
            @Override public synchronized int read(byte[] value,int offset,int length){int count=super.read(value,offset,length);if(count>0)read.addAndGet(count);return count;}
            @Override public synchronized int read(){int value=super.read();if(value>=0)read.incrementAndGet();return value;}
        };
        var request=new MockHttpServletRequest(){
            @Override public long getContentLengthLong(){return -1;}
            @Override public jakarta.servlet.ServletInputStream getInputStream(){return new org.springframework.mock.web.DelegatingServletInputStream(input);}
        };
        request.setContentType("application/json");
        assertThatThrownBy(()->parser.resetRequest(request)).isInstanceOf(IdentityLifecycleException.class);
        assertThat(read).hasValue(8193);
    }
    private static String resetBody(){return "{\"resetId\":\""+ID+"\",\"token\":\""+TOKEN+"\",\"newPassword\":\""+PASSWORD+"\"}";}
    private static MockHttpServletRequest request(String body){var request=new MockHttpServletRequest("POST","/api/v1/auth/password-reset-requests");request.setContentType("application/json");request.addHeader("Idempotency-Key",KEY);request.setContent(body.getBytes(StandardCharsets.UTF_8));return request;}
}
