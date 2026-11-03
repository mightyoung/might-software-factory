import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { AppShell } from "./components/AppShell";
import { FindingsLedger } from "./features/findings/FindingsLedger";
import { FindingReportForm } from "./components/FindingReportForm";
import { LoginPage } from "./features/session/LoginPage";
import { SessionProvider, useSession } from "./features/session/SessionProvider";
import { useAppMenu } from "./features/session/useAppMenu";
import { hasPermission, type ApplicationId, type SessionBootstrap } from "./domain/session";
import { LOGIN_PATH, loginPathWithNext, parsePath, safeNextPath, tenantPath } from "./routing/paths";
import { navigate, useLocation } from "./routing/router";
import type { SessionAdapter } from "./adapters/sessionAdapter";
import { syntheticFindingStore } from "./adapters/syntheticFindingStore";
import type { FindingLedgerAdapter } from "./adapters/findingLedger";
import type { FindingCommandAdapter } from "./adapters/findingCommands";
import type { FindingSubmitAdapter } from "./adapters/findingSubmit";

/** 台账读取与上报提交的一对适配器；合成实现共享内存数据，HTTP 实现各自走服务端。 */
export interface FindingStore {
  ledger: FindingLedgerAdapter;
  submit: FindingSubmitAdapter;
  /** 流程命令；合成存储没有。 */
  commands?: FindingCommandAdapter;
}
import type { FindingView } from "./adapters/findingLedger";

interface LedgerPage {
  kind: "ledger";
  title: string;
  description: string;
  view: FindingView;
}

interface PlaceholderPage {
  kind: "placeholder";
  title: string;
  description: string;
}

interface RouteDef {
  /** 不含租户前缀的应用内路径。 */
  rest: string;
  application: ApplicationId;
  /** 仅用于界面呈现的权限摘要判断；服务端对每个请求仍须最终鉴权。 */
  permission: string;
  page: LedgerPage | PlaceholderPage;
}

const ROUTES: readonly RouteDef[] = [
  {
    rest: "safety/findings",
    application: "safety",
    permission: "safety.finding.read",
    page: { kind: "ledger", title: "隐患台账", description: "查询合成隐患样例，查看确认、派单、整改和验收节点。", view: "all" },
  },
  {
    rest: "safety/inbox",
    application: "safety",
    permission: "safety.inbox.read",
    page: { kind: "ledger", title: "待确认", description: "查看处于确认节点的合成样例。", view: "confirmation" },
  },
  {
    rest: "safety/acceptance",
    application: "safety",
    permission: "safety.acceptance.read",
    page: { kind: "ledger", title: "整改验收", description: "查看处于验收节点的合成样例。", view: "acceptance" },
  },
  {
    rest: "safety/risks",
    application: "safety",
    permission: "safety.risk.read",
    page: { kind: "placeholder", title: "风险管控", description: "首批交付只实现隐患台账与流程状态展示。" },
  },
  {
    rest: "safety/inspections",
    application: "safety",
    permission: "safety.inspection.read",
    page: { kind: "placeholder", title: "排查任务", description: "排查任务页面尚未接入；本页不生成或提交检查记录。" },
  },
  {
    rest: "equipment/overview",
    application: "equipment",
    permission: "equipment.overview.read",
    page: { kind: "placeholder", title: "设备管理", description: "首批只展示应用导航入口。" },
  },
  {
    rest: "platform/overview",
    application: "platform",
    permission: "platform.overview.read",
    page: { kind: "placeholder", title: "综合管理", description: "首批只展示应用导航入口。" },
  },
];

const APPLICATION_IDS: readonly string[] = ["safety", "equipment", "platform"];

function applicationOfPath(rest: string): ApplicationId | null {
  const first = rest.split("/")[0] ?? "";
  return APPLICATION_IDS.includes(first) ? (first as ApplicationId) : null;
}

function splitLocation(location: string): { pathname: string; search: string } {
  const index = location.indexOf("?");
  return index === -1
    ? { pathname: location || "/", search: "" }
    : { pathname: location.slice(0, index) || "/", search: location.slice(index) };
}

/** 已登录时需要先规范化的地址：登录页、根路径和没有租户前缀的旧路径。返回 null 表示无需跳转。 */
function canonicalTarget(session: SessionBootstrap, location: string): string | null {
  const { pathname, search } = splitLocation(location);
  const home = session.applications[0];

  if (pathname === LOGIN_PATH) {
    const next = safeNextPath(new URLSearchParams(search).get("next"), session.tenant.id);
    return next ?? (home ? tenantPath(session.tenant.id, home.entryPath) : tenantPath(session.tenant.id, ""));
  }
  const parsed = parsePath(pathname);
  if (parsed.tenantId === null) {
    if (parsed.rest === "") return home ? tenantPath(session.tenant.id, home.entryPath) : null;
    return `${tenantPath(session.tenant.id, parsed.rest)}${search}`;
  }
  if (parsed.tenantId === session.tenant.id && parsed.rest === "" && home) {
    return tenantPath(session.tenant.id, home.entryPath);
  }
  return null;
}

function StatusPage({ title, description, note }: { title: string; description: string; note?: string }) {
  return (
    <section className="placeholder-page">
      <h1>{title}</h1>
      <p>{description}</p>
      {note && <div className="placeholder-note">{note}</div>}
    </section>
  );
}

/** A create-only member never mounts the ledger or a detail-read action. */
function FindingReportPage({ adapter, onUnauthenticated }: { adapter: FindingSubmitAdapter; onUnauthenticated: () => void }) {
  const [open, setOpen] = useState(true);
  return <section className="ledger-main">
    <div className="page-heading"><h1>隐患上报</h1>{!open && <button type="button" className="button button-primary" onClick={() => setOpen(true)}>新建上报</button>}</div>
    <p>可上报你有权提交的隐患。</p>
    {open && <FindingReportForm adapter={adapter} onClose={() => setOpen(false)} onSubmitted={() => {}} onUnauthenticated={onUnauthenticated} />}
  </section>;
}

function AppRouter({ createFindingStore }: { createFindingStore: () => FindingStore }) {
  const { state, logout, expire, requiresTenantCode } = useSession();
  const location = useLocation();
  const { pathname } = splitLocation(location);
  const session = state.status === "signedIn" ? state.session : null;
  // 按身份和租户重建：适配器可能缓存 schema，不得跨账号或租户复用。
  const identityKey = session ? JSON.stringify([session.tenant.id, session.actor.id, session.identityScope?.accountId, session.identityScope?.memberId, [...session.permissions].sort(), session.applications.map((app) => [app.id, app.entryPath]).sort()]) : "";
  const previousIdentity = useRef("");
  const pendingSelectionReset = useRef(false);
  if (identityKey && previousIdentity.current && previousIdentity.current !== identityKey) pendingSelectionReset.current = true;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const findingStore = useMemo(() => createFindingStore(), [identityKey]);

  const parsed = parsePath(pathname);
  const redirect = session ? canonicalTarget(session, location) : null;
  const tenantMatches = session !== null && parsed.tenantId === session.tenant.id;
  const route = tenantMatches ? ROUTES.find((candidate) => candidate.rest === parsed.rest) : undefined;
  const pathApplication = tenantMatches ? applicationOfPath(parsed.rest) : null;
  const authorizedApplication =
    session && pathApplication && session.applications.some((app) => app.id === pathApplication) ? pathApplication : null;
  const shellApplication = redirect ? null : authorizedApplication;
  const menu = useAppMenu(shellApplication);
  const canReadRoute = session !== null && route !== undefined && hasPermission(session, route.permission);
  const canReportHere = session !== null && route?.rest === "safety/findings" && hasPermission(session, "safety.finding.report");
  const reportOnly = canReportHere && !canReadRoute;
  // Do not consume the reset at /login or an intermediate canonical redirect. Never
  // mount a new identity's reader while a previous identity's URL selection remains.
  const parameters = new URLSearchParams(splitLocation(location).search);
  let selectionResetTarget: string | null = null;
  if (session && !redirect && (pendingSelectionReset.current || reportOnly) && parameters.has("caseId")) {
    parameters.delete("caseId");
    const suffix = parameters.toString();
    selectionResetTarget = `${pathname}${suffix ? `?${suffix}` : ""}`;
  }
  const resetInitialSelection = pendingSelectionReset.current;
  useLayoutEffect(() => {
    if (identityKey) previousIdentity.current = identityKey;
    if (session && !redirect && selectionResetTarget === null) pendingSelectionReset.current = false;
  }, [identityKey, session, redirect, selectionResetTarget]);

  useEffect(() => {
    if (state.status === "signedOut") {
      const target = state.notice === "signedOut" ? LOGIN_PATH : loginPathWithNext(location);
      if (pathname !== LOGIN_PATH) navigate(target, true);
    } else if (redirect) {
      navigate(redirect, true);
    } else if (selectionResetTarget) {
      navigate(selectionResetTarget, true);
    }
  }, [state, location, pathname, redirect, selectionResetTarget]);

  if (state.status === "restoring") {
    return (
      <div className="restoring-page" role="status">
        正在恢复会话…
      </div>
    );
  }
  if (state.status === "signedOut") return <LoginPage notice={state.notice} />;
  if (!session || redirect || selectionResetTarget) return null;

  let title: string;
  let content: ReactNode;

  if (!tenantMatches) {
    title = "无权访问";
    content = (
      <StatusPage
        title="无权访问"
        description="你的账号无法访问此地址，或该地址不存在。"
        note="此提示仅用于界面呈现，实际访问权限由服务端校验。"
      />
    );
  } else if (!route) {
    title = session.applications.length === 0 ? "没有可用应用" : "页面未开放";
    content =
      session.applications.length === 0 ? (
        <StatusPage title="没有可用应用" description="当前账号尚未开通任何可进入的应用，请联系管理员。" />
      ) : (
        <StatusPage title="页面未开放" description="该路由不属于首批前端交付范围。" />
      );
  } else if (!authorizedApplication || (!canReadRoute && !canReportHere)) {
    title = "无权访问";
    content = (
      <StatusPage
        title="无权访问"
        description="你的账号没有访问此页面的权限，或页面不存在。"
        note="导航和此提示仅用于界面呈现，不是授权凭据；实际访问权限由服务端校验。"
      />
    );
  } else if (reportOnly) {
    title = "隐患上报";
    content = <FindingReportPage adapter={findingStore.submit} onUnauthenticated={expire} />;
  } else if (route.page.kind === "ledger") {
    title = route.page.title;
    content = (
      <FindingsLedger
        key={route.page.view}
        view={route.page.view}
        title={route.page.title}
        description={route.page.description}
        adapter={findingStore.ledger}
        synthetic={!requiresTenantCode}
        resetInitialSelection={resetInitialSelection}
        submitAdapter={findingStore.submit}
        commandAdapter={findingStore.commands}
        canReport={hasPermission(session, "safety.finding.report")}
        onUnauthenticated={expire}
      />
    );
  } else {
    title = route.page.title;
    content = (
      <StatusPage
        title={route.page.title}
        description={route.page.description}
        note="此处尚未接入业务数据或真实操作。"
      />
    );
  }

  return (
    // 以身份为 key：切换账号后丢弃上一个身份的页面状态（筛选、选中、详情）。
    <AppShell
      key={identityKey}
      session={session}
      application={authorizedApplication}
      currentRest={parsed.rest}
      menu={menu.state}
      onRetryMenu={menu.retry}
      onLogout={logout}
      title={title}
      synthetic={!requiresTenantCode}
    >
      {content}
    </AppShell>
  );
}

export function App({
  sessionAdapter,
  findingStore = syntheticFindingStore,
  createFindingStore,
}: {
  sessionAdapter?: SessionAdapter;
  /** 固定的适配器对（测试、合成演示）。 */
  findingStore?: FindingStore;
  /** 每个登录身份新建一对适配器；提供时优先于 findingStore。 */
  createFindingStore?: () => FindingStore;
}) {
  return (
    <SessionProvider adapter={sessionAdapter}>
      <AppRouter createFindingStore={createFindingStore ?? (() => findingStore)} />
    </SessionProvider>
  );
}
