import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/httpClient";
import { ModeProvider } from "../../mode";
import { parseEntityRoute } from "../../routing/entityPaths";
import { clearAllDrafts } from "../session/draftStash";
import { EntityLedgerPage } from "./EntityLedgerPage";
import { EntityLedgerForm } from "./EntityLedgerForm";
import { adapter, data, DEF, ID, OTHER_ID, RECORD } from "./testFixture";
import type { EntityLedgerAdapter } from "./model";
afterEach(() => { cleanup(); clearAllDrafts(); });
function page(a: EntityLedgerAdapter, path = "apps/inventory/entities/widget", permissions = ["widget.read", "widget.create", "widget.update"]) {
  return render(<ModeProvider value="http"><EntityLedgerPage adapter={a} route={parseEntityRoute(path)!} tenantId="t" actorId="a" identityScope={{ accountId: "account", memberId: "member" }} permissions={permissions} onUnauthenticated={vi.fn()} /></ModeProvider>);
}
describe("generic ledger page capabilities", () => {
  it("create-only discovers CREATE without any list or record read, and shows command metadata only", async () => {
    const a = adapter();vi.mocked(a.definition).mockImplementation(async use => data(use, ["widget.create"]));page(a, undefined, ["widget.create"]);
    expect(await screen.findByRole("heading", { name: "新增物品" })).toBeInTheDocument();
    expect(a.definition).toHaveBeenCalledWith("CREATE", expect.any(AbortSignal));
    fireEvent.change(screen.getByLabelText("编号 *"), { target: { value: "new" } });
    const select = await screen.findByRole("combobox", { name: "管理范围" });await waitFor(() => expect(select).not.toBeDisabled());
    fireEvent.change(select, { target: { value: OTHER_ID } });fireEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("heading", { name: "保存成功" })).toBeInTheDocument();
    expect(a.query).not.toHaveBeenCalled();expect(a.record).not.toHaveBeenCalled();expect(screen.queryByRole("button", { name: "查看已保存记录" })).not.toBeInTheDocument();
  });
  it("shows an explicit update-only entry state without loading or writing records", async () => {
    const a = adapter();vi.mocked(a.definition).mockImplementation(async use => data(use, ["widget.update"]));page(a, undefined, ["widget.update"]);
    expect(await screen.findByText("当前仅有修改权限，无法浏览或加载档案。")).toHaveAttribute("role", "status");
    expect(a.definition).toHaveBeenCalledWith("EDIT", expect.any(AbortSignal));
    expect(screen.queryByRole("button", { name: "保存" })).not.toBeInTheDocument();
    expect(a.query).not.toHaveBeenCalled();expect(a.record).not.toHaveBeenCalled();expect(a.reference).not.toHaveBeenCalled();expect(a.create).not.toHaveBeenCalled();expect(a.update).not.toHaveBeenCalled();
  });
  it("does not present edit from global grants when the current record denies it", async () => {
    const a = adapter();vi.mocked(a.record).mockResolvedValue({ ...RECORD, effectiveActions: ["widget.read"] });page(a, `apps/inventory/entities/widget/records/${ID}`);
    expect(await screen.findByText("W-01")).toBeInTheDocument();expect(screen.getByRole("button", { name: "返回台账" })).toBeInTheDocument();expect(screen.queryByRole("button", { name: "修改物品" })).not.toBeInTheDocument();expect(a.reference).not.toHaveBeenCalled();
  });
  it("refuses a direct edit route when row-effective update is absent", async () => {
    const a = adapter();vi.mocked(a.record).mockResolvedValue({ ...RECORD, effectiveActions: ["widget.read"] });page(a, `apps/inventory/entities/widget/records/${ID}/edit`);
    expect(await screen.findByRole("alert")).toHaveTextContent("没有执行权限");expect(screen.queryByRole("button", { name: "保存" })).not.toBeInTheDocument();
  });
  it("does not turn a revoked READ definition into a create probe or synthetic results", async () => {
    const a = adapter();vi.mocked(a.definition).mockRejectedValue(new ApiError(403, "DENIED", null, false));page(a);
    expect(await screen.findByRole("alert")).toHaveTextContent("没有执行权限");expect(a.definition).toHaveBeenCalledTimes(1);expect(a.query).not.toHaveBeenCalled();expect(a.create).not.toHaveBeenCalled();
  });
  it("queries one-based page20 and renders shared table controls", async () => {
    const a = adapter();page(a);expect(await screen.findByText("W-01")).toBeInTheDocument();expect(a.query).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ page: { number: 1 }, size: 20 }), expect.any(AbortSignal));
    expect(screen.queryByRole("button", { name: "返回台账" })).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "每页条数" })).toHaveValue("20");
    expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "上一页" })).toHaveClass("button", "button-subtle");
    expect(screen.getByRole("button", { name: "下一页" })).toHaveClass("button", "button-subtle");
    fireEvent.change(screen.getByRole("combobox", { name: "每页条数" }), { target: { value: "50" } });
    await waitFor(() => expect(a.query).toHaveBeenLastCalledWith(expect.anything(), expect.objectContaining({ page: { number: 1 }, size: 50 }), expect.any(AbortSignal)));
  });
});
describe("stable standard write requests", () => {
  function form(a: EntityLedgerAdapter, unauthenticated = vi.fn()) {
    return render(<EntityLedgerForm definition={DEF} use="EDIT" adapter={a} record={RECORD} owner="t/a" draftKey="edit" onSuccess={vi.fn()} onUnauthenticated={unauthenticated} onRefresh={vi.fn()} />);
  }
  it("unknown submit preserves input and repeats exactly the same key only after a manual retry", async () => {
    const a = adapter();vi.mocked(a.update).mockRejectedValue(new ApiError(0, "NETWORK", null, true));form(a);
    fireEvent.change(screen.getByLabelText("编号 *"), { target: { value: "kept" } });fireEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("结果尚未确认");expect(screen.getByLabelText("编号 *")).toHaveValue("kept");expect(screen.getByLabelText("编号 *")).toBeDisabled();expect(a.update).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "核对后重试原请求" }));await waitFor(() => expect(a.update).toHaveBeenCalledTimes(2));
    expect(vi.mocked(a.update).mock.calls[0]![3]).toBe(vi.mocked(a.update).mock.calls[1]![3]);
    expect(vi.mocked(a.update).mock.calls[0]![2]).toEqual(vi.mocked(a.update).mock.calls[1]![2]);
  });
  it.each([[403, "ACCESS_DENIED", "没有执行权限"], [409, "VERSION_CONFLICT", "其他人修改"], [409, "DEFINITION_CONFLICT", "字段定义已更新"]] as const)("HTTP%s/%s retains the original values", async (status, code, message) => {
    const a = adapter();vi.mocked(a.update).mockRejectedValue(new ApiError(status, code, null, false));form(a);fireEvent.change(screen.getByLabelText("编号 *"), { target: { value: "kept" } });fireEvent.click(screen.getByRole("button", { name: "保存" }));expect(await screen.findByRole("alert")).toHaveTextContent(message);expect(screen.getByLabelText("编号 *")).toHaveValue("kept");expect(a.update).toHaveBeenCalledTimes(1);
  });
});
