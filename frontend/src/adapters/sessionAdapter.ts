import { DEMO_ACCOUNTS } from "./demoAccounts";
export { DEMO_ACCOUNTS } from "./demoAccounts";
import {
  SessionError,
  type AppMenu,
  type ApplicationEntry,
  type ApplicationId,
  type DemoApplicationId,
  type SessionBootstrap,
} from "../domain/session";

export interface Credentials {
  username: string;
  password: string;
  /** 真实后端登录必填；合成实现忽略。 */
  tenantCode?: string;
}

/**
 * 会话读写端口。真实实现应走同域 /api 的 Cookie 会话（HttpOnly、SameSite、CSRF），
 * 凭证与会话标识不进入 localStorage/sessionStorage，也不出现在 URL 或日志中。
 * HTTP 实现见 httpSessionAdapter；下面的合成实现只用于显式演示模式。
 */
export interface SessionAdapter {
  /** 真实后端登录需要租户码：登录页据此显示输入框并隐藏合成演示说明。 */
  readonly requiresTenantCode?: boolean;
  login(credentials: Credentials, signal?: AbortSignal): Promise<SessionBootstrap>;
  /** 无有效会话时返回 null（对应服务端 401），其他故障抛 SessionError("unavailable")。 */
  restore(signal?: AbortSignal): Promise<SessionBootstrap | null>;
  logout(signal?: AbortSignal): Promise<void>;
  /**
   * 自助改密（可选；合成演示没有）。成功后服务端使旧凭据代的所有会话失效，调用方必须回到登录页。
   * 当前密码错误抛 invalid_credentials；会话已失效抛 unauthenticated。
   */
  changePassword?(currentPassword: string, newPassword: string, signal?: AbortSignal): Promise<void>;
  /** 进入某个应用时才取它的菜单，不在登录时拉取全部应用的菜单树。 */
  loadMenu(applicationId: ApplicationId, signal?: AbortSignal): Promise<AppMenu>;
}

const TENANT = { id: "demo-tenant", name: "演示租户（合成）" } as const;

const ALL_APPLICATIONS: Record<DemoApplicationId, ApplicationEntry> = {
  safety: { id: "safety", name: "生产安全", entryPath: "safety/findings" },
  equipment: { id: "equipment", name: "设备管理", entryPath: "equipment/overview" },
  platform: { id: "platform", name: "系统设置", entryPath: "platform/permissions" },
};

export const MENUS: Record<DemoApplicationId, AppMenu> = {
  safety: {
    applicationId: "safety",
    title: "安全管理",
    nodes: [
      {
        id: "hazards",
        label: "隐患治理",
        icon: "warning",
        children: [
          { id: "findings", label: "隐患台账", path: "safety/findings", permission: "safety.finding.read" },
          { id: "inbox", label: "待确认", path: "safety/inbox", permission: "safety.inbox.read" },
          { id: "acceptance", label: "整改验收", path: "safety/acceptance", permission: "safety.acceptance.read" },
        ],
      },
      { id: "risks", label: "风险管控", icon: "risk", path: "safety/risks", permission: "safety.risk.read" },
      { id: "inspections", label: "排查任务", icon: "task", path: "safety/inspections", permission: "safety.inspection.read" },
    ],
  },
  equipment: {
    applicationId: "equipment",
    title: "设备管理",
    nodes: [{ id: "overview", label: "设备概览", path: "equipment/overview", permission: "equipment.overview.read" }],
  },
  platform: {
    applicationId: "platform",
    title: "系统设置",
    nodes: [{ id: "permissions", label: "权限配置", path: "platform/permissions", permission: "platform.permission.read" }],
  },
};

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
}

function bootstrapFor(username: string): SessionBootstrap | null {
  const account = DEMO_ACCOUNTS[username];
  if (!account) return null;
  return {
    actor: { id: `synthetic:${username}`, displayName: account.displayName },
    tenant: { ...TENANT },
    applications: account.applications.map((id) => ALL_APPLICATIONS[id]),
    permissions: [...account.permissions],
  };
}

export interface SyntheticSessionOptions {
  /** 测试或演示时预先登录的账号。 */
  signedInAs?: string;
}

/**
 * 合成会话：状态只保存在内存里，刷新页面即退出；不读写任何浏览器存储，也不联网。
 * 只认 DEMO_ACCOUNTS 中的账号名，密码只要求非空——这不是认证，不提供任何安全保证。
 * 账号不存在与密码为空返回同一个错误，不暴露账号是否存在。
 */
export function createSyntheticSessionAdapter(options: SyntheticSessionOptions = {}): SessionAdapter {
  let current: SessionBootstrap | null = options.signedInAs ? bootstrapFor(options.signedInAs) : null;

  return {
    async login({ username, password }, signal) {
      throwIfAborted(signal);
      const next = password.length > 0 ? bootstrapFor(username.trim().toLowerCase()) : null;
      if (!next) throw new SessionError("invalid_credentials");
      current = next;
      return next;
    },
    async restore(signal) {
      throwIfAborted(signal);
      return current;
    },
    async logout(signal) {
      throwIfAborted(signal);
      current = null;
    },
    async loadMenu(applicationId, signal) {
      throwIfAborted(signal);
      if (!current) throw new SessionError("unauthenticated");
      if (!current.applications.some((app) => app.id === applicationId)) throw new SessionError("forbidden");
      if (!Object.hasOwn(MENUS, applicationId)) throw new SessionError("forbidden");
      return MENUS[applicationId as DemoApplicationId];
    },
  };
}

export const syntheticSessionAdapter: SessionAdapter = /* @__PURE__ */ createSyntheticSessionAdapter();
