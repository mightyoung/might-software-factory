package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class IdentityRequestIdsTest {
    @Test void serverGeneratesOnePairAndIgnoresCallerCorrelationHeaders() {
        var request=new MockHttpServletRequest();var supplied=UUID.randomUUID();
        request.addHeader("X-Request-Id",supplied.toString());request.addHeader("X-Trace-Id",supplied.toString());
        assertThat(IdentityRequestIds.existing(request)).isEmpty();
        var ids=IdentityRequestIds.get(request);
        assertThat(ids.requestId()).isNotEqualTo(supplied).isNotEqualTo(ids.traceId());
        assertThat(ids.traceId()).isNotEqualTo(supplied);
        assertThat(IdentityRequestIds.get(request)).isSameAs(ids);
        assertThat(IdentityRequestIds.existing(request)).containsSame(ids);
    }
    @Test void frameworkWrappersReuseTheInitializedPairAndAnotherRequestDoesNot() {
        var request=new MockHttpServletRequest();var ids=IdentityRequestIds.get(request);
        assertThat(IdentityRequestIds.get(new HttpServletRequestWrapper(request))).isSameAs(ids);
        var other=IdentityRequestIds.get(new MockHttpServletRequest());
        assertThat(other.requestId()).isNotEqualTo(ids.requestId());assertThat(other.traceId()).isNotEqualTo(ids.traceId());
    }
    @Test void identityErrorsReuseInitializedTraceWithoutAddingAClientControlledHeader() throws Exception {
        var request=new MockHttpServletRequest();var ids=IdentityRequestIds.get(request);var response=new MockHttpServletResponse();
        IdentityHttpResponses.writeError(request,response,403,"ACTION_DENIED","The request is not allowed",false);
        var body=JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        assertThat(body.get("traceId").stringValue()).isEqualTo(ids.traceId().toString());assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("X-Trace-Id")).isNull();assertThat(body.get("code").stringValue()).isEqualTo("ACTION_DENIED");
    }
    @Test void uninitializedLegacyRequestStillGetsAServerTraceWithoutCreatingNewContext() throws Exception {
        var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();
        IdentityHttpResponses.writeError(request,response,401,"AUTH_REQUIRED","A verified session is required",false);
        var body=JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        assertThat(UUID.fromString(body.get("traceId").stringValue())).isNotNull();assertThat(IdentityRequestIds.existing(request)).isEmpty();
    }
    @Test void corruptedServerAttributeFailsClosedAndNullInputIsRejected() {
        var request=new MockHttpServletRequest();request.setAttribute(IdentityRequestIds.class.getName()+".ids","not server-issued IDs");
        assertThatThrownBy(()->IdentityRequestIds.get(request)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->IdentityRequestIds.existing(request)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->IdentityRequestIds.get(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->new IdentityRequestIds.Ids(null,UUID.randomUUID())).isInstanceOf(NullPointerException.class);
    }
}
