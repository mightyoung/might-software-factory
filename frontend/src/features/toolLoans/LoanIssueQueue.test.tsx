import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ApiError } from "../../api/httpClient";
import { clearAllDrafts } from "../session/draftStash";
import { LoanApplicationsPage } from "./LoanApplicationsPage";
import type { LoanAdapter, LoanRecord } from "./model";
const id = "11111111-1111-4111-8111-111111111111";
const other = "22222222-2222-4222-8222-222222222222";
const row: LoanRecord = { id, version: 2, definitionVersion: "1", state: "PENDING_ISSUE", organizationId: id, custodyScopeId: id, purpose: "装配发放测试", toolIds: [id, other], tools: [{ id, label: "T-001 工装一" }, { id: other, label: "T-002 工装二" }], createdAt: "2026-11-19T00:00:00Z", updatedAt: "2026-11-19T00:00:00Z" };
function setup() {
  const adapter: LoanAdapter = { receiveQueue: vi.fn(), receiveDetail: vi.fn(), issueQueue: vi.fn(async () => [row]), issueDetail: vi.fn(async () => row), list: vi.fn(), detail: vi.fn(), candidates: vi.fn(), command: vi.fn(async () => ({ ...row, state: "ISSUED" as const, version: 3 })) };
  const expire = vi.fn();
  const view = render(<LoanApplicationsPage adapter={adapter} owner="t/actor" identityScope={{ accountId: "account", memberId: "member" }} permissions={["tool_loan.issue"]} onUnauthenticated={expire} />);
  return { adapter, expire, view };
}
async function detail() {
  fireEvent.click(await screen.findByRole("button", { name: "核对发放" }));
  await screen.findByRole("button", { name: "确认整单发放" });
}
afterEach(() => { cleanup();clearAllDrafts(); });
it("loads the keeper queue and confirms all current items with one versioned command", async () => {
  const { adapter } = setup();await detail();
  expect(adapter.list).not.toHaveBeenCalled();expect(adapter.detail).not.toHaveBeenCalled();
  expect(screen.getByText("T-001 工装一")).toBeVisible();expect(screen.getByText("T-002 工装二")).toBeVisible();expect(adapter.command).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "确认整单发放" }));
  expect(await screen.findByText("整单已发放。")).toBeVisible();
  expect(adapter.command).toHaveBeenCalledWith("issue", id, { definitionVersion: "1", expectedVersion: 2 }, expect.any(String));
  expect(screen.queryByRole("button", { name: "确认整单发放" })).not.toBeInTheDocument();
});
it("retains the original issue key through unknown response, tab remount and manual retry", async () => {
  const { adapter, view, expire } = setup();vi.mocked(adapter.command).mockRejectedValueOnce(new ApiError(503, "UNAVAILABLE", null, false));
  await detail();fireEvent.click(screen.getByRole("button", { name: "确认整单发放" }));
  await screen.findByRole("button", { name: "核对后重试原发放请求" });
  expect(screen.queryByRole("button", { name: "读取最新申请并重新核对" })).not.toBeInTheDocument();
  const first = vi.mocked(adapter.command).mock.calls[0];view.unmount();
  vi.mocked(adapter.issueQueue).mockResolvedValue([]);
  vi.mocked(adapter.issueDetail).mockResolvedValue({ ...row, state: "ISSUED", version: 3 });
  render(<LoanApplicationsPage adapter={adapter} owner="t/actor" identityScope={{ accountId: "account", memberId: "member" }} permissions={["tool_loan.issue"]} onUnauthenticated={expire} />);
  fireEvent.click(await screen.findByRole("button", { name: "核对后重试原发放请求" }));
  await screen.findByText("整单已发放。");expect(vi.mocked(adapter.command).mock.calls[1]).toEqual(first);
});
it("requires explicit reread after a definitive stale version and then uses a new key", async () => {
  const { adapter } = setup();vi.mocked(adapter.command).mockRejectedValueOnce(new ApiError(409, "VERSION_CONFLICT", null, false));
  await detail();fireEvent.click(screen.getByRole("button", { name: "确认整单发放" }));
  const reconcile = await screen.findByRole("button", { name: "读取最新申请并重新核对" });
  vi.mocked(adapter.issueDetail).mockResolvedValue({ ...row, version: 4, toolIds: [id], tools: [{ id, label: "T-001 修订后" }] });
  fireEvent.click(reconcile);await screen.findByText("T-001 修订后");
  fireEvent.click(screen.getByRole("button", { name: "确认整单发放" }));await screen.findByText("整单已发放。");
  const calls = vi.mocked(adapter.command).mock.calls;
  expect(calls[1]![2]).toEqual({ definitionVersion: "1", expectedVersion: 4 });expect(calls[1]![3]).not.toEqual(calls[0]![3]);
});
it("offers manual list retry after a load failure without sending a command", async () => {
  const { adapter } = setup();vi.mocked(adapter.issueQueue).mockRejectedValueOnce(new ApiError(503, "UNAVAILABLE", null, false));
  // First read has already started. Trigger the controlled failed refresh.
  await screen.findByRole("button", { name: "核对发放" });fireEvent.click(screen.getByRole("button", { name: "刷新待发放申请" }));
  await screen.findByText("待发放申请读取失败，请重试。");
  fireEvent.click(screen.getByRole("button", { name: "刷新待发放申请" }));await screen.findByRole("button", { name: "核对发放" });expect(adapter.command).not.toHaveBeenCalled();
});
it("does not expire another identity when a previous detail request returns late", async () => {
  const { adapter, view, expire } = setup();let reject!: (error: unknown) => void;
  vi.mocked(adapter.issueDetail).mockImplementationOnce(() => new Promise((_resolve, failed) => { reject = failed; }));
  fireEvent.click(await screen.findByRole("button", { name: "核对发放" }));await screen.findByText("正在读取当前申请明细…");view.unmount();
  await act(async () => { reject(new ApiError(401, "UNAUTHENTICATED", null, false)); });expect(expire).not.toHaveBeenCalled();
});
it("keeps an uncertain request locked when retry is denied rather than changing its key", async () => {
  const { adapter } = setup();vi.mocked(adapter.command).mockRejectedValueOnce(new ApiError(503, "UNAVAILABLE", null, false)).mockRejectedValueOnce(new ApiError(403, "FORBIDDEN", null, false));
  await detail();fireEvent.click(screen.getByRole("button", { name: "确认整单发放" }));
  fireEvent.click(await screen.findByRole("button", { name: "核对后重试原发放请求" }));
  await screen.findByText("当前没有此申请的办理资格，或申请已不可见。");
  expect(screen.queryByRole("button", { name: "读取最新申请并重新核对" })).not.toBeInTheDocument();
  await waitFor(() => expect(adapter.command).toHaveBeenCalledTimes(2));expect(vi.mocked(adapter.command).mock.calls[1]).toEqual(vi.mocked(adapter.command).mock.calls[0]);
});
