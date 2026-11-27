import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { createSyntheticSessionAdapter } from "./adapters/sessionAdapter";
import { App } from "./App";
import { navigate } from "./routing/router";

const namedRouteComponents = vi.hoisted(() => new Map<string, unknown>());
const registeredRouteComponents = vi.hoisted(() => new Map<string, unknown>());
vi.mock("@tanstack/react-router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@tanstack/react-router")>();
  return {
    ...actual,
    lazyRouteComponent: (importer: Parameters<typeof actual.lazyRouteComponent>[0], name?: string) => {
      const component = actual.lazyRouteComponent(importer, name);
      if (name) namedRouteComponents.set(name, component);
      return component;
    },
    createRoute: (options: Parameters<typeof actual.createRoute>[0]) => {
      if ("path" in options && typeof options.path === "string") registeredRouteComponents.set(options.path, options.component);
      return actual.createRoute(options);
    },
  };
});

it("registers the existing deferred config module as the real TanStack configuration route", () => {
  const component = namedRouteComponents.get("LedgerConfigPage");
  expect(component).toBeTypeOf("function");
  expect(registeredRouteComponents.get("platform/ledger-configurations")).toBe(component);
  expect(registeredRouteComponents.get("platform/ledgers")).not.toBe(component);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

it("preserves the local configuration draft and revalidates the directory only on active visits", async () => {
  const path = "/t/demo-tenant/platform/ledger-configurations";
  window.history.replaceState(null, "", "/t/demo-tenant/safety/findings");
  window.history.pushState(null, "", path);
  const adapter = createSyntheticSessionAdapter({ signedInAs: "demo.admin" });
  const current = (await adapter.restore())!;
  const grants = ["ledger.configuration.read", "ledger.configuration.manage"];
  const fetchConfig = vi.fn().mockResolvedValue(Response.json({ items: [] }));
  vi.stubGlobal("fetch", fetchConfig);
  const countDirectoryReads = () => fetchConfig.mock.calls.filter(([url]) => url === "/api/v1/admin/ledger-configurations").length;
  render(<App mode="http" sessionAdapter={{
    ...adapter,
    restore: async () => ({
      ...current,
      permissions: [...current.permissions, ...grants],
      canonicalPermissions: [...(current.canonicalPermissions ?? current.permissions), ...grants],
    }),
  }} />);
  await waitFor(() => expect(screen.getByLabelText("台账名称")).toBeEnabled());
  expect(countDirectoryReads()).toBe(1);
  fireEvent.change(screen.getByLabelText("台账名称"), { target: { value: "尚未保存的配置" } });
  window.history.back();
  expect(await screen.findByRole("heading", { name: "隐患台账" })).toBeVisible();
  expect(screen.queryByRole("heading", { name: "台账配置" })).not.toBeInTheDocument();
  expect(countDirectoryReads()).toBe(1);
  window.history.forward();
  expect(await screen.findByRole("heading", { name: "台账配置" })).toBeVisible();
  await waitFor(() => expect(screen.getByLabelText("台账名称")).toBeEnabled());
  expect(screen.getByLabelText("台账名称")).toHaveValue("尚未保存的配置");
  expect(countDirectoryReads()).toBe(2);
  navigate("/safety/findings");
  await waitFor(() => expect(window.location.pathname).toBe("/t/demo-tenant/safety/findings"));
  expect(await screen.findByRole("heading", { name: "隐患台账" })).toBeVisible();
  expect(countDirectoryReads()).toBe(2);
  navigate(path);
  expect(await screen.findByRole("heading", { name: "台账配置" })).toBeVisible();
  await waitFor(() => expect(screen.getByLabelText("台账名称")).toBeEnabled());
  expect(screen.getByLabelText("台账名称")).toHaveValue("尚未保存的配置");
  expect(countDirectoryReads()).toBe(3);
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

it("loads the authorized published-ledger route lazily and keeps its draft in the same identity", async () => {
  const path = "/t/demo-tenant/platform/ledgers";
  const findings = "/t/demo-tenant/safety/findings";
  window.history.replaceState(null, "", findings);
  const adapter = createSyntheticSessionAdapter({ signedInAs: "demo.admin" });
  const session = (await adapter.restore())!;
  const grant = "ledger.directory.open";
  const recordApi = vi.fn(async (url: string) => url.endsWith("/ledgers")
    ? Response.json({ items: [{
      key: "purchase", definitionVersion: 1,
      definition: { name: "采购申请", fields: [{ key: "name", label: "名称", type: "text", required: true }], listFields: [], queryFields: [] },
      organizations: [{ id: "org", name: "生产部" }],
      capabilities: { create: true, read: false, update: false, delete: false },
    }] })
    : Response.json({}));
  vi.stubGlobal("fetch", recordApi);
  render(<App mode="http" sessionAdapter={{ ...adapter, restore: async () => ({
    ...session,
    permissions: [...session.permissions, grant],
    canonicalPermissions: [...(session.canonicalPermissions ?? session.permissions), grant],
  }) }} />);
  const directoryReads = () => recordApi.mock.calls.filter(([url]) => url === "/api/v1/ledgers").length;
  await screen.findByRole("heading", { name: "隐患台账" });
  expect(directoryReads()).toBe(0);
  navigate(path);
  fireEvent.click(await screen.findByRole("button", { name: "采购申请" }));
  fireEvent.click(screen.getByRole("button", { name: "新增记录" }));
  fireEvent.change(screen.getByLabelText("名称（必填）"), { target: { value: "保留原草稿" } });
  expect(directoryReads()).toBe(1);
  window.history.back();
  await screen.findByRole("heading", { name: "隐患台账" });
  expect(screen.queryByRole("heading", { name: "业务台账" })).not.toBeInTheDocument();
  expect(directoryReads()).toBe(1);
  window.history.forward();
  await screen.findByRole("heading", { name: "业务台账" });
  expect(screen.getByLabelText("名称（必填）")).toHaveValue("保留原草稿");
  expect(directoryReads()).toBe(1);
});

it("does not carry a published-ledger write across the same actor's permission change", async () => {
  const path = "/t/demo-tenant/platform/ledgers";
  window.history.replaceState(null, "", path);
  const adapter = createSyntheticSessionAdapter({ signedInAs: "demo.admin" });
  const current = (await adapter.restore())!;
  const grants = ["ledger.directory.open", "platform.permission.manage"];
  const revoked = "ledger.purchase.create";
  const withGrants = (codes: string[]) => ({
    ...current,
    permissions: [...current.permissions, ...codes],
    canonicalPermissions: [...(current.canonicalPermissions ?? current.permissions), ...codes],
  });
  const bootstrap = vi.fn()
    .mockResolvedValueOnce(withGrants([...grants, revoked]))
    .mockResolvedValueOnce(withGrants(grants));
  const recordApi = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith("/records") && options?.method === "POST") throw new Error("Lost response");
    if (url.endsWith("/ledgers")) return Response.json({ items: [{
      key: "purchase", definitionVersion: 1,
      definition: { name: "采购申请", fields: [{ key: "name", label: "名称", type: "text", required: true }], listFields: [], queryFields: [] },
      organizations: [{ id: "org", name: "生产部" }],
      capabilities: { create: bootstrap.mock.calls.length === 1, read: false, update: false, delete: false },
    }] });
    return Response.json({});
  });
  vi.stubGlobal("fetch", recordApi);
  const role = { id: "role", code: "role", name: "配置员", active: true, permissionCodes: [revoked], memberCount: 1, version: "1" };
  const permissions = {
    load: async () => ({ roles: [role], catalog: [{ code: revoked, application: "platform", label: "新增采购申请" }] }),
    saveRolePermissions: vi.fn().mockResolvedValue({ ...role, permissionCodes: [], version: "2" }),
  };
  render(<App mode="http" sessionAdapter={{ ...adapter, restore: bootstrap }} createPermissionAdmin={() => permissions} />);
  fireEvent.click(await screen.findByRole("button", { name: "采购申请" }));
  fireEvent.click(screen.getByRole("button", { name: "新增记录" }));
  fireEvent.change(screen.getByLabelText("名称（必填）"), { target: { value: "采购草稿" } });
  fireEvent.click(screen.getByRole("button", { name: "保存记录" }));
  expect(await screen.findByRole("button", { name: "重试原操作" })).toBeVisible();
  navigate("/t/demo-tenant/platform/permissions");
  fireEvent.click(await screen.findByRole("checkbox", { name: "新增采购申请" }));
  fireEvent.click(screen.getByRole("button", { name: "保存" }));
  await waitFor(() => expect(bootstrap).toHaveBeenCalledTimes(2));
  navigate(path);
  fireEvent.click(await screen.findByRole("button", { name: "采购申请" }));
  expect(screen.queryByRole("button", { name: "重试原操作" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "新增记录" })).not.toBeInTheDocument();
  expect(screen.queryByLabelText("名称（必填）")).not.toBeInTheDocument();
  expect(recordApi.mock.calls.filter(([url, request]) => url.endsWith("/records") && request?.method === "POST")).toHaveLength(1);
});
