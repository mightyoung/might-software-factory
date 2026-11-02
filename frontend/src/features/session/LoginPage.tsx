import { useEffect, useRef, useState, type FormEvent } from "react";
import { DEMO_ACCOUNTS } from "../../adapters/sessionAdapter";
import { SessionError } from "../../domain/session";
import { useSession, type SessionState } from "./SessionProvider";

type Notice = Extract<SessionState, { status: "signedOut" }>["notice"];

const NOTICE_TEXT: Record<Exclude<Notice, "none">, string> = {
  signedOut: "已退出登录。",
  expired: "登录已失效，请重新登录。",
  unavailable: "暂时无法确认登录状态，请稍后重试。",
};

function failureMessage(error: unknown): string {
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
  const [error, setError] = useState<string | null>(null);
  const mounted = useRef(true);
  const usernameRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    setPending(true);
    setError(null);
    try {
      await login(username, password, tenantCode);
      // 成功后 SessionProvider 切换为已登录，本组件会被卸载；返回地址由 App 统一处理。
    } catch (caught) {
      if (!mounted.current) return;
      setError(failureMessage(caught));
      setPassword("");
      setPending(false);
      usernameRef.current?.focus();
    }
  }

  return (
    <main className="login-page" aria-label="登录">
      <section className="login-card">
        <div className="login-brand">
          <span className="brand-mark" aria-hidden="true">M</span>
          <span>MIGHT 工厂安全</span>
        </div>
        <h1>登录</h1>

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
          <button className="button button-primary login-submit" type="submit" disabled={pending}>
            {pending ? "登录中…" : "登录"}
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
    </main>
  );
}
