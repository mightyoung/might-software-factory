import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FindingReportForm, type FindingReportFormProps } from "./FindingReportForm";
import {
  FindingSubmitError,
  type FindingSubmitAdapter,
  type FindingSubmitContext,
  type FindingSubmitResult,
} from "../adapters/findingSubmit";
import { createEmptyDraft, type FindingDraft } from "../domain/findingDraft";
import { stashDraft, clearAllDrafts } from "../features/session/draftStash";

import { findingDefinitionFixture } from "../adapters/findingDefinition.fixtures";

const NOW = new Date("2026-10-30T14:30:00+08:00"); // 上海时间
const RESULT: FindingSubmitResult = {
  id: "00000000-0000-4000-8000-000000000008",
  code: "DEMO-HZ-008",
  state: "PERSON_CONFIRM_PENDING",
  submittedAt: "2026-10-30T14:30:00",
  backfill: false,
};

type SubmitFn = (draft: FindingDraft, context: FindingSubmitContext) => Promise<FindingSubmitResult>;

function setup(submit: SubmitFn = async () => RESULT, extra: Partial<FindingReportFormProps> = {}) {
  const adapter: FindingSubmitAdapter = { submit: vi.fn(submit) };
  let keyCount = 0;
  const props = {
    adapter,
    onClose: vi.fn(),
    onSubmitted: vi.fn(),
    onOpenSubmitted: vi.fn(),
    onUnauthenticated: vi.fn(),
    now: () => NOW,
    createKey: vi.fn(() => `key-${++keyCount}`),
    ...extra,
  };
  const view = render(<FindingReportForm {...props} />);
  return { adapter, props, submit: adapter.submit as ReturnType<typeof vi.fn>, ...view };
}

const field = (name: RegExp) => screen.getByLabelText(name);
const submitButton = () => screen.getByRole("button", { name: /提交上报|重试提交|提交中/ });

function fillValid() {
  fireEvent.change(field(/所在位置/), { target: { value: "演示仓库 · 北侧通道" } });
  fireEvent.change(field(/隐患描述/), { target: { value: "  通道护栏连接件松动  " } });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

afterEach(()=>{cleanup();clearAllDrafts();});

describe("新建上报表单", () => {
  it("提供必要字段，等级只读为“待判定”，没有上报人或租户输入", () => {
    setup();
    expect(field(/来源/)).toHaveValue("FIELD_OBSERVATION");
    expect(field(/发现时间/)).toHaveValue("2026-10-30T14:30");
    expect(screen.getByRole("radio", { name: "一般" })).toBeChecked();
    expect(screen.getByText("待判定")).toBeInTheDocument();
    expect(screen.getByRole("note", { name: "" })).toBeInTheDocument();
    expect(screen.getByText(/不会通知任何人/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/上报人|租户|隐患等级/)).not.toBeInTheDocument();
  });

  it("打开时焦点在第一个字段", () => {
    setup();
    expect(field(/来源/)).toHaveFocus();
  });

  it("空表单提交：显示字段错误和数量，焦点移到第一个出错字段，不调用提交", () => {
    const { submit } = setup();
    fireEvent.click(submitButton());
    expect(screen.getByRole("alert")).toHaveTextContent("有 2 项需要修正。");
    expect(screen.getByText("请填写所在位置或设备")).toBeInTheDocument();
    expect(screen.getByText("请填写隐患描述")).toBeInTheDocument();
    expect(field(/所在位置/)).toHaveFocus();
    expect(field(/所在位置/)).toHaveAttribute("aria-invalid", "true");
    expect(field(/所在位置/).getAttribute("aria-describedby")).toContain("error");
    expect(submit).not.toHaveBeenCalled();
  });

  it("编辑某字段只清除它自己的错误", () => {
    setup();
    fireEvent.click(submitButton());
    fireEvent.change(field(/所在位置/), { target: { value: "演示工房" } });
    expect(screen.queryByText("请填写所在位置或设备")).not.toBeInTheDocument();
    expect(screen.getByText("请填写隐患描述")).toBeInTheDocument();
    expect(field(/所在位置/)).toHaveAttribute("aria-invalid", "false");
  });

  it("发现时间晚于当前时间时拒绝提交", () => {
    const { submit } = setup();
    fillValid();
    fireEvent.change(field(/发现时间/), { target: { value: "2026-10-31T09:00" } });
    fireEvent.click(submitButton());
    expect(screen.getByText("发现时间不能晚于当前时间")).toBeInTheDocument();
    expect(field(/发现时间/)).toHaveFocus();
    expect(submit).not.toHaveBeenCalled();
  });

  it("提交成功：用规范化后的内容和幂等键调用一次，随后显示编号和合成演示说明", async () => {
    const { submit, props } = setup();
    fillValid();
    fireEvent.click(screen.getByRole("radio", { name: "紧急" }));
    fireEvent.click(submitButton());

    expect(await screen.findByText("已提交上报")).toBeInTheDocument();
    expect(screen.getByText("DEMO-HZ-008")).toBeInTheDocument();
    expect(screen.getByText(/没有通知任何人/)).toBeInTheDocument();
    expect(submit).toHaveBeenCalledTimes(1);
    const [draft, context] = submit.mock.calls[0] as [FindingDraft, FindingSubmitContext];
    expect(draft).toEqual({
      source: "FIELD_OBSERVATION",
      description: "通道护栏连接件松动",
      location: "演示仓库 · 北侧通道",
      foundAt: "2026-10-30T14:30",
      urgency: "URGENT",
      interimMeasure: "",
    });
    expect(context.idempotencyKey).toBe("key-1");
    expect(props.onSubmitted).toHaveBeenCalledWith(RESULT);
  });

  it("提交期间禁用表单与关闭按钮，重复触发提交只调用一次", async () => {
    const gate = deferred<FindingSubmitResult>();
    const { submit } = setup(() => gate.promise);
    fillValid();
    fireEvent.click(submitButton());

    const pending = await screen.findByRole("button", { name: "提交中…" });
    expect(pending).toBeDisabled();
    expect(screen.getByRole("button", { name: "关闭上报" })).toBeDisabled();
    expect(field(/隐患描述/)).toBeDisabled();
    fireEvent.submit(pending.closest("form")!);
    expect(submit).toHaveBeenCalledTimes(1);

    await act(async () => gate.resolve(RESULT));
    expect(await screen.findByText("已提交上报")).toBeInTheDocument();
  });

  it("同一 React 批次连续触发表单提交时只发送一次请求和成功回调", async () => {
    const gate = deferred<FindingSubmitResult>();
    const { submit, props } = setup(() => gate.promise);
    fillValid();
    const form = submitButton().closest("form")!;
    act(() => {
      form.requestSubmit();
      form.requestSubmit();
    });
    expect(submit).toHaveBeenCalledTimes(1);
    await act(async () => gate.resolve(RESULT));
    expect(await screen.findByText("已提交上报")).toBeInTheDocument();
    expect(props.onSubmitted).toHaveBeenCalledTimes(1);
  });

  it("服务不可用：保留已填内容，提示可安全重试；重试复用同一个幂等键", async () => {
    let attempts = 0;
    const { submit, props } = setup(async () => {
      attempts += 1;
      if (attempts === 1) throw new FindingSubmitError("unavailable");
      return RESULT;
    });
    fillValid();
    fireEvent.click(submitButton());

    expect(await screen.findByRole("alert")).toHaveTextContent("重试不会重复创建记录");
    expect(field(/隐患描述/)).toHaveValue("  通道护栏连接件松动  ");
    const retry = screen.getByRole("button", { name: "重试提交" });

    fireEvent.click(retry);
    expect(await screen.findByText("已提交上报")).toBeInTheDocument();
    expect(submit).toHaveBeenCalledTimes(2);
    const keys = submit.mock.calls.map((call) => (call[1] as FindingSubmitContext).idempotencyKey);
    expect(keys).toEqual(["key-1", "key-1"]);
    expect(props.createKey).toHaveBeenCalledTimes(1);
  });

  it("失败后修改内容再提交，视为新的命令，使用新的幂等键", async () => {
    let attempts = 0;
    const { submit } = setup(async () => {
      attempts += 1;
      if (attempts === 1) throw new FindingSubmitError("unavailable");
      return RESULT;
    });
    fillValid();
    fireEvent.click(submitButton());
    await screen.findByRole("alert");

    fireEvent.change(field(/隐患描述/), { target: { value: "通道护栏连接件严重松动" } });
    fireEvent.click(screen.getByRole("button", { name: "重试提交" }));
    await screen.findByText("已提交上报");
    const keys = submit.mock.calls.map((call) => (call[1] as FindingSubmitContext).idempotencyKey);
    expect(keys).toEqual(["key-1", "key-2"]);
  });

  it("服务端校验失败：显示服务端给出的字段错误并聚焦", async () => {
    setup(async () => {
      throw new FindingSubmitError("validation", { description: "描述包含不允许的内容" });
    });
    fillValid();
    fireEvent.click(submitButton());
    expect(await screen.findByText("描述包含不允许的内容")).toBeInTheDocument();
    expect(screen.getAllByRole("alert").some((node) => node.textContent?.includes("服务端校验未通过"))).toBe(true);
    // 异步失败路径：等字段重新可编辑后才会聚焦，不假设与错误文字同一时刻完成。
    await waitFor(() => expect(field(/隐患描述/)).toHaveFocus());
  });

  it.each([
    ["forbidden", "你没有提交隐患上报的权限。"],
    ["conflict", "内容不一致，已被拒绝"],
  ] as const)("%s：显示对应提示，保留内容，且不是“可重试”", async (kind, text) => {
    setup(async () => {
      throw new FindingSubmitError(kind);
    });
    fillValid();
    fireEvent.click(submitButton());
    expect(await screen.findByRole("alert")).toHaveTextContent(text);
    expect(screen.queryByRole("button", { name: "重试提交" })).not.toBeInTheDocument();
    expect(field(/所在位置/)).toHaveValue("演示仓库 · 北侧通道");
  });

  it("未登录（401）：通知应用回到登录页", async () => {
    const { props } = setup(async () => {
      throw new FindingSubmitError("unauthenticated");
    });
    fillValid();
    fireEvent.click(submitButton());
    await waitFor(() => expect(props.onUnauthenticated).toHaveBeenCalledTimes(1));
  });

  it("未知错误按“不可用”处理，并允许重试", async () => {
    setup(async () => {
      throw new Error("boom");
    });
    fillValid();
    fireEvent.click(submitButton());
    expect(await screen.findByRole("button", { name: "重试提交" })).toBeInTheDocument();
  });

  it("补报提示与“需立即处置”警示按选择显示", () => {
    setup();
    expect(screen.queryByText(/将按补报处理/)).not.toBeInTheDocument();
    fireEvent.change(field(/发现时间/), { target: { value: "2026-10-27T09:00" } });
    expect(screen.getByText(/将按补报处理/)).toBeInTheDocument();

    expect(screen.queryByText(/不会触发任何通知或呼叫/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("radio", { name: "需立即处置" }));
    expect(screen.getByText(/不会触发任何通知或呼叫/)).toBeInTheDocument();
  });

  it("没有改动时 Escape / 取消直接关闭", () => {
    const { props } = setup();
    fireEvent.keyDown(field(/来源/), { key: "Escape" });
    expect(props.onClose).toHaveBeenCalledTimes(1);
  });

  it("有未提交内容时先确认：继续编辑保留内容，放弃才关闭", () => {
    const { props } = setup();
    fillValid();
    fireEvent.keyDown(field(/隐患描述/), { key: "Escape" });
    expect(props.onClose).not.toHaveBeenCalled();
    expect(screen.getByText("放弃已填写的内容？")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "继续编辑" }));
    expect(screen.queryByText("放弃已填写的内容？")).not.toBeInTheDocument();
    expect(field(/隐患描述/)).toHaveValue("  通道护栏连接件松动  ");

    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    fireEvent.click(screen.getByRole("button", { name: "放弃" }));
    expect(props.onClose).toHaveBeenCalledTimes(1);
  });

  it("“继续编辑”按钮不能被原地复用成提交按钮（否则真实浏览器里这次点击会变成提交）", () => {
    const { submit } = setup();
    fillValid();
    fireEvent.keyDown(field(/隐患描述/), { key: "Escape" });
    const keepEditing = screen.getByRole("button", { name: "继续编辑" });
    const discard = screen.getByRole("button", { name: "放弃" });

    fireEvent.click(keepEditing);

    // 真实浏览器在点击派发期间同步重渲染，随后按“此刻”的 type 执行默认动作：
    // 若被点击的节点被复用并变成了 type=submit 的“提交上报”，这次点击就会提交表单。
    // jsdom 里 fireEvent 会推迟重渲染复现不出提交，所以直接断言根因：旧节点必须被卸载而不是变身。
    expect(keepEditing.isConnected).toBe(false);
    expect(discard.isConnected).toBe(false);
    expect(keepEditing).not.toHaveAttribute("type", "submit");
    expect(submit).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "提交上报" })).toBeInTheDocument();
    expect(field(/隐患描述/)).toHaveValue("  通道护栏连接件松动  ");
  });

  it("有未提交内容时刷新/关闭标签页会触发浏览器原生确认；没有改动或提交成功后不会", async () => {
    setup();
    const pristine = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(pristine);
    expect(pristine.defaultPrevented).toBe(false);

    fillValid();
    const dirty = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);

    fireEvent.click(submitButton());
    await screen.findByText("已提交上报");
    const done = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(done);
    expect(done.defaultPrevented).toBe(false);
  });

  it("后端没有对应字段的四项带“后端暂未接入”标记，发现时间和描述不带", () => {
    setup();
    const tags = screen.getAllByText("后端暂未接入");
    expect(tags).toHaveLength(4);

    // 标记在标签 / 图例内，读屏会和字段名一起读到。
    expect(field(/来源.*后端暂未接入/)).toBeInTheDocument();
    expect(field(/所在位置.*后端暂未接入/)).toBeInTheDocument();
    expect(field(/已采取的临时措施.*后端暂未接入/)).toBeInTheDocument();
    expect(screen.getByRole("group", { name: /紧急程度.*后端暂未接入/ })).toBeInTheDocument();

    expect((field(/发现时间/) as HTMLInputElement).labels?.[0]).not.toHaveTextContent("后端暂未接入");
    expect((field(/隐患描述/) as HTMLTextAreaElement).labels?.[0]).not.toHaveTextContent("后端暂未接入");
  });

  it("成功页写明：未接入的四项接入真实后端后目前不会被保存", async () => {
    setup();
    fillValid();
    fireEvent.click(submitButton());
    const status = await screen.findByRole("status");
    expect(status).toHaveTextContent(/来源.*所在位置.*紧急程度.*临时措施.*后端暂未接入.*不会被保存/);
  });

  it("成功页出现的那一刻刷新确认就已解除，不留下“已提交却仍拦截关闭标签页”的窗口", async () => {
    setup();
    fillValid();
    // DOM 一变化就同步检查守卫（微任务早于 React 的被动 effect 清理），所以不依赖时序运气。
    let guardWhenSuccessAppeared: boolean | null = null;
    const observer = new MutationObserver(() => {
      if (guardWhenSuccessAppeared !== null || !screen.queryByText("已提交上报")) return;
      const probe = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(probe);
      guardWhenSuccessAppeared = probe.defaultPrevented;
    });
    observer.observe(document.body, { childList: true, subtree: true });
    fireEvent.click(submitButton());
    await screen.findByText("已提交上报");
    observer.disconnect();
    expect(guardWhenSuccessAppeared).toBe(false);
  });

  it("成功后可查看详情、继续上报（重置表单并换新的幂等键）或关闭", async () => {
    const { props, submit } = setup();
    fillValid();
    fireEvent.click(submitButton());
    await screen.findByText("已提交上报");

    fireEvent.click(screen.getByRole("button", { name: "查看详情" }));
    expect(props.onOpenSubmitted).toHaveBeenCalledWith("00000000-0000-4000-8000-000000000008");

    fireEvent.click(screen.getByRole("button", { name: "继续上报" }));
    expect(field(/隐患描述/)).toHaveValue("");
    expect(field(/所在位置/)).toHaveValue("");
    fillValid();
    fireEvent.click(submitButton());
    await screen.findByText("已提交上报");
    const keys = submit.mock.calls.map((call) => (call[1] as FindingSubmitContext).idempotencyKey);
    expect(keys).toEqual(["key-1", "key-2"]);
  });

  it("面板卸载时取消进行中的请求，且不再更新界面", async () => {
    const gate = deferred<FindingSubmitResult>();
    let signal: AbortSignal | undefined;
    const { unmount, props } = setup((_draft, context) => {
      signal = context.signal;
      return gate.promise;
    });
    fillValid();
    fireEvent.click(submitButton());
    await screen.findByRole("button", { name: "提交中…" });
    unmount();
    expect(signal?.aborted).toBe(true);
    await act(async () => gate.resolve(RESULT));
    expect(props.onSubmitted).not.toHaveBeenCalled();
  });
});

describe("所属组织选择", () => {
  const orgs = [
    { id: "o1", code: "A", name: "一车间" },
    { id: "o2", code: "B", name: "二车间" },
  ];
  function withOrgs(listOrganizations: FindingSubmitAdapter["listOrganizations"]) {
    const adapter: FindingSubmitAdapter = { submit: vi.fn(async () => RESULT), listOrganizations };
    render(
      <FindingReportForm adapter={adapter} onClose={vi.fn()} onSubmitted={vi.fn()} onOpenSubmitted={vi.fn()} now={() => NOW} />,
    );
    return adapter;
  }

  it("必须先选择组织，选择后随提交带上 organizationId", async () => {
    const adapter = withOrgs(async () => orgs);
    await screen.findByRole("option", { name: "二车间" });
    fillValid();
    fireEvent.click(submitButton());
    expect(await screen.findByText("请选择所属组织")).toBeInTheDocument();
    expect(adapter.submit).not.toHaveBeenCalled();

    fireEvent.change(field(/所属组织/), { target: { value: "o2" } });
    fireEvent.click(submitButton());
    await waitFor(() => expect(adapter.submit).toHaveBeenCalled());
    expect(vi.mocked(adapter.submit).mock.calls[0]![1]).toMatchObject({ organizationId: "o2" });
  });

  it("缺组织时焦点落到该字段并用 aria-describedby 关联错误；选择后错误消失", async () => {
    withOrgs(async () => orgs);
    await screen.findByRole("option", { name: "二车间" });
    fillValid();
    fireEvent.click(submitButton());
    const select = field(/所属组织/);
    await waitFor(() => expect(document.activeElement).toBe(select));
    const error = screen.getByText("请选择所属组织");
    expect(select).toHaveAttribute("aria-describedby", error.id);
    expect(select).toHaveAttribute("aria-invalid", "true");
    fireEvent.change(select, { target: { value: "o1" } });
    await waitFor(() => expect(screen.queryByText("请选择所属组织")).not.toBeInTheDocument());
  });

  it("用户亲自选了组织就算有未提交内容：Esc 先确认；自动选中的单个组织不算", async () => {
    const adapter: FindingSubmitAdapter = { submit: vi.fn(async () => RESULT), listOrganizations: async () => orgs };
    const onClose = vi.fn();
    const { unmount } = render(<FindingReportForm adapter={adapter} onClose={onClose} onSubmitted={vi.fn()} onOpenSubmitted={vi.fn()} now={() => NOW} />);
    await screen.findByRole("option", { name: "二车间" });
    fireEvent.change(field(/所属组织/), { target: { value: "o2" } });
    fireEvent.keyDown(screen.getByRole("complementary"), { key: "Escape" });
    expect(await screen.findByText("放弃已填写的内容？")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    unmount();

    const single: FindingSubmitAdapter = { submit: vi.fn(async () => RESULT), listOrganizations: async () => [orgs[0]!] };
    render(<FindingReportForm adapter={single} onClose={onClose} onSubmitted={vi.fn()} onOpenSubmitted={vi.fn()} now={() => NOW} />);
    await waitFor(() => expect(field(/所属组织/)).toHaveValue("o1"));
    fireEvent.keyDown(screen.getByRole("complementary"), { key: "Escape" });
    expect(onClose).toHaveBeenCalled();
  });

  it("只有一个组织时自动选中", async () => {
    const adapter = withOrgs(async () => [orgs[0]!]);
    await waitFor(() => expect(field(/所属组织/)).toHaveValue("o1"));
    fillValid();
    fireEvent.click(submitButton());
    await waitFor(() => expect(adapter.submit).toHaveBeenCalled());
  });

  it("没有可上报的组织时说明原因并禁用提交", async () => {
    withOrgs(async () => []);
    expect(await screen.findByText(/没有可上报的组织/)).toBeInTheDocument();
    expect(submitButton()).toBeDisabled();
  });

  it("加载失败可重试", async () => {
    const list = vi.fn().mockRejectedValueOnce(new FindingSubmitError("unavailable")).mockResolvedValueOnce(orgs);
    withOrgs(list);
    fireEvent.click(await screen.findByRole("button", { name: "重试" }));
    expect(await screen.findByRole("option", { name: "一车间" })).toBeInTheDocument();
    expect(list).toHaveBeenCalledTimes(2);
  });
});

describe("published create form and bounded organization search", () => {
  const orgA = { id: "00000000-0000-4000-8000-000000000101", code: "A", name: "甲车间" };
  const orgB = { id: "00000000-0000-4000-8000-000000000102", code: "B", name: "乙车间" };

  it.each([false,true])("revalidates restored organization before submission (current page includes old ID: %s)",async includesOld=>{
    const draftKey=`restore-org-${includesOld}`;
    stashDraft(draftKey,{draft:{...createEmptyDraft(NOW),location:"恢复的位置",description:"恢复的隐患描述"},organizationId:orgA.id});
    const pending=deferred<{items:typeof orgA[]}>();
    const adapter:FindingSubmitAdapter={loadDefinition:async()=>findingDefinitionFixture("CREATE"),queryOrganizations:vi.fn(()=>pending.promise),submit:vi.fn(async()=>RESULT)};
    setup(undefined,{adapter,draftKey});
    const org=await screen.findByLabelText(/所属组织/);
    await waitFor(()=>expect(adapter.queryOrganizations).toHaveBeenCalledOnce());
    expect(org).toHaveValue("");expect(submitButton()).toBeDisabled();
    fireEvent.submit(submitButton().closest("form")!);expect(adapter.submit).not.toHaveBeenCalled();
    await act(async()=>pending.resolve({items:[includesOld?orgA:orgB]}));
    await waitFor(()=>expect(org).toBeEnabled());
    expect(org).toHaveValue(includesOld?orgA.id:"");
    fireEvent.submit(submitButton().closest("form")!);
    if(includesOld){await waitFor(()=>expect(adapter.submit).toHaveBeenCalledOnce());expect((adapter.submit as ReturnType<typeof vi.fn>).mock.calls[0]?.[1]).toMatchObject({organizationId:orgA.id});}
    else{expect(adapter.submit).not.toHaveBeenCalled();expect(screen.getByText("请选择所属组织")).toBeInTheDocument();}
  });

  it("uses published labels/order and never auto-selects a one-item page", async () => {
    const definition = findingDefinitionFixture("CREATE"); definition.fields.description!.presentation.label = "现场描述";
    const adapter: FindingSubmitAdapter = { loadDefinition: async () => definition, queryOrganizations: vi.fn(async () => ({ items: [orgA], nextAfterId: orgA.id })), submit: vi.fn(async () => RESULT) };
    setup(undefined, { adapter });
    const description = await screen.findByLabelText(/现场描述/);
    const org = await screen.findByLabelText(/所属组织/);
    await waitFor(() => expect(org).toBeEnabled());
    expect(org).toHaveValue("");
    expect(org.compareDocumentPosition(description) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(field(/所在位置/)).not.toHaveAttribute("maxlength");
    expect(description).toHaveAttribute("maxlength", "20000");
    expect(screen.queryByLabelText(/来源事件|关联风险|关联隐患/)).not.toBeInTheDocument();
    fireEvent.change(description, { target: { value: "短" } });
    fireEvent.change(field(/所在位置/), { target: { value: "😀".repeat(200) } });
    fireEvent.click(submitButton());
    expect(adapter.submit).not.toHaveBeenCalled();
    expect(screen.getByText("请选择所属组织")).toBeInTheDocument();
    fireEvent.change(org, { target: { value: orgA.id } });
    fireEvent.click(submitButton());
    await waitFor(() => expect(adapter.submit).toHaveBeenCalledOnce());
    expect(adapter.queryOrganizations).toHaveBeenCalledTimes(1);
  });

  it("requests only the selected next page and retains the selected stable ID", async () => {
    const query = vi.fn(async ({ afterId }: { afterId?: string }) => afterId ? { items: [orgB] } : { items: [orgA], nextAfterId: orgA.id });
    const adapter: FindingSubmitAdapter = { loadDefinition: async () => findingDefinitionFixture("CREATE"), queryOrganizations: query, submit: vi.fn(async () => RESULT) };
    setup(undefined, { adapter });
    const org = await screen.findByLabelText(/所属组织/);
    await screen.findByRole("button", { name: "下一页组织" });
    fireEvent.change(org, { target: { value: orgA.id } });
    fireEvent.click(screen.getByRole("button", { name: "下一页组织" }));
    await screen.findByRole("option", { name: /乙车间/ });
    expect(org).toHaveValue(orgA.id);
    expect(screen.getByRole("option", { name: /甲车间.*已选/ })).toBeInTheDocument();
    expect(query).toHaveBeenCalledTimes(2);
    expect(query.mock.calls[1]?.[0]).toEqual({ query: "", afterId: orgA.id, limit: 20 });
  });

  it("aborts obsolete search and ignores stale results even if the port ignores cancellation", async () => {
    const old = deferred<{ items: typeof orgA[] }>();
    const query = vi.fn(({ query }: { query: string }) => query ? Promise.resolve({ items: [orgB] }) : old.promise);
    const adapter: FindingSubmitAdapter = { loadDefinition: async () => findingDefinitionFixture("CREATE"), queryOrganizations: query, submit: async () => RESULT };
    setup(undefined, { adapter });
    const search = await screen.findByLabelText("检索组织");
    await waitFor(() => expect(query).toHaveBeenCalledTimes(1));
    fireEvent.change(search, { target: { value: "乙" } });
    await screen.findByRole("option", { name: /乙车间/ });
    await act(async () => old.resolve({ items: [orgA] }));
    expect(screen.queryByRole("option", { name: /甲车间/ })).not.toBeInTheDocument();
    expect((query.mock.calls[0] as unknown[])[1]).toMatchObject({ aborted: true });
  });

  it("clears old draft, selection and pending results when the adapter context changes", async () => {
    const old = deferred<{ items: typeof orgA[] }>();
    const first: FindingSubmitAdapter = { loadDefinition: async () => findingDefinitionFixture("CREATE"), queryOrganizations: () => old.promise, submit: async () => RESULT };
    const second: FindingSubmitAdapter = { loadDefinition: async () => findingDefinitionFixture("CREATE"), queryOrganizations: async () => ({ items: [orgB] }), submit: async () => RESULT };
    const view = setup(undefined, { adapter: first });
    fireEvent.change(await screen.findByLabelText(/隐患描述/), { target: { value: "上个身份的内容" } });
    view.rerender(<FindingReportForm {...view.props} adapter={second} />);
    await screen.findByRole("option", { name: /乙车间/ });
    expect(field(/隐患描述/)).toHaveValue("");
    expect(field(/所属组织/)).toHaveValue("");
    await act(async () => old.resolve({ items: [orgA] }));
    expect(screen.queryByRole("option", { name: /甲车间/ })).not.toBeInTheDocument();
  });

  it("does not fall back to synthetic fields or submit when the definition is denied", async () => {
    const adapter: FindingSubmitAdapter = { loadDefinition: async () => { throw new FindingSubmitError("forbidden"); }, queryOrganizations: vi.fn(), submit: vi.fn() };
    setup(undefined, { adapter });
    await screen.findByText(/上报定义读取失败/);
    expect(screen.queryByLabelText(/隐患描述/)).not.toBeInTheDocument();
    expect(submitButton()).toBeDisabled();
    expect(adapter.queryOrganizations).not.toHaveBeenCalled();
    expect(adapter.submit).not.toHaveBeenCalled();
  });
});
