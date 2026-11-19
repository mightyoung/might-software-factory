package com.mightyoung.softwarefactory.platformadmin;

public final class LedgerConfigurationException extends RuntimeException {
    public enum Reason { NOT_FOUND, VERSION_CONFLICT, REQUEST_CONFLICT }
    private final Reason reason;
    public LedgerConfigurationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
