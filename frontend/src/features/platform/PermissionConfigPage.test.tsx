import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
    expect(save).toBeDisabled();
    fireEvent.click(screen.getByLabelText(/查看风险管控/));
    expect(screen.getByText(/新增 1 项/)).toBeInTheDocument();
    fireEvent.click(save);
    expect(await screen.findByText(/已保存/)).toBeInTheDocument();
  });

  it("keeps the saved role selected until its delayed save settles", async () => {
    const base = createSyntheticPermissionAdmin();let complete!: (role: RoleView) => void;
    const saveRolePermissions = vi.fn<PermissionAdminAdapter["saveRolePermissions"]>(() => new Promise(resolve => { complete = resolve; }));
    const data = await base.load();const reporter = data.roles.find(role => role.id === "r-reporter")!;
    render(<PermissionConfigPage adapter={{ load: base.load, saveRolePermissions }} canManage onUnauthenticated={vi.fn()} />);
    fireEvent.click(await screen.findByLabelText(/查看风险管控/));
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    const other = screen.getByRole("button", { name: /安全员/ });
    vi.spyOn(window, "confirm").mockReturnValue(true);
    fireEvent.click(other);
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("上报人");
    expect(other).toBeDisabled();expect(saveRolePermissions).toHaveBeenCalledTimes(1);
    complete({ ...reporter, permissionCodes: [...reporter.permissionCodes, "risk.read"], version: "saved" });
    await screen.findByText(/已保存/);expect(other).not.toBeDisabled();
    fireEvent.click(other);
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("安全员");
    expect(screen.getByLabelText(/查看风险管控/)).not.toBeChecked();
    expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();
    expect(saveRolePermissions).toHaveBeenCalledTimes(1);
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
