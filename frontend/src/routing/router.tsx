import { useEffect, useSyncExternalStore, type AnchorHTMLAttributes, type MouseEvent, type ReactNode } from "react";

type Listener = () => void;
const listeners = new Set<Listener>();

function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  window.addEventListener("popstate", listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("popstate", listener);
  };
}

function getLocation(): string {
  return `${window.location.pathname}${window.location.search}`;
}

export function useLocation(): string {
  return useSyncExternalStore(subscribe, getLocation, () => "/safety/findings");
}

export function navigate(to: string, replace = false): void {
  if (replace) window.history.replaceState(null, "", to);
  else window.history.pushState(null, "", to);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

interface RouteLinkProps extends AnchorHTMLAttributes<HTMLAnchorElement> {
  to: string;
  children: ReactNode;
}

export function RouteLink({ to, onClick, children, ...props }: RouteLinkProps) {
  function handleClick(event: MouseEvent<HTMLAnchorElement>) {
    onClick?.(event);
    if (
      event.defaultPrevented ||
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey
    ) {
      return;
    }
    event.preventDefault();
    if (!confirmLeave()) return;
    navigate(to);
  }

  return (
    <a href={to} onClick={handleClick} {...props}>
      {children}
    </a>
  );
}

type LeaveGuard = () => boolean;
const leaveGuards = new Set<LeaveGuard>();

/** 用户主动点应用内链接离开前询问；系统发起的跳转（登录失效、规范化地址）用 navigate，不经过它。 */
function confirmLeave(): boolean {
  return [...leaveGuards].every((guard) => guard());
}

/**
 * 有未保存内容时，点击应用内链接（侧栏、顶栏）先确认。浏览器的前进 / 后退按钮拦不住（History API 没有可靠的取消办法），
 * 刷新或关闭标签页由调用方另用 beforeunload 处理。
 */
export function useLeaveGuard(active: boolean, message: string): void {
  useEffect(() => {
    if (!active) return;
    const guard: LeaveGuard = () => window.confirm(message);
    leaveGuards.add(guard);
    return () => {
      leaveGuards.delete(guard);
    };
  }, [active, message]);
}
