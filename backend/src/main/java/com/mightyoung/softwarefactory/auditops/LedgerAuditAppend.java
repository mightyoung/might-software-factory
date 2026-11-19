package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.authorization.ApplicationActionAuthorization.Decision;
import java.util.Set;
import java.util.UUID;

/** Appends a redacted success inside the currently issued action's transaction. */
public interface LedgerAuditAppend {
    UUID appendSuccess(Decision decision,UUID commandId,UUID recordId,UUID traceId,Set<String> changedFields);
}
