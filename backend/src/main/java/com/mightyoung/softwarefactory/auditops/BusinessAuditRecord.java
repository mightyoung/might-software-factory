package com.mightyoung.softwarefactory.auditops;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** IDs are allocated by the trusted command owner; identity/action/time come only from its authority scope. */
public record BusinessAuditRecord(UUID auditId, UUID commandId, UUID resourceId, ChangeKind changeKind,
                                  Set<FieldCategory> changedFields, UUID traceId) {
    public enum ChangeKind { CREATE, UPDATE, ARCHIVE, BIND, END }
    public enum FieldCategory { IDENTITY, NAME, DESCRIPTION, LIFECYCLE, OBSERVATION, ORDERING, RELATIONSHIP, RESPONSIBILITY, PERIOD }
    public BusinessAuditRecord {
        Objects.requireNonNull(auditId); Objects.requireNonNull(commandId); Objects.requireNonNull(resourceId);
        Objects.requireNonNull(changeKind); Objects.requireNonNull(traceId); changedFields=Set.copyOf(changedFields);
        if(changedFields.isEmpty()) throw new IllegalArgumentException("A changed field category is required");
    }
}
