import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { SessionError } from "../domain/session";
import { PASSWORD_LIMITS, validatePasswordChange, type PasswordChangeErrors } from "../domain/password";

interface ChangePasswordDialogProps {
  /** 成功时由会话层清除登录态并回到登录页，本对话框随之卸载；失败抛 SessionError。 */
  onSubmit: (currentPassword: string, newPassword: string) => Promise<void>;
  onClose: () => void;
}

const FAILURE_MESSAGE: Partial<Record<SessionError["kind"], string>> = {
  invalid_request: `新密码不符合要求：至少 ${PASSWORD_LIMITS.newMinCodePoints} 个字符，且不超过 ${PASSWORD_LIMITS.maxUtf8Bytes} 字节。`,
  stale_attempt: "校验已过期，请再提交一次。",
  forbidden: "当前账号无法修改密码。",
  unauthenticated: "登录已失效，请重新登录。",
  unavailable:
    "服务暂时不可用，修改结果尚未确认。如果之后发现旧密码已无法登录，说明新密码已生效，请用新密码登录。",
};

export function ChangePasswordDialog({ onSubmit, onClose }: ChangePasswordDialogProps) {
  const uid = useId();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [errors, setErrors] = useState<PasswordChangeErrors>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [blockedUntil, setBlockedUntil] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const currentRef = useRef<HTMLInputElement>(null);
  const mounted = useRef(true);
  const cardRef = useRef<HTMLElement>(null);

  useEffect(() => {
    mounted.current = true;
    // 打开前的焦点（头像菜单按钮）；关闭后还给它，键盘用户不会丢失位置。
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    currentRef.current?.focus();
    return () => {
      mounted.current = false;
      if (opener?.isConnected) opener.focus();
    };
  }, []);

  useEffect(() => {
    if (blockedUntil <= Date.now()) return;
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [blockedUntil]);
  const remaining = Math.max(0, Math.ceil((blockedUntil - now) / 1000));

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending || remaining > 0) return;
    const found = validatePasswordChange({ current, next, confirm });
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) return;
    setPending(true);
    try {
      await onSubmit(current, next);
      // 成功：会话层已回到登录页，本组件会被卸载。
    } catch (error) {
      if (!mounted.current) return;
      setPending(false);
      const kind = error instanceof SessionError ? error.kind : "unavailable";
      if (kind === "invalid_credentials") {
        setErrors({ current: "当前密码不正确" });
        setCurrent("");
        currentRef.current?.focus();
      } else if (kind === "invalid_request" && error instanceof SessionError && error.passwordFields.length) {
        const fields: PasswordChangeErrors = {};
        for (const field of error.passwordFields) {
          fields[field] = field === "current" ? "当前密码格式不符合要求" : FAILURE_MESSAGE.invalid_request;
        }
        setErrors(fields);
      } else if (kind === "rate_limited") {
        const seconds = error instanceof SessionError ? (error.retryAfterSeconds ?? 5) : 5;
        setBlockedUntil(Date.now() + seconds * 1000);
        setNow(Date.now());
        setFailure("尝试过于频繁，请稍后再试。");
      } else {
        setFailure(FAILURE_MESSAGE[kind] ?? FAILURE_MESSAGE.unavailable!);
      }
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "Escape" && !pending) {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== "Tab") return;
    // 焦点陷阱：Tab / Shift+Tab 只在对话框内循环，不落到背景页面。
    const focusable = Array.from(
      cardRef.current?.querySelectorAll<HTMLElement>("input:not(:disabled), button:not(:disabled)") ?? [],
    );
    const first = focusable[0];
    const last = focusable.at(-1);
    if (!first || !last) return;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  const field = (id: string, label: string, value: string, set: (v: string) => void, error: string | undefined, autoComplete: string, ref?: React.Ref<HTMLInputElement>) => (
    <div className="login-field">
      <label htmlFor={`${uid}-${id}`}>{label}</label>
      <input
        id={`${uid}-${id}`}
        ref={ref}
        type="password"
        autoComplete={autoComplete}
        value={value}
        disabled={pending}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${uid}-${id}-error` : undefined}
        onChange={(event) => set(event.target.value)}
      />
      {error && (
        <small className="field-error" id={`${uid}-${id}-error`}>
          {error}
        </small>
      )}
    </div>
  );

  return (
    <div className="modal-backdrop" onKeyDown={onKeyDown}>
      <section ref={cardRef} className="modal-card" role="dialog" aria-modal="true" aria-label="修改密码">
        <h2>修改密码</h2>
        <p className="field-hint">
          修改成功后，所有已登录的设备都需要用新密码重新登录。新密码至少 {PASSWORD_LIMITS.newMinCodePoints} 个字符，不超过{" "}
          {PASSWORD_LIMITS.maxUtf8Bytes} 字节；不会去除空格。
        </p>
        {failure && (
          <p className="login-error" role="alert">
            {failure}
            {remaining > 0 && `（${remaining} 秒后可再试）`}
          </p>
        )}
        <form onSubmit={handleSubmit} noValidate aria-busy={pending}>
          {field("current", "当前密码", current, setCurrent, errors.current, "current-password", currentRef)}
          {field("next", "新密码", next, setNext, errors.next, "new-password")}
          {field("confirm", "再次输入新密码", confirm, setConfirm, errors.confirm, "new-password")}
          <div className="report-actions">
            <button type="button" className="button button-subtle" onClick={onClose} disabled={pending}>
              取消
            </button>
            <button type="submit" className="button button-primary" disabled={pending || remaining > 0}>
              {pending ? "提交中…" : remaining > 0 ? `等待 ${remaining} 秒` : "修改密码"}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}
