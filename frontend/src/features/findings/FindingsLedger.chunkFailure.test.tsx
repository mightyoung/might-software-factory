import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { FindingLedgerAdapter } from "../../adapters/findingLedger";
import { FindingLedgerAccessError } from "../../adapters/findingLedger";
import { navigate } from "../../routing/router";
import { FindingsLedger } from "./FindingsLedger";

const chunk = vi.hoisted(() => ({ reject: undefined as (() => void) | undefined }));
vi.mock("../../components/FindingDetails", () => new Promise((_resolve, reject) => {
  chunk.reject = () => reject(new TypeError("versioned module not available"));
}));

afterEach(() => {
  cleanup();
  window.history.replaceState(null, "", "/");
});

function openDetail(adapter: FindingLedgerAdapter) {
  navigate("/safety/findings?caseId=detail-1&keep=1", true);
  render(<FindingsLedger view="all" title="隐患台账" description="" adapter={adapter} />);
}

it("keeps close and the scoped URL available when only the detail module fails", async () => {
  const get = vi.fn(async () => null);
  openDetail({ get, list: async () => ({ items: [], total: 0, pageCount: 1, page: { index: 1, size: 20 } }) });
  expect(screen.getByRole("button", { name: "关闭详情" })).toBeEnabled();
  await vi.waitFor(() => expect(chunk.reject).toBeTypeOf("function"));
  chunk.reject!();
  expect(await screen.findByRole("button", { name: "重新加载页面" })).toBeEnabled();
  expect(screen.getByRole("alert")).toHaveTextContent("无法载入隐患详情");
  expect(window.location.search).toBe("?caseId=detail-1&keep=1");
  expect(get).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole("button", { name: "关闭详情" }));
  expect(screen.queryByRole("complementary", { name: "隐患详情面板" })).not.toBeInTheDocument();
  expect(window.location.search).toBe("");
});

it("does not replace an authoritative access denial with the chunk recovery error", async () => {
  openDetail({
    get: async () => { throw new FindingLedgerAccessError("forbidden"); },
    list: async () => ({ items: [], total: 0, pageCount: 1, page: { index: 1, size: 20 } }),
  });
  await vi.waitFor(() => expect(chunk.reject).toBeTypeOf("function"));
  chunk.reject!();
  const alert = await screen.findByRole("alert");
  expect(alert.textContent).toMatch(/无权|权限/);
  expect(screen.queryByRole("button", { name: "重新加载页面" })).not.toBeInTheDocument();
});
