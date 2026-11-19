import { ThemeSelector } from "../theme/ThemeProvider";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { DEMO_ACCOUNTS } from "../../adapters/sessionAdapter";
import { SessionError } from "../../domain/session";
import { useSession, type SessionState } from "./SessionProvider";

type Notice = Extract<SessionState, { status: "signedOut" }>["notice"];

const NOTICE_TEXT: Record<Exclude<Notice, "none">, string> = {
  signedOut: "已退出登录。",
  expired: "登录已失效，请重新登录。",
  unavailable: "暂时无法确认登录状态，请稍后重试。",
  forbidden: "当前账号暂时无法登录，请联系管理员。",
  passwordChanged: "密码已修改。请使用新密码重新登录。",
};

function failureMessage(error: unknown): string {
  if (error instanceof SessionError && error.kind === "rate_limited") return "登录请求过于频繁，请稍后重试。";
  if (error instanceof SessionError && error.kind === "forbidden") return "当前账号暂时无法登录。";
  if (error instanceof SessionError && error.kind === "unavailable") return "服务暂时不可用，请稍后重试。";
  // 账号不存在与密码错误使用同一提示，不暴露账号是否存在。
  return "账号或密码不正确。";
}

export function LoginPage({ notice }: { notice: Notice }) {
  const { login, requiresTenantCode } = useSession();
  const [tenantCode, setTenantCode] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState(false);
  const [retryUntil, setRetryUntil] = useState<number | null>(null);
  const [remainingSeconds, setRemainingSeconds] = useState(0);
  const blockedUntil = useRef(0);
  const [error, setError] = useState<string | null>(null);
  const mounted = useRef(true);
  const usernameRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (retryUntil === null) return;
    const update = () => {
      const remaining = Math.max(0, Math.ceil((retryUntil - Date.now()) / 1000));
      setRemainingSeconds(remaining);
      if (remaining === 0) setRetryUntil(null);
    };
    const timer = window.setInterval(update, 250);
    return () => window.clearInterval(timer);
  }, [retryUntil]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending || Date.now() < blockedUntil.current) return;
    setPending(true);
    setError(null);
    try {
      await login(username, password, tenantCode);
      // 成功后 SessionProvider 切换为已登录，本组件会被卸载；返回地址由 App 统一处理。
    } catch (caught) {
      if (!mounted.current) return;
      setError(failureMessage(caught));
      if (caught instanceof SessionError && caught.kind === "rate_limited") {
        // Missing/invalid Retry-After gets a short local pause, never an automatic retry.
        const seconds = caught.retryAfterSeconds ?? 5;
        blockedUntil.current = Date.now() + seconds * 1000;
        setRemainingSeconds(seconds);
        setRetryUntil(blockedUntil.current);
      }
      setPassword("");
      setPending(false);
      usernameRef.current?.focus();
    }
  }

  return (
    <main className="login-page" aria-label="登录">
      <div className="login-theme"><ThemeSelector /></div>
      <div className="login-layout">
        <header className="login-identity">
          <div className="login-brand">
            <span className="brand-mark" aria-hidden="true" />
            <span className="login-wordmark">MIGHT <span>工厂安全</span></span>
          </div>
        </header>
        <section className="login-card" aria-labelledby="login-title">
          <h1 id="login-title">登录</h1>

          {notice !== "none" && !error && (
            <p className="login-notice" role="status">
              {NOTICE_TEXT[notice]}
            </p>
          )}
          {error && (
            <p className="login-error" role="alert">
              {error}
            </p>
          )}

          <form onSubmit={handleSubmit} noValidate>
            {requiresTenantCode && (
              <label className="login-field">
                <span>租户编码</span>
                <input
                  name="tenantCode"
                  type="text"
                  autoComplete="organization"
                  autoFocus
                  value={tenantCode}
                  onChange={(event) => setTenantCode(event.target.value)}
                  disabled={pending}
                />
              </label>
            )}
            <label className="login-field">
              <span>账号</span>
              <input
                ref={usernameRef}
                name="username"
                type="text"
                autoComplete="username"
                autoFocus={!requiresTenantCode}
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                disabled={pending}
              />
            </label>
            <label className="login-field">
              <span>密码</span>
              <input
                name="password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                disabled={pending}
              />
            </label>
            <button className="button button-primary login-submit" type="submit" disabled={pending || remainingSeconds > 0}>
              {pending ? "登录中…" : remainingSeconds > 0 ? `等待 ${remainingSeconds} 秒` : "登录"}
            </button>
          </form>

          {!requiresTenantCode && (
          <aside className="login-demo" aria-label="合成演示说明">
            <p>
              <strong>合成演示：</strong>
              账号只认下列演示账号，密码任意非空即可。这不是真实认证，不提供任何安全保证，刷新页面后需重新登录。
            </p>
            <ul>
              {Object.entries(DEMO_ACCOUNTS).map(([name, account]) => (
                <li key={name}>
                  <button
                    type="button"
                    className="login-demo-fill"
                    onClick={() => {
                      setUsername(name);
                      usernameRef.current?.focus();
                    }}
                    disabled={pending}
                  >
                    {name}
                  </button>
                  <span>{account.description}</span>
                </li>
              ))}
            </ul>
          </aside>
          )}
        </section>
      </div>
    </main>
  );
}
