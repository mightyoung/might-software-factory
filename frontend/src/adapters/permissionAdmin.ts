/** 系统设置-权限配置：角色拥有哪些功能权限。界面只做呈现，保存由服务端按 platform.permission.manage 校验。 */
export interface PermissionCatalogItem {
  code: string;
  application: string;
  label: string;
}

export interface RoleView {
  id: string;
  code: string;
  name: string;
  active: boolean;
  permissionCodes: readonly string[];
  memberCount: number;
  /** 该角色当前权限集合的指纹，保存时回传做并发校验。 */
  version: string;
}

export interface PermissionAdminData {
  catalog: readonly PermissionCatalogItem[];
  roles: readonly RoleView[];
}

export type PermissionAdminFailure =
  | "unauthenticated"
  | "forbidden"
  | "conflict"
  | "last_manager"
  | "no_scope"
  | "unknown_permission"
  | "unavailable";

export class PermissionAdminError extends Error {
  constructor(readonly kind: PermissionAdminFailure) {
    super(kind);
    this.name = "PermissionAdminError";
  }
}

export interface PermissionAdminAdapter {
  load(signal?: AbortSignal): Promise<PermissionAdminData>;
  saveRolePermissions(
    roleId: string,
    permissionCodes: readonly string[],
    expectedVersion: string,
    signal?: AbortSignal,
  ): Promise<RoleView>;
}
