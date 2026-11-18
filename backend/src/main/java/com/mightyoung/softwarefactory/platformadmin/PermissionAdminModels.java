package com.mightyoung.softwarefactory.platformadmin;

import java.util.List;

public final class PermissionAdminModels {
    private PermissionAdminModels() {}

    public record CatalogItem(String code, String application, String label) {}
    public record CatalogResponse(List<CatalogItem> items) {}
    public record RoleView(String id, String code, String name, boolean active, List<String> permissionCodes,
                           int memberCount, String version) {}
    public record RolesResponse(List<RoleView> roles) {}
    public record ReplaceRequest(List<String> permissionCodes, String expectedVersion) {}
}
