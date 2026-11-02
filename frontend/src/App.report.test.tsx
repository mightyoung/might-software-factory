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
  it("不显示合成演示标识，台账与上报使用注入的适配器", async () => {
    window.history.replaceState(null, "", "/safety/findings");
    const base = createSyntheticSessionAdapter({ signedInAs: "demo.safety" });
    const ledger = { list: vi.fn(async () => ({ items: [], page: { index: 1, size: 20 }, total: 0, pageCount: 0 })), get: vi.fn(async () => null) };
    const submit = { submit: vi.fn(), listOrganizations: vi.fn(async () => [{ id: "o1", code: "A", name: "一车间" }]) };
    render(<App sessionAdapter={{ ...base, requiresTenantCode: true }} createFindingStore={() => ({ ledger, submit })} />);
    await screen.findByRole("heading", { name: "隐患台账" });
    expect(screen.queryByText("合成演示")).not.toBeInTheDocument();
    expect(screen.queryByText("前端合成数据")).not.toBeInTheDocument();
    expect(screen.queryByText(/合成演示数据/)).not.toBeInTheDocument();
    await waitFor(() => expect(ledger.list).toHaveBeenCalled());
    fireEvent.click(screen.getByRole("button", { name: /新建上报/ }));
    expect(await screen.findByRole("option", { name: "一车间" })).toBeInTheDocument();
    expect(screen.queryByText("后端暂未接入")).not.toBeInTheDocument();
  });
});
