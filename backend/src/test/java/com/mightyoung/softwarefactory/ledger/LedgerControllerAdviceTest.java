package com.mightyoung.softwarefactory.ledger;

import com.mightyoung.softwarefactory.dualprevention.FindingApiExceptionHandler;
import java.sql.SQLException;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LedgerControllerAdviceTest {
    @Test void scopedLedgerAdviceWinsOverExistingGlobalFindingAdvice() throws Exception {
        var runtime=mock(LedgerRuntime.class);
        var mvc=MockMvcBuilders.standaloneSetup(new LedgerController(runtime,new LedgerHttpParser()),new OtherController())
                .setControllerAdvice(new FindingApiExceptionHandler(),new LedgerExceptionHandler()).build();
        when(runtime.definition(any(),eq("tooling"),eq("tool"),eq(LedgerDefinition.Use.CREATE)))
                .thenThrow(new DataAccessException("must stay private",new SQLException("private", "23505")));
        mvc.perform(get("/api/v1/apps/tooling/entities/tool/definition").param("use","CREATE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMAND_CONFLICT"))
                .andExpect(jsonPath("$.retryable").value(false)).andExpect(jsonPath("$.message").value("请求未完成"))
                .andExpect(header().string("Cache-Control","no-store"));
        when(runtime.definition(any(),eq("tooling"),eq("tool"),eq(LedgerDefinition.Use.CREATE)))
                .thenThrow(new DataAccessException("private",new SQLException("private","42804")));
        mvc.perform(get("/api/v1/apps/tooling/entities/tool/definition").param("use","CREATE"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(false)).andExpect(jsonPath("$.message").value("请求未完成"));
        mvc.perform(get("/existing-other-controller"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.message").value("The finding command could not be completed safely"));
    }
    @RestController static class OtherController {
        @GetMapping("/existing-other-controller") public String read() {throw new DataAccessException("private",new SQLException("private","42804"));}
    }
}
