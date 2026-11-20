import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { createSyntheticSessionAdapter } from "./adapters/sessionAdapter";
import { App } from "./App";
import { navigate } from "./routing/router";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

it("preserves the configuration in the same authenticated tab through real history traversal", async () => {
  const path = "/t/demo-tenant/platform/ledger-configurations";
  window.history.replaceState(null, "", "/t/demo-tenant/safety/findings");
  window.history.pushState(null, "", path);
  const adapter = createSyntheticSessionAdapter({ signedInAs: "demo.admin" });
  const current = (await adapter.restore())!;
  const grants = ["ledger.configuration.read", "ledger.configuration.manage"];
  const fetchConfig = vi.fn().mockResolvedValue(Response.json({ items: [] }));
  vi.stubGlobal("fetch", fetchConfig);
  render(<App mode="http" sessionAdapter={{
    ...adapter,
    restore: async () => ({
      ...current,
      permissions: [...current.permissions, ...grants],
      canonicalPermissions: [...(current.canonicalPermissions ?? current.permissions), ...grants],
    }),
  }} />);
  await waitFor(() => expect(screen.getByLabelText("台账名称")).toBeEnabled());
  fireEvent.change(screen.getByLabelText("台账名称"), { target: { value: "尚未保存的配置" } });
  window.history.back();
  expect(await screen.findByRole("heading", { name: "隐患台账" })).toBeVisible();
  expect(screen.queryByRole("heading", { name: "台账配置" })).not.toBeInTheDocument();
  window.history.forward();
  expect(await screen.findByRole("heading", { name: "台账配置" })).toBeVisible();
  expect(screen.getByLabelText("台账名称")).toHaveValue("尚未保存的配置");
  navigate("/safety/findings");
  await waitFor(() => expect(window.location.pathname).toBe("/t/demo-tenant/safety/findings"));
  expect(await screen.findByRole("heading", { name: "隐患台账" })).toBeVisible();
  navigate(path);
  expect(await screen.findByRole("heading", { name: "台账配置" })).toBeVisible();
  expect(screen.getByLabelText("台账名称")).toHaveValue("尚未保存的配置");
  expect(fetchConfig.mock.calls.filter(([url]) => url === "/api/v1/admin/ledger-configurations")).toHaveLength(1);
});

it("discards the hidden editor and a pending retry when the same actor's permissions change", async () => {
  const configPath = "/t/demo-tenant/platform/ledger-configurations";
  window.history.replaceState(null, "", configPath);
  const adapter = createSyntheticSessionAdapter({ signedInAs: "demo.admin" });
  const current = (await adapter.restore())!;
  const grants = ["ledger.configuration.read", "ledger.configuration.manage", "platform.permission.manage"];
  const revoked = "ledger.configuration.publish";
  const withGrants = (codes: string[]) => ({
    ...current,
    permissions: [...current.permissions, ...codes],
    canonicalPermissions: [...(current.canonicalPermissions ?? current.permissions), ...codes],
  });
  const publishGrant = vi.fn()
    .mockResolvedValueOnce(withGrants([...grants, revoked]))
    .mockResolvedValueOnce(withGrants(grants));
  const fetchConfig = vi.fn(async (_url: string, options?: RequestInit) => {
    if (options?.method === "POST") throw new Error("Lost save response");
    return Response.json({ items: [] });
  });
  vi.stubGlobal("fetch", fetchConfig);
  const role = {
    id: "test-role", code: "test-role", name: "配置员", active: true,
    permissionCodes: [revoked], memberCount: 1, version: "1",
  };
  const permissions = {
    load: async () => ({
      roles: [role],
      catalog: [{ code: revoked, application: "platform", label: "发布台账" }],
    }),
    saveRolePermissions: vi.fn().mockResolvedValue({ ...role, permissionCodes: [], version: "2" }),
  };
  render(<App mode="http" sessionAdapter={{ ...adapter, restore: publishGrant }} createPermissionAdmin={() => permissions} />);
  await waitFor(() => expect(screen.getByLabelText("台账名称")).toBeEnabled());
  fireEvent.change(screen.getByLabelText("台账标识"), { target: { value: "inventory" } });
  fireEvent.change(screen.getByLabelText("台账名称"), { target: { value: "尚未确认的草稿" } });
  fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
  expect(await screen.findByRole("button", { name: "重试原操作" })).toBeEnabled();

  navigate("/t/demo-tenant/platform/permissions");
  fireEvent.click(await screen.findByRole("checkbox", { name: "发布台账" }));
  fireEvent.click(screen.getByRole("button", { name: "保存" }));
  await waitFor(() => expect(publishGrant).toHaveBeenCalledTimes(2));
  navigate(configPath);
  await waitFor(() => expect(screen.getByLabelText("台账名称")).toBeEnabled());
  expect(screen.getByLabelText("台账名称")).toHaveValue("");
  expect(screen.getByLabelText("台账标识")).toHaveValue("");
  expect(screen.queryByRole("button", { name: "重试原操作" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "发布当前版本" })).not.toBeInTheDocument();
  expect(fetchConfig.mock.calls.filter(([, request]) => request?.method === "POST")).toHaveLength(1);
});
