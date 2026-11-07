import { within, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FindingDetails } from "./FindingDetails";
import { FindingCommandPanel } from "./FindingCommandPanel";
import { FindingReviewHistory } from "./FindingReviewHistory";
import { FindingCommandError, type FindingCommandAdapter, type FindingReviewHistory as History } from "../adapters/findingCommands";
import type { FindingRecord } from "../domain/finding";

const finding: FindingRecord = { id: "f", code: "YH-1", description: "d", location: "l", state: "SAFETY_CONFIRM_PENDING", reportedAt: "", reporter: "—", responsible: "—", riskLabel: "重大", round: 3, version: 1, history: [] };
const snapshot = (overrides: Partial<History> = {}): History => ({ entries: [], currentVersion: 12, currentState: "SAFETY_CONFIRM_PENDING", reviewCycle: 2, reviewProtocol: "CYCLE_V1", processDefinitionKey: "findingRemediationV3", reviewStage: "SAFETY", availableReviewActions: ["APPROVE", "RETURN_FOR_INFORMATION", "NOT_ESTABLISHED"], blockedReason: null, hasMore: false, nextCursor: null, ...overrides });
afterEach(cleanup);
function setup(data = snapshot(), run = vi.fn().mockResolvedValue({ state: "REPORTER_SUPPLEMENT_PENDING", roundNo: 3, version: 13, reviewCycle: 2 })) {
  const adapter: FindingCommandAdapter = { run, history: vi.fn().mockResolvedValue(data) };
  const props = { finding, adapter, onChanged: vi.fn() };
  const view = render(<FindingCommandPanel {...props} />);
  return { ...view, adapter, run, props };
}
describe("review cycles (mock HTTP authority snapshots)", () => {
  it("uses server stage/version/cycle; return omits grade even after an approval draft", async () => {
    const { run } = setup();
    fireEvent.click(await screen.findByRole("button", { name: "安全确认" }));
    fireEvent.change(screen.getByLabelText(/隐患等级/), { target: { value: "MAJOR" } });
    fireEvent.click(screen.getByRole("button", { name: "退回补充" }));
    expect(screen.queryByLabelText(/隐患等级/)).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "补充现场情况" } });
    fireEvent.click(screen.getByRole("button", { name: "确认退回补充" }));
    await waitFor(() => expect(run).toHaveBeenCalledWith("f", 12, { kind: "reviewDecision", reviewCycle: 2, stage: "SAFETY", decision: "RETURN_FOR_INFORMATION", reason: "补充现场情况" }, expect.anything()));
  });
  it("renders only server-authorized reporter supplement and submits returned cycle", async () => {
    const { run } = setup(snapshot({ currentState: "REPORTER_SUPPLEMENT_PENDING", reviewStage: null, availableReviewActions: ["SUPPLEMENT"] }));
    fireEvent.click(await screen.findByRole("button", { name: "补充说明" }));
    fireEvent.change(screen.getByRole("textbox", { name: /补充说明/ }), { target: { value: "新增说明" } });
    fireEvent.click(screen.getByRole("button", { name: "确认补充说明" }));
    await waitFor(() => expect(run).toHaveBeenCalledWith("f", 12, { kind: "supplement", reviewCycle: 2, statement: "新增说明" }, expect.anything()));
    cleanup();
    setup(snapshot({ currentState: "REPORTER_SUPPLEMENT_PENDING", reviewStage: null, availableReviewActions: [], blockedReason: "NOT_CURRENT_REVIEW_PARTICIPANT" }));
    expect(await screen.findByText(/指定确认人或原上报人/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "补充说明" })).not.toBeInTheDocument();
  });
  it("refuses unknown protocol/key pairs and absent task assignment", async () => {
    setup(snapshot({ processDefinitionKey: "futureProcess" }));
    expect(await screen.findByText(/暂不支持此操作/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "安全确认" })).not.toBeInTheDocument();
    cleanup();
    setup(snapshot({ availableReviewActions: [], blockedReason: "CURRENT_TASK_NOT_ASSIGNED" }));
    expect(await screen.findByText(/尚未分配给你/)).toBeInTheDocument();
  });
  it("uncertain retry retains exact payload/key/version despite refresh and disables editing", async () => {
    const run = vi.fn().mockRejectedValueOnce(new FindingCommandError("unavailable")).mockResolvedValueOnce({ state: "REPORTER_SUPPLEMENT_PENDING", roundNo: 3, version: 13 });
    const { adapter, props, rerender } = setup(snapshot(), run);
    fireEvent.click(await screen.findByRole("button", { name: "退回补充" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "保留说明" } });
    fireEvent.click(screen.getByRole("button", { name: "确认退回补充" }));
    await screen.findByRole("alert");
    expect(screen.getByLabelText(/意见/)).toBeDisabled();
    vi.mocked(adapter.history!).mockResolvedValue(snapshot({ currentVersion: 15, reviewCycle: 3, availableReviewActions: [] }));
    rerender(<FindingCommandPanel {...props} finding={{ ...finding, version: 15 }} />);
    await waitFor(() => expect(adapter.history).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByRole("button", { name: "重试" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
    expect(run.mock.calls[1]!.slice(0, 3)).toEqual(run.mock.calls[0]!.slice(0, 3));
    expect(run.mock.calls[1]![3].idempotencyKey).toEqual(run.mock.calls[0]![3].idempotencyKey);
  });
  it("conflict retains draft and requires explicit resubmission with freshly read version/cycle", async () => {
    const run = vi.fn().mockRejectedValueOnce(new FindingCommandError("versionConflict")).mockResolvedValueOnce({ state: "REPORTER_SUPPLEMENT_PENDING", roundNo: 3, version: 16 });
    const { adapter } = setup(snapshot(), run);
    fireEvent.click(await screen.findByRole("button", { name: "退回补充" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "保留草稿" } });
    vi.mocked(adapter.history!).mockResolvedValue(snapshot({ currentVersion: 15, reviewCycle: 3 }));
    fireEvent.click(screen.getByRole("button", { name: "确认退回补充" }));
    await waitFor(() => expect(adapter.history).toHaveBeenCalledTimes(2));
    expect(screen.getByLabelText(/意见/)).toHaveValue("保留草稿");
    expect(run).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByRole("button", { name: "确认退回补充" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "确认退回补充" }));
    await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
    expect(run.mock.calls[1]![1]).toBe(15);
    expect(run.mock.calls[1]![2].reviewCycle).toBe(3);
    expect(run.mock.calls[1]![3].idempotencyKey).not.toBe(run.mock.calls[0]![3].idempotencyKey);
  });
  it("enforces raw UTF-16 limit and Unicode blank text", async () => {
    const { run } = setup();
    fireEvent.click(await screen.findByRole("button", { name: "退回补充" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "😀".repeat(2001) } });
    fireEvent.click(screen.getByRole("button", { name: "确认退回补充" }));
    expect(screen.getByText(/不能超过/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "\u0085\u3000" } });
    fireEvent.click(screen.getByRole("button", { name: "确认退回补充" }));
    expect(screen.getByText("请填写意见")).toBeInTheDocument();
    expect(run).not.toHaveBeenCalled();
  });
  it.each(["findingRemediationV1", "findingRemediationV2"])("keeps %s approval on legacy routes and disables unsupported return", async key => {
    const { run } = setup(snapshot({ reviewProtocol: "LEGACY", processDefinitionKey: key, reviewStage: "PERSON", availableReviewActions: ["APPROVE", "RETURN_FOR_INFORMATION", "NOT_ESTABLISHED"] }));
    fireEvent.click(await screen.findByRole("button", { name: "确认发现" }));
    expect(screen.queryByRole("button", { name: "退回补充" })).not.toBeInTheDocument();
    if (key === "findingRemediationV1") expect(screen.queryByRole("button", { name: "判定未成立" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "legacy" } });
    fireEvent.click(within(screen.getByRole("form", { name: "确认发现" })).getByRole("button", { name: "确认发现" }));
    await waitFor(() => expect(run).toHaveBeenCalledWith("f", 12, { kind: "confirmPerson", reason: "legacy" }, expect.anything()));
  });
  it("keeps legacy V2 not-established body and safety stage", async () => {
    const { run } = setup(snapshot({ reviewProtocol: "LEGACY", processDefinitionKey: "findingRemediationV2" }));
    fireEvent.click(await screen.findByRole("button", { name: "判定未成立" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "no" } });
    fireEvent.click(screen.getByRole("button", { name: "确认判定未成立" }));
    await waitFor(() => expect(run).toHaveBeenCalledWith("f", 12, { kind: "closeNotEstablished", stage: "SAFETY", reason: "no" }, expect.anything()));
  });
  it("preserves draft when refreshing history fails; reload requires an explicit retry", async () => {
    const run = vi.fn().mockRejectedValue(new FindingCommandError("versionConflict"));
    const { adapter } = setup(snapshot(), run);
    fireEvent.click(await screen.findByRole("button", { name: "退回补充" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "保留说明" } });
    vi.mocked(adapter.history!).mockRejectedValueOnce(new FindingCommandError("unavailable"));
    fireEvent.click(screen.getByRole("button", { name: "确认退回补充" }));
    expect(await screen.findByText(/无法读取确认记录/)).toBeInTheDocument();
    expect(screen.getByLabelText(/意见/)).toHaveValue("保留说明");
    expect(screen.getByRole("button", { name: "确认退回补充" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "重新读取确认记录" }));
    await screen.findByRole("button", { name: "退回补充" });
    expect(run).toHaveBeenCalledTimes(1);
  });
  it("keeps form mounted across a transient detail refresh error and blocks fresh submission", async () => {
    const adapter: FindingCommandAdapter = { run: vi.fn(), history: vi.fn().mockResolvedValue(snapshot()) };
    const props = { finding, loading: false, error: null, onClose: vi.fn(), onRetry: vi.fn(), commandAdapter: adapter };
    const view = render(<FindingDetails {...props} />);
    fireEvent.click(await screen.findByRole("button", { name: "退回补充" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "仍在编辑" } });
    view.rerender(<FindingDetails {...props} error="unknown" />);
    expect(screen.getByLabelText(/意见/)).toHaveValue("仍在编辑");
    expect(screen.getByRole("button", { name: "确认退回补充" })).toBeDisabled();
    view.rerender(<FindingDetails {...props} />);
    expect(screen.getByLabelText(/意见/)).toHaveValue("仍在编辑");
    expect(screen.getByRole("button", { name: "确认退回补充" })).toBeEnabled();
    expect(adapter.run).not.toHaveBeenCalled();
  });
  it.each(["unauthenticated", "forbidden"] as const)("clears drafts and pending requests on %s", async kind => {
    const adapter: FindingCommandAdapter = { run: vi.fn().mockRejectedValue(new FindingCommandError(kind)), history: vi.fn().mockResolvedValue(snapshot()) };
    const onForbidden = vi.fn();
    const onUnauthenticated = vi.fn();
    render(<FindingCommandPanel finding={finding} adapter={adapter} onChanged={vi.fn()} onForbidden={onForbidden} onUnauthenticated={onUnauthenticated} />);
    fireEvent.click(await screen.findByRole("button", { name: "退回补充" }));
    fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "待清理草稿" } });
    fireEvent.click(screen.getByRole("button", { name: "确认退回补充" }));
    await waitFor(() => expect(kind === "forbidden" ? onForbidden : onUnauthenticated).toHaveBeenCalled());
    expect(screen.queryByDisplayValue("待清理草稿")).not.toBeInTheDocument();
    expect(screen.queryByRole("form")).not.toBeInTheDocument();
  });
  it("renders historical grade only when recorded, cycle order and honest unavailable names", () => {
    const entry = { id: "one", origin: "REVIEW_DECISION" as const, reviewCycle: 2, stage: "SAFETY" as const, decision: "APPROVE" as const, actorMemberId: "12345678-abcd", actorDisplay: { label: null, provenance: "UNAVAILABLE" as const }, occurredAt: "2026-11-03T08:00:00Z", text: "确认", recordedGrade: "MAJOR" as const, nextReviewCycle: null };
    render(<FindingReviewHistory entries={[entry, { ...entry, id: "old", origin: "LEGACY_CONFIRMATION", reviewCycle: 1, recordedGrade: null, text: null }]} />);
    expect(screen.getAllByText(/名称不可用/)).toHaveLength(2);
    expect(screen.getByText("未记录意见")).toBeInTheDocument();
    expect(screen.getAllByText(/本次记录等级/)).toHaveLength(1);
    expect(screen.getAllByRole("heading", { level: 4 }).map(el => el.textContent)).toEqual(["第 1 次确认", "第 2 次确认"]);
  });
});
