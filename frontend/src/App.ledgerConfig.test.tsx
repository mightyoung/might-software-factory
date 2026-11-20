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
