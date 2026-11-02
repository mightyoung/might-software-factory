import { useState, type ReactNode } from "react";
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
import { UserMenu } from "./UserMenu";

const ICON_GLYPH: Record<MenuIcon, { glyph: string; className: string }> = {
  warning: { glyph: "◇", className: "glyph-warning" },
  risk: { glyph: "◈", className: "glyph-risk" },
  task: { glyph: "▤", className: "glyph-task" },
};

interface AppShellProps {
  /** 合成演示会话时为 true（默认）：显示“合成演示”标识；真实后端会话不显示。 */
  synthetic?: boolean;
  session: SessionBootstrap;
  /** 当前所在应用；为 null 时（如无权限页）不显示侧栏。 */
  application: ApplicationId | null;
  /** 不含租户前缀的当前路径，如 "safety/findings"。 */
  currentRest: string;
  menu: AppMenuState;
  onRetryMenu: () => void;
  onLogout: () => Promise<void> | void;
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
}: {
  synthetic: boolean;
  session: SessionBootstrap;
  menu: AppMenuState;
  currentRest: string;
  onRetryMenu: () => void;
}) {
  return (
    <aside className="sidebar">
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
          <span className="demo-dot" aria-hidden="true" />
          前端合成数据
        </div>
      )}
    </aside>
  );
}

export function AppShell({ session, application, currentRest, menu, onRetryMenu, onLogout, title, synthetic = true, children }: AppShellProps) {
  const home = session.applications[0];
  return (
    <div className="app-root">
      <header className="topbar">
        <RouteLink
          to={tenantPath(session.tenant.id, home?.entryPath ?? "")}
          className="brand"
          aria-label="MIGHT 工厂安全首页"
        >
          <span className="brand-mark" aria-hidden="true">M</span>
          <span>MIGHT 工厂安全</span>
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
          {synthetic && <span className="demo-environment">合成演示</span>}
          <UserMenu displayName={session.actor.displayName} tenantName={session.tenant.name} onLogout={onLogout} />
        </div>
      </header>

      <div className="workspace">
        {application && <Sidebar synthetic={synthetic} session={session} menu={menu} currentRest={currentRest} onRetryMenu={onRetryMenu} />}
        <main className="main-region" aria-label={title}>
          {children}
        </main>
      </div>
    </div>
  );
}
