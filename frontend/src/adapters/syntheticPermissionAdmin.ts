import {
  PermissionAdminError,
  type PermissionAdminAdapter,
  type PermissionCatalogItem,
  type RoleView,
} from "./permissionAdmin";

const CATALOG: readonly PermissionCatalogItem[] = [
  { code: "finding.read", application: "safety", label: "查看隐患" },
  { code: "finding.create", application: "safety", label: "上报隐患" },
  { code: "finding.confirm.safety", application: "safety", label: "安全确认" },
  { code: "finding.dispatch", application: "safety", label: "派单" },
  { code: "finding.verify", application: "safety", label: "整改验收" },
  { code: "risk.read", application: "safety", label: "查看风险管控" },
  { code: "platform.permission.read", application: "platform", label: "查看权限配置" },
  { code: "platform.permission.manage", application: "platform", label: "修改权限配置" },
];

function version(codes: readonly string[]): string {
  return [...codes].sort().join("|");
}

function role(id: string, code: string, name: string, codes: string[], memberCount: number): RoleView {
  return { id, code, name, active: true, permissionCodes: codes, memberCount, version: version(codes) };
}

/** 演示模式：内存中的合成角色，刷新即重置；不是真实授权。 */
export function createSyntheticPermissionAdmin(): PermissionAdminAdapter {
  let roles: RoleView[] = [
    role("r-reporter", "reporter", "上报人", ["finding.read", "finding.create"], 3),
    role("r-safety", "safety-officer", "安全员", ["finding.read", "finding.confirm.safety", "finding.dispatch", "finding.verify"], 2),
    role("r-admin", "admin", "系统管理员", ["finding.read", "risk.read", "platform.permission.read", "platform.permission.manage"], 1),
  ];
  return {
    async load() {
      return { catalog: CATALOG, roles };
    },
    async saveRolePermissions(roleId, permissionCodes, expectedVersion) {
      const current = roles.find((candidate) => candidate.id === roleId);
      if (!current) throw new PermissionAdminError("unavailable");
      if (current.version !== expectedVersion) throw new PermissionAdminError("conflict");
      const next = new Set(permissionCodes);
      const others = roles.filter((candidate) => candidate.id !== roleId);
      if (!next.has("platform.permission.manage") && !others.some((candidate) => candidate.permissionCodes.includes("platform.permission.manage"))) {
        throw new PermissionAdminError("last_manager");
      }
      const updated = role(current.id, current.code, current.name, [...next].sort(), current.memberCount);
      roles = roles.map((candidate) => (candidate.id === roleId ? updated : candidate));
      return updated;
    },
  };
}
