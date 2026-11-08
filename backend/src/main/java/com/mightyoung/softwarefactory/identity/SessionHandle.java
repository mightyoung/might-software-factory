package com.mightyoung.softwarefactory.identity;

import java.util.UUID;

/** An opaque persisted PRIMARY_ID selector, never a bearer SESSION_ID or ownership proof. */
public record SessionHandle(UUID value) {
    public SessionHandle { if(value==null)throw new IllegalArgumentException("Session handle required"); }
    public static SessionHandle parse(String value) {
        if(value==null)throw new IllegalArgumentException("Session handle required");
        UUID parsed=UUID.fromString(value);
        if(!parsed.toString().equals(value))throw new IllegalArgumentException("Canonical session handle required");
        return new SessionHandle(parsed);
    }
    @Override public String toString(){return value.toString();}
}
