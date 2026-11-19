import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../../App";
import type { SessionAdapter } from "../../adapters/sessionAdapter";
import type { SessionBootstrap } from "../../domain/session";
import { adapter } from "./testFixture";
afterEach(() => { cleanup();window.history.replaceState(null, "", "/"); });
function session(): SessionBootstrap { return { actor: { id: "a", displayName: "业务人员" }, identityScope: { accountId: "account", memberId: "member" }, tenant: { id: "t", name: "厂区" }, applications: [{ id: "inventory", name: "库存", entryPath: "apps/inventory/entities/widget" }], permissions: [], canonicalPermissions: ["widget.read", "widget.create", "widget.update"] }; }
function sessions(): SessionAdapter { return { restore: vi.fn(async () => session()), login: vi.fn(async () => session()), logout: vi.fn(async () => {}), loadMenu: vi.fn(async () => ({ applicationId: "inventory", title: "库存导航", nodes: [{ id: "widgets", label: "物品", path: "apps/inventory/entities/widget", permission: "widget.read" }] })) }; }
describe("one generic application route", () => {
  it("mounts a new application without adding its name to any frontend registry", async () => {
    window.history.replaceState(null, "", "/t/t/apps/inventory/entities/widget");const a = adapter(), factory = vi.fn(() => a);
    render(<App mode="http" sessionAdapter={sessions()} createEntityLedger={factory} />);
    expect(await screen.findByText("W-01")).toBeInTheDocument();expect(screen.getByRole("link", { name: "MIGHT 工作台首页" })).toHaveTextContent("MIGHT 工作台");expect(factory).toHaveBeenCalledWith("inventory", "widget");expect(await screen.findByRole("link", { name: "物品" })).toHaveAttribute("href", "/t/t/apps/inventory/entities/widget");
    fireEvent.click(screen.getByRole("button", { name: "新增物品" }));await waitFor(() => expect(window.location.pathname).toBe("/t/t/apps/inventory/entities/widget/create"));expect(await screen.findByRole("button", { name: "保存" })).toBeInTheDocument();
  });
  it("rejects another tenant before creating a reader", async () => {
    window.history.replaceState(null, "", "/t/other/apps/inventory/entities/widget");const a = adapter();render(<App mode="http" sessionAdapter={sessions()} createEntityLedger={() => a} />);expect(await screen.findByRole("heading", { name: "无权访问" })).toBeInTheDocument();expect(a.definition).not.toHaveBeenCalled();
  });
});
