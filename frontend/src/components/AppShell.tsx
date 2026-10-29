import { useState, type ReactNode } from "react";
import { RouteLink } from "../routing/router";

type Application = "safety" | "equipment" | "platform";

const appLinks: { app: Application; label: string; to: string }[] = [
  { app: "safety", label: "生产安全", to: "/safety/findings" },
  { app: "equipment", label: "设备管理", to: "/equipment/overview" },
  { app: "platform", label: "综合管理", to: "/platform/overview" },
];

interface AppShellProps {
  application: Application;
  currentPath: string;
  title: string;
  children: ReactNode;
}

function SafetySidebar({ currentPath }: { currentPath: string }) {
  const [hazardsOpen, setHazardsOpen] = useState(true);
  const isHazardsPage = ["/safety/findings", "/safety/inbox", "/safety/acceptance"].includes(currentPath);
  const isCurrent = (to: string) => currentPath === to;

  return (
    <aside className="sidebar">
      <div className="sidebar-title">安全管理</div>
      <nav aria-label="生产安全导航">
        <section className="nav-section">
          <button
            className={`nav-parent ${isHazardsPage ? "nav-parent-current" : ""}`}
            type="button"
            aria-expanded={hazardsOpen}
            onClick={() => setHazardsOpen((open) => !open)}
          >
            <span className="nav-glyph glyph-warning" aria-hidden="true">◇</span>
            <span>隐患治理</span>
            <span className="nav-chevron" aria-hidden="true">{hazardsOpen ? "⌄" : "›"}</span>
          </button>
          {hazardsOpen && (
            <div className="nav-children">
              <RouteLink className={`nav-child ${isCurrent("/safety/findings") ? "nav-current" : ""}`} to="/safety/findings" aria-current={isCurrent("/safety/findings") ? "page" : undefined}>
                隐患台账
              </RouteLink>
              <RouteLink className={`nav-child ${isCurrent("/safety/inbox") ? "nav-current" : ""}`} to="/safety/inbox" aria-current={isCurrent("/safety/inbox") ? "page" : undefined}>
                待确认
              </RouteLink>
              <RouteLink className={`nav-child ${isCurrent("/safety/acceptance") ? "nav-current" : ""}`} to="/safety/acceptance" aria-current={isCurrent("/safety/acceptance") ? "page" : undefined}>
                整改验收
              </RouteLink>
            </div>
          )}
        </section>
        <RouteLink className={`nav-link ${isCurrent("/safety/risks") ? "nav-current" : ""}`} to="/safety/risks" aria-current={isCurrent("/safety/risks") ? "page" : undefined}>
          <span className="nav-glyph glyph-risk" aria-hidden="true">◈</span>
          风险管控
        </RouteLink>
        <RouteLink className={`nav-link ${isCurrent("/safety/inspections") ? "nav-current" : ""}`} to="/safety/inspections" aria-current={isCurrent("/safety/inspections") ? "page" : undefined}>
          <span className="nav-glyph glyph-task" aria-hidden="true">▤</span>
          排查任务
        </RouteLink>
      </nav>
      <div className="sidebar-footer">
        <span className="demo-dot" aria-hidden="true" />
        前端合成数据
      </div>
    </aside>
  );
}

function SecondarySidebar({ application }: { application: Application }) {
  const label = application === "equipment" ? "设备管理" : "综合管理";
  return (
    <aside className="sidebar">
      <div className="sidebar-title">{label}</div>
      <p className="sidebar-placeholder">此应用目前仅有导航入口。</p>
    </aside>
  );
}

export function AppShell({ application, currentPath, title, children }: AppShellProps) {
  return (
    <div className="app-root">
      <header className="topbar">
        <RouteLink to="/safety/findings" className="brand" aria-label="MIGHT 工厂安全首页">
          <span className="brand-mark" aria-hidden="true">M</span>
          <span>MIGHT 工厂安全</span>
        </RouteLink>
        <nav className="application-switcher" aria-label="应用选择">
          {appLinks.map((appLink) => (
            <RouteLink
              className={`application-link ${application === appLink.app ? "application-current" : ""}`}
              aria-current={application === appLink.app ? "page" : undefined}
              key={appLink.app}
              to={appLink.to}
            >
              {appLink.label}
            </RouteLink>
          ))}
        </nav>
        <div className="topbar-right">
          <span className="demo-environment">合成演示</span>
          <span className="user-context">未连接业务账户</span>
        </div>
      </header>

      <div className="workspace">
        {application === "safety" ? <SafetySidebar currentPath={currentPath} /> : <SecondarySidebar application={application} />}
        <main className="main-region" aria-label={title}>
          {children}
        </main>
      </div>
    </div>
  );
}
