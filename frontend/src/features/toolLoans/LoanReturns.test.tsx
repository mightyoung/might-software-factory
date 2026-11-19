import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ApiError } from "../../api/httpClient";
import { clearAllDrafts } from "../session/draftStash";
import { LoanReturnForm } from "./LoanReturns";
import { LoanApplicationsPage } from "./LoanApplicationsPage";
import type { LoanAdapter, LoanRecord, ReturnItem } from "./model";
const id = "11111111-1111-4111-8111-111111111111", tool2 = "22222222-2222-4222-8222-222222222222", tool3 = "33333333-3333-4333-8333-333333333333";
const items: ReturnItem[] = [id, tool2, tool3].map((toolId, index) => ({ toolId, label: `工装${index + 1}`, state: "OUTSTANDING" }));
const loan: LoanRecord = { id, definitionVersion: "1", version: 2, state: "ISSUED", organizationId: id, custodyScopeId: id, purpose: "归还验收", toolIds: [id, tool2, tool3], returnItems: items, createdAt: "2026-11-20T00:00:00Z", updatedAt: "2026-11-20T00:00:00Z" };
function adapter(): LoanAdapter {
  return { definition: vi.fn(async () => ({ definitionVersion: "1" as const, usageLocationRequired: false, usageLocationMaxLength: 200 })), lossQueue: vi.fn(), lossDetail: vi.fn(), receiveQueue: vi.fn(async () => [loan]), receiveDetail: vi.fn(async () => loan), issueQueue: vi.fn(), issueDetail: vi.fn(), list: vi.fn(async () => [loan]), detail: vi.fn(async () => loan), candidates: vi.fn(), command: vi.fn(async () => loan) };
}
function form(a: LoanAdapter, action: "return-requests" | "receive", record = loan) {
  return render(<LoanReturnForm adapter={a} action={action} record={record} owner="tenant/actor" identityKey="account/member" onUnauthenticated={vi.fn()} onSaved={vi.fn()} />);
}
afterEach(() => { cleanup();clearAllDrafts(); });
it("requests a selected subset and shows that it remains unsettled and pending receipt", async () => {
  const a = adapter();vi.mocked(a.command).mockResolvedValue({ ...loan, version: 3, returnItems: [{ ...items[0]!, state: "REQUESTED", requestId: tool2 }, items[1]!, items[2]!] });
  form(a, "return-requests");fireEvent.click(screen.getByRole("checkbox", { name: "工装1" }));fireEvent.click(screen.getByRole("button", { name: "申请归还所选工装" }));
  await screen.findByText("归还申请已提交，所选工装仍占用至保管人接收。");
  expect(a.command).toHaveBeenCalledWith("return-requests", id, { definitionVersion: "1", expectedVersion: 2, toolIds: [id] }, expect.any(String));
  expect(screen.getByText("尚未结清 3 件，待接收 1 件，已接收 0 件")).toBeVisible();
  expect(screen.getByRole("checkbox", { name: "工装1" })).toBeDisabled();expect(screen.getByRole("checkbox", { name: "工装2" })).not.toBeDisabled();
});
it("selects pending receipt items individually and requires a damage reason", async () => {
  const pending: LoanRecord = { ...loan, version: 4, returnItems: items.map(item => ({ ...item, state: "REQUESTED", requestId: tool3 })) };
  const a = adapter();form(a, "receive", pending);
  fireEvent.click(screen.getByRole("checkbox", { name: "工装1" }));fireEvent.click(screen.getByRole("checkbox", { name: "工装2" }));
  fireEvent.change(screen.getByRole("combobox", { name: "接收情况（工装2）" }), { target: { value: "DAMAGED" } });
  fireEvent.submit(screen.getByRole("form", { name: "接收归还工装" }));
  expect(await screen.findByText("损坏接收必须填写损坏说明。")).toBeVisible();expect(a.command).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("接收说明（工装2）"), { target: { value: "定位销断裂" } });
  vi.mocked(a.command).mockResolvedValue({ ...pending, version: 5, returnItems: [
    { ...items[0]!, state: "RECEIVED", condition: "NORMAL" }, { ...items[1]!, state: "RECEIVED", condition: "DAMAGED", reason: "定位销断裂" }, pending.returnItems![2]!,
  ] });
  fireEvent.click(screen.getByRole("button", { name: "确认接收所选工装" }));await screen.findByText("所选工装已接收。");
  expect(a.command).toHaveBeenCalledWith("receive", id, { definitionVersion: "1", expectedVersion: 4, items: [{ toolId: id, condition: "NORMAL" }, { toolId: tool2, condition: "DAMAGED", reason: "定位销断裂" }] }, expect.any(String));
  expect(screen.getByText("尚未结清 1 件，待接收 1 件，已接收 2 件")).toBeVisible();expect(screen.getByText(/损坏接收 · 定位销断裂/)).toBeVisible();
});
it("shows settlement only when the server confirms all items received", async () => {
  const pending = { ...loan, returnItems: items.map(item => ({ ...item, state: "REQUESTED" as const })) };
  const a = adapter();vi.mocked(a.command).mockResolvedValue({ ...pending, version: 3, state: "SETTLED", returnItems: items.map(item => ({ ...item, state: "RECEIVED", condition: "NORMAL" })) });
  form(a, "receive", pending);for (const label of ["工装1", "工装2", "工装3"]) fireEvent.click(screen.getByRole("checkbox", { name: label }));
  fireEvent.click(screen.getByRole("button", { name: "确认接收所选工装" }));await screen.findByText("借用单已结清 · 第 3 版");
  expect(screen.getByText("尚未结清 0 件，待接收 0 件，已接收 3 件")).toBeVisible();expect(screen.queryByRole("button", { name: "确认接收所选工装" })).not.toBeInTheDocument();
});
it("restores an uncertain command after remount and retries exactly the same subset, conditions and key", async () => {
  const pending = { ...loan, returnItems: items.map(item => ({ ...item, state: "REQUESTED" as const })) };
  const a = adapter();vi.mocked(a.command).mockRejectedValueOnce(new ApiError(503, "UNAVAILABLE", null, false)).mockResolvedValueOnce({ ...pending, state: "SETTLED", version: 3, returnItems: items.map(item => ({ ...item, state: "RECEIVED" })) });
  const view = form(a, "receive", pending);fireEvent.click(screen.getByRole("checkbox", { name: "工装1" }));fireEvent.click(screen.getByRole("button", { name: "确认接收所选工装" }));
  await screen.findByText("原请求结果尚未确认，核对后只能重试该请求。");const original = vi.mocked(a.command).mock.calls[0];
  expect(screen.getByRole("checkbox", { name: "工装2" })).toBeDisabled();view.unmount();
  form(a, "receive", { ...pending, state: "SETTLED", returnItems: items.map(item => ({ ...item, state: "RECEIVED" })) });
  fireEvent.click(await screen.findByRole("button", { name: "核对后重试原请求" }));await screen.findByText("所选工装已接收。");
  expect(vi.mocked(a.command).mock.calls[1]).toEqual(original);
});
it("reconciles a definitive version conflict and uses the newly read version only after explicit selection", async () => {
  const a = adapter();vi.mocked(a.command).mockRejectedValueOnce(new ApiError(409, "VERSION_CONFLICT", null, false));
  vi.mocked(a.detail).mockResolvedValue({ ...loan, version: 5 });
  form(a, "return-requests");fireEvent.click(screen.getByRole("checkbox", { name: "工装1" }));fireEvent.click(screen.getByRole("button", { name: "申请归还所选工装" }));
  fireEvent.click(await screen.findByRole("button", { name: "读取最新明细并重新核对" }));await screen.findByText("借用单未结清 · 第 5 版");
  expect(a.command).toHaveBeenCalledTimes(1);expect(screen.getByRole("checkbox", { name: "工装1" })).not.toBeChecked();
  fireEvent.click(screen.getByRole("checkbox", { name: "工装2" }));fireEvent.click(screen.getByRole("button", { name: "申请归还所选工装" }));
  await waitFor(() => expect(a.command).toHaveBeenCalledTimes(2));expect(vi.mocked(a.command).mock.calls[1]![2]).toEqual({ definitionVersion: "1", expectedVersion: 5, toolIds: [tool2] });
});
it("does not expire a new owner when a prior owner's command returns late", async () => {
  const a = adapter();let reject!: (error: unknown) => void;
  vi.mocked(a.command).mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail; }));
  const expire = vi.fn();const view = render(<LoanReturnForm adapter={a} action="return-requests" record={loan} owner="a" identityKey="a" onUnauthenticated={expire} onSaved={vi.fn()} />);
  fireEvent.click(screen.getByRole("checkbox", { name: "工装1" }));fireEvent.click(screen.getByRole("button", { name: "申请归还所选工装" }));view.unmount();
  await act(async () => { reject(new ApiError(401, "UNAUTHENTICATED", null, false)); });expect(expire).not.toHaveBeenCalled();
});
it("opens a receive-only work queue without applicant or issue access", async () => {
  const a = adapter();vi.mocked(a.receiveDetail).mockResolvedValue({ ...loan, returnItems: [{ ...items[0]!, state: "REQUESTED" }, items[1]!, items[2]!] });
  render(<LoanApplicationsPage adapter={a} owner="keeper" permissions={["tool_loan.receive"]} onUnauthenticated={vi.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: "核对接收" }));await screen.findByRole("form", { name: "接收归还工装" });
  expect(a.list).not.toHaveBeenCalled();expect(a.issueQueue).not.toHaveBeenCalled();expect(a.receiveDetail).toHaveBeenCalledWith(id);
});
