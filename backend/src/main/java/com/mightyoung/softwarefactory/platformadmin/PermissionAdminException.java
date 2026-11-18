package com.mightyoung.softwarefactory.platformadmin;

public final class PermissionAdminException extends RuntimeException {
    public enum Reason { ROLE_NOT_FOUND, VERSION_CONFLICT, ROLE_HAS_NO_SCOPE, LAST_MANAGER, UNKNOWN_PERMISSION }

    private final Reason reason;

    public PermissionAdminException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
