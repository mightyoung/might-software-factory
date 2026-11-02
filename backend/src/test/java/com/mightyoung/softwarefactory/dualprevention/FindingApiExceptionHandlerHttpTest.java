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
