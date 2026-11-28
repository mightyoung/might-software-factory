import { createBrowserHistory } from "@tanstack/react-router";
import { useEffect, useSyncExternalStore, type AnchorHTMLAttributes, type MouseEvent, type ReactNode } from "react";

// One history per browser document; raw search remains untouched for legacy deep links.
let browserHistory: ReturnType<typeof createBrowserHistory> | undefined;
export function getWorkspaceHistory() {
  return (browserHistory ??= createBrowserHistory());
}

function subscribe(listener: () => void): () => void {
  return getWorkspaceHistory().subscribe(listener);
}

function getLocation(): string {
  const { pathname, search } = getWorkspaceHistory().location;
  return `${pathname}${search}`;
}

export function useLocation(): string {
  return useSyncExternalStore(subscribe, getLocation, () => "/safety/findings");
}

export function navigate(to: string, replace = false): void {
  const target = new URL(to, window.location.href);
  if (target.origin !== window.location.origin) throw new Error("Expected an in-app route");
  const href = `${target.pathname}${target.search}${target.hash}`;
  const history = getWorkspaceHistory();
  if (replace) history.replace(href);
  else history.push(href);
  // Callers read window.location immediately after user navigation. Do not batch
  // a push and the following canonical replace into a different history shape.
  history.flush();
}

let tabOpener: (url: string) => void = (url) => { window.open(url, "_blank", "noopener"); };

/** 详情、新建上报、关联上报都在新标签页打开；测试可用 setTabOpener 换成原地跳转。 */
export function openInNewTab(url: string): void {
  tabOpener(url);
}

export function setTabOpener(opener: (url: string) => void, closer?: () => void): void {
  tabOpener = opener;
  if (closer) tabCloser = closer;
}

let tabCloser: () => void = () => window.close();

/** 独立标签页里的“关闭”：脚本打开的标签页能直接关掉；其余浏览器会忽略，调用方随后退回台账。 */
export function closeCurrentTab(): void {
  tabCloser();
}

interface RouteLinkProps extends AnchorHTMLAttributes<HTMLAnchorElement> {
  to: string;
  children: ReactNode;
  onNavigate?(): void;
}

export function RouteLink({ to, onClick, onNavigate, children, ...props }: RouteLinkProps) {
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
    onNavigate?.();
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
