package com.mightyoung.softwarefactory.dualprevention;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Identifies an unsupported database dependency without classifying arbitrary transaction failures. */
public final class AssetFoundationCapabilityUnavailable extends SQLException {
    public AssetFoundationCapabilityUnavailable() { super("Asset foundation requires PostgreSQL publication storage","0A000"); }
    public AssetFoundationCapabilityUnavailable(Throwable cause) { this();initCause(cause); }
    public static boolean causedBy(Throwable failure) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable next=failure;next!=null&&seen.add(next);next=next.getCause())
            if(next instanceof AssetFoundationCapabilityUnavailable)return true;
        return false;
    }
}
