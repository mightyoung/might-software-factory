import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createSyntheticPermissionAdmin } from "../../adapters/syntheticPermissionAdmin";
import { PermissionAdminError, type PermissionAdminAdapter, type RoleView } from "../../adapters/permissionAdmin";
import { PermissionConfigPage, diffCodes } from "./PermissionConfigPage";

describe("diffCodes", () => {
  it("reports additions and removals", () => {
    expect(diffCodes(["a", "b"], new Set(["b", "c"]))).toEqual({ added: ["c"], removed: ["a"] });
  });
});

afterEach(() => { cleanup();vi.restoreAllMocks(); });

describe("PermissionConfigPage", () => {
  it("lets a manager toggle and save a role", async () => {
    render(<PermissionConfigPage adapter={createSyntheticPermissionAdmin()} canManage onUnauthenticated={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: /上报人/ }));
    const save = screen.getByRole("button", { name: "保存" });
    expect(save).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(screen.getByLabelText(/查看风险管控/));
    expect(screen.getByText(/新增 1 项/)).toBeInTheDocument();
    fireEvent.click(save);
    expect(await screen.findByText(/已保存/)).toBeInTheDocument();
  });

  it("discards a draft without saving and prevents a clean save", async () => {
    const base = createSyntheticPermissionAdmin();
    const saveRolePermissions = vi.fn(base.saveRolePermissions);
    render(<PermissionConfigPage adapter={{ load: base.load, saveRolePermissions }} canManage onUnauthenticated={vi.fn()} />);
    const permission = await screen.findByLabelText(/查看风险管控/);
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(saveRolePermissions).not.toHaveBeenCalled();
    fireEvent.click(permission);
    expect(permission).toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "放弃修改" }));
    expect(permission).not.toBeChecked();
    expect(screen.queryByText(/待保存/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存" })).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(saveRolePermissions).not.toHaveBeenCalled();
  });

  it("keeps unsaved permissions on cancel and switches without saving only after confirmation", async () => {
    const prototype = HTMLDialogElement.prototype;
    const previousShow = Object.getOwnPropertyDescriptor(prototype, "showModal");
    const previousClose = Object.getOwnPropertyDescriptor(prototype, "close");
    Object.defineProperties(prototype, {
      showModal: { configurable: true, value(this: HTMLDialogElement) { this.setAttribute("open", ""); } },
      close: { configurable: true, value(this: HTMLDialogElement) { this.removeAttribute("open"); } },
    });
    const nativeConfirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    try {
      const base = createSyntheticPermissionAdmin();
      const saveRolePermissions = vi.fn(base.saveRolePermissions);
      const load = vi.fn(base.load);
      render(<PermissionConfigPage adapter={{ load, saveRolePermissions }} canManage onUnauthenticated={vi.fn()} />);
      const reporter = await screen.findByRole("button", { name: /上报人/ });
      const other = screen.getByRole("button", { name: /安全员/ });
      const permission = screen.getByLabelText(/查看风险管控/);
      fireEvent.click(permission);
      fireEvent.click(reporter);
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
      expect(permission).toBeChecked();
      other.focus();
      fireEvent.click(other);
      const confirmation = await screen.findByRole("alertdialog", { name: "放弃未保存的权限修改？" });
      const cancel = within(confirmation).getByRole("button", { name: "继续编辑" });
      const discard = within(confirmation).getByRole("button", { name: "放弃并切换" });
      expect(cancel).toHaveFocus();
      fireEvent.keyDown(cancel, { key: "Tab", shiftKey: true });
      expect(discard).toHaveFocus();
      fireEvent.keyDown(discard, { key: "Tab" });
      expect(cancel).toHaveFocus();
      fireEvent(confirmation, new Event("cancel", { cancelable: true }));
      expect(other).toHaveFocus();
      expect(screen.getByRole("heading", { name: "上报人" })).toBeInTheDocument();
      expect(permission).toBeChecked();
      expect(screen.getByText(/待保存/)).toBeInTheDocument();
      fireEvent.click(other);
      fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "放弃并切换" }));
      expect(screen.getByRole("heading", { name: "安全员" })).toBeInTheDocument();
      expect(permission).not.toBeChecked();
      expect(screen.queryByText(/待保存/)).not.toBeInTheDocument();
      expect(load).toHaveBeenCalledTimes(1);
      expect(saveRolePermissions).not.toHaveBeenCalled();
      expect(nativeConfirm).not.toHaveBeenCalled();
    } finally {
      cleanup();
      for (const [name, descriptor] of [["showModal", previousShow], ["close", previousClose]] as const) {
        if (descriptor) Object.defineProperty(prototype, name, descriptor);
        else Reflect.deleteProperty(prototype, name);
      }
    }
  });

  it("retries a failed load without writing permissions", async () => {
    const base = createSyntheticPermissionAdmin();
    const load = vi.fn().mockRejectedValueOnce(new PermissionAdminError("unavailable")).mockImplementation(base.load);
    const saveRolePermissions = vi.fn(base.saveRolePermissions);
    render(<PermissionConfigPage adapter={{ load, saveRolePermissions }} canManage onUnauthenticated={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "重试" }));
    expect(await screen.findByRole("heading", { name: "上报人" })).toBeInTheDocument();
    expect(load).toHaveBeenCalledTimes(2);
    expect(saveRolePermissions).not.toHaveBeenCalled();
  });

  it("keeps the saved role selected until its delayed save settles", async () => {
    const base = createSyntheticPermissionAdmin();let complete!: (role: RoleView) => void;
    const saveRolePermissions = vi.fn<PermissionAdminAdapter["saveRolePermissions"]>(() => new Promise(resolve => { complete = resolve; }));
    const data = await base.load();const reporter = data.roles.find(role => role.id === "r-reporter")!;
    render(<PermissionConfigPage adapter={{ load: base.load, saveRolePermissions }} canManage onUnauthenticated={vi.fn()} />);
    fireEvent.click(await screen.findByLabelText(/查看风险管控/));
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    const other = screen.getByRole("button", { name: /安全员/ });
    expect(screen.getByLabelText(/查看风险管控/)).toBeDisabled();
    expect(screen.getByRole("button", { name: "放弃修改" })).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(screen.getByRole("button", { name: "保存中…" }));
    fireEvent.click(screen.getByRole("button", { name: "放弃修改" }));
    expect(screen.getByLabelText(/查看风险管控/)).toBeChecked();
    expect(saveRolePermissions).toHaveBeenCalledTimes(1);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    fireEvent.click(other);
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("上报人");
    expect(other).toBeDisabled();expect(saveRolePermissions).toHaveBeenCalledTimes(1);
    complete({ ...reporter, permissionCodes: [...reporter.permissionCodes, "risk.read"], version: "saved" });
    await screen.findByText(/已保存/);expect(other).not.toBeDisabled();
    fireEvent.click(other);
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("安全员");
    expect(screen.getByLabelText(/查看风险管控/)).not.toBeChecked();
    expect(screen.getByRole("button", { name: "保存" })).toHaveAttribute("aria-disabled", "true");
    expect(saveRolePermissions).toHaveBeenCalledTimes(1);
  });

  it("does not claim synchronization or resend a saved PUT when bootstrap refresh fails", async () => {
    const base = createSyntheticPermissionAdmin(), saveRolePermissions = vi.fn(base.saveRolePermissions);
    const refresh = vi.fn(async () => { throw new Error("bootstrap unavailable"); });
    render(<PermissionConfigPage adapter={{ load: base.load, saveRolePermissions }} canManage onUnauthenticated={vi.fn()} onPermissionsChanged={refresh} />);
    fireEvent.click(await screen.findByLabelText(/查看风险管控/));fireEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("权限已保存，但当前会话尚未同步");
    expect(screen.queryByText("已保存，权限即时生效。")).not.toBeInTheDocument();expect(screen.getByRole("button", { name: "保存" })).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(screen.getByRole("button", { name: "保存" }));expect(saveRolePermissions).toHaveBeenCalledTimes(1);expect(refresh).toHaveBeenCalledTimes(1);
  });
  it("ignores a late save completion after its identity page is replaced", async () => {
    const base = createSyntheticPermissionAdmin(), before = (await base.load()).roles[0]!;
    let complete!: (role: RoleView) => void;
    const saved = vi.fn<PermissionAdminAdapter["saveRolePermissions"]>(() => new Promise(resolve => { complete = resolve; }));
    const oldRefresh = vi.fn(), expired = vi.fn();
    const view = render(<PermissionConfigPage adapter={{ load: base.load, saveRolePermissions: saved }} canManage onUnauthenticated={expired} onPermissionsChanged={oldRefresh} />);
    fireEvent.click(await screen.findByLabelText(/查看风险管控/));fireEvent.click(screen.getByRole("button", { name: "保存" }));view.unmount();
    const nextRefresh = vi.fn();render(<PermissionConfigPage adapter={createSyntheticPermissionAdmin()} canManage onUnauthenticated={vi.fn()} onPermissionsChanged={nextRefresh} />);
    await screen.findByLabelText(/查看风险管控/);await act(async () => { complete({ ...before, version: "2", permissionCodes: [...before.permissionCodes, "risk.read"] }); });
    expect(oldRefresh).not.toHaveBeenCalled();expect(nextRefresh).not.toHaveBeenCalled();expect(expired).not.toHaveBeenCalled();
    expect(screen.getByLabelText(/查看风险管控/)).not.toBeChecked();
  });

  it("is read-only without manage permission", async () => {
    render(<PermissionConfigPage adapter={createSyntheticPermissionAdmin()} canManage={false} onUnauthenticated={() => {}} />);
    await screen.findByRole("heading", { name: "上报人" });
    expect(screen.queryByRole("button", { name: "保存" })).toBeNull();
    expect(screen.getByLabelText(/查看隐患/)).toBeDisabled();
  });

  it("explains the last-manager guard", async () => {
    render(<PermissionConfigPage adapter={createSyntheticPermissionAdmin()} canManage onUnauthenticated={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: /系统管理员/ }));
    fireEvent.click(screen.getByLabelText(/修改权限配置/));
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("至少需要保留");
  });

  it("reloads and warns on a version conflict", async () => {
    const base = createSyntheticPermissionAdmin();
    const load = vi.fn(base.load);
    const adapter: PermissionAdminAdapter = {
      load,
      saveRolePermissions: async () => { throw new PermissionAdminError("conflict"); },
    };
    render(<PermissionConfigPage adapter={adapter} canManage onUnauthenticated={() => {}} />);
    fireEvent.click(await screen.findByLabelText(/上报隐患/));
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("已被他人修改");
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
  });

  it("signals an expired session on load", async () => {
    const onUnauthenticated = vi.fn();
    const adapter: PermissionAdminAdapter = {
      load: async () => { throw new PermissionAdminError("unauthenticated"); },
      saveRolePermissions: async () => { throw new Error("unused"); },
    };
    render(<PermissionConfigPage adapter={adapter} canManage onUnauthenticated={onUnauthenticated} />);
    await waitFor(() => expect(onUnauthenticated).toHaveBeenCalled());
  });
});
