import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { createSyntheticSessionAdapter, type SessionAdapter } from "./adapters/sessionAdapter";
import { SessionError } from "./domain/session";

const T = "/t/demo-tenant";

function setPath(path: string) {
  window.history.replaceState(null, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

function renderSignedIn(user: string, path = "/safety/findings", adapter?: SessionAdapter) {
  setPath(path);
  return render(<App sessionAdapter={adapter ?? createSyntheticSessionAdapter({ signedInAs: user })} />);
}

function fillAndSubmit(username: string, password: string) {
  fireEvent.change(screen.getByLabelText("账号"), { target: { value: username } });
  fireEvent.change(screen.getByLabelText("密码"), { target: { value: password } });
  fireEvent.click(screen.getByRole("button", { name: "登录" }));
}

afterEach(() => {
  cleanup();
  setPath("/");
});

describe("已登录时的应用路由与导航", () => {
  beforeEach(() => setPath("/safety/findings"));

  it("旧的无租户路径被规范化为带租户前缀的路径，并保留查询串", async () => {
    renderSignedIn("demo.manager", "/safety/findings?caseId=00000000-0000-4000-8000-000000000001");
    expect(await screen.findByRole("heading", { name: "隐患台账" })).toBeInTheDocument();
    await waitFor(() => expect(window.location.pathname).toBe(`${T}/safety/findings`));
    expect(window.location.search).toBe("?caseId=00000000-0000-4000-8000-000000000001");
  });

  it("根路径进入第一个有权应用的入口", async () => {
    renderSignedIn("demo.manager", "/");
    expect(await screen.findByRole("heading", { name: "隐患台账" })).toBeInTheDocument();
    expect(window.location.pathname).toBe(`${T}/safety/findings`);
  });

  it("在安全应用内通过侧栏切换路由，并可切换顶部应用", async () => {
    renderSignedIn("demo.manager");
    expect(await screen.findByRole("heading", { name: "隐患台账" })).toBeInTheDocument();

    fireEvent.click(await screen.findByRole("link", { name: "排查任务" }));
    expect(screen.getByRole("heading", { name: "排查任务" })).toBeInTheDocument();
    expect(window.location.pathname).toBe(`${T}/safety/inspections`);

    fireEvent.click(screen.getByRole("link", { name: "设备管理" }));
    expect(screen.getByRole("heading", { name: "设备管理" })).toBeInTheDocument();
    expect(window.location.pathname).toBe(`${T}/equipment/overview`);
    expect(await screen.findByRole("link", { name: "设备概览" })).toBeInTheDocument();
  });

  it("支持浏览器历史后退与前进恢复导航", async () => {
    renderSignedIn("demo.manager");
    fireEvent.click(await screen.findByRole("link", { name: "排查任务" }));
    expect(screen.getByRole("heading", { name: "排查任务" })).toBeInTheDocument();

    window.history.back();
    await waitFor(() => expect(window.location.pathname).toBe(`${T}/safety/findings`));
    expect(screen.getByRole("heading", { name: "隐患台账" })).toBeInTheDocument();

    window.history.forward();
    await waitFor(() => expect(window.location.pathname).toBe(`${T}/safety/inspections`));
    expect(screen.getByRole("heading", { name: "排查任务" })).toBeInTheDocument();
  });
});

describe("受限导航与路由守卫", () => {
  it("只显示有权的应用和菜单项", async () => {
    renderSignedIn("demo.safety");
    const nav = await screen.findByRole("navigation", { name: "安全管理导航" });
    expect(within(nav).getByRole("link", { name: "隐患台账" })).toBeInTheDocument();
    expect(within(nav).queryByRole("link", { name: "风险管控" })).not.toBeInTheDocument();
    expect(within(nav).queryByRole("link", { name: "排查任务" })).not.toBeInTheDocument();
    const apps = screen.getByRole("navigation", { name: "应用选择" });
    expect(within(apps).getAllByRole("link").map((link) => link.textContent)).toEqual(["生产安全"]);
  });

  it("直接访问无权的页面显示无权访问，且不显示页面内容", async () => {
    renderSignedIn("demo.safety", `${T}/safety/risks`);
    expect(await screen.findByRole("heading", { name: "无权访问" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "风险管控" })).not.toBeInTheDocument();
  });

  it("直接访问未开通的应用显示无权访问，且不请求该应用的菜单", async () => {
    const base = createSyntheticSessionAdapter({ signedInAs: "demo.safety" });
    const loadMenu = vi.fn(base.loadMenu);
    renderSignedIn("demo.safety", `${T}/equipment/overview`, { ...base, loadMenu });
    expect(await screen.findByRole("heading", { name: "无权访问" })).toBeInTheDocument();
    expect(loadMenu).not.toHaveBeenCalled();
  });

  it("路径中的租户与会话租户不一致时显示无权访问，不泄露该租户是否存在", async () => {
    renderSignedIn("demo.admin", "/t/other-tenant/safety/findings");
    expect(await screen.findByRole("heading", { name: "无权访问" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "隐患台账" })).not.toBeInTheDocument();
  });

  it("未登记的路径显示页面未开放", async () => {
    renderSignedIn("demo.admin", `${T}/safety/unknown`);
    expect(await screen.findByRole("heading", { name: "页面未开放" })).toBeInTheDocument();
  });

  it("应用菜单加载失败时显示提示并可重试", async () => {
    const base = createSyntheticSessionAdapter({ signedInAs: "demo.safety" });
    const loadMenu = vi.fn(base.loadMenu).mockRejectedValueOnce(new SessionError("unavailable"));
    renderSignedIn("demo.safety", "/safety/findings", { ...base, loadMenu });
    expect(await screen.findByText("导航加载失败。")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByRole("navigation", { name: "安全管理导航" })).toBeInTheDocument();
    expect(loadMenu).toHaveBeenCalledTimes(2);
  });
});

describe("登录与退出", () => {
  it("未登录访问受保护页面时进入登录页，并带上返回地址", async () => {
    setPath(`${T}/safety/inbox?x=1`);
    render(<App sessionAdapter={createSyntheticSessionAdapter()} />);
    expect(await screen.findByRole("heading", { name: "登录" })).toBeInTheDocument();
    await waitFor(() => expect(window.location.pathname).toBe("/login"));
    expect(new URLSearchParams(window.location.search).get("next")).toBe(`${T}/safety/inbox?x=1`);
    expect(screen.queryByRole("heading", { name: "待确认" })).not.toBeInTheDocument();
  });

  it("登录成功后回到返回地址", async () => {
    setPath(`${T}/safety/inbox`);
    render(<App sessionAdapter={createSyntheticSessionAdapter()} />);
    await screen.findByRole("heading", { name: "登录" });
    fillAndSubmit("demo.manager", "anything");
    expect(await screen.findByRole("heading", { name: "待确认" })).toBeInTheDocument();
    expect(window.location.pathname).toBe(`${T}/safety/inbox`);
  });

  it("返回地址是尚无租户前缀的旧地址时，登录后规范化并回到该页面", async () => {
    setPath("/safety/inbox");
    render(<App sessionAdapter={createSyntheticSessionAdapter()} />);
    await screen.findByRole("heading", { name: "登录" });
    // 登录页先渲染，地址改为 /login?next=… 在随后的 effect 里完成：等它完成再读。
    await waitFor(() => expect(new URLSearchParams(window.location.search).get("next")).toBe("/safety/inbox"));
    fillAndSubmit("demo.manager", "x");
    expect(await screen.findByRole("heading", { name: "待确认" })).toBeInTheDocument();
    await waitFor(() => expect(window.location.pathname).toBe(`${T}/safety/inbox`));
  });

  it.each([
    ["协议相对地址", "//evil.example/x"],
    ["绝对地址", "https://evil.example/"],
    ["其他租户", "/t/other-tenant/safety/findings"],
  ])("返回地址是%s时忽略它，进入默认首页", async (_name, next) => {
    setPath(`/login?next=${encodeURIComponent(next)}`);
    render(<App sessionAdapter={createSyntheticSessionAdapter()} />);
    await screen.findByRole("heading", { name: "登录" });
    fillAndSubmit("demo.safety", "x");
    expect(await screen.findByRole("heading", { name: "隐患台账" })).toBeInTheDocument();
    expect(window.location.pathname).toBe(`${T}/safety/findings`);
  });

  it("账号不存在和密码为空显示同一条错误，失败后清空密码并停留在登录页", async () => {
    setPath("/login");
    render(<App sessionAdapter={createSyntheticSessionAdapter()} />);
    await screen.findByRole("heading", { name: "登录" });

    fillAndSubmit("nobody", "pw");
    const first = await screen.findByRole("alert");
    expect(first).toHaveTextContent("账号或密码不正确。");
    expect(screen.getByLabelText("密码")).toHaveValue("");

    fireEvent.change(screen.getByLabelText("账号"), { target: { value: "demo.admin" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("账号或密码不正确。");
    expect(window.location.pathname).toBe("/login");
  });

  it("服务不可用时给出可重试提示", async () => {
    const base = createSyntheticSessionAdapter();
    const login = vi.fn().mockRejectedValue(new SessionError("unavailable"));
    setPath("/login");
    render(<App sessionAdapter={{ ...base, login }} />);
    await screen.findByRole("heading", { name: "登录" });
    fillAndSubmit("demo.admin", "x");
    expect(await screen.findByRole("alert")).toHaveTextContent("服务暂时不可用");
    expect(screen.getByRole("button", { name: "登录" })).toBeEnabled();
  });

  it("提交期间禁用表单，避免重复提交", async () => {
    const base = createSyntheticSessionAdapter();
    let release: () => void = () => {};
    const login = vi.fn(
      (credentials: Parameters<SessionAdapter["login"]>[0]) =>
        new Promise<Awaited<ReturnType<SessionAdapter["login"]>>>((resolve) => {
          release = () => void base.login(credentials).then(resolve);
        }),
    );
    setPath("/login");
    render(<App sessionAdapter={{ ...base, login }} />);
    await screen.findByRole("heading", { name: "登录" });
    fillAndSubmit("demo.admin", "x");
    const pending = await screen.findByRole("button", { name: "登录中…" });
    expect(pending).toBeDisabled();
    fireEvent.submit(pending.closest("form")!);
    expect(login).toHaveBeenCalledTimes(1);
    release();
    expect(await screen.findByRole("heading", { name: "隐患台账" })).toBeInTheDocument();
  });

  it("演示账号按钮只填入账号，不填密码", async () => {
    setPath("/login");
    render(<App sessionAdapter={createSyntheticSessionAdapter()} />);
    await screen.findByRole("heading", { name: "登录" });
    fireEvent.click(screen.getByRole("button", { name: "demo.safety" }));
    expect(screen.getByLabelText("账号")).toHaveValue("demo.safety");
    expect(screen.getByLabelText("密码")).toHaveValue("");
  });

  it("通过头像菜单退出：回到登录页，不再展示受保护内容，后退也不能回到页面", async () => {
    renderSignedIn("demo.manager");
    await screen.findByRole("heading", { name: "隐患台账" });

    fireEvent.click(screen.getByRole("button", { name: /演示部门负责人/ }));
    fireEvent.click(screen.getByRole("button", { name: "退出登录" }));

    expect(await screen.findByRole("heading", { name: "登录" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("已退出登录。");
    await waitFor(() => expect(window.location.pathname).toBe("/login"));
    expect(window.location.search).toBe("");

    window.history.back();
    await waitFor(() => expect(window.location.pathname).not.toBe(`${T}/safety/findings`));
    expect(screen.queryByRole("heading", { name: "隐患台账" })).not.toBeInTheDocument();
  });

  it("退出请求失败时仍清除本地登录态", async () => {
    const base = createSyntheticSessionAdapter({ signedInAs: "demo.admin" });
    const logout = vi.fn().mockRejectedValue(new SessionError("unavailable"));
    renderSignedIn("demo.admin", "/safety/findings", { ...base, logout });
    await screen.findByRole("heading", { name: "隐患台账" });
    fireEvent.click(screen.getByRole("button", { name: /演示管理员/ }));
    fireEvent.click(screen.getByRole("button", { name: "退出登录" }));
    expect(await screen.findByRole("heading", { name: "登录" })).toBeInTheDocument();
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it("服务端判定会话失效时回到登录页并提示", async () => {
    const base = createSyntheticSessionAdapter({ signedInAs: "demo.safety" });
    const loadMenu = vi.fn().mockRejectedValue(new SessionError("unauthenticated"));
    renderSignedIn("demo.safety", "/safety/findings", { ...base, loadMenu });
    expect(await screen.findByRole("heading", { name: "登录" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("登录已失效，请重新登录。");
  });

  it("恢复会话失败时回到登录页并提示，而不是停在空白", async () => {
    const base = createSyntheticSessionAdapter();
    const restore = vi.fn().mockRejectedValue(new SessionError("unavailable"));
    setPath(`${T}/safety/findings`);
    render(<App sessionAdapter={{ ...base, restore }} />);
    expect(await screen.findByRole("heading", { name: "登录" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("暂时无法确认登录状态");
  });
});

describe("头像菜单", () => {
  it("Escape 关闭并把焦点还给触发按钮；点击菜单外部也会关闭", async () => {
    renderSignedIn("demo.manager");
    await screen.findByRole("heading", { name: "隐患台账" });
    const trigger = screen.getByRole("button", { name: /演示部门负责人/ });

    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("演示租户（合成）")).toBeInTheDocument();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveFocus();

    fireEvent.click(trigger);
    fireEvent.mouseDown(document.body);
    expect(trigger).toHaveAttribute("aria-expanded", "false");
  });

  it("尚未接入的入口显示为禁用", async () => {
    renderSignedIn("demo.manager");
    await screen.findByRole("heading", { name: "隐患台账" });
    fireEvent.click(screen.getByRole("button", { name: /演示部门负责人/ }));
    expect(screen.getByRole("button", { name: /个人资料/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /个人会话/ })).toBeDisabled();
  });
});

describe("真实后端登录（需要租户码）", () => {
  it("显示租户编码输入框、隐藏演示说明，并把租户码交给适配器", async () => {
    const base = createSyntheticSessionAdapter();
    const login = vi.fn().mockRejectedValue(new SessionError("invalid_credentials"));
    setPath("/login");
    render(<App sessionAdapter={{ ...base, requiresTenantCode: true, login }} />);
    await screen.findByRole("heading", { name: "登录" });
    expect(screen.queryByLabelText("合成演示说明")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("租户编码"), { target: { value: "acme" } });
    fillAndSubmit("zhang", "pw");
    await screen.findByRole("alert");
    expect(login).toHaveBeenCalledWith({ username: "zhang", password: "pw", tenantCode: "acme" });
  });

  it("合成模式不显示租户编码", async () => {
    setPath("/login");
    render(<App sessionAdapter={createSyntheticSessionAdapter()} />);
    await screen.findByRole("heading", { name: "登录" });
    expect(screen.queryByLabelText("租户编码")).not.toBeInTheDocument();
  });
});

describe("修改密码", () => {
  const NEW = "a-new-long-password";

  it("演示模式：菜单项存在但禁用并说明", async () => {
    renderSignedIn("demo.admin");
    fireEvent.click(await screen.findByRole("button", { name: /演示管理员/ }));
    expect(screen.getByRole("button", { name: /修改密码/ })).toBeDisabled();
  });

  it("支持改密的会话：成功后清除登录态并回到登录页，提示用新密码重新登录", async () => {
    const base = createSyntheticSessionAdapter({ signedInAs: "demo.admin" });
    const changePassword = vi.fn(async () => {});
    renderSignedIn("demo.admin", "/safety/findings", { ...base, changePassword });
    fireEvent.click(await screen.findByRole("button", { name: /演示管理员/ }));
    fireEvent.click(screen.getByRole("button", { name: "修改密码" }));
    fireEvent.change(screen.getByLabelText("当前密码"), { target: { value: "old" } });
    fireEvent.change(screen.getByLabelText("新密码"), { target: { value: NEW } });
    fireEvent.change(screen.getByLabelText("再次输入新密码"), { target: { value: NEW } });
    fireEvent.click(screen.getAllByRole("button", { name: "修改密码" }).at(-1)!);
    await waitFor(() => expect(window.location.pathname).toBe("/login"));
    expect(changePassword).toHaveBeenCalledWith("old", NEW);
    expect(await screen.findByRole("status")).toHaveTextContent("密码已修改");
  });

  it("会话已失效（401）：回到登录页并提示已失效", async () => {
    const base = createSyntheticSessionAdapter({ signedInAs: "demo.admin" });
    const changePassword = vi.fn(async () => { throw new SessionError("unauthenticated"); });
    renderSignedIn("demo.admin", "/safety/findings", { ...base, changePassword });
    fireEvent.click(await screen.findByRole("button", { name: /演示管理员/ }));
    fireEvent.click(screen.getByRole("button", { name: "修改密码" }));
    fireEvent.change(screen.getByLabelText("当前密码"), { target: { value: "old" } });
    fireEvent.change(screen.getByLabelText("新密码"), { target: { value: NEW } });
    fireEvent.change(screen.getByLabelText("再次输入新密码"), { target: { value: NEW } });
    fireEvent.click(screen.getAllByRole("button", { name: "修改密码" }).at(-1)!);
    expect(await screen.findByRole("status")).toHaveTextContent("登录已失效");
  });
});

describe("恢复会话失败的提示", () => {
  it("403（账号 / 租户已停用）提示无法登录，而不是“稍后重试”", async () => {
    const base = createSyntheticSessionAdapter();
    setPath("/safety/findings");
    render(<App sessionAdapter={{ ...base, restore: vi.fn().mockRejectedValue(new SessionError("forbidden")) }} />);
    expect(await screen.findByText(/当前账号暂时无法登录/)).toBeInTheDocument();
  });

  it("503 仍提示稍后重试", async () => {
    const base = createSyntheticSessionAdapter();
    setPath("/safety/findings");
    render(<App sessionAdapter={{ ...base, restore: vi.fn().mockRejectedValue(new SessionError("unavailable")) }} />);
    expect(await screen.findByText(/暂时无法确认登录状态/)).toBeInTheDocument();
  });
});

