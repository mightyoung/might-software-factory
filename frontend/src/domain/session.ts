import { parseEntityRoute } from "../routing/entityPaths";

/** Server-registered identifiers; synthetic applications remain a finite local catalog. */
export type ApplicationId = string;
export type DemoApplicationId = "safety" | "equipment" | "platform";

/** 应用切换器只需要轻量的 ID/名称/入口，不携带菜单树。入口为不含租户前缀的相对路径。 */
export interface ApplicationEntry {
  id: ApplicationId;
  name: string;
  entryPath: string;
}

export type MenuIcon = "warning" | "risk" | "task";

/**
 * 菜单节点。菜单只用于呈现，不是授权凭据：服务端对每个请求仍须最终鉴权。
 * 叶子节点带 path（不含租户前缀）与 permission；分组节点只有 children，最多两层。
 */
export interface MenuNode {
  id: string;
  label: string;
  icon?: MenuIcon;
  path?: string;
  permission?: string;
  children?: readonly MenuNode[];
}

export interface AppMenu {
  applicationId: ApplicationId;
  title: string;
  nodes: readonly MenuNode[];
}

/** 登录后的最小 bootstrap：身份、租户、有权应用的轻量列表和权限摘要。 */
export interface SessionBootstrap {
  actor: { id: string; displayName: string };
  /** Server identity metadata for clearing local UI state only; never command authority. */
  identityScope?: { accountId: string; memberId: string };
  tenant: { id: string; name: string };
  applications: readonly ApplicationEntry[];
  /** 权限摘要仅用于界面呈现；不得据此放行任何业务操作。 */
  permissions: readonly string[];
  /** Canonical server grants for registered generic applications; never legacy UI aliases. */
  canonicalPermissions?: readonly string[];
}

export type SessionFailureKind =
  | "invalid_credentials"
  | "unauthenticated"
  | "forbidden"
  | "unavailable"
  | "rate_limited"
  /** 请求被服务端判为格式不合规（400）。 */
  | "invalid_request"
  /** 服务端的一次性校验准备已过期（409），重新提交即可。 */
  | "stale_attempt";

export class SessionError extends Error {
  readonly kind: SessionFailureKind;
  readonly retryable: boolean;
  /** Bounded display cooldown; the server decides whether a later manual attempt is allowed. */
  readonly retryAfterSeconds: number | null;

  constructor(kind: SessionFailureKind, retryable = kind === "unavailable", retryAfterSeconds: number | null = null, readonly passwordFields: readonly ("current" | "next")[] = []) {
    super(kind);
    this.name = "SessionError";
    this.kind = kind;
    this.retryable = retryable;
    this.retryAfterSeconds = kind === "rate_limited" && retryAfterSeconds !== null
      && Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0
      ? Math.min(300, Math.ceil(retryAfterSeconds)) : null;
  }
}

export function hasPermission(session: Pick<SessionBootstrap, "permissions">, permission: string): boolean {
  return session.permissions.includes(permission);
}

/** 过滤掉无权限的叶子；分组在没有任何可见子项时一并隐藏。 */
export function visibleMenuNodes(
  nodes: readonly MenuNode[],
  session: Pick<SessionBootstrap, "permissions" | "canonicalPermissions">,
): MenuNode[] {
  const result: MenuNode[] = [];
  for (const node of nodes) {
    if (node.children) {
      const children = visibleMenuNodes(node.children, session);
      if (children.length > 0) result.push({ ...node, children });
    } else if (node.permission && (hasPermission(session, node.permission)
      || (node.path && parseEntityRoute(node.path)?.page === "entry" && session.canonicalPermissions?.includes(node.permission)))) {
      result.push(node);
    }
  }
  return result;
}
