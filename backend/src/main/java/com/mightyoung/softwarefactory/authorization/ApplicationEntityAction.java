package com.mightyoung.softwarefactory.authorization;

/** Server-registered application-qualified action; never selected from client-supplied permission text. */
public record ApplicationEntityAction(String appKey, String resourceType, String action) {
    public ApplicationEntityAction {
        if (appKey==null || !appKey.matches("[a-z][a-z0-9_]{0,63}") || resourceType==null
                || !resourceType.matches("[a-z][a-z0-9_]{0,63}") || action==null || !action.matches("read|create|update|issue|receive")) {
            throw new IllegalArgumentException("Invalid registered application action");
        }
    }
    public String permissionCode() { return resourceType+"."+action; }
}
