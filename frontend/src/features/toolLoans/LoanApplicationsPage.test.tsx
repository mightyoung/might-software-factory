import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ApiError } from "../../api/httpClient";
import { clearAllDrafts } from "../session/draftStash";
import { LoanApplicationsPage } from "./LoanApplicationsPage";
import type { LoanAdapter, LoanRecord } from "./model";
const scope = "11111111-1111-4111-8111-111111111111", org = "22222222-2222-4222-8222-222222222222", tool = "33333333-3333-4333-8333-333333333333", tool2 = "44444444-4444-4444-8444-444444444444";
const row: LoanRecord = { id: "55555555-5555-4555-8555-555555555555", version: 1, state: "PENDING_ISSUE", definitionVersion: "1", organizationId: org, custodyScopeId: scope, purpose: "装配使用", toolIds: [tool, tool2], createdAt: "2026-11-18T00:00:00Z", updatedAt: "2026-11-18T00:00:00Z" };
const grants = ["tool_loan.read", "tool_loan.create", "tool_loan.update"];
function setup(records: LoanRecord[] = []) {
  const adapter: LoanAdapter = { definition: vi.fn(async () => ({ definitionVersion: "1" as const, usageLocationRequired: false, usageLocationMaxLength: 200 })), lossQueue: vi.fn(), lossDetail: vi.fn(), receiveQueue: vi.fn(), receiveDetail: vi.fn(), issueQueue: vi.fn(), issueDetail: vi.fn(), list: vi.fn(async () => records), detail: vi.fn(async () => row), candidates: vi.fn(async () => ({ departments: [{ id: org, label: "申请部门" }], scopes: [{ id: scope, organizationId: org, label: "一车间库房" }], tools: [{ id: tool, label: "工装一" }, { id: tool2, label: "工装二" }] })), command: vi.fn(async () => row) };
  const expire = vi.fn();
  const view = render(<LoanApplicationsPage adapter={adapter} owner="tenant/member" permissions={grants} onUnauthenticated={expire} />);
  return { adapter, expire, view };
}
async function fill() {
  fireEvent.click(await screen.findByRole("button", { name: "新建借用申请" }));
  await waitFor(() => expect(screen.getByLabelText("保管范围")).not.toBeDisabled());
  fireEvent.change(screen.getByLabelText("保管范围"), { target: { value: scope } });
  await waitFor(() => expect(screen.getByLabelText("第 1 件工装")).not.toBeDisabled());
  fireEvent.change(screen.getByLabelText("借用用途"), { target: { value: "装配使用" } });
  fireEvent.change(screen.getByLabelText("第 1 件工装"), { target: { value: tool } });
  fireEvent.click(screen.getByRole("button", { name: "添加工装" }));
  fireEvent.change(screen.getByLabelText("第 2 件工装"), { target: { value: tool2 } });
}
afterEach(() => { cleanup();clearAllDrafts(); });
it("submits multiple selected tools with the selected scope organization and no client identity", async () => {
  const { adapter } = setup();await fill();fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  await waitFor(() => expect(adapter.command).toHaveBeenCalledTimes(1));
  expect(adapter.command).toHaveBeenCalledWith("create", undefined, { definitionVersion: "1", organizationId: org, custodyScopeId: scope, purpose: "装配使用", toolIds: [tool, tool2] }, expect.any(String));
  expect(await screen.findByRole("heading", { name: "申请详情" })).toBeVisible();
});
it("locates duplicate details and never sends a partial application", async () => {
  const { adapter } = setup();await fill();fireEvent.change(screen.getByLabelText("第 2 件工装"), { target: { value: tool } });
  fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  expect(await screen.findByText("同一申请不能重复选择工装。")).toBeVisible();expect(adapter.command).not.toHaveBeenCalled();
});
it("retains the original payload and key across uncertain response and form remount", async () => {
  const { adapter } = setup();vi.mocked(adapter.command).mockRejectedValueOnce(new ApiError(503, "UNAVAILABLE", null, false));
  await fill();fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  await screen.findByRole("button", { name: "核对后重试原请求" });
  const first = vi.mocked(adapter.command).mock.calls[0]!;
  expect(screen.getByLabelText("借用用途")).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "返回列表（保留输入）" }));fireEvent.click(screen.getByRole("button", { name: "新建借用申请" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "核对后重试原请求" })).not.toBeDisabled());
  fireEvent.click(screen.getByRole("button", { name: "核对后重试原请求" }));
  await waitFor(() => expect(adapter.command).toHaveBeenCalledTimes(2));expect(vi.mocked(adapter.command).mock.calls[1]).toEqual(first);
});
it("preserves the pending request when the route unmounts before the response", async () => {
  const { adapter, expire, view } = setup();
  let completeFirst!: (result: LoanRecord) => void;
  vi.mocked(adapter.command).mockImplementationOnce(() => new Promise(resolve => { completeFirst = resolve; }));
  await fill();
  fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  expect(adapter.command).toHaveBeenCalledTimes(1);
  const first = vi.mocked(adapter.command).mock.calls[0];

  view.unmount();
  render(<LoanApplicationsPage adapter={adapter} owner="tenant/member" permissions={grants} onUnauthenticated={expire} />);
  fireEvent.click(await screen.findByRole("button", { name: "新建借用申请" }));
  const retry = await screen.findByRole("button", { name: "核对后重试原请求" });
  expect(screen.getByText("此次提交结果尚未确认。请核对后使用原请求重试。")).toBeVisible();
  expect(screen.queryByRole("button", { name: "读取最新版本并核对后重新提交" })).not.toBeInTheDocument();

  await act(async () => { completeFirst(row); });
  await waitFor(() => expect(retry).not.toBeDisabled());
  fireEvent.click(retry);
  await screen.findByRole("heading", { name: "申请详情" });
  expect(adapter.command).toHaveBeenCalledTimes(2);
  expect(vi.mocked(adapter.command).mock.calls[1]).toEqual(first);
});
it("refreshes a rejected in-flight creation after an authoritative definition conflict", async () => {
  const { adapter, expire, view } = setup();
  let failFirst!: (error: ApiError) => void;
  const conflict = new ApiError(409, "DEFINITION_CONFLICT", null, false);
  vi.mocked(adapter.command)
    .mockImplementationOnce(() => new Promise((_resolve, reject) => { failFirst = reject; }))
    .mockRejectedValueOnce(new ApiError(403, "FORBIDDEN", null, false))
    .mockRejectedValueOnce(conflict);
  await fill();
  fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  const first = vi.mocked(adapter.command).mock.calls[0];
  view.unmount();
  await act(async () => { failFirst(conflict); });

  vi.mocked(adapter.definition).mockResolvedValue({ definitionVersion: "2", usageLocationRequired: true, usageLocationMaxLength: 200 });
  render(<LoanApplicationsPage adapter={adapter} owner="tenant/member" permissions={grants} onUnauthenticated={expire} />);
  fireEvent.click(await screen.findByRole("button", { name: "新建借用申请" }));
  const retry = await screen.findByRole("button", { name: "核对后重试原请求" });
  const refresh = "读取最新版本并核对后重新提交";
  await waitFor(() => expect(retry).not.toBeDisabled());
  fireEvent.click(retry);
  await screen.findByRole("alert");
  expect(screen.queryByRole("button", { name: refresh })).not.toBeInTheDocument();
  expect(vi.mocked(adapter.command).mock.calls[1]).toEqual(first);

  fireEvent.click(retry);
  fireEvent.click(await screen.findByRole("button", { name: refresh }));
  expect(vi.mocked(adapter.command).mock.calls[2]).toEqual(first);
  const location = await screen.findByLabelText("使用地点");
  expect(screen.getByLabelText("借用用途")).toHaveValue("装配使用");
  fireEvent.change(location, { target: { value: "二车间" } });
  fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  await screen.findByRole("heading", { name: "申请详情" });
  expect(vi.mocked(adapter.command).mock.calls[3]![2]).toMatchObject({ definitionVersion: "2", usageLocation: "二车间" });
  expect(vi.mocked(adapter.command).mock.calls[3]![3]).not.toEqual(first![3]);
});
it("reconciles a definitive stale version before sending a new revision", async () => {
  const { adapter } = setup([row]);vi.mocked(adapter.command).mockRejectedValueOnce(new ApiError(409, "VERSION_CONFLICT", null, false));vi.mocked(adapter.detail).mockResolvedValue({ ...row, version: 2 });
  fireEvent.click(await screen.findByRole("button", { name: "查看申请" }));fireEvent.click(await screen.findByRole("button", { name: "修订申请" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "提交申请" })).not.toBeDisabled());fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  fireEvent.click(await screen.findByRole("button", { name: "读取最新版本并核对后重新提交" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "提交申请" })).not.toBeDisabled());fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  await waitFor(() => expect(adapter.command).toHaveBeenCalledTimes(2));expect(vi.mocked(adapter.command).mock.calls[1]![2]).toMatchObject({ expectedVersion: 2 });
  expect(vi.mocked(adapter.command).mock.calls[1]![3]).not.toEqual(vi.mocked(adapter.command).mock.calls[0]![3]);
});
it("requires explicit withdrawal confirmation and sends the confirmed version", async () => {
  const { adapter } = setup([row]);fireEvent.click(await screen.findByRole("button", { name: "查看申请" }));fireEvent.click(await screen.findByRole("button", { name: "撤销申请" }));expect(adapter.command).not.toHaveBeenCalled();
  await act(async () => { fireEvent.click(screen.getByRole("button", { name: "确认撤销" })); });
  expect(adapter.command).toHaveBeenCalledWith("cancel", row.id, { definitionVersion: "1", expectedVersion: 1 }, expect.any(String));
});
it("keeps the submitted department fixed when the owner now belongs to another department", async () => {
  const { adapter } = setup([row]);
  vi.mocked(adapter.candidates).mockResolvedValue({ departments: [{ id: tool2, label: "现部门" }], scopes: [{ id: scope, organizationId: org, label: "原保管范围" }], tools: [{ id: tool, label: "工装一" }, { id: tool2, label: "工装二" }] });
  fireEvent.click(await screen.findByRole("button", { name: "查看申请" }));
  fireEvent.click(await screen.findByRole("button", { name: "修订申请" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "提交申请" })).not.toBeDisabled());
  expect(screen.getByLabelText("申请部门")).toBeDisabled();expect(screen.getByLabelText("申请部门")).toHaveValue(org);
  expect(screen.getByLabelText("保管范围")).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  await waitFor(() => expect(adapter.command).toHaveBeenCalled());
  expect(vi.mocked(adapter.command).mock.calls[0]![2]).toMatchObject({ organizationId: org, custodyScopeId: scope, expectedVersion: 1 });
});
it("shows that another eligible custodian is needed only when the pending detail explicitly says so", async () => {
  const { adapter } = setup([row]);
  vi.mocked(adapter.detail).mockResolvedValueOnce({ ...row, awaitingOtherCustodian: true }).mockResolvedValueOnce(row);
  fireEvent.click(await screen.findByRole("button", { name: "查看申请" }));
  expect(await screen.findByText("对应保管范围目前无人可办（需另一名有效保管人发放）")).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "查看申请" }));
  await waitFor(() => expect(adapter.detail).toHaveBeenCalledTimes(2));
  await screen.findByRole("heading", { name: "申请详情" });
  expect(screen.queryByText("对应保管范围目前无人可办（需另一名有效保管人发放）")).not.toBeInTheDocument();
});
it("shows scoped applications with detail only even when the reader also owns command permissions", async () => {
  const adapter: LoanAdapter = { definition: vi.fn(async () => ({ definitionVersion: "1" as const, usageLocationRequired: false, usageLocationMaxLength: 200 })), lossQueue: vi.fn(), lossDetail: vi.fn(), receiveQueue: vi.fn(), receiveDetail: vi.fn(), issueQueue: vi.fn(), issueDetail: vi.fn(), list: vi.fn().mockResolvedValue([row]), detail: vi.fn().mockResolvedValue(row), candidates: vi.fn(), command: vi.fn() };
  render(<LoanApplicationsPage adapter={adapter} owner="scope-test" visibility="department" permissions={[...grants,"tool_loan_department.read"]} onUnauthenticated={vi.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: "查看申请" }));
  await screen.findByRole("region", { name: "借用单只读详情" });
  expect(adapter.list).toHaveBeenCalledWith(expect.any(AbortSignal), "department");
  expect(adapter.detail).toHaveBeenCalledWith(row.id, undefined, "department");
  expect(screen.queryByRole("button", { name: "新建借用申请" })).toBeNull();
  expect(screen.queryByRole("button", { name: "修订申请" })).toBeNull();
  expect(screen.queryByRole("button", { name: "撤销申请" })).toBeNull();
  expect(adapter.command).not.toHaveBeenCalled();
});

it.each(["department", "custody"] as const)("loads scoped applications with only the %s read grant", async visibility => {
  const adapter: LoanAdapter = { definition: vi.fn(async () => ({ definitionVersion: "1" as const, usageLocationRequired: false, usageLocationMaxLength: 200 })), lossQueue: vi.fn(), lossDetail: vi.fn(), receiveQueue: vi.fn(), receiveDetail: vi.fn(), issueQueue: vi.fn(), issueDetail: vi.fn(), list: vi.fn().mockResolvedValue([row]), detail: vi.fn().mockResolvedValue(row), candidates: vi.fn(), command: vi.fn() };
  render(<LoanApplicationsPage adapter={adapter} owner="only-reader" visibility={visibility} permissions={[`tool_loan_${visibility}.read`]} onUnauthenticated={vi.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: "查看申请" }));
  await screen.findByRole("region", { name: "借用单只读详情" });
  expect(adapter.list).toHaveBeenCalledWith(expect.any(AbortSignal), visibility);
  expect(adapter.candidates).not.toHaveBeenCalled();
  expect(adapter.command).not.toHaveBeenCalled();
});

it("creates V2 with published required location while keeping old revisions on V1", async () => {
  const { adapter } = setup([row]);
  vi.mocked(adapter.definition).mockResolvedValue({ definitionVersion: "2", usageLocationRequired: true, usageLocationMaxLength: 200 });
  await fill();
  const location = await screen.findByLabelText("使用地点");
  expect(location).toBeRequired();
  expect(location).toHaveAttribute("maxlength", "200");
  fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  expect(adapter.command).not.toHaveBeenCalled();
  fireEvent.change(location, { target: { value: "二车间装配工位" } });
  fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  await waitFor(() => expect(adapter.command).toHaveBeenCalledTimes(1));
  expect(vi.mocked(adapter.command).mock.calls[0]![2]).toMatchObject({ definitionVersion: "2", usageLocation: "二车间装配工位" });
  fireEvent.click(await screen.findByRole("button", { name: "修订申请" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "提交申请" })).not.toBeDisabled());
  expect(screen.queryByLabelText("使用地点")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  await waitFor(() => expect(adapter.command).toHaveBeenCalledTimes(2));
  expect(vi.mocked(adapter.command).mock.calls[1]![2]).toMatchObject({ definitionVersion: "1" });
  expect(vi.mocked(adapter.command).mock.calls[1]![2]).not.toHaveProperty("usageLocation");
});
it("retries the exact V1 request after a V2 publication while an outcome is unknown", async () => {
  const { adapter } = setup();
  vi.mocked(adapter.command).mockRejectedValueOnce(new ApiError(503, "UNAVAILABLE", null, false));
  await fill();
  fireEvent.click(screen.getByRole("button", { name: "提交申请" }));
  await screen.findByRole("button", { name: "核对后重试原请求" });
  const original = vi.mocked(adapter.command).mock.calls[0]!;
  vi.mocked(adapter.definition).mockResolvedValue({ definitionVersion: "2", usageLocationRequired: true, usageLocationMaxLength: 200 });
  fireEvent.click(screen.getByRole("button", { name: "返回列表（保留输入）" }));
  fireEvent.click(screen.getByRole("button", { name: "新建借用申请" }));
  await waitFor(() => expect(adapter.definition).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(screen.getByRole("button", { name: "核对后重试原请求" })).not.toBeDisabled());
  expect(screen.queryByLabelText("使用地点")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "核对后重试原请求" }));
  await waitFor(() => expect(adapter.command).toHaveBeenCalledTimes(2));
  expect(vi.mocked(adapter.command).mock.calls[1]).toEqual(original);
});
