import type { components } from "../api/generated/session-api";
import { ApiError, type HttpClient } from "../api/httpClient";
import { SessionError, type AppMenu, type ApplicationEntry, type ApplicationId, type SessionBootstrap } from "../domain/session";
import type { Credentials, SessionAdapter } from "./sessionAdapter";

type CurrentSession = components["schemas"]["CurrentSessionResponse"]["data"];

/**
 * 后端目前没有应用列表/菜单/权限目录接口（见 docs/frontend-contract-gap.md S1/S2）。
 * 调用方必须显式提供，本适配器不替后端臆造；这些只用于界面呈现，服务端对每个请求仍须鉴权。
 * 没有提供菜单的应用，loadMenu 返回 unavailable。
 */
export interface HttpSessionCapabilities {
  applications: readonly ApplicationEntry[];
  permissions: readonly string[];
  menus?: Readonly<Partial<Record<ApplicationId, AppMenu>>>;
}

function toSessionError(error: unknown): unknown {
  if (!(error instanceof ApiError)) return error;
  if (error.status === 401) return new SessionError("unauthenticated");
  if (error.status === 403) return new SessionError("forbidden");
  return new SessionError("unavailable", error.retryable);
}

function toBootstrap(current: CurrentSession, capabilities: HttpSessionCapabilities): SessionBootstrap {
  return {
    actor: { id: current.actorId, displayName: current.displayName },
    tenant: { id: current.tenantId, name: current.tenantName },
    applications: capabilities.applications,
    permissions: capabilities.permissions,
  };
}

export function createHttpSessionAdapter(client: HttpClient, capabilities: HttpSessionCapabilities): SessionAdapter {
  async function current(signal?: AbortSignal): Promise<SessionBootstrap> {
    const body = await client.get<components["schemas"]["CurrentSessionResponse"]>("/api/v1/session/current", { signal });
    return toBootstrap(body.data, capabilities);
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
        if (error instanceof ApiError && (error.status === 401 || error.status === 400)) {
          throw new SessionError("invalid_credentials");
        }
        throw toSessionError(error);
      }
    },
    async restore(signal) {
      try {
        return await current(signal);
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) return null;
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
    async loadMenu(applicationId) {
      const menu = capabilities.menus?.[applicationId];
      if (!menu) throw new SessionError("unavailable", false);
      return menu;
    },
  };
}
