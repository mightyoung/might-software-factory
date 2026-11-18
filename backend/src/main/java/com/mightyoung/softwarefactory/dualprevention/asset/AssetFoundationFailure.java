package com.mightyoung.softwarefactory.dualprevention.asset;

/** Public failures deliberately contain no SQL, entity text, or authority details. */
public final class AssetFoundationFailure extends RuntimeException {
    private final int status;
    public AssetFoundationFailure(int status) { super("Asset foundation request could not be completed"); this.status=status; }
    AssetFoundationFailure(int status,Throwable cause) {
        super("Asset foundation request could not be completed",java.util.Objects.requireNonNull(cause));this.status=status;
    }
    String sqlState() {
        if(!(getCause() instanceof org.jooq.exception.DataAccessException failure))return null;
        String state=failure.sqlState();return state!=null&&state.matches("[0-9A-Z]{5}")?state:null;
    }
    String category() { return getCause()==null?"REQUEST":com.mightyoung.softwarefactory.dualprevention.AssetFoundationCapabilityUnavailable.causedBy(getCause())?"CAPABILITY_UNAVAILABLE":status==409?"DATABASE_CONFLICT":"DATABASE_UNAVAILABLE"; }
    public int status() { return status; }
}
