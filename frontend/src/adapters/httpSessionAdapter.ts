import type { components } from "../api/generated/session-api";
import { classifyHttpError } from "../api/classifyError";
import { ApiError, type HttpClient } from "../api/httpClient";
import { SessionError, type AppMenu, type ApplicationId, type SessionBootstrap } from "../domain/session";
import type { Credentials, SessionAdapter } from "./sessionAdapter";

type BootstrapResponse = components["schemas"]["SessionBootstrapResponse"];
type MenuResponse = components["schemas"]["ApplicationMenuResponse"];
const UI_PERMISSIONS: Readonly<Record<string, string>> = {
  "finding.read": "safety.finding.read",
  "finding.create": "safety.finding.report",
  "equipment.read": "equipment.overview.read",
  "platform.permission.read": "platform.permission.read",
  "platform.permission.manage": "platform.permission.manage",
};
const APPLICATIONS = new Set<string>(["safety", "equipment", "platform"]);
function permission(value: string): string | undefined {
  return Object.hasOwn(UI_PERMISSIONS, value) ? UI_PERMISSIONS[value] : undefined;
}
function permissions(values: string[]): string[] {
  return values.flatMap((value) => permission(value) ?? []);
}
function checkVersion(version: number): void {
  if (version !== 1) throw new SessionError("unavailable", false);
}

function toSessionError(error: unknown): unknown {
  if (!(error instanceof ApiError)) return error;
  switch (classifyHttpError(error)) {
    case "rateLimited":
      return new SessionError("rate_limited", false, error.retryAfterSeconds);
    case "unauthenticated":
      return new SessionError("unauthenticated");
    case "forbidden":
      return new SessionError("forbidden");
    default:
      return new SessionError("unavailable", error.retryable);
  }
}

function toBootstrap(body: BootstrapResponse["data"]): SessionBootstrap {
  checkVersion(body.schemaVersion);
  const current = body.identity;
  return {
    actor: { id: current.actorId, displayName: current.displayName },
    identityScope: { accountId: current.accountId, memberId: current.memberId },
    tenant: { id: current.tenantId, name: current.tenantName },
    applications: body.applications.filter((app) => APPLICATIONS.has(app.id)).map((app) => ({ ...app, id: app.id as ApplicationId })),
    permissions: permissions(body.permissions),
  };
}

export function createHttpSessionAdapter(client: HttpClient): SessionAdapter {
  async function current(signal?: AbortSignal): Promise<SessionBootstrap> {
    const body = await client.get<BootstrapResponse>("/api/v1/session/bootstrap", { signal });
    return toBootstrap(body.data);
  }

  return {
    requiresTenantCode: true,
    async login({ username, password, tenantCode }: Credentials, signal) {
      // 与合成实现一致：缺少信息和凭证错误同一个错误，不暴露账号是否存在。
      if (!tenantCode?.trim() || !username.trim() || !password) throw new SessionError("invalid_credentials");
      try {
        await client.post<void>("/api/v1/auth/login", {
          body: { loginKey: username.trim(), password, tenantCode: tenantCode.trim() },
          signal,
        });
        return await current(signal);
      } catch (error) {
        if (error instanceof ApiError && ["unauthenticated", "invalid"].includes(classifyHttpError(error))) {
          throw new SessionError("invalid_credentials");
        }
        throw toSessionError(error);
      }
    },
    async restore(signal) {
      try {
        return await current(signal);
      } catch (error) {
        if (error instanceof ApiError && classifyHttpError(error) === "unauthenticated") return null;
        throw toSessionError(error);
      }
    },
    async logout(signal) {
      try {
        await client.post<void>("/api/v1/auth/logout", { signal });
      } catch (error) {
        throw toSessionError(error);
      }
    },
    async changePassword(currentPassword, newPassword, signal) {
      try {
        await client.post<void>("/api/v1/account/password", { body: { currentPassword, newPassword }, signal });
      } catch (error) {
        if (error instanceof ApiError) {
          const kind = classifyHttpError(error);
          if (kind === "invalid") throw new SessionError("invalid_request", false);
          if (kind === "conflict") throw new SessionError("stale_attempt", true);
          if (kind === "unauthenticated") {
            // 契约里 401 既可能是“会话已失效”也可能是“当前密码不对”：再读一次会话来区分。
            try {
              await current(signal);
            } catch (probe) {
              if (probe instanceof ApiError && classifyHttpError(probe) === "unauthenticated") throw new SessionError("unauthenticated");
              throw toSessionError(probe);
            }
            throw new SessionError("invalid_credentials");
          }
        }
        throw toSessionError(error);
      }
    },
    async loadMenu(applicationId, signal) {
      try {
        const { data } = await client.get<MenuResponse>(`/api/v1/applications/${encodeURIComponent(applicationId)}/menu`, { signal });
        checkVersion(data.schemaVersion);
        if (data.applicationId !== applicationId) throw new SessionError("unavailable", false);
        const granted = new Set(permissions(data.permissions));
        const nodes = data.nodes.flatMap((node) => {
          const mapped = permission(node.permission);
          return mapped && granted.has(mapped) ? [{ ...node, permission: mapped }] : [];
        });
        return { applicationId, title: data.title, nodes } satisfies AppMenu;
      } catch (error) {
        throw toSessionError(error);
      }
    },
  };
}
