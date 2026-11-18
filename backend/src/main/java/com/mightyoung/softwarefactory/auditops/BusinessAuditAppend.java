package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import java.sql.Connection;
import java.util.UUID;

/** Success is appended inside the actual command transaction; a receipt is not commit acknowledgment. */
public interface BusinessAuditAppend {
    AuditAppendReceipt appendSuccess(ActionScope scope, BusinessAuditRecord record);
    UUID requireMatches(AuditAppendReceipt receipt, ActionScope scope, BusinessAuditRecord record);

    final class AuditAppendReceipt {
        final Object issuer;
        final ActionScope scope;
        final BusinessAuditRecord record;
        final Connection connection;
        AuditAppendReceipt(Object issuer, ActionScope scope, BusinessAuditRecord record, Connection connection) {
            this.issuer=issuer; this.scope=scope; this.record=record; this.connection=connection;
        }
        @Override public String toString() { return "BusinessAuditAppendReceipt[REDACTED]"; }
    }
}
