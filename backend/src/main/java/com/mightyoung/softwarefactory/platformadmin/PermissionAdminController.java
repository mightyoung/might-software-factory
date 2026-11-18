package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.CatalogResponse;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.ReplaceRequest;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.RoleView;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.RolesResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public final class PermissionAdminController {
    private final PermissionAdminService service;

    public PermissionAdminController(PermissionAdminService service) { this.service = service; }

    @GetMapping("/api/v1/admin/permissions")
    public CatalogResponse catalog() { return service.catalog(); }

    @GetMapping("/api/v1/admin/roles")
    public RolesResponse roles() { return service.roles(); }

    @PutMapping("/api/v1/admin/roles/{roleId}/permissions")
    public RoleView replace(@PathVariable String roleId, @RequestBody ReplaceRequest request) {
        UUID parsed;
        try {
            parsed = UUID.fromString(roleId);
            if (!parsed.toString().equalsIgnoreCase(roleId)) throw new IllegalArgumentException("Non-canonical UUID");
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException("roleId", "invalid_uuid", "The role identifier is invalid");
        }
        return service.replace(parsed, request);
    }
}
