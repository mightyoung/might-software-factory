package com.mightyoung.softwarefactory.ledger;

/** Stable client-facing validation reason; database and policy details remain private. */
public final class LedgerInputException extends RuntimeException {
    private final String field;
    private final String reason;
    public LedgerInputException(String field, String reason) {
        super("Ledger input rejected: "+reason); this.field=field; this.reason=reason;
    }
    public String field() { return field; }
    public String reason() { return reason; }
}
