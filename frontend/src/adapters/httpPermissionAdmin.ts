import { ApiError, type HttpClient } from "../api/httpClient";
import {
  PermissionAdminError,
  type PermissionAdminAdapter,
  type PermissionAdminData,
  type PermissionCatalogItem,
  type RoleView,
} from "./permissionAdmin";

function toError(error: unknown): unknown {
  if (!(error instanceof ApiError)) return error;
  if (error.status === 401) return new PermissionAdminError("unauthenticated");
  if (error.status === 403) return new PermissionAdminError("forbidden");
  switch (error.code) {
    case "VERSION_CONFLICT":
      return new PermissionAdminError("conflict");
    case "LAST_PERMISSION_MANAGER":
      return new PermissionAdminError("last_manager");
    case "ROLE_HAS_NO_SCOPE":
      return new PermissionAdminError("no_scope");
    case "UNKNOWN_PERMISSION":
      return new PermissionAdminError("unknown_permission");
    default:
      return new PermissionAdminError("unavailable");
  }
}

export function createHttpPermissionAdmin(client: HttpClient): PermissionAdminAdapter {
  return {
    async load(signal): Promise<PermissionAdminData> {
      try {
        const [catalog, roles] = await Promise.all([
          client.get<{ items: PermissionCatalogItem[] }>("/api/v1/admin/permissions", { signal }),
          client.get<{ roles: RoleView[] }>("/api/v1/admin/roles", { signal }),
        ]);
        return { catalog: catalog.items, roles: roles.roles };
      } catch (error) {
        throw toError(error);
      }
    },
    async saveRolePermissions(roleId, permissionCodes, expectedVersion, signal): Promise<RoleView> {
      try {
        return await client.put<RoleView>(`/api/v1/admin/roles/${encodeURIComponent(roleId)}/permissions`, {
          body: { permissionCodes, expectedVersion },
          signal,
        });
      } catch (error) {
        throw toError(error);
      }
    },
  };
}
