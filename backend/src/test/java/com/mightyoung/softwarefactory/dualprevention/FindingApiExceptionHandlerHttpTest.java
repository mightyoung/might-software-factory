package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException;
import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FindingApiExceptionHandlerHttpTest {
    @Test void guardDatabaseContentionOrConnectionFailureHasSafeRetryable503Contract() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GuardFailureController())
                .setControllerAdvice(new FindingApiExceptionHandler()).build();
        mvc.perform(get("/guard-unavailable")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.message").value("The finding command could not be completed safely"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }

    @Test void guardProtocolViolationsAreNotConvertedToRetryableDependencyFailures() {
        var mvc = MockMvcBuilders.standaloneSetup(new GuardFailureController())
                .setControllerAdvice(new FindingApiExceptionHandler()).build();
        assertThatThrownBy(() -> mvc.perform(get("/guard-protocol")))
                .hasRootCauseExactlyInstanceOf(AuthorizationWriteGuardException.class);
    }

    @Test void uploadReasonsHavePreciseSafeHttpCodes() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new UploadFailureController())
                .setControllerAdvice(new FindingApiExceptionHandler()).build();
        String[][] cases = {{"DECLARED_SIZE_EXCEEDED","413","INVALID_REQUEST"}, {"MEDIA_TYPE_NOT_ALLOWED","415","INVALID_REQUEST"}, {"IDEMPOTENCY_CONFLICT","409","IDEMPOTENCY_CONFLICT"},
                {"VERSION_CONFLICT","409","VERSION_CONFLICT"}, {"EVIDENCE_REVISION_CONFLICT","409","EVIDENCE_REVISION_CONFLICT"},
                {"ASSIGNMENT_UNRESOLVED","422","WORKFLOW_ASSIGNMENT_UNRESOLVED"},
                {"QUOTA_EXCEEDED","429","RATE_LIMITED"}, {"UNAVAILABLE","503","DEPENDENCY_UNAVAILABLE"}};
        for (String[] entry : cases) {
            var result = mvc.perform(get("/upload-failure/" + entry[0])).andExpect(status().is(Integer.parseInt(entry[1])))
                    .andExpect(jsonPath("$.code").value(entry[2])).andExpect(jsonPath("$.traceId").isString());
            if (entry[0].equals("QUOTA_EXCEEDED")) result.andExpect(header().string("Retry-After", "1"));
        }
    }
    @Test void evidenceSetInitializationReasonsHavePreciseSafeHttpCodes() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new InitializationFailureController())
                .setControllerAdvice(new FindingApiExceptionHandler()).build();
        String[][] cases = {{"IDEMPOTENCY_CONFLICT","409","IDEMPOTENCY_CONFLICT"}, {"VERSION_CONFLICT","409","VERSION_CONFLICT"},
                {"ASSIGNMENT_UNRESOLVED","422","WORKFLOW_ASSIGNMENT_UNRESOLVED"}, {"QUOTA_EXCEEDED","429","RATE_LIMITED"},
                {"UNAVAILABLE","503","DEPENDENCY_UNAVAILABLE"}};
        for (String[] entry : cases) {
            var result = mvc.perform(get("/initialization-failure/" + entry[0])).andExpect(status().is(Integer.parseInt(entry[1])))
                    .andExpect(jsonPath("$.code").value(entry[2])).andExpect(jsonPath("$.traceId").isString());
            if (entry[0].equals("QUOTA_EXCEEDED")) result.andExpect(header().string("Retry-After", "1"));
        }
    }
    @RestController
    static class InitializationFailureController {
        @GetMapping("/initialization-failure/{reason}") String fail(@org.springframework.web.bind.annotation.PathVariable String reason) {
            throw new com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationException(
                    com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationException.Reason.valueOf(reason));
        }
    }

    @RestController
    static class UploadFailureController {
        @GetMapping("/upload-failure/{reason}") String fail(@org.springframework.web.bind.annotation.PathVariable String reason) {
            throw new com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadException(
                    com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadException.Reason.valueOf(reason));
        }
    }

    @RestController
    static class GuardFailureController {
        @GetMapping("/guard-unavailable") String unavailable() {
            throw new AuthorizationWriteGuardUnavailableException("private database detail",
                    new SQLException("private host/statement", "55P03"));
        }
        @GetMapping("/guard-protocol") String protocol() {
            throw new AuthorizationWriteGuardException("Guard requires a managed write transaction");
        }
    }
}
