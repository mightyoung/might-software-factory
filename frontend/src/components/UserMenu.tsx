import { useEffect, useId, useRef, useState } from "react";

interface UserMenuProps {
  displayName: string;
  tenantName: string;
  onLogout: () => Promise<void> | void;
}

/** 头像入口：个人资料、个人会话尚未接入（禁用并说明），退出登录可用。点击、键盘均可操作。 */
export function UserMenu({ displayName, tenantName, onLogout }: UserMenuProps) {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
        buttonRef.current?.focus();
      }
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  async function handleLogout() {
    if (pending) return;
    setPending(true);
    await onLogout();
    // 退出后整个已登录界面会被卸载，不再回写本组件状态。
  }

  return (
    <div className="user-menu" ref={rootRef}>
      <button
        ref={buttonRef}
        type="button"
        className="user-menu-button"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => setOpen((value) => !value)}
      >
        <span className="user-avatar" aria-hidden="true">{displayName.slice(0, 1)}</span>
        <span className="user-name">{displayName}</span>
      </button>
      {open && (
        <div className="user-menu-panel" id={panelId} role="group" aria-label="账户">
          <div className="user-menu-identity">
            <strong>{displayName}</strong>
            <span>{tenantName}</span>
          </div>
          <button type="button" className="user-menu-item" disabled>
            个人资料<small>尚未接入</small>
          </button>
          <button type="button" className="user-menu-item" disabled>
            个人会话<small>尚未接入</small>
          </button>
          <button type="button" className="user-menu-item user-menu-logout" onClick={handleLogout} disabled={pending}>
            {pending ? "正在退出…" : "退出登录"}
          </button>
        </div>
      )}
    </div>
  );
}
