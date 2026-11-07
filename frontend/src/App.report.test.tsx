import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { createSyntheticSessionAdapter } from "./adapters/sessionAdapter";
import { createSyntheticFindingStore, type SyntheticFindingStore } from "./adapters/syntheticFindingStore";
import { FindingSubmitError } from "./adapters/findingSubmit";

const T = "/t/demo-tenant";

function setPath(path: string) {
  window.history.replaceState(null, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

function renderAs(user: string, path = `${T}/safety/findings`, store: SyntheticFindingStore = createSyntheticFindingStore()) {
  setPath(path);
  render(<App sessionAdapter={createSyntheticSessionAdapter({ signedInAs: user })} findingStore={store} />);
  return store;
}

const openButton = () => screen.queryByRole("button", { name: "新建上报" });

async function fillAndSubmit() {
  fireEvent.change(await screen.findByLabelText(/所在位置/), { target: { value: "演示仓库 · 北侧通道" } });
  fireEvent.change(screen.getByLabelText(/隐患描述/), { target: { value: "通道护栏连接件松动" } });
  fireEvent.change(screen.getByLabelText(/已采取的临时措施/), { target: { value: "已拉警戒线" } });
  fireEvent.click(screen.getByRole("radio", { name: "紧急" }));
  fireEvent.click(screen.getByRole("button", { name: "提交上报" }));
}

afterEach(() => {
  cleanup();
  setPath("/");
});

describe("新建上报入口与权限", () => {
  it.each(["demo.safety", "demo.manager", "demo.admin"])("%s 有上报权限，台账页显示“新建上报”", async (user) => {
    renderAs(user);
    await screen.findByRole("heading", { name: "隐患台账" });
    expect(openButton()).toBeInTheDocument();
  });

  it("只读账号看不到“新建上报”", async () => {
    renderAs("demo.viewer");
    await screen.findByRole("heading", { name: "隐患台账" });
    expect(await screen.findByRole("navigation", { name: "安全管理导航" })).toBeInTheDocument();
    expect(openButton()).not.toBeInTheDocument();
  });

  it("只在台账视图显示，待确认等其他视图不显示", async () => {
    renderAs("demo.manager", `${T}/safety/inbox`);
    await screen.findByRole("heading", { name: "待确认" });
    expect(openButton()).not.toBeInTheDocument();
  });
});

describe("上报流程", () => {
  it("提交后列表刷新、新记录排在首位，可查看详情并看到发现时间、来源、紧急程度和临时措施", async () => {
    renderAs("demo.safety");
    await screen.findByRole("heading", { name: "隐患台账" });
    expect(await screen.findByText("共 7 条演示记录")).toBeInTheDocument();

    fireEvent.click(openButton()!);
    await fillAndSubmit();
    expect(await screen.findByText("已提交上报")).toBeInTheDocument();
    // 编号同时出现在成功页和（异步刷新后的）列表里：限定在上报面板内，避免与列表刷新的时序相关。
    const report = screen.getByRole("complementary", { name: "新建上报面板" });
    expect(within(report).getByText("DEMO-HZ-008")).toBeInTheDocument();

    fireEvent.click(within(report).getByRole("button", { name: "查看详情" }));
    const panel = await screen.findByRole("complementary", { name: "隐患详情面板" });
    expect(within(panel).getByText("DEMO-HZ-008")).toBeInTheDocument();
    expect(within(panel).getByText("通道护栏连接件松动")).toBeInTheDocument();
    expect(within(panel).getByText("紧急")).toBeInTheDocument();
    expect(within(panel).getByText("现场发现")).toBeInTheDocument();
    expect(within(panel).getByText("已拉警戒线")).toBeInTheDocument();
    expect(within(panel).getByText("本人（演示）")).toBeInTheDocument();
    expect(within(panel).getAllByText("待一人确认").length).toBeGreaterThan(0);
    expect(window.location.search).toBe("?caseId=00000000-0000-4000-8000-000000000008");

    expect(await screen.findByText("共 8 条演示记录")).toBeInTheDocument();
    const rows = screen.getAllByRole("row");
    expect(within(rows[1]!).getByText("DEMO-HZ-008")).toBeInTheDocument();
  });

  it("上报面板打开时主区域不可交互，关闭后恢复并把焦点还给“新建上报”按钮", async () => {
    renderAs("demo.safety");
    await screen.findByRole("heading", { name: "隐患台账" });
    const main = document.querySelector(".ledger-main")!;
    expect(main).not.toHaveAttribute("inert");

    fireEvent.click(openButton()!);
    expect(main).toHaveAttribute("inert");
    expect(screen.getByRole("complementary", { name: "新建上报面板" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "关闭上报" }));
    await waitFor(() => expect(main).not.toHaveAttribute("inert"));
    expect(screen.queryByRole("complementary", { name: "新建上报面板" })).not.toBeInTheDocument();
    expect(openButton()).toHaveFocus();
  });

  it("上报与详情共用右侧面板：已打开详情时点“新建上报”会先关闭详情", async () => {
    renderAs("demo.safety", `${T}/safety/findings?caseId=00000000-0000-4000-8000-000000000001`);
    expect(await screen.findByRole("complementary", { name: "隐患详情面板" })).toBeInTheDocument();
    fireEvent.click(openButton()!);
    expect(screen.queryByRole("complementary", { name: "隐患详情面板" })).not.toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "新建上报面板" })).toBeInTheDocument();
    expect(window.location.search).toBe("");
  });

  it("补报：发现时间早于 24 小时，详情里显示“补报”标记，发现时间保持原值", async () => {
    renderAs("demo.manager");
    await screen.findByRole("heading", { name: "隐患台账" });
    fireEvent.click(openButton()!);
    fireEvent.change(await screen.findByLabelText(/发现时间/), { target: { value: "2026-10-20T09:00" } });
    await fillAndSubmit();
    // 表单里的提示是“将按补报处理”，成功页是“已按补报处理”：只匹配后者，避免命中随后被替换掉的表单文字。
    expect(await screen.findByText(/已按补报处理/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "查看详情" }));
    const panel = await screen.findByRole("complementary", { name: "隐患详情面板" });
    expect(within(panel).getByText("2026-10-20 09:00")).toBeInTheDocument();
    expect(within(panel).getByText("补报")).toBeInTheDocument();
  });

  it("提交时服务端返回 401：回到登录页并提示登录已失效", async () => {
    const store = createSyntheticFindingStore();
    const submit = vi.fn().mockRejectedValue(new FindingSubmitError("unauthenticated"));
    renderAs("demo.safety", `${T}/safety/findings`, { ...store, submit: { submit } });
    await screen.findByRole("heading", { name: "隐患台账" });
    fireEvent.click(openButton()!);
    await fillAndSubmit();
    expect(await screen.findByRole("heading", { name: "登录" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("登录已失效，请重新登录。");
  });

  it("台账读取收到“未登录”时同样回到登录页", async () => {
    const store = createSyntheticFindingStore();
    const { FindingLedgerAccessError } = await import("./adapters/findingLedger");
    const ledger = { ...store.ledger, list: vi.fn().mockRejectedValue(new FindingLedgerAccessError("unauthenticated")) };
    renderAs("demo.safety", `${T}/safety/findings`, { ...store, ledger });
    expect(await screen.findByRole("heading", { name: "登录" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("登录已失效，请重新登录。");
  });

  it("另一个账号看到的是同一份内存数据（合成存储随应用实例共享），但各存储实例互不影响", async () => {
    const store = createSyntheticFindingStore();
    renderAs("demo.safety", `${T}/safety/findings`, store);
    await screen.findByRole("heading", { name: "隐患台账" });
    fireEvent.click(openButton()!);
    await fillAndSubmit();
    await screen.findByText("已提交上报");
    expect((await store.ledger.list({ query: {}, page: { index: 1, size: 20 }, view: "all" })).total).toBe(8);
    expect((await createSyntheticFindingStore().ledger.list({ query: {}, page: { index: 1, size: 20 }, view: "all" })).total).toBe(7);
  });
});

describe("真实后端会话（非合成）", () => {
  const live = { id: "00000000-0000-4000-8000-0000000000aa", code: "YH-20261102-0001", description: "护栏松动", location: "A 工房", state: "PERSON_CONFIRM_PENDING" as const,
    reportedAt: "2026-11-02T09:00:00+08:00", reporter: "—", responsible: "—", riskLabel: "待专业评估", round: 1, version: 1, history: [] };

  it("任何界面文字都不再称真实数据为演示/合成；台账、详情、上报使用注入的适配器", async () => {
    window.history.replaceState(null, "", "/safety/findings");
    const base = createSyntheticSessionAdapter({ signedInAs: "demo.safety" });
    const ledger = {
      list: vi.fn(async () => ({ items: [live], page: { index: 1, size: 20 }, total: 1, pageCount: 1 })),
      get: vi.fn(async () => live),
    };
    const submit = { submit: vi.fn(), urgencies: ["NORMAL", "IMMEDIATE"] as const, listOrganizations: vi.fn(async () => [{ id: "o1", code: "A", name: "一车间" }]) };
    // 演示适配器的账号显示名本身带“演示”，换成中性名字，只检查产品文案。
    const restore: typeof base.restore = async () => ({ ...(await base.restore())!, actor: { id: "u1", displayName: "张三" } });
    render(<App mode="http" sessionAdapter={{ ...base, restore, requiresTenantCode: true }} createFindingStore={() => ({ ledger, submit, commands: { run: vi.fn() } })} />);
    await screen.findByRole("heading", { name: "隐患台账" });
    expect(screen.queryByText("合成演示")).not.toBeInTheDocument();
    expect(screen.queryByText("前端合成数据")).not.toBeInTheDocument();
    expect(screen.queryByText(/合成演示数据/)).not.toBeInTheDocument();
    await waitFor(() => expect(ledger.list).toHaveBeenCalled());

    await screen.findByText("共 1 条记录");
    const noDemoWording = () => {
      const text = document.body.textContent ?? "";
      expect(text).not.toMatch(/演示|合成|示例对象/);
    };
    noDemoWording();

    fireEvent.click(screen.getByRole("button", { name: "详情" }));
    await screen.findByText("处理轨迹");
    noDemoWording();

    fireEvent.click(screen.getByRole("button", { name: "关闭详情" }));
    fireEvent.click(screen.getByRole("button", { name: /新建上报/ }));
    expect(await screen.findByRole("option", { name: "一车间" })).toBeInTheDocument();
    expect(screen.queryByText("后端暂未接入")).not.toBeInTheDocument();
    // 适配器声明的紧急程度：没有后端不接受的“紧急”档
    expect(screen.queryByLabelText("紧急")).not.toBeInTheDocument();
    expect(screen.getByLabelText("需立即处置")).toBeInTheDocument();
    noDemoWording();
  });

  it("演示模式仍标注演示", async () => {
    window.history.replaceState(null, "", "/safety/findings");
    render(<App sessionAdapter={createSyntheticSessionAdapter({ signedInAs: "demo.safety" })} />);
    await screen.findByText(/共 \d+ 条演示记录/);
    expect(screen.getByText("合成演示")).toBeInTheDocument();
  });
});

describe("READ and CREATE route projections", () => {
  async function renderWithPermissions(permissions: string[]) {
    const { createHttpFindingLedger } = await import("./adapters/httpFindingLedger");
    const { createHttpFindingSubmit } = await import("./adapters/httpFindingSubmit");
    const { definitionResponse } = await import("./adapters/findingDefinition.fixtures");
    const base = createSyntheticSessionAdapter({ signedInAs: "demo.safety" });
    const session = (await base.restore())!;
    const gets: string[] = [], posts: string[] = [];
    const orgId = "00000000-0000-4000-8000-000000000401";
    const client = {
      get: async (path: string) => { gets.push(path); return definitionResponse(path.endsWith("CREATE") ? "CREATE" : "READ"); },
      post: async (path: string) => {
        posts.push(path);
        if (path.endsWith("report-organization/query")) return { data: { items: [{ id: orgId, code: "A", name: "当前车间" }] }, meta: { schemaVersion: "1" } };
        if (path === "/api/v1/findings") return { data: { findingId: "00000000-0000-4000-8000-000000000402", code: "REPORT-ONLY-1", state: "PERSON_CONFIRM_PENDING" } };
        return { data: { items: [], page: { number: 1, size: 20, total: 0, pageCount: 1 } } };
      },
    } as unknown as import("./api/httpClient").HttpClient;
    setPath(`${T}/safety/findings${permissions.includes("safety.finding.read") ? "" : "?caseId=previous-record"}`);
    render(<App mode="http" sessionAdapter={{ ...base, requiresTenantCode: true, restore: async () => ({ ...session, permissions }) }} findingStore={{ ledger: createHttpFindingLedger(client), submit: createHttpFindingSubmit(client) }} />);
    return { gets, posts, orgId };
  }

  it("lets a CREATE-only member submit without any READ request or receipt-to-detail action", async () => {
    const { gets, posts, orgId } = await renderWithPermissions(["safety.finding.report"]);
    await screen.findByRole("heading", { name: "隐患上报" });
    await screen.findByRole("option", { name: /当前车间/ });
    fireEvent.change(screen.getByLabelText(/所属组织/), { target: { value: orgId } });
    fireEvent.change(screen.getByLabelText(/隐患描述/), { target: { value: "现场情况" } });
    fireEvent.change(screen.getByLabelText(/所在位置/), { target: { value: "作业点" } });
    fireEvent.click(screen.getByRole("button", { name: "提交上报" }));
    await screen.findByText("REPORT-ONLY-1");
    expect(screen.queryByRole("button", { name: "查看详情" })).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(gets).toEqual(["/api/v1/entities/finding/definition?use=CREATE", "/api/v1/entities/finding/definition?use=CREATE"]);
    expect(posts).toEqual(["/api/v1/entities/finding/references/report-organization/query", "/api/v1/findings"]);
    expect(window.location.search).not.toContain("caseId");
  });

  it("keeps a READ-only member out of the create projection", async () => {
    const { gets, posts } = await renderWithPermissions(["safety.finding.read"]);
    await screen.findByRole("heading", { name: "隐患台账" });
    await screen.findByText("共 0 条记录");
    expect(screen.queryByRole("button", { name: "新建上报" })).not.toBeInTheDocument();
    expect(gets.every((path) => !path.includes("use=CREATE"))).toBe(true);
    expect(posts).not.toContain("/api/v1/entities/finding/references/report-organization/query");
  });

  it("does not mount either projection when neither permission is present", async () => {
    const { gets, posts } = await renderWithPermissions([]);
    await screen.findByRole("heading", { name: "无权访问" });
    expect(gets).toEqual([]); expect(posts).toEqual([]);
    expect(screen.queryByRole("complementary", { name: "新建上报面板" })).not.toBeInTheDocument();
  });
});

describe("未提交草稿的离开保护", () => {
  async function openDirtyDraft() {
    renderAs("demo.manager");
    await screen.findByRole("heading", { name: "隐患台账" });
    fireEvent.click(screen.getByRole("button", { name: "新建上报" }));
    fireEvent.change(await screen.findByLabelText(/隐患描述/), { target: { value: "通道护栏连接件松动" } });
  }

  it("点应用内链接离开先确认：取消则留在原页并保留草稿", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    await openDirtyDraft();
    fireEvent.click(await screen.findByRole("link", { name: "待确认" }));
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("还没有提交"));
    expect(window.location.pathname).toBe(`${T}/safety/findings`);
    expect(screen.getByLabelText(/隐患描述/)).toHaveValue("通道护栏连接件松动");
    confirm.mockRestore();
  });

  it("确认后离开", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    await openDirtyDraft();
    fireEvent.click(await screen.findByRole("link", { name: "待确认" }));
    await waitFor(() => expect(window.location.pathname).toBe(`${T}/safety/inbox`));
    confirm.mockRestore();
  });

  it("没有改动、已提交成功或已关闭面板时不询问", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    renderAs("demo.manager");
    await screen.findByRole("heading", { name: "隐患台账" });
    fireEvent.click(screen.getByRole("button", { name: "新建上报" }));
    await screen.findByLabelText(/隐患描述/);
    fireEvent.click(await screen.findByRole("link", { name: "待确认" }));
    await waitFor(() => expect(window.location.pathname).toBe(`${T}/safety/inbox`));
    expect(confirm).not.toHaveBeenCalled();
    confirm.mockRestore();
  });

  it("提交成功后不再询问", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    renderAs("demo.safety");
    await screen.findByRole("heading", { name: "隐患台账" });
    fireEvent.click(screen.getByRole("button", { name: "新建上报" }));
    await fillAndSubmit();
    await screen.findByText("已提交上报");
    fireEvent.click(await screen.findByRole("link", { name: "隐患台账" }));
    expect(confirm).not.toHaveBeenCalled();
    confirm.mockRestore();
  });
});

describe("会话被动失效后的草稿恢复", () => {
  function expiringStore(): SyntheticFindingStore {
    const base = createSyntheticFindingStore();
    return { ...base, submit: { submit: vi.fn(async () => { throw new FindingSubmitError("unauthenticated"); }) } };
  }
  async function fillAndExpire(user: string) {
    renderAs(user, `${T}/safety/findings`, expiringStore());
    await screen.findByRole("heading", { name: "隐患台账" });
    fireEvent.click(screen.getByRole("button", { name: "新建上报" }));
    fireEvent.change(await screen.findByLabelText(/所在位置/), { target: { value: "北侧通道" } });
    fireEvent.change(screen.getByLabelText(/隐患描述/), { target: { value: "护栏连接件松动" } });
    fireEvent.click(screen.getByRole("button", { name: "提交上报" }));
    await screen.findByText("登录已失效，请重新登录。");
  }
  async function signInAs(user: string) {
    fireEvent.change(screen.getByLabelText("账号"), { target: { value: user } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "x" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));
    await screen.findByRole("heading", { name: "隐患台账" });
  }

  it("同一个人重新登录后，上报表单恢复未提交的内容并提示", async () => {
    await fillAndExpire("demo.manager");
    await signInAs("demo.manager");
    fireEvent.click(screen.getByRole("button", { name: "新建上报" }));
    expect(await screen.findByLabelText(/隐患描述/)).toHaveValue("护栏连接件松动");
    expect(screen.getByLabelText(/所在位置/)).toHaveValue("北侧通道");
    expect(screen.getByText(/已恢复登录失效前还没提交的内容/)).toBeInTheDocument();
  });

  it("换成另一个账号登录，看不到上一个人的草稿", async () => {
    await fillAndExpire("demo.manager");
    await signInAs("demo.admin");
    fireEvent.click(screen.getByRole("button", { name: "新建上报" }));
    expect(await screen.findByLabelText(/隐患描述/)).toHaveValue("");
    expect(screen.queryByText(/已恢复登录失效前/)).not.toBeInTheDocument();
  });

  it("主动退出不保留草稿，再登录也不恢复", async () => {
    renderAs("demo.manager");
    await screen.findByRole("heading", { name: "隐患台账" });
    fireEvent.click(screen.getByRole("button", { name: "新建上报" }));
    fireEvent.change(await screen.findByLabelText(/隐患描述/), { target: { value: "护栏连接件松动" } });
    fireEvent.click(screen.getByRole("button", { name: /演示部门负责人/ }));
    fireEvent.click(screen.getByRole("button", { name: "退出登录" }));
    await screen.findByRole("heading", { name: "登录" });
    await signInAs("demo.manager");
    fireEvent.click(screen.getByRole("button", { name: "新建上报" }));
    expect(await screen.findByLabelText(/隐患描述/)).toHaveValue("");
  });
});
