import { officeEditors, type OfficeEditorKind } from "../features/office/editors";
import { Text } from "@astryxdesign/core/Text";
import { lazy, Suspense, useEffect, useRef, useState, type ReactNode } from "react";
import { ThemeSelector, useTheme } from "../features/theme/ThemeProvider";
import type { AppMenuState } from "../features/session/useAppMenu";
import {
  visibleMenuNodes,
  type ApplicationId,
  type MenuIcon,
  type MenuNode,
  type SessionBootstrap,
} from "../domain/session";
import { RouteLink } from "../routing/router";
import { tenantPath } from "../routing/paths";
import { useAppMode } from "../mode";
import { UserMenu } from "./UserMenu";

const OfficeDialog = lazy(() => import("../features/office/OfficeDialog").then(module => ({ default: module.OfficeDialog })));

const sidebarKey = "might.sidebar.collapsed";

function sidebarPreference() {
  try { return localStorage.getItem(sidebarKey) === "1"; }
  catch { return false; }
}

function QuickNavigation({ session, menu, onClose }: Pick<AppShellProps, "session" | "menu"> & { onClose(): void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState("");
  useEffect(() => {
    const opener = document.activeElement;
    const modal = dialog.current!;
    modal.showModal();
    search.current?.focus();
    return () => {
      modal.close();
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  }, []);
  const applicationNames = new Map(session.applications.map(app => [app.entryPath, app.name]));
  const pages = new Map(applicationNames);
  if (menu.status === "ready") {
    const visible = visibleMenuNodes(menu.menu.nodes, session);
    for (const item of visible.flatMap(node => node.children ?? [node])) {
      if (item.path) pages.set(item.path, item.label);
    }
  }
  const term = query.trim().toLocaleLowerCase();
  const results = [...pages].filter(([path, label]) =>
    label.toLocaleLowerCase().includes(term) || applicationNames.get(path)?.toLocaleLowerCase().includes(term),
  );
  return <dialog ref={dialog} className="quick-navigation" aria-labelledby="quick-navigation-title"
    onCancel={event => { event.preventDefault(); onClose(); }}
    onKeyDown={event => {
      if (event.key !== "Tab") return;
      const items = event.currentTarget.querySelectorAll<HTMLElement>("input, a[href], button");
      const first = items[0], last = items[items.length - 1];
      if (document.activeElement === (event.shiftKey ? first : last)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      }
    }}>
    <h2 id="quick-navigation-title">快捷导航</h2>
    <input ref={search} type="search" aria-label="搜索页面" placeholder="输入台账或模块名称…" value={query}
      onChange={event => setQuery(event.target.value)} />
    {results.length ? <ul>{results.map(([path, label]) => <li key={path}>
      <RouteLink to={tenantPath(session.tenant.id, path)} onNavigate={onClose}>{label}</RouteLink>
    </li>)}</ul> : <p role="status">没有匹配的页面</p>}
    <button className="button" type="button" onClick={onClose}>关闭</button>
  </dialog>;
}

const ICON_GLYPH: Record<MenuIcon, { glyph: string; className: string }> = {
  warning: { glyph: "◇", className: "glyph-warning" },
  risk: { glyph: "◈", className: "glyph-risk" },
  task: { glyph: "▤", className: "glyph-task" },
};

interface AppShellProps {
  session: SessionBootstrap;
  /** 当前所在应用；为 null 时（如无权限页）不显示侧栏。 */
  application: ApplicationId | null;
  /** 不含租户前缀的当前路径，如 "safety/findings"。 */
  currentRest: string;
  menu: AppMenuState;
  onRetryMenu: () => void;
  onLogout: () => Promise<void> | void;
  /** 提供时头像菜单出现可用的“修改密码”。 */
  onChangePassword?: (currentPassword: string, newPassword: string) => Promise<void>;
  title: string;
  children: ReactNode;
}

function NavGroup({ node, tenantId, currentRest }: { node: MenuNode; tenantId: string; currentRest: string }) {
  const [open, setOpen] = useState(true);
  const children = node.children ?? [];
  const isCurrentGroup = children.some((child) => child.path === currentRest);
  const icon = node.icon ? ICON_GLYPH[node.icon] : null;

  return (
    <section className="nav-section">
      <button
        className={`nav-parent ${isCurrentGroup ? "nav-parent-current" : ""}`}
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        {icon && <span className={`nav-glyph ${icon.className}`} aria-hidden="true">{icon.glyph}</span>}
        <span>{node.label}</span>
        <span className="nav-chevron" aria-hidden="true">{open ? "⌄" : "›"}</span>
      </button>
      {open && (
        <div className="nav-children">
          {children.map((child) => (
            <NavLeaf key={child.id} node={child} tenantId={tenantId} currentRest={currentRest} nested />
          ))}
        </div>
      )}
    </section>
  );
}

function NavLeaf({
  node,
  tenantId,
  currentRest,
  nested = false,
}: {
  node: MenuNode;
  tenantId: string;
  currentRest: string;
  nested?: boolean;
}) {
  if (!node.path) return null;
  const current = currentRest === node.path;
  const icon = node.icon ? ICON_GLYPH[node.icon] : null;
  return (
    <RouteLink
      className={`${nested ? "nav-child" : "nav-link"} ${current ? "nav-current" : ""}`}
      to={tenantPath(tenantId, node.path)}
      aria-current={current ? "page" : undefined}
    >
      {icon && <span className={`nav-glyph ${icon.className}`} aria-hidden="true">{icon.glyph}</span>}
      {node.label}
    </RouteLink>
  );
}

function Sidebar({
  session,
  menu,
  currentRest,
  onRetryMenu,
  synthetic,
  collapsed,
}: {
  collapsed: boolean;
  synthetic: boolean;
  session: SessionBootstrap;
  menu: AppMenuState;
  currentRest: string;
  onRetryMenu: () => void;
}) {
  return (
    <aside className="sidebar" id="workspace-sidebar" hidden={collapsed}>
      {menu.status === "ready" ? (
        <>
          <div className="sidebar-title">{menu.menu.title}</div>
          <nav aria-label={`${menu.menu.title}导航`}>
            {visibleMenuNodes(menu.menu.nodes, session).map((node) =>
              node.children ? (
                <NavGroup key={node.id} node={node} tenantId={session.tenant.id} currentRest={currentRest} />
              ) : (
                <NavLeaf key={node.id} node={node} tenantId={session.tenant.id} currentRest={currentRest} />
              ),
            )}
          </nav>
        </>
      ) : menu.status === "error" ? (
        <div className="sidebar-status" role="alert">
          <p>{menu.kind === "forbidden" ? "没有此应用的导航权限。" : "导航加载失败。"}</p>
          {menu.kind === "unavailable" && (
            <button type="button" className="button button-subtle" onClick={onRetryMenu}>
              重试
            </button>
          )}
        </div>
      ) : (
        <div className="sidebar-status" role="status">导航加载中…</div>
      )}
      {synthetic && (
        <div className="sidebar-footer">
          前端合成数据
        </div>
      )}
    </aside>
  );
}

export function AppShell({ session, application, currentRest, menu, onRetryMenu, onLogout, onChangePassword, title, children }: AppShellProps) {
  const synthetic = useAppMode() === "synthetic";
  const [officeKind, setOfficeKind] = useState<OfficeEditorKind | null>(null);
  const [collapsed, setCollapsed] = useState(sidebarPreference);
  const [quickNavigation, setQuickNavigation] = useState(false);
  useEffect(() => {
    function openNavigation(event: KeyboardEvent) {
      if (event.defaultPrevented || event.repeat || event.isComposing || event.altKey || event.shiftKey
        || !(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== "k"
        || document.querySelector("dialog[open], [aria-modal=true]")) return;
      event.preventDefault();
      setQuickNavigation(true);
    }
    window.addEventListener("keydown", openNavigation);
    return () => window.removeEventListener("keydown", openNavigation);
  }, []);
  const { resolvedTheme } = useTheme();
  const home = session.applications[0];
  return (
    <div className="app-root">
      <header className="topbar">
        <RouteLink
          to={tenantPath(session.tenant.id, home?.entryPath ?? "")}
          className="brand"
          aria-label="MIGHT 工作台首页"
        >
          <span className="brand-mark" aria-hidden="true" />
          <Text as="span" type="inherit" color="inherit" className="brand-wordmark">MIGHT <span>工作台</span></Text>
        </RouteLink>
        <nav className="application-switcher" aria-label="应用选择">
          {session.applications.map((app) => (
            <RouteLink
              className={`application-link ${application === app.id ? "application-current" : ""}`}
              aria-current={application === app.id ? "page" : undefined}
              key={app.id}
              to={tenantPath(session.tenant.id, app.entryPath)}
            >
              {app.name}
            </RouteLink>
          ))}
        </nav>
        <div className="topbar-right">
          {application && <button className="button office-open" type="button" aria-controls="workspace-sidebar"
            aria-expanded={!collapsed} onClick={() => {
              const next = !collapsed;
              setCollapsed(next);
              try { localStorage.setItem(sidebarKey, next ? "1" : "0"); } catch { /* optional preference */ }
            }}>{collapsed ? "展开导航" : "收起导航"}</button>}
          <button className="button office-open" type="button" aria-keyshortcuts="Control+K Meta+K"
            onClick={() => setQuickNavigation(true)}>快捷导航</button>
          {(["sheet", "doc"] as const).map(kind => (
            <button className="button office-open" type="button" key={kind} onClick={() => setOfficeKind(kind)}>
              {officeEditors[kind].title}
            </button>
          ))}
          <ThemeSelector />
          {synthetic && <span className="demo-environment">合成演示</span>}
          <UserMenu displayName={session.actor.displayName} tenantName={session.tenant.name} onLogout={onLogout}
            onChangePassword={onChangePassword}
          />
        </div>
      </header>

      {quickNavigation && <QuickNavigation session={session} menu={menu} onClose={() => setQuickNavigation(false)} />}
      {officeKind && <Suspense fallback={<p role="status">正在加载{officeEditors[officeKind].title}…</p>}>
        <OfficeDialog kind={officeKind} theme={resolvedTheme} onClose={() => setOfficeKind(null)} />
      </Suspense>}
      <div className="workspace">
        {application && <Sidebar collapsed={collapsed} synthetic={synthetic} session={session} menu={menu} currentRest={currentRest} onRetryMenu={onRetryMenu} />}
        <main className="main-region" aria-label={title}>
          {children}
        </main>
      </div>
    </div>
  );
}
