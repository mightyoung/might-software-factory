package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ErrorResponse;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager.LifecycleException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One fixed response for an actual G014 command whose transaction outcome cannot be confirmed. */
public final class G014LifecycleHttpResponses {
    private G014LifecycleHttpResponses() { }
    public static ErrorResponse unknown(LifecycleException failure, UUID trace) {
        Objects.requireNonNull(failure);
        return new ErrorResponse("COMMAND_OUTCOME_UNKNOWN", "操作结果暂无法确认，请查看结果状态",
                Objects.requireNonNull(trace).toString(), false, List.of(), null);
    }
}
