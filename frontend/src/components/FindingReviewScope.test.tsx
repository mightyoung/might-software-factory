import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { App, type FindingStore } from "../App";
import type { SessionAdapter } from "../adapters/sessionAdapter";
import type { SessionBootstrap } from "../domain/session";
import type { FindingRecord } from "../domain/finding";
import { FindingCommandError, type FindingReviewHistory } from "../adapters/findingCommands";
import { FindingLedgerAccessError } from "../adapters/findingLedger";

const id = "00000000-0000-4000-8000-000000000001";
const record: FindingRecord = { id, code: "YH-private", description: "旧身份详情", location: "l", state: "PERSON_CONFIRM_PENDING", reportedAt: "", reporter: "—", responsible: "—", riskLabel: "待专业评估", round: 1, version: 1, history: [] };
const history: FindingReviewHistory = { entries: [], currentVersion: 1, currentState: record.state, reviewCycle: 1, reviewProtocol: "CYCLE_V1", processDefinitionKey: "findingRemediationV3", reviewStage: "PERSON", availableReviewActions: ["APPROVE"], blockedReason: null, hasMore: false, nextCursor: null };
function session(memberId = "m1", accountId = "a1"): SessionBootstrap {
  return { actor: { id: "same-actor", displayName: "用户" }, tenant: { id: "t1", name: "租户" }, identityScope: { memberId, accountId }, applications: [{ id: "safety", name: "安全", entryPath: "safety/findings" }], permissions: ["safety.finding.read"] };
}
function sessionAdapter(value: SessionBootstrap): SessionAdapter {
  return { restore: async () => value, login: async () => value, logout: async () => {}, loadMenu: async () => ({ applicationId: "safety", title: "安全", nodes: [] }) };
}
afterEach(() => { cleanup(); window.history.replaceState(null, "", "/"); });
it.each(["member", "account", "tenant"])("clears draft and uncertain request when verified %s scope changes, even if the new read fails", async scope => {
  window.history.replaceState(null, "", `/t/t1/safety/findings?caseId=${id}`);
  const run = vi.fn().mockRejectedValue(new FindingCommandError("unavailable"));
  const oldStore = { ledger: { list: async () => ({ items: [record], total: 1, pageCount: 1, page: { index: 1, size: 20 } }), get: async () => record }, commands: { run, history: async () => history } } as unknown as FindingStore;
  const nextGet = vi.fn().mockRejectedValue(new FindingLedgerAccessError("forbidden"));
  const nextRow = { ...record, description: "新身份可见摘要" };
  const nextStore = { ...oldStore, ledger: { list: async () => ({ items: [nextRow], total: 1, pageCount: 1, page: { index: 1, size: 20 } }), get: nextGet }, commands: { run: vi.fn(), history: vi.fn().mockRejectedValue(new Error("503")) } } as unknown as FindingStore;
  let currentStore = oldStore;
  const factory = () => currentStore;
  const view = render(<App sessionAdapter={sessionAdapter(session())} createFindingStore={factory} />);
  fireEvent.click(await screen.findByRole("button", { name: "确认发现" }));
  fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "旧身份私有草稿" } });
  fireEvent.click(screen.getByRole("button", { name: "确认确认发现" }));
  await screen.findByText(/操作尚未确认成功/);
  currentStore = nextStore;
  const nextSession = session(scope === "member" ? "m2" : "m1", scope === "account" ? "a2" : "a1");
  if (scope === "tenant") nextSession.tenant = { id: "t2", name: "新租户" };
  view.rerender(<App sessionAdapter={sessionAdapter(nextSession)} createFindingStore={factory} />);
  await waitFor(() => expect(screen.queryByDisplayValue("旧身份私有草稿")).not.toBeInTheDocument());
  expect(screen.queryByText("旧身份详情")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();
  expect(run).toHaveBeenCalledTimes(1);
  if (scope === "tenant") {
    await screen.findByRole("heading", { name: "无权访问" });
    expect(new URLSearchParams(window.location.search).has("caseId")).toBe(false);
    expect(nextGet).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("link", { name: "MIGHT 工作台首页" }));
  }
  await screen.findByText("新身份可见摘要");
  expect(new URLSearchParams(window.location.search).has("caseId")).toBe(false);
  expect(nextGet).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "详情" }));
  await screen.findByText("当前身份无权读取隐患详情。");
  expect(nextGet).toHaveBeenCalledTimes(1);
  expect(nextGet).toHaveBeenCalledWith(id, expect.objectContaining({ signal: expect.any(AbortSignal) }));
  expect(screen.queryByDisplayValue("旧身份私有草稿")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();
  expect(run).toHaveBeenCalledTimes(1);
});
it("clears draft and uncertain request when selecting a different finding", async () => {
  // FindingDetails is keyed by finding ID; selecting a new record remounts the command panel.
  const { FindingDetails } = await import("./FindingDetails");
  const run = vi.fn().mockRejectedValue(new FindingCommandError("unavailable"));
  const adapter = { run, history: async () => history };
  const props = { finding: record, loading: false, error: null, onClose: vi.fn(), onRetry: vi.fn(), commandAdapter: adapter };
  const view = render(<FindingDetails {...props} />);
  fireEvent.click(await screen.findByRole("button", { name: "确认发现" }));
  fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "前一条草稿" } });
  fireEvent.click(screen.getByRole("button", { name: "确认确认发现" }));
  await screen.findByText(/操作尚未确认成功/);
  view.rerender(<FindingDetails {...props} finding={{ ...record, id: "another" }} />);
  await screen.findByRole("button", { name: "确认发现" });
  expect(screen.queryByDisplayValue("前一条草稿")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();
  expect(run).toHaveBeenCalledTimes(1);
});
