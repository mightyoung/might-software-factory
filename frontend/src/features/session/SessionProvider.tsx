import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { syntheticSessionAdapter, type SessionAdapter } from "../../adapters/sessionAdapter";
import { SessionError, type AppMenu, type ApplicationId, type SessionBootstrap } from "../../domain/session";

export type SessionState =
  | { status: "restoring" }
  | { status: "signedOut"; notice: "none" | "signedOut" | "expired" | "unavailable" | "passwordChanged" }
  | { status: "signedIn"; session: SessionBootstrap };

export interface SessionContextValue {
  state: SessionState;
  /** 失败时抛出 SessionError，由调用方（登录页）展示；成功后返回 bootstrap。 */
  login(username: string, password: string, tenantCode?: string): Promise<SessionBootstrap>;
  /** 当前适配器登录是否需要租户码。 */
  requiresTenantCode: boolean;
  logout(): Promise<void>;
  /** 当前适配器是否支持自助改密。 */
  canChangePassword: boolean;
  /** 成功后本地登录态清除并回到登录页（服务端已使所有旧会话失效）；失败抛 SessionError。 */
  changePassword(currentPassword: string, newPassword: string): Promise<void>;
  /** 服务端返回 401（会话失效或被撤销）时调用：清除本地登录态并回到登录页。 */
  expire(): void;
  loadMenu(applicationId: ApplicationId, signal?: AbortSignal): Promise<AppMenu>;
}

const SessionContext = createContext<SessionContextValue | null>(null);

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

export function SessionProvider({
  adapter = syntheticSessionAdapter,
  children,
}: {
  adapter?: SessionAdapter;
  children: ReactNode;
}) {
  const [state, setState] = useState<SessionState>({ status: "restoring" });
  // 用代际号丢弃过期的异步结果：恢复会话期间用户已登录/退出时，迟到的恢复结果不得覆盖新状态。
  const generation = useRef(0);

  useEffect(() => {
    const controller = new AbortController();
    const mine = ++generation.current;
    adapter
      .restore(controller.signal)
      .then((session) => {
        if (mine !== generation.current) return;
        setState(session ? { status: "signedIn", session } : { status: "signedOut", notice: "none" });
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || mine !== generation.current) return;
        setState({ status: "signedOut", notice: "unavailable" });
      });
    return () => controller.abort();
  }, [adapter]);

  const login = useCallback(
    async (username: string, password: string, tenantCode?: string) => {
      const session = await adapter.login({ username, password, tenantCode });
      generation.current += 1;
      setState({ status: "signedIn", session });
      return session;
    },
    [adapter],
  );

  const logout = useCallback(async () => {
    generation.current += 1;
    try {
      await adapter.logout();
    } catch {
      // 退出请求失败也必须清掉本地登录态，避免界面继续展示受保护内容；服务端会话由其自身过期处理。
    }
    setState({ status: "signedOut", notice: "signedOut" });
  }, [adapter]);

  const expire = useCallback(() => {
    generation.current += 1;
    setState({ status: "signedOut", notice: "expired" });
  }, []);

  const changePassword = useCallback(
    async (currentPassword: string, newPassword: string) => {
      if (!adapter.changePassword) throw new SessionError("unavailable", false);
      try {
        await adapter.changePassword(currentPassword, newPassword);
      } catch (error) {
        // 会话已失效：回到登录页，而不是停在对话框里报错。
        if (error instanceof SessionError && error.kind === "unauthenticated") expire();
        throw error;
      }
      generation.current += 1;
      setState({ status: "signedOut", notice: "passwordChanged" });
    },
    [adapter, expire],
  );

  const loadMenu = useCallback(
    async (applicationId: ApplicationId, signal?: AbortSignal) => {
      try {
        return await adapter.loadMenu(applicationId, signal);
      } catch (error) {
        // 服务端判定未登录（会话过期/被撤销）：回到登录页并提示，而不是停在受保护页面上报错。
        if (error instanceof SessionError && error.kind === "unauthenticated") expire();
        throw error;
      }
    },
    [adapter, expire],
  );

  const requiresTenantCode = adapter.requiresTenantCode === true;
  const canChangePassword = adapter.changePassword !== undefined;
  const value = useMemo(
    () => ({ state, login, logout, expire, loadMenu, requiresTenantCode, canChangePassword, changePassword }),
    [state, login, logout, expire, loadMenu, requiresTenantCode, canChangePassword, changePassword],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error("useSession must be used inside SessionProvider");
  return value;
}
