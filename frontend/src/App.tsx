import { useEffect } from "react";
import { AppShell } from "./components/AppShell";
import { FindingsLedger } from "./features/findings/FindingsLedger";
import { navigate, useLocation } from "./routing/router";
import type { FindingView } from "./adapters/findingLedger";

type Application = "safety" | "equipment" | "platform";

interface LedgerRoute {
  kind: "ledger";
  title: string;
  description: string;
  view: FindingView;
}

interface PlaceholderRoute {
  kind: "placeholder";
  title: string;
  description: string;
}

type AppRoute = LedgerRoute | PlaceholderRoute;

function resolveApplication(path: string): Application {
  if (path.startsWith("/equipment")) return "equipment";
  if (path.startsWith("/platform")) return "platform";
  return "safety";
}

function resolveRoute(path: string): AppRoute {
  if (path === "/" || path === "/safety/findings") {
    return {
      kind: "ledger",
      title: "隐患台账",
      description: "查询合成隐患样例，查看确认、派单、整改和验收节点。",
      view: "all",
    };
  }
  if (path === "/safety/inbox") {
    return {
      kind: "ledger",
      title: "待确认",
      description: "查看处于确认节点的合成样例。",
      view: "confirmation",
    };
  }
  if (path === "/safety/acceptance") {
    return {
      kind: "ledger",
      title: "整改验收",
      description: "查看处于验收节点的合成样例。",
      view: "acceptance",
    };
  }
  if (path === "/safety/risks") {
    return { kind: "placeholder", title: "风险管控", description: "首批交付只实现隐患台账与流程状态展示。" };
  }
  if (path === "/safety/inspections") {
    return { kind: "placeholder", title: "排查任务", description: "排查任务页面尚未接入；本页不生成或提交检查记录。" };
  }
  if (path.startsWith("/equipment")) {
    return { kind: "placeholder", title: "设备管理", description: "首批只展示应用导航入口。" };
  }
  if (path.startsWith("/platform")) {
    return { kind: "placeholder", title: "综合管理", description: "首批只展示应用导航入口。" };
  }
  return { kind: "placeholder", title: "页面未开放", description: "该路由不属于首批前端交付范围。" };
}

function PlaceholderPage({ route }: { route: PlaceholderRoute }) {
  return (
    <section className="placeholder-page">
      <h1>{route.title}</h1>
      <p>{route.description}</p>
      <div className="placeholder-note">此处尚未接入业务数据或真实操作。</div>
    </section>
  );
}

export function App() {
  const location = useLocation();
  const currentPath = location.split("?")[0] || "/";
  const path = currentPath === "/" ? "/safety/findings" : currentPath;
  const application = resolveApplication(path);
  const route = resolveRoute(path);

  useEffect(() => {
    if (currentPath === "/" && window.location.pathname !== path) navigate(path, true);
  }, [currentPath, path]);

  return (
    <AppShell application={application} currentPath={path} title={route.title}>
      {route.kind === "ledger" ? (
        <FindingsLedger
          key={route.view}
          view={route.view}
          title={route.title}
          description={route.description}
        />
      ) : (
        <PlaceholderPage route={route} />
      )}
    </AppShell>
  );
}
