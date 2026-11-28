import { createBrowserHistory } from "@tanstack/react-router";
import {
  createContext, useContext, useEffect, useLayoutEffect, useState, useSyncExternalStore,
  type AnchorHTMLAttributes, type Dispatch, type MouseEvent, type ReactNode, type SetStateAction,
} from "react";
import { WorkbenchConfirmDialog } from "../components/WorkbenchConfirmDialog";

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

type LeaveGuard = { message: string };
const leaveGuards = new Set<LeaveGuard>();
type LeaveRequest = {
  from: string;
  to: string;
  guards: LeaveGuard[];
  onNavigate?: () => void;
};
const LeaveConfirmation = createContext<Dispatch<SetStateAction<LeaveRequest | null>> | null>(null);

/** One host, outside the quick navigation dialog, prevents Escape or Tab reaching that dialog. */
export function RouteLeaveProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<LeaveRequest | null>(null);
  useEffect(() => subscribe(() => setPending(null)), []);

  function confirm() {
    setPending(null);
    if (!pending || window.location.href !== pending.from || pending.guards.length !== leaveGuards.size
      || !pending.guards.every(guard => leaveGuards.has(guard))) return;
    navigate(pending.to);
    pending.onNavigate?.();
  }

  return (
    <LeaveConfirmation.Provider value={setPending}>
      {children}
      {pending && <WorkbenchConfirmDialog
        title="离开当前页面？"
        description={pending.guards.map(guard => guard.message).join(" ")}
        confirmLabel="确认离开"
        onCancel={() => setPending(null)}
        onConfirm={confirm}
      />}
    </LeaveConfirmation.Provider>
  );
}

interface RouteLinkProps extends AnchorHTMLAttributes<HTMLAnchorElement> {
  to: string;
  children: ReactNode;
  onNavigate?(): void;
}

export function RouteLink({ to, onClick, onNavigate, children, ...props }: RouteLinkProps) {
  const requestLeave = useContext(LeaveConfirmation);
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
    if (leaveGuards.size !== 0) {
      const request = { from: window.location.href, to, onNavigate, guards: [...leaveGuards] };
      requestLeave?.(current => current ?? request);
      return;
    }
    navigate(to);
    onNavigate?.();
  }

  return (
    <a href={to} onClick={handleClick} {...props}>
      {children}
    </a>
  );
}

/** Only app links ask; system redirects and browser history retain their original behavior. */
export function useLeaveGuard(active: boolean, message: string): void {
  useLayoutEffect(() => {
    if (!active) return;
    const guard = { message };
    leaveGuards.add(guard);
    return () => {
      leaveGuards.delete(guard);
    };
  }, [active, message]);
}
