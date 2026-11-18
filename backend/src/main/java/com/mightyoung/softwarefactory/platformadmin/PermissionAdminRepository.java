package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.CatalogItem;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.RoleView;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface PermissionAdminRepository {
    List<CatalogItem> catalog(UUID tenantId);

    List<RoleView> roles(UUID tenantId);

    /** Must run inside an identity security transaction; replaces the role's permission set atomically. */
    RoleView replaceRolePermissions(UUID tenantId, UUID roleId, Set<String> desiredCodes, String expectedVersion,
                                    UUID actorMemberId, UUID traceId);
}
