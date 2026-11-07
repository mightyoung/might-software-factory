package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager.LifecycleOutcome;
import com.mightyoung.softwarefactory.support.G014UnknownOutcomeTestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual owner-emitted failures through MVC advice; not database or full authenticated-command acceptance. */
class G014LifecycleAdviceHttpTest {
    @ParameterizedTest @EnumSource(LifecycleOutcome.class)
    void actualUnknownOutcomeIsNotAdvertisedAsRetryable(LifecycleOutcome outcome) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CommandController())
                .setControllerAdvice(new FindingApiExceptionHandler()).build();
        var result = mvc.perform(get("/owner-outcome/" + outcome))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMAND_OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.message").value("操作结果暂无法确认，请查看结果状态"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.fieldErrors").isEmpty()).andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("PRIVATE", "08006", "java.", "rolled back", "请求未完成");
    }

    @RestController
    static class CommandController {
        @GetMapping("/owner-outcome/{outcome}")
        String command(@PathVariable LifecycleOutcome outcome) throws Exception {
            throw G014UnknownOutcomeTestFixture.failure(outcome);
        }
    }
}
