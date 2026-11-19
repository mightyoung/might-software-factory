package com.mightyoung.softwarefactory.ledger;

public final class LedgerException extends RuntimeException {
    private final int status;
    private final String code;
    public LedgerException(int status,String code) { super(code); this.status=status; this.code=code; }
    public int status() { return status; }
    public String code() { return code; }
}
