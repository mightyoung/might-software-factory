package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ErrorResponse;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager.LifecycleException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Fixed responses preserve the G014 owner's transaction fact without exposing its cleanup details. */
public final class G014LifecycleHttpResponses {
    private G014LifecycleHttpResponses() { }
    public static ErrorResponse error(LifecycleException failure, UUID trace) {
        String traceId = Objects.requireNonNull(trace).toString();
        return switch (Objects.requireNonNull(failure).outcome()) {
            case COMMIT_UNKNOWN, ROLLBACK_UNKNOWN -> new ErrorResponse("COMMAND_OUTCOME_UNKNOWN",
                    "操作结果暂无法确认，请查看结果状态", traceId, false, List.of(), null);
            case COMMITTED_CLEANUP_FAILED -> new ErrorResponse("COMMAND_COMMITTED",
                    "操作已提交，请查看结果", traceId, false, List.of(), null);
        };
    }
}
