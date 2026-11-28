import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { App } from "../../App";
import type { SessionAdapter } from "../../adapters/sessionAdapter";
import type { PermissionAdminAdapter } from "../../adapters/permissionAdmin";
import type { SessionBootstrap } from "../../domain/session";
const initial = (): SessionBootstrap => ({ actor: { id: "a", displayName: "管理员" }, identityScope: { accountId: "a", memberId: "m" }, tenant: { id: "t", name: "工厂" }, applications: [{ id: "platform", name: "系统设置", entryPath: "platform/permissions" }], permissions: ["platform.permission.read", "platform.permission.manage"] });
afterEach(() => { cleanup();window.history.replaceState(null, "", "/"); });
function setup(revoke = false) {
  let session = initial();let role = { id: "self", code: "self", name: "本人角色", active: true, memberCount: 1, version: "1", permissionCodes: ["platform.permission.read", "platform.permission.manage"] };
  const sessions: SessionAdapter = { restore: vi.fn(async () => session), login: vi.fn(async () => session), logout: vi.fn(async () => {}), loadMenu: vi.fn(async id => ({ applicationId: id, title: "导航", nodes: id === "platform" ? [{ id: "permissions", label: "权限配置", permission: "platform.permission.read", path: "platform/permissions" }] : [{ id: "findings", label: "隐患台账", permission: "safety.finding.read", path: "safety/findings" }] })) };
  const admin: PermissionAdminAdapter = { load: vi.fn(async () => ({ catalog: [{ code: "finding.read", application: "safety", label: "查看隐患" }, { code: "platform.permission.manage", application: "platform", label: "修改权限配置" }], roles: [role] })), saveRolePermissions: vi.fn(async (_id, codes) => {
    role = { ...role, permissionCodes: [...codes], version: "2" };
    session = revoke ? { ...initial(), permissions: ["platform.permission.read"] } : { ...initial(), applications: [...initial().applications, { id: "safety", name: "生产安全", entryPath: "safety/findings" }], permissions: [...initial().permissions, "safety.finding.read"] };
    return role;
  }) };
  window.history.replaceState(null, "", "/t/t/platform/permissions");render(<App sessionAdapter={sessions} createPermissionAdmin={() => admin} />);
  return { sessions, admin };
}
it("makes a newly granted application and route available in the current session", async () => {
  const { sessions, admin } = setup();
  fireEvent.click(await screen.findByLabelText(/查看隐患/));
  expect(admin.load).toHaveBeenCalledTimes(1);
  expect(admin.saveRolePermissions).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "保存" }));
  const link = await screen.findByRole("link", { name: "生产安全" });
  expect(sessions.restore).toHaveBeenCalledTimes(2);
  fireEvent.click(link);expect(await screen.findByRole("heading", { name: "隐患台账" })).toBeInTheDocument();
});
it("immediately makes the current editor read-only after revoking its management grant", async () => {
  const { sessions } = setup(true);fireEvent.click(await screen.findByLabelText(/修改权限配置/));fireEvent.click(screen.getByRole("button", { name: "保存" }));
  await waitFor(() => expect(screen.queryByRole("button", { name: "保存" })).not.toBeInTheDocument());
  expect(await screen.findByLabelText(/修改权限配置/)).toBeDisabled();expect(sessions.restore).toHaveBeenCalledTimes(2);
});
