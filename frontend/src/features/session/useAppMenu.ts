import { useCallback, useEffect, useState } from "react";
import { SessionError, type AppMenu, type ApplicationId } from "../../domain/session";
import { isAbortError, useSession } from "./SessionProvider";

export type AppMenuState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; menu: AppMenu }
  | { status: "error"; kind: "forbidden" | "unavailable" };

/** 进入应用时按需取该应用的菜单；切换应用会取消上一个未完成的请求，迟到的响应不会覆盖新应用。 */
export function useAppMenu(applicationId: ApplicationId | null): { state: AppMenuState; retry: () => void } {
  const { loadMenu } = useSession();
  const [state, setState] = useState<AppMenuState>({ status: "idle" });
  const [attempt, setAttempt] = useState(0);
  const retry = useCallback(() => setAttempt((value) => value + 1), []);

  useEffect(() => {
    if (!applicationId) {
      setState({ status: "idle" });
      return;
    }
    const controller = new AbortController();
    setState({ status: "loading" });
    loadMenu(applicationId, controller.signal)
      .then((menu) => {
        if (!controller.signal.aborted) setState({ status: "ready", menu });
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || controller.signal.aborted) return;
        // unauthenticated 已由 SessionProvider 处理为回到登录页，这里不再展示菜单错误。
        if (error instanceof SessionError && error.kind === "unauthenticated") return;
        setState({ status: "error", kind: error instanceof SessionError && error.kind === "forbidden" ? "forbidden" : "unavailable" });
      });
    return () => controller.abort();
  }, [applicationId, loadMenu, attempt]);

  return { state, retry };
}
