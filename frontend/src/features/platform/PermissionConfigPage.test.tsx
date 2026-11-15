import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createSyntheticPermissionAdmin } from "../../adapters/syntheticPermissionAdmin";
import { PermissionAdminError, type PermissionAdminAdapter } from "../../adapters/permissionAdmin";
import { PermissionConfigPage, diffCodes } from "./PermissionConfigPage";

describe("diffCodes", () => {
  it("reports additions and removals", () => {
    expect(diffCodes(["a", "b"], new Set(["b", "c"]))).toEqual({ added: ["c"], removed: ["a"] });
  });
});

afterEach(cleanup);

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
