import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { FindingsLedger } from "../features/findings/FindingsLedger";
import { FindingLedgerAccessError, type FindingLedgerAdapter } from "../adapters/findingLedger";
import { FindingCommandError, type FindingCommandAdapter, type FindingReviewHistory } from "../adapters/findingCommands";
import type { FindingRecord } from "../domain/finding";
import { navigate } from "../routing/router";

const id = "00000000-0000-4000-8000-000000000001";
const record: FindingRecord = { id, code: "YH-private", description: "已撤权的详情", location: "l", state: "PERSON_CONFIRM_PENDING", reportedAt: "", reporter: "—", responsible: "—", riskLabel: "待专业评估", round: 1, version: 1, history: [] };
const history: FindingReviewHistory = { entries: [], currentVersion: 1, currentState: record.state, reviewCycle: 1, reviewProtocol: "CYCLE_V1", processDefinitionKey: "findingRemediationV3", reviewStage: "PERSON", availableReviewActions: ["APPROVE"], blockedReason: null, hasMore: false, nextCursor: null };
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
afterEach(() => { cleanup(); navigate("/", true); });
it.each(["unavailable", "versionConflict"] as const)("list 403 clears the %s draft, selection and pending request, including late detail reads", async commandFailure => {
  navigate(`/safety/findings?caseId=${id}`, true);
  const deniedList = deferred<never>();
  const lateDetail = deferred<FindingRecord>();
  const list = vi.fn().mockResolvedValueOnce({ items: [record], total: 41, pageCount: 3, page: { index: 1, size: 20 } }).mockReturnValue(deniedList.promise);
  const get = vi.fn().mockResolvedValueOnce(record).mockReturnValue(lateDetail.promise);
  const run = vi.fn().mockRejectedValue(new FindingCommandError(commandFailure));
  const adapter: FindingLedgerAdapter = { list, get };
  const commands: FindingCommandAdapter = { run, history: async () => history };
  render(<FindingsLedger title="隐患台账" description="mock" view="all" adapter={adapter} commandAdapter={commands} />);
  fireEvent.click(await screen.findByRole("button", { name: "确认发现" }));
  fireEvent.change(screen.getByLabelText(/意见/), { target: { value: "撤权前私有草稿" } });
  fireEvent.click(screen.getByRole("button", { name: "确认确认发现" }));
  if (commandFailure === "unavailable") {
    await screen.findByRole("button", { name: "重试" });
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
  } else {
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
  }
  await waitFor(() => expect(list).toHaveBeenCalledTimes(2));
  await act(async () => deniedList.reject(new FindingLedgerAccessError("forbidden")));
  expect(await screen.findByText("当前身份无权读取隐患记录。")).toBeInTheDocument();
  expect(screen.queryByDisplayValue("撤权前私有草稿")).not.toBeInTheDocument();
  expect(screen.queryByRole("complementary", { name: "隐患详情面板" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();
  expect(window.location.search).toBe("");
  await act(async () => lateDetail.resolve({ ...record, description: "迟到的详情不能复活" }));
  expect(screen.queryByText("迟到的详情不能复活")).not.toBeInTheDocument();
  expect(screen.queryByText("已撤权的详情")).not.toBeInTheDocument();
  expect(run).toHaveBeenCalledTimes(1);
});
