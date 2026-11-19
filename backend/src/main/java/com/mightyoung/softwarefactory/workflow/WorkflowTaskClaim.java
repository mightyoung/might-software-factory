package com.mightyoung.softwarefactory.workflow;

import java.util.Map;
import java.util.UUID;

/** Claim an explicitly pooled task after the business module authorizes its current handler. */
public interface WorkflowTaskClaim {
    /** A business module registers the exact process and task keys that support pooled handling. */
    record Pool(String businessType, String definitionKey, String taskKey) {}
    void claimAndComplete(WorkflowTaskReadSnapshot expected, UUID memberId, Map<String, Object> variables);
}
