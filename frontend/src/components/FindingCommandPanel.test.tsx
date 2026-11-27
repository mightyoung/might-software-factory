import { QueryClient } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FindingCommandPanel } from "./FindingCommandPanel";
import { FindingCommandError, type FindingCommandAdapter } from "../adapters/findingCommands";
import type { FindingRecord, FindingState } from "../domain/finding";

const record = (state: FindingState, version: number | null = 4): FindingRecord => ({
  id: "f1", code: "YH-1", description: "d", location: "l", state, reportedAt: "2026-10-30T09:00:00+08:00",
  reporter: "—", responsible: "—", riskLabel: "待专业评估", round: 1, history: [], ...(version === null ? {} : { version }),
});
const okResult = { state: "SAFETY_CONFIRM_PENDING" as const, roundNo: 1, version: 5 };

function setup(state: FindingState, run: FindingCommandAdapter["run"] = async () => okResult) {
  const adapter: FindingCommandAdapter = { run: vi.fn(run), history: async () => ({
    entries: [], currentVersion: 4, currentState: state, reviewCycle: 1, reviewProtocol: "LEGACY", processDefinitionKey: "findingRemediationV2",
    reviewStage: state === "PERSON_CONFIRM_PENDING" ? "PERSON" : state === "SAFETY_CONFIRM_PENDING" ? "SAFETY" : null,
    availableReviewActions: ["APPROVE"], blockedReason: null, hasMore: false, nextCursor: null,
  }) };
  const onChanged = vi.fn();
  const onUnauthenticated = vi.fn();
  render(<FindingCommandPanel finding={record(state)} adapter={adapter} onChanged={onChanged} onUnauthenticated={onUnauthenticated} />);
  return { adapter, onChanged, onUnauthenticated, run: adapter.run as ReturnType<typeof vi.fn> };
}
afterEach(cleanup);

describe("流程命令面板", () => {
  it("按状态只显示契约已有的动作，其余禁用并写明原因", () => {
    setup("SAFETY_DISPATCH_PENDING");
    expect(screen.getByRole("button", { name: "分派整改" })).toBeDisabled();
    expect(screen.getAllByText(/候选查询能力/).length).toBeGreaterThan(0);
    cleanup();
    setup("ACCEPTANCE_PENDING");
    expect(screen.getByRole("button", { name: "验收通过" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "退回整改" })).toBeEnabled();
    cleanup();
    setup("CLOSED");
    expect(screen.getByText("已闭环，不可再变更")).toBeInTheDocument();
  });

  it("未成立终态不提供流程操作，并提示新证据创建关联记录", () => {
    const { run } = setup("NOT_ESTABLISHED");
    expect(screen.getByText("未成立记录已终止；新证据应创建关联记录")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.queryByRole("form")).not.toBeInTheDocument();
    expect(run).not.toHaveBeenCalled();
  });

  it("缺少服务端操作快照时不提供确认动作", () => {
    render(<FindingCommandPanel finding={record("PERSON_CONFIRM_PENDING", null)} adapter={{ run: vi.fn() }} onChanged={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "确认发现" })).not.toBeInTheDocument();
  });

  it("rejects a grade outside the workflow input contract before sending a command", async () => {
    const { run } = setup("SAFETY_CONFIRM_PENDING");
    fireEvent.click(await screen.findByRole("button", { name: "安全确认" }));
    fireEvent.change(screen.getByRole("textbox", { name: /意见/ }), { target: { value: "确认现场情况" } });
    const grade = screen.getByRole("combobox", { name: /隐患等级/ });
    grade.append(new Option("无效", "INVALID"));
    fireEvent.change(grade, { target: { value: "INVALID" } });
    fireEvent.click(screen.getByRole("button", { name: "确认安全确认" }));
    expect(screen.getByText("请选择隐患等级")).toBeInTheDocument();
    expect(run).not.toHaveBeenCalled();
  });

  it("必填意见为空或只有空白时拦截，不发请求", async () => {
    const { run } = setup("PERSON_CONFIRM_PENDING");
    fireEvent.click(await screen.findByRole("button", { name: "确认发现" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "  \u0085 " } });
    fireEvent.click(screen.getByRole("button", { name: "确认确认发现" }));
    expect(screen.getByText("请填写意见")).toBeInTheDocument();
    expect(run).not.toHaveBeenCalled();
  });

  it("安全确认必须选等级；带 expectedVersion 与 trim 后的意见提交，成功后通知刷新", async () => {
    const { run, onChanged } = setup("SAFETY_CONFIRM_PENDING");
    fireEvent.click(await screen.findByRole("button", { name: "安全确认" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "  属实  " } });
    fireEvent.click(screen.getByRole("button", { name: "确认安全确认" }));
    expect(await screen.findByText("请选择隐患等级")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/隐患等级/), { target: { value: "MAJOR" } });
    fireEvent.click(screen.getByRole("button", { name: "确认安全确认" }));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(run).toHaveBeenCalledWith("f1", 4, { kind: "confirmSafety", reason: "属实", grade: "MAJOR" }, expect.objectContaining({ idempotencyKey: expect.any(String) }));
    expect(screen.getByText(/已完成“安全确认”/)).toBeInTheDocument();
  });

  it("验收：退回必须有意见，通过的意见选填", async () => {
    const { run } = setup("ACCEPTANCE_PENDING");
    fireEvent.click(screen.getByRole("button", { name: "退回整改" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "确认退回整改" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "确认退回整改" }));
    expect(screen.getByText("请填写意见")).toBeInTheDocument();
    expect(run).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "验收通过" }));
    fireEvent.click(screen.getByRole("button", { name: "确认验收通过" }));
    await waitFor(() => expect(run).toHaveBeenCalledWith("f1", 4, { kind: "verify", decision: "PASS" }, expect.anything()));
  });

  it("可重试的失败复用同一个幂等键", async () => {
    const run = vi.fn().mockRejectedValueOnce(new FindingCommandError("unavailable")).mockResolvedValueOnce(okResult);
    setup("PERSON_CONFIRM_PENDING", run);
    fireEvent.click(await screen.findByRole("button", { name: "确认发现" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "属实" } });
    fireEvent.click(screen.getByRole("button", { name: "确认确认发现" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("重试不会重复执行");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
    expect(run.mock.calls[0]![3].idempotencyKey).toBe(run.mock.calls[1]![3].idempotencyKey);
  });

  it("版本冲突提示并要求刷新；未登录通知应用", async () => {
    const conflict = setup("PERSON_CONFIRM_PENDING", async () => { throw new FindingCommandError("versionConflict"); });
    fireEvent.click(await screen.findByRole("button", { name: "确认发现" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "属实" } });
    fireEvent.click(screen.getByRole("button", { name: "确认确认发现" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("刚被他人更新");
    expect(conflict.onChanged).toHaveBeenCalled();
    cleanup();
    const expired = setup("PERSON_CONFIRM_PENDING", async () => { throw new FindingCommandError("unauthenticated"); });
    fireEvent.click(await screen.findByRole("button", { name: "确认发现" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "属实" } });
    fireEvent.click(screen.getByRole("button", { name: "确认确认发现" }));
    await waitFor(() => expect(expired.onUnauthenticated).toHaveBeenCalled());
  });

  it("详情重新读取期间按钮不可用，避免带着过期版本号提交", () => {
    const adapter = { run: vi.fn() };
    const { rerender } = render(<FindingCommandPanel finding={record("DISCOVERED")} adapter={adapter} onChanged={vi.fn()} refreshing />);
    expect(screen.getByRole("button", { name: "启动确认流程" })).toBeDisabled();
    expect(screen.getByText("正在重新读取最新状态…")).toBeInTheDocument();
    rerender(<FindingCommandPanel finding={record("DISCOVERED")} adapter={adapter} onChanged={vi.fn()} refreshing={false} />);
    expect(screen.getByRole("button", { name: "启动确认流程" })).toBeEnabled();
  });
});

describe("分派整改", () => {
  const page = (items: { memberId: string; displayName: string }[], extra = {}) => ({
    items, hasMore: false, next: null, findingVersion: 4, state: "SAFETY_DISPATCH_PENDING" as const, roundNo: 1, ...extra,
  });
  type Search = NonNullable<FindingCommandAdapter["searchDispatchCandidates"]>;
  const people = [{ memberId: "m1", displayName: "张三" }, { memberId: "m2", displayName: "张伟" }];

  function dispatchSetup(search: Search = vi.fn(async () => page(people)), state: FindingState = "SAFETY_DISPATCH_PENDING") {
    const adapter: FindingCommandAdapter = { run: vi.fn(async () => okResult), searchDispatchCandidates: search };
    const onChanged = vi.fn();
    render(<FindingCommandPanel finding={record(state)} adapter={adapter} onChanged={onChanged} />);
    return { adapter, search, onChanged, run: adapter.run as ReturnType<typeof vi.fn> };
  }

  it("搜索候选、选择责任人、填写期限与要求后提交；期限按北京时间换算", async () => {
    const { run, search, onChanged } = dispatchSetup();
    fireEvent.click(screen.getByRole("button", { name: "分派整改" }));
    fireEvent.click(await screen.findByLabelText("张伟"));
    expect(await screen.findByText("已选：张伟")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/整改期限/), { target: { value: "2026-12-01T18:00" } });
    fireEvent.change(screen.getByLabelText(/整改要求/), { target: { value: "  加装防护罩并复测  " } });
    fireEvent.click(screen.getByRole("button", { name: "确认分派整改" }));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(run).toHaveBeenCalledWith("f1", 4, { kind: "dispatch", assigneeMemberId: "m2", dueAt: "2026-12-01T18:00:00+08:00", requirements: "加装防护罩并复测" }, expect.anything());
    expect(search).toHaveBeenCalledWith("f1", expect.objectContaining({ prefix: "", limit: 20 }), expect.anything());
  });

  it("三项都必填：未选人、没有期限、要求只有空白都拦截且不发请求", () => {
    const { run } = dispatchSetup();
    fireEvent.click(screen.getByRole("button", { name: "分派整改" }));
    fireEvent.change(screen.getByLabelText(/整改要求/), { target: { value: " \u0085 " } });
    fireEvent.click(screen.getByRole("button", { name: "确认分派整改" }));
    expect(screen.getByText("请选择整改责任人")).toBeInTheDocument();
    expect(screen.getByText("请填写整改期限")).toBeInTheDocument();
    expect(screen.getByText("请填写整改要求")).toBeInTheDocument();
    expect(run).not.toHaveBeenCalled();
  });

  it("输入姓名开头会防抖后重新搜索；没有结果时说明", async () => {
    const search: Search = vi.fn(async (_id, q) => page(q.prefix === "赵" ? [] : people));
    dispatchSetup(search);
    fireEvent.click(screen.getByRole("button", { name: "分派整改" }));
    await screen.findByLabelText("张三");
    fireEvent.change(screen.getByLabelText("按姓名开头搜索成员"), { target: { value: "赵" } });
    expect(await screen.findByText("没有符合条件的成员。")).toBeInTheDocument();
    expect(search).toHaveBeenLastCalledWith("f1", expect.objectContaining({ prefix: "赵" }), expect.anything());
  });

  it("cancels the old scoped query after an input change and never writes on search or denial", async () => {
    const query = vi.spyOn(QueryClient.prototype, "fetchQuery");
    let finishOld!: (value: ReturnType<typeof page>) => void;
    const old = new Promise<ReturnType<typeof page>>((resolve) => { finishOld = resolve; });
    const search = vi.fn().mockReturnValueOnce(old).mockRejectedValueOnce(new FindingCommandError("forbidden"));
    try {
      const { run } = dispatchSetup(search);
      fireEvent.click(screen.getByRole("button", { name: "分派整改" }));
      await waitFor(() => expect(search).toHaveBeenCalledTimes(1));
      expect(query.mock.calls.filter(([call]) => call.queryKey[0] === "dispatch-candidates")).toHaveLength(1);
      const oldSignal: AbortSignal = search.mock.calls[0][2];
      fireEvent.change(screen.getByRole("searchbox"), { target: { value: "赵" } });
      expect(oldSignal.aborted).toBe(true);
      expect(search).toHaveBeenCalledTimes(1);
      finishOld(page(people));
      expect(await screen.findByRole("alert")).toHaveTextContent("无权");
      expect(search).toHaveBeenCalledTimes(2);
      expect(screen.queryByLabelText("张三")).not.toBeInTheDocument();
      expect(run).not.toHaveBeenCalled();
    } finally { query.mockRestore(); }
  });

  it("reports an expired candidate-lookup session exactly once, without retrying or writing", async () => {
    const onUnauthenticated = vi.fn();
    const adapter = { run: vi.fn(), searchDispatchCandidates: vi.fn().mockRejectedValue(new FindingCommandError("unauthenticated")) };
    render(<FindingCommandPanel finding={record("SAFETY_DISPATCH_PENDING")} adapter={adapter} onChanged={vi.fn()} onUnauthenticated={onUnauthenticated} />);
    fireEvent.click(screen.getByRole("button", { name: "分派整改" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("登录已失效");
    expect(onUnauthenticated).toHaveBeenCalledTimes(1);
    expect(adapter.searchDispatchCandidates).toHaveBeenCalledTimes(1);
    expect(adapter.run).not.toHaveBeenCalled();
  });

  it("分页：加载更多带上 afterMemberId 并追加", async () => {
    const search: Search = vi.fn(async (_id, q) =>
      q.afterMemberId ? page([{ memberId: "m3", displayName: "李四" }]) : page(people, { hasMore: true, next: "m2" }));
    dispatchSetup(search);
    fireEvent.click(screen.getByRole("button", { name: "分派整改" }));
    fireEvent.click(await screen.findByRole("button", { name: "加载更多" }));
    expect(await screen.findByLabelText("李四")).toBeInTheDocument();
    expect(screen.getByLabelText("张三")).toBeInTheDocument();
    expect(search).toHaveBeenLastCalledWith("f1", expect.objectContaining({ afterMemberId: "m2" }), expect.anything());
  });

  it("候选返回的版本与详情不一致：说明记录已变化，通知刷新", async () => {
    const { onChanged } = dispatchSetup(vi.fn(async () => page(people, { findingVersion: 5 })));
    fireEvent.click(screen.getByRole("button", { name: "分派整改" }));
    await screen.findByLabelText("张三");
    expect(onChanged).toHaveBeenCalled();
  });

  it("候选 409（当前轮已有责任人）给出明确提示；失败可重试", async () => {
    const search: Search = vi.fn().mockRejectedValueOnce(new FindingCommandError("stateConflict"));
    dispatchSetup(search);
    fireEvent.click(screen.getByRole("button", { name: "分派整改" }));
    expect(await screen.findByText(/当前状态不能分派/)).toBeInTheDocument();
  });

  it("验收退回后的新一轮：RECTIFYING 提供“重新分派”", async () => {
    dispatchSetup(undefined, "RECTIFYING");
    expect(screen.getByRole("button", { name: "重新分派" })).toBeEnabled();
  });
});
