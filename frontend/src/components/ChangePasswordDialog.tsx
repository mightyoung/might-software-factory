import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { useForm } from "react-hook-form";
import * as z from "zod/mini";
import { SessionError } from "../domain/session";
import { PASSWORD_LIMITS, validatePasswordChange, type PasswordChangeInput } from "../domain/password";

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

const names = ["current", "next", "confirm"] as const;
const schema = z.custom<PasswordChangeInput>((value: unknown) =>
  typeof value === "object" && value !== null && names.every((key) =>
    key in value && typeof (value as Record<string, unknown>)[key] === "string",
  ),
).check(
  z.superRefine((input, ctx) => {
    const errors = validatePasswordChange(input);
    for (const name of names) {
      const message = errors[name];
      if (message) ctx.addIssue({ code: "custom", path: [name], message });
    }
  }),
);

export function ChangePasswordDialog({ onSubmit, onClose }: ChangePasswordDialogProps) {
  const uid = useId();
  const { register, getValues, setError, clearErrors, resetField, formState: { errors } } = useForm<PasswordChangeInput>({
    defaultValues: { current: "", next: "", confirm: "" },
  });
  const sending = useRef(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [blockedUntil, setBlockedUntil] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const currentRef = useRef<HTMLInputElement | null>(null);
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
    if (sending.current || remaining > 0) return;
    const result = schema.safeParse(getValues());
    clearErrors();
    setFailure(null);
    if (!result.success) {
      for (const issue of result.error.issues) {
        const field = names.find((name) => issue.path[0] === name);
        if (field) setError(field, { type: "validation", message: issue.message });
      }
      return;
    }
    const { current, next } = result.data;
    sending.current = true;
    setPending(true);
    try {
      await onSubmit(current, next);
      // 成功：会话层已回到登录页，本组件会被卸载。
    } catch (error) {
      if (!mounted.current) return;
      sending.current = false;
      setPending(false);
      const kind = error instanceof SessionError ? error.kind : "unavailable";
      if (kind === "invalid_credentials") {
        resetField("current");
        setError("current", { type: "server", message: "当前密码不正确" }, { shouldFocus: true });
      } else if (kind === "invalid_request" && error instanceof SessionError && error.passwordFields.length) {
        for (const field of error.passwordFields) {
          setError(field, { type: "server", message: field === "current" ? "当前密码格式不符合要求" : FAILURE_MESSAGE.invalid_request });
        }
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

  const field = (id: keyof PasswordChangeInput, label: string, autoComplete: string) => {
    const error = errors[id]?.message;
    const registration = register(id);
    return (
    <div className="login-field">
      <label htmlFor={`${uid}-${id}`}>{label}</label>
      <input
        id={`${uid}-${id}`}
        {...registration}
        ref={(element) => {
          registration.ref(element);
          if (id === "current") currentRef.current = element;
        }}
        type="password"
        autoComplete={autoComplete}
        disabled={pending}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${uid}-${id}-error` : undefined}
      />
      {error && (
        <small className="field-error" id={`${uid}-${id}-error`}>
          {error}
        </small>
      )}
    </div>
    );
  };

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
          {field("current", "当前密码", "current-password")}
          {field("next", "新密码", "new-password")}
          {field("confirm", "再次输入新密码", "new-password")}
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
