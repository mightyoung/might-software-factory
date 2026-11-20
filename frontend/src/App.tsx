import { lazy, Suspense, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { AppShell } from "./components/AppShell";
import { FindingsLedger } from "./features/findings/FindingsLedger";
import { FindingReportForm } from "./components/FindingReportForm";
import { LoginPage } from "./features/session/LoginPage";
import { SessionProvider, useSession } from "./features/session/SessionProvider";
import { useAppMenu } from "./features/session/useAppMenu";
import { hasPermission, type ApplicationId, type SessionBootstrap } from "./domain/session";
import { LOGIN_PATH, loginPathWithNext, parsePath, safeNextPath, tenantPath } from "./routing/paths";
import { navigate, useLocation } from "./routing/router";
import { draftOwnerKey } from "./features/session/draftStash";
import { ModeProvider, useAppMode, type AppMode } from "./mode";
import type { SessionAdapter } from "./adapters/sessionAdapter";
import type { PermissionAdminAdapter } from "./adapters/permissionAdmin";
import { createSyntheticPermissionAdmin } from "./adapters/syntheticPermissionAdmin";
import { syntheticFindingStore } from "./adapters/syntheticFindingStore";
import type { FindingLedgerAdapter } from "./adapters/findingLedger";
import type { FindingCommandAdapter } from "./adapters/findingCommands";
import type { FindingEvidenceAdapter } from "./adapters/findingEvidence";
import type { FindingSubmitAdapter } from "./adapters/findingSubmit";
import { LoanApplicationsPage } from "./features/toolLoans/LoanApplicationsPage";
import type { LoanFactory } from "./features/toolLoans/model";
import { parseEntityRoute } from "./routing/entityPaths";
import { EntityLedgerPage } from "./features/ledger/EntityLedgerPage";
import type { EntityLedgerFactory } from "./features/ledger/model";

const PermissionConfigPage = lazy(() => import("./features/platform/PermissionConfigPage").then(module => ({ default: module.PermissionConfigPage })));
const LedgerConfigPage = lazy(() => import("./features/ledgerConfig/LedgerConfigPage"));

/** 台账读取与上报提交的一对适配器；合成实现共享内存数据，HTTP 实现各自走服务端。 */
export interface FindingStore {
  ledger: FindingLedgerAdapter;
  submit: FindingSubmitAdapter;
  /** 流程命令；合成存储没有。 */
  commands?: FindingCommandAdapter;
  /** 整改证据上传；合成存储没有。 */
  evidence?: FindingEvidenceAdapter;
}
import type { FindingView } from "./adapters/findingLedger";

interface LedgerPage {
  kind: "ledger";
  title: string;
  description: string;
  /** 真实后端模式下的说明；缺省沿用 description。 */
  liveDescription?: string;
  view: FindingView;
}

interface PlaceholderPage {
  kind: "placeholder";
  title: string;
  description: string;
}

interface PermissionsPage {
  kind: "permissions" | "ledger-configurations";
  title: string;
  description: string;
}

interface RouteDef {
  /** 不含租户前缀的应用内路径。 */
  rest: string;
  application: ApplicationId;
  /** 仅用于界面呈现的权限摘要判断；服务端对每个请求仍须最终鉴权。 */
  permission: string;
  page: LedgerPage | PlaceholderPage | PermissionsPage;
}

const ROUTES: readonly RouteDef[] = [
  {
    rest: "platform/ledger-configurations",
    application: "platform",
    permission: "ledger.configuration.read",
    page: { kind: "ledger-configurations", title: "台账配置", description: "配置台账结构与版本。" },
  },
  {
    rest: "safety/findings",
    application: "safety",
    permission: "safety.finding.read",
    page: { kind: "ledger", title: "隐患台账", description: "查询合成隐患样例，查看确认、派单、整改和验收节点。", liveDescription: "查询隐患记录，查看确认、派单、整改和验收节点。", view: "all" },
  },
  {
    rest: "safety/inbox",
    application: "safety",
    permission: "safety.inbox.read",
    page: { kind: "ledger", title: "待确认", description: "查看处于确认节点的合成样例。", liveDescription: "查看处于确认节点的隐患记录。", view: "confirmation" },
  },
  {
    rest: "safety/acceptance",
    application: "safety",
    permission: "safety.acceptance.read",
    page: { kind: "ledger", title: "整改验收", description: "查看处于验收节点的合成样例。", liveDescription: "查看处于验收节点的隐患记录。", view: "acceptance" },
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
    rest: "platform/permissions",
    application: "platform",
    permission: "platform.permission.read",
    page: { kind: "permissions", title: "权限配置", description: "按角色配置可使用的功能权限。" },
  },
];

function applicationOfPath(rest: string): ApplicationId | null {
  const entity = parseEntityRoute(rest);
  if (entity) return entity.appKey;
  const first = rest.split("/")[0] ?? "";
  return /^[a-z][a-z0-9_-]{0,63}$/.test(first) ? first : null;
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
function FindingReportPage({ adapter, draftKey, onUnauthenticated }: { adapter: FindingSubmitAdapter; draftKey: string; onUnauthenticated: () => void }) {
  const [open, setOpen] = useState(true);
  return <section className="ledger-main">
    <div className="page-heading"><h1>隐患上报</h1>{!open && <button type="button" className="button button-primary" onClick={() => setOpen(true)}>新建上报</button>}</div>
    <p>可上报你有权提交的隐患。</p>
    {open && <FindingReportForm adapter={adapter} draftKey={draftKey} onClose={() => setOpen(false)} onSubmitted={() => {}} onUnauthenticated={onUnauthenticated} />}
  </section>;
}

// Browser traversal cannot be cancelled reliably. Retain the editor in memory
// after its first visit, only within this authorized AppShell identity.
function LedgerConfigurationHost({ active, canManage, canPublish, onUnauthenticated }: {
  active: boolean;
  canManage: boolean;
  canPublish: boolean;
  onUnauthenticated: () => void;
}) {
  const [visited, setVisited] = useState(active);
  useLayoutEffect(() => {
    if (active) setVisited(true);
  }, [active]);
  if (!active && !visited) return null;
  return (
    <div hidden={!active}>
      <Suspense fallback={<p role="status">正在载入配置页面…</p>}>
        <LedgerConfigPage
          active={active}
          canManage={canManage}
          canPublish={canPublish}
          onUnauthenticated={onUnauthenticated}
        />
      </Suspense>
    </div>
  );
}

interface AppAdapterFactories {
  createFindingStore: () => FindingStore;
  createPermissionAdmin: () => PermissionAdminAdapter;
  createEntityLedger?: EntityLedgerFactory;
  createLoans?: LoanFactory;
}

function AppRouter({ createFindingStore, createPermissionAdmin, createEntityLedger, createLoans }: AppAdapterFactories) {
  const { state, logout, refresh, expire, canChangePassword, changePassword } = useSession();
  const mode = useAppMode();
  const location = useLocation();
  const { pathname } = splitLocation(location);
  const session = state.status === "signedIn" ? state.session : null;
  // 按身份和租户重建：适配器可能缓存 schema，不得跨账号或租户复用。
  const identityKey = session ? JSON.stringify([session.tenant.id, session.actor.id, session.identityScope?.accountId, session.identityScope?.memberId, [...session.permissions].sort(), [...(session.canonicalPermissions ?? [])].sort(), session.applications.map((app) => [app.id, app.entryPath]).sort()]) : "";
  const previousIdentity = useRef("");
  const pendingSelectionReset = useRef(false);
  if (identityKey && previousIdentity.current && previousIdentity.current !== identityKey) pendingSelectionReset.current = true;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const findingStore = useMemo(() => createFindingStore(), [identityKey]);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const permissionAdmin = useMemo(() => createPermissionAdmin(), [identityKey]);

  const parsed = parsePath(pathname);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const loanAdapter = useMemo(() => createLoans?.() ?? null, [identityKey]);
  const entityRoute = parseEntityRoute(parsed.rest);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const entityAdapter = useMemo(() => entityRoute && createEntityLedger ? createEntityLedger(entityRoute.appKey, entityRoute.entityKey) : null, [identityKey, entityRoute?.appKey, entityRoute?.entityKey]);
  const redirect = session ? canonicalTarget(session, location) : null;
  const tenantMatches = session !== null && parsed.tenantId === session.tenant.id;
  const route = tenantMatches ? ROUTES.find((candidate) => candidate.rest === parsed.rest) : undefined;
  const pathApplication = tenantMatches ? applicationOfPath(parsed.rest) : null;
  const authorizedApplication =
    session && pathApplication && session.applications.some((app) => app.id === pathApplication) ? pathApplication : null;
  const shellApplication = redirect ? null : authorizedApplication;
  const menu = useAppMenu(shellApplication);
  const canReadRoute = session !== null && route !== undefined && (
    hasPermission(session, route.permission) || (route.page.kind === "ledger-configurations" &&
      ["ledger.configuration.manage", "ledger.configuration.publish"].some(code => hasPermission(session, code)))
  );
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
  if (!session) return null;
  // A same-identity canonical replace is not a session boundary.
  const routingTransition = Boolean(redirect || selectionResetTarget);

  const canManageLedgerConfig = hasPermission(session, "ledger.configuration.manage");
  const canPublishLedgerConfig = hasPermission(session, "ledger.configuration.publish");
  const canKeepLedgerConfig = mode === "http" &&
    session.applications.some(app => app.id === "platform") &&
    (hasPermission(session, "ledger.configuration.read") || canManageLedgerConfig || canPublishLedgerConfig);

  let title: string;
  let content: ReactNode;

  if (routingTransition) {
    title = "";
    content = null;
  } else if (!tenantMatches) {
    title = "无权访问";
    content = (
      <StatusPage
        title="无权访问"
        description="你的账号无法访问此地址，或该地址不存在。"
        note="此提示仅用于界面呈现，实际访问权限由服务端校验。"
      />
    );
  } else if (!route && !entityRoute) {
    title = session.applications.length === 0 ? "没有可用应用" : "页面未开放";
    content =
      session.applications.length === 0 ? (
        <StatusPage title="没有可用应用" description="当前账号尚未开通任何可进入的应用，请联系管理员。" />
      ) : (
        <StatusPage title="页面未开放" description="该路由不属于首批前端交付范围。" />
      );
  } else if (entityRoute?.appKey === "tooling" && ["tool_loan_department","tool_loan_custody"].includes(entityRoute.entityKey) && entityRoute.page === "entry") {
    const visibility = entityRoute.entityKey === "tool_loan_department" ? "department" : "custody";
    title = visibility === "department" ? "部门借用单" : "保管范围借用单";
    const grants = session.canonicalPermissions ?? session.permissions;
    content = !authorizedApplication || !grants.includes(`${entityRoute.entityKey}.read`)
      ? <StatusPage title="无权访问" description="当前账号没有此借用单查看权限。" />
      : !loanAdapter || mode !== "http" ? <StatusPage title="需连接后端" description="请连接后端后查看借用单。" />
        : <LoanApplicationsPage key={JSON.stringify([identityKey,visibility])} visibility={visibility} adapter={loanAdapter} owner={draftOwnerKey(session.tenant.id, session.actor.id)} identityScope={session.identityScope} permissions={grants} onUnauthenticated={expire} />;
  } else if (entityRoute?.appKey === "tooling" && entityRoute.entityKey === "tool_loan" && entityRoute.page === "entry") {
    title = "工装借用申请";
    const grants = session.canonicalPermissions ?? session.permissions;
    content = !authorizedApplication || !grants.some(code => ["tool_loan.read", "tool_loan.create", "tool_loan.update", "tool_loan.issue", "tool_loan.receive", "tool_loan.close_lost"].includes(code))
      ? <StatusPage title="无权访问" description="当前账号没有借用申请权限。" />
      : !loanAdapter || mode !== "http" ? <StatusPage title="需连接后端" description="请连接后端后使用借用申请。" />
        : <LoanApplicationsPage key={identityKey} adapter={loanAdapter} owner={draftOwnerKey(session.tenant.id, session.actor.id)} identityScope={session.identityScope} permissions={grants} onUnauthenticated={expire} />;
  } else if (entityRoute) {
    title = "通用台账";
    content = !authorizedApplication ? <StatusPage title="无权访问" description="当前账号没有此应用的访问权限。" />
      : mode !== "http" || !entityAdapter ? <StatusPage title="需连接后端" description="此台账使用已发布的服务端定义，请连接后端后使用。" />
        : <EntityLedgerPage key={JSON.stringify([identityKey, entityRoute.appKey, entityRoute.entityKey, entityRoute.page, entityRoute.id])} adapter={entityAdapter} route={entityRoute} tenantId={session.tenant.id} actorId={session.actor.id} identityScope={session.identityScope} permissions={session.canonicalPermissions ?? session.permissions} onUnauthenticated={expire} />;
  } else if (!route || !authorizedApplication || (!canReadRoute && !canReportHere)) {
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
    content = <FindingReportPage adapter={findingStore.submit} draftKey={draftOwnerKey(session.tenant.id, session.actor.id)} onUnauthenticated={expire} />;
  } else if (route.page.kind === "ledger") {
    title = route.page.title;
    content = (
      <FindingsLedger
        key={route.page.view}
        view={route.page.view}
        title={route.page.title}
        description={mode === "http" ? (route.page.liveDescription ?? route.page.description) : route.page.description}
        adapter={findingStore.ledger}
        synthetic={mode === "synthetic"}
        resetInitialSelection={resetInitialSelection}
        submitAdapter={findingStore.submit}
        commandAdapter={findingStore.commands}
        evidenceAdapter={findingStore.evidence}
        canReport={hasPermission(session, "safety.finding.report")}
        draftKey={draftOwnerKey(session.tenant.id, session.actor.id)}
        onUnauthenticated={expire}
      />
    );
  } else if (route.page.kind === "ledger-configurations") {
    title = route.page.title;
    content = mode !== "http" ? <StatusPage title="需连接后端" description="请连接后端后配置台账。" /> : null;
  } else if (route.page.kind === "permissions") {
    title = route.page.title;
    content = (
      <Suspense fallback={<p role="status">正在载入权限配置…</p>}>
        <PermissionConfigPage
          adapter={permissionAdmin}
          onPermissionsChanged={refresh}
          canManage={hasPermission(session, "platform.permission.manage")}
          onUnauthenticated={expire}
        />
      </Suspense>
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
      currentRest={entityRoute?.base ?? parsed.rest}
      menu={menu.state}
      onRetryMenu={menu.retry}
      onLogout={logout}
      onChangePassword={canChangePassword ? changePassword : undefined}
      title={title}
    >
      {content}
      {canKeepLedgerConfig && (
        <LedgerConfigurationHost
          active={!routingTransition && route?.page.kind === "ledger-configurations" && authorizedApplication === "platform" && canReadRoute}
          canManage={canManageLedgerConfig}
          canPublish={canPublishLedgerConfig}
          onUnauthenticated={expire}
        />
      )}
    </AppShell>
  );
}

export function App({
  sessionAdapter,
  findingStore = syntheticFindingStore,
  createFindingStore,
  createPermissionAdmin,
  createEntityLedger,
  createLoans,
  mode = "synthetic",
}: {
  sessionAdapter?: SessionAdapter;
  /** 固定的适配器对（测试、合成演示）。 */
  findingStore?: FindingStore;
  /** 每个登录身份新建一对适配器；提供时优先于 findingStore。 */
  createFindingStore?: () => FindingStore;
  /** 每个登录身份新建的权限配置适配器；缺省为内存合成实现。 */
  createPermissionAdmin?: () => PermissionAdminAdapter;
  createEntityLedger?: EntityLedgerFactory;
  createLoans?: LoanFactory;
  /** 运行模式；决定界面是否标注演示。默认 synthetic。 */
  mode?: AppMode;
}) {
  return (
    <ModeProvider value={mode}>
      <SessionProvider adapter={sessionAdapter}>
        <AppRouter createFindingStore={createFindingStore ?? (() => findingStore)} createPermissionAdmin={createPermissionAdmin ?? createSyntheticPermissionAdmin} createEntityLedger={createEntityLedger} createLoans={createLoans} />
      </SessionProvider>
    </ModeProvider>
  );
}
