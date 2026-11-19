import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { App } from "../../App";
import type { SessionAdapter } from "../../adapters/sessionAdapter";
import type { SessionBootstrap } from "../../domain/session";
import type { LoanAdapter } from "./model";
afterEach(() => { cleanup();window.history.replaceState(null, "", "/"); });
function setup(grants: string[], tenant = "t") {
  window.history.replaceState(null, "", `/t/${tenant}/apps/tooling/entities/tool_loan`);
  const session: SessionBootstrap = { actor: { id: "a", displayName: "借用人" }, identityScope: { accountId: "a", memberId: "m" }, tenant: { id: "t", name: "工厂" }, applications: [{ id: "tooling", name: "工装管理", entryPath: "apps/tooling/entities/tool_loan" }], permissions: [], canonicalPermissions: grants };
  const sessions: SessionAdapter = { restore: vi.fn(async () => session), login: vi.fn(async () => session), logout: vi.fn(async () => {}), loadMenu: vi.fn(async () => ({ applicationId: "tooling", title: "工装管理", nodes: [{ id: "loans", label: "借用申请", path: "apps/tooling/entities/tool_loan", permission: grants.includes("tool_loan.read") ? "tool_loan.read" : "tool_loan.issue" }] })) };
  const loans: LoanAdapter = { receiveQueue: vi.fn(async () => []), receiveDetail: vi.fn(), issueQueue: vi.fn(async () => []), issueDetail: vi.fn(), list: vi.fn(async () => []), detail: vi.fn(), candidates: vi.fn(), command: vi.fn() };
  const entities = vi.fn();render(<App mode="http" sessionAdapter={sessions} createLoans={() => loans} createEntityLedger={entities} />);
  return { loans, entities };
}
it("opens the authorized loan page through the actual application route and menu", async () => {
  const { loans } = setup(["tool_loan.read", "tool_loan.create"]);
  expect(await screen.findByRole("heading", { name: "工装借用申请" })).toBeVisible();
  expect(await screen.findByRole("link", { name: "借用申请" })).toHaveAttribute("href", "/t/t/apps/tooling/entities/tool_loan");
  expect(await screen.findByText("暂无本人借用申请。")).toBeVisible();expect(loans.list).toHaveBeenCalledTimes(1);
});
it("does not call loan endpoints without a loan permission", async () => {
  const { loans } = setup(["tool.read"]);expect(await screen.findByRole("heading", { name: "无权访问" })).toBeVisible();expect(loans.list).not.toHaveBeenCalled();
});
it("does not mount another tenant's loan reader", async () => {
  const { loans } = setup(["tool_loan.read"], "other");expect(await screen.findByRole("heading", { name: "无权访问" })).toBeVisible();expect(loans.list).not.toHaveBeenCalled();
});

it("opens keeper work from canonical issue permission without applicant read rights", async () => {
  const { loans } = setup(["tool_loan.issue"]);
  expect(await screen.findByText("当前保管范围内没有待发放申请。")).toBeVisible();
  expect(loans.issueQueue).toHaveBeenCalledTimes(1);expect(loans.list).not.toHaveBeenCalled();
  expect(await screen.findByRole("link", { name: "借用申请" })).toBeVisible();
});
