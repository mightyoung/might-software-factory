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
  const adapter: FindingCommandAdapter = { run: vi.fn(run) };
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
    expect(screen.getAllByText(/成员查询接口/).length).toBeGreaterThan(0);
    cleanup();
    setup("RECTIFYING");
    expect(screen.getByRole("button", { name: "提交整改证据" })).toBeDisabled();
    cleanup();
    setup("ACCEPTANCE_PENDING");
    expect(screen.getByRole("button", { name: "验收通过" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "退回整改" })).toBeEnabled();
    cleanup();
    setup("CLOSED");
    expect(screen.getByText("已闭环，不可再变更")).toBeInTheDocument();
  });

  it("没有版本号时不能执行", () => {
    render(<FindingCommandPanel finding={record("PERSON_CONFIRM_PENDING", null)} adapter={{ run: vi.fn() }} onChanged={vi.fn()} />);
    expect(screen.getByRole("button", { name: "确认发现" })).toBeDisabled();
    expect(screen.getByText(/没有版本号/)).toBeInTheDocument();
  });

  it("必填意见为空或只有空白时拦截，不发请求", () => {
    const { run } = setup("PERSON_CONFIRM_PENDING");
    fireEvent.click(screen.getByRole("button", { name: "确认发现" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "  \u0085 " } });
    fireEvent.click(screen.getByRole("button", { name: "确认确认发现" }));
    expect(screen.getByText("请填写意见")).toBeInTheDocument();
    expect(run).not.toHaveBeenCalled();
  });

  it("安全确认必须选等级；带 expectedVersion 与 trim 后的意见提交，成功后通知刷新", async () => {
    const { run, onChanged } = setup("SAFETY_CONFIRM_PENDING");
    fireEvent.click(screen.getByRole("button", { name: "安全确认" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "  属实  " } });
    fireEvent.click(screen.getByRole("button", { name: "确认安全确认" }));
    expect(await screen.findByText("请选择隐患等级")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/隐患等级/), { target: { value: "MAJOR" } });
    fireEvent.click(screen.getByRole("button", { name: "确认安全确认" }));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(run).toHaveBeenCalledWith("f1", 4, { kind: "confirmSafety", reason: "属实", grade: "MAJOR" }, expect.objectContaining({ idempotencyKey: expect.any(String) }));
    expect(screen.getByRole("status")).toHaveTextContent("已完成“安全确认”");
  });

  it("验收：退回必须有意见，通过的意见选填", async () => {
    const { run } = setup("ACCEPTANCE_PENDING");
    fireEvent.click(screen.getByRole("button", { name: "退回整改" }));
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
    fireEvent.click(screen.getByRole("button", { name: "确认发现" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "属实" } });
    fireEvent.click(screen.getByRole("button", { name: "确认确认发现" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("重试不会重复执行");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
    expect(run.mock.calls[0]![3].idempotencyKey).toBe(run.mock.calls[1]![3].idempotencyKey);
  });

  it("版本冲突提示并要求刷新；未登录通知应用", async () => {
    const conflict = setup("PERSON_CONFIRM_PENDING", async () => { throw new FindingCommandError("versionConflict"); });
    fireEvent.click(screen.getByRole("button", { name: "确认发现" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "属实" } });
    fireEvent.click(screen.getByRole("button", { name: "确认确认发现" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("刚被他人更新");
    expect(conflict.onChanged).toHaveBeenCalled();
    cleanup();
    const expired = setup("PERSON_CONFIRM_PENDING", async () => { throw new FindingCommandError("unauthenticated"); });
    fireEvent.click(screen.getByRole("button", { name: "确认发现" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "属实" } });
    fireEvent.click(screen.getByRole("button", { name: "确认确认发现" }));
    await waitFor(() => expect(expired.onUnauthenticated).toHaveBeenCalled());
  });

  it("详情重新读取期间按钮不可用，避免带着过期版本号提交", () => {
    const adapter = { run: vi.fn() };
    const { rerender } = render(<FindingCommandPanel finding={record("PERSON_CONFIRM_PENDING")} adapter={adapter} onChanged={vi.fn()} refreshing />);
    expect(screen.getByRole("button", { name: "确认发现" })).toBeDisabled();
    expect(screen.getByText("正在重新读取最新状态…")).toBeInTheDocument();
    rerender(<FindingCommandPanel finding={record("PERSON_CONFIRM_PENDING")} adapter={adapter} onChanged={vi.fn()} refreshing={false} />);
    expect(screen.getByRole("button", { name: "确认发现" })).toBeEnabled();
  });
});
