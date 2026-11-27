import { ThemeSelector } from "../theme/ThemeProvider";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useForm } from "react-hook-form";
import * as z from "zod/mini";
import { DemoLoginAccounts } from "./DemoLoginAccounts";
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

type LoginValues = { username: string; password: string; tenantCode: string };
const loginValues = z.custom<LoginValues>((data: unknown) =>
  typeof data === "object" && data !== null
  && "username" in data && typeof data.username === "string"
  && "password" in data && typeof data.password === "string"
  && "tenantCode" in data && typeof data.tenantCode === "string",
);

export function LoginPageCore({ notice, DemoAccounts }: { notice: Notice; DemoAccounts?: typeof DemoLoginAccounts }) {
  const { login, requiresTenantCode } = useSession();
  const { register, getValues, resetField, setValue } = useForm<LoginValues>({
    defaultValues: { tenantCode: "", username: "", password: "" },
  });
  const sending = useRef(false);
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
    if (sending.current || Date.now() < blockedUntil.current) return;
    // Empty values and whitespace are deliberately left to the existing session adapter.
    const values = loginValues.safeParse(getValues());
    if (!values.success) return;
    const { tenantCode, username, password } = values.data;
    sending.current = true;
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
      resetField("password");
      sending.current = false;
      setPending(false);
      usernameRef.current?.focus();
    }
  }

  const usernameInput = register("username");
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
                  {...register("tenantCode")}
                  type="text"
                  autoComplete="organization"
                  autoFocus
                  disabled={pending}
                />
              </label>
            )}
            <label className="login-field">
              <span>账号</span>
              <input
                {...usernameInput}
                ref={(element) => {
                  usernameInput.ref(element);
                  usernameRef.current = element;
                }}
                type="text"
                autoComplete="username"
                autoFocus={!requiresTenantCode}
                disabled={pending}
              />
            </label>
            <label className="login-field">
              <span>密码</span>
              <input
                {...register("password")}
                type="password"
                autoComplete="current-password"
                disabled={pending}
              />
            </label>
            <button className="button button-primary login-submit" type="submit" disabled={pending || remainingSeconds > 0}>
              {pending ? "登录中…" : remainingSeconds > 0 ? `等待 ${remainingSeconds} 秒` : "登录"}
            </button>
          </form>

          {!requiresTenantCode && DemoAccounts && <DemoAccounts pending={pending} onSelect={(name) => {
            setValue("username", name);
            usernameRef.current?.focus();
          }} />}
        </section>
      </div>
    </main>
  );
}

export function LoginPage({ notice }: { notice: Notice }) {
  return <LoginPageCore notice={notice} DemoAccounts={DemoLoginAccounts} />;
}
