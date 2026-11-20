import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ApiError } from "../../api/httpClient";
import { PublishedLedgersPage } from "./PublishedLedgersPage";
import type {
  PublishedLedger,
  PublishedLedgerAdapter,
  RecordDetail,
} from "./model";

const capabilities = { read: true, create: true, update: true, delete: true };
const definition = {
  name: "采购申请",
  fields: [
    { key: "quantity", label: "数量", type: "number" as const, required: true },
  ],
  listFields: ["quantity"],
  queryFields: ["quantity"],
};
const ledger: PublishedLedger = {
  key: "purchase",
  definitionVersion: 2,
  definition,
  capabilities,
  organizations: [{ id: "org", name: "生产部" }],
};
const detail: RecordDetail = {
  capabilities,
  definition,
  record: {
    id: "record",
    key: "purchase",
    definitionVersion: 1,
    organizationId: "org",
    values: { quantity: 4 },
    version: 1,
    createdAt: "",
    updatedAt: "",
  },
};
function adapter(): PublishedLedgerAdapter {
  return {
    list: vi.fn().mockResolvedValue([ledger]),
    query: vi
      .fn()
      .mockResolvedValue({
        records: [detail.record],
        total: 1,
        page: 1,
        size: 20,
        hasMore: false,
      }),
    detail: vi.fn().mockResolvedValue(detail),
    create: vi.fn().mockResolvedValue(detail.record),
    update: vi.fn().mockResolvedValue(detail.record),
    remove: vi.fn().mockResolvedValue({}),
  };
}
async function select(a: PublishedLedgerAdapter) {
  render(<PublishedLedgersPage adapter={a} onUnauthenticated={vi.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: "采购申请" }));
  await screen.findByText("共 1 条记录");
}
afterEach(cleanup);

it("sends typed filters and edits using the record's pinned version", async () => {
  const a = adapter();
  await select(a);
  fireEvent.change(screen.getByLabelText("数量"), { target: { value: "4" } });
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  await waitFor(() =>
    expect(a.query).toHaveBeenLastCalledWith(
      "purchase",
      expect.objectContaining({
        filters: [{ field: "quantity", operator: "eq", value: 4 }],
      }),
      expect.any(AbortSignal),
    ),
  );
  fireEvent.click(screen.getByRole("button", { name: "查看记录" }));
  await screen.findByText("定义版本 1 · 记录版本 1");
  fireEvent.click(screen.getByRole("button", { name: "编辑草稿" }));
  const editor = screen.getByRole("region", { name: "记录详情" });
  fireEvent.change(within(editor).getByLabelText("数量（必填）"), {
    target: { value: "6" },
  });
  fireEvent.click(within(editor).getByRole("button", { name: "保存记录" }));
  await waitFor(() =>
    expect(a.update).toHaveBeenCalledWith(
      "purchase",
      "record",
      expect.objectContaining({ expectedVersion: 1, values: { quantity: 6 } }),
    ),
  );
});

it("reuses the create request after an unknown result and preserves conflict input", async () => {
  const a = adapter();
  vi.mocked(a.create)
    .mockRejectedValueOnce(new ApiError(0, "NETWORK", null, true))
    .mockRejectedValueOnce(new ApiError(409, "VERSION_CONFLICT", null, false));
  await select(a);
  fireEvent.click(screen.getByRole("button", { name: "新增记录" }));
  const editor = screen.getByRole("region", { name: "新增记录" });
  fireEvent.change(within(editor).getByLabelText("数量（必填）"), {
    target: { value: "7" },
  });
  fireEvent.click(within(editor).getByRole("button", { name: "保存记录" }));
  await screen.findByRole("alert");
  fireEvent.click(screen.getByRole("button", { name: "重试原操作" }));
  await waitFor(() => expect(a.create).toHaveBeenCalledTimes(2));
  expect(vi.mocked(a.create).mock.calls[0]![1].requestId).toBe(
    vi.mocked(a.create).mock.calls[1]![1].requestId,
  );
  const quantity = within(editor).getByLabelText("数量（必填）");
  expect(quantity).toHaveValue(7);
  expect(quantity).toBeEnabled();
});

it("freezes an unknown operation until the exact body is reconciled", async () => {
  const a = adapter();
  vi.mocked(a.create)
    .mockRejectedValueOnce(new ApiError(0, "NETWORK", null, true))
    .mockRejectedValueOnce(new ApiError(403, "FORBIDDEN", null, false));
  await select(a);
  fireEvent.click(screen.getByRole("button", { name: "新增记录" }));
  const editor = screen.getByRole("region", { name: "新增记录" });
  const quantity = within(editor).getByLabelText("数量（必填）");
  fireEvent.change(quantity, { target: { value: "7" } });
  fireEvent.click(within(editor).getByRole("button", { name: "保存记录" }));
  await screen.findByRole("alert");
  expect(quantity).toBeDisabled();
  expect(screen.getByRole("button", { name: "新增记录" })).toHaveAttribute("aria-disabled", "true");
  const retry = screen.getByRole("button", { name: "重试原操作" });
  fireEvent.click(retry);
  await screen.findByText(/当前授权不允许/);
  expect(a.create).toHaveBeenCalledTimes(2);
  expect(vi.mocked(a.create).mock.calls[0]).toEqual(vi.mocked(a.create).mock.calls[1]);
  expect(quantity).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "只读刷新记录" }));
  await waitFor(() => expect(a.query).toHaveBeenCalledTimes(2));
  expect(a.create).toHaveBeenCalledTimes(2);
  const confirmation = vi.spyOn(window, "confirm").mockReturnValue(true);
  fireEvent.click(screen.getByRole("button", { name: "结束追踪" }));
  expect(confirmation).toHaveBeenCalledOnce();
  confirmation.mockRestore();
  expect(a.create).toHaveBeenCalledTimes(2);
  expect(screen.queryByRole("region", { name: "新增记录" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "新增记录" })).not.toHaveAttribute("aria-disabled", "true");
});

it("does not offer writes to a reader", async () => {
  const a = adapter();
  vi.mocked(a.list).mockResolvedValue([
    {
      ...ledger,
      capabilities: {
        ...capabilities,
        create: false,
        update: false,
        delete: false,
      },
    },
  ]);
  vi.mocked(a.detail).mockResolvedValue({
    ...detail,
    capabilities: {
      ...capabilities,
      create: false,
      update: false,
      delete: false,
    },
  });
  await select(a);
  expect(
    screen.queryByRole("button", { name: "新增记录" }),
  ).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "查看记录" }));
  await screen.findByRole("region", { name: "记录详情" });
  expect(
    screen.queryByRole("button", { name: "编辑草稿" }),
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "删除草稿" }),
  ).not.toBeInTheDocument();
});
