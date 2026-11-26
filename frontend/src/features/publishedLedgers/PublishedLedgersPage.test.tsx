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
  fireEvent.click(await screen.findByRole("button", { name: "查看记录" }));
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
  expect(within(editor).getByLabelText("所属组织")).toBeDisabled();
  expect(screen.getByRole("button", { name: "新增记录" })).toHaveAttribute("aria-disabled", "true");
  const retry = screen.getByRole("button", { name: "重试原操作" });
  fireEvent.click(retry);
  await screen.findByText("当前授权不允许此操作，原结果未确认。");
  expect(screen.getByRole("button", { name: /^刷新台账$/ })).toHaveAttribute("aria-disabled", "true");
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

it("does not suggest refreshing an unresolved operation when the member cannot read records", async () => {
  const a = adapter();
  vi.mocked(a.list).mockResolvedValue([{ ...ledger, capabilities: { ...capabilities, read: false } }]);
  vi.mocked(a.create)
    .mockRejectedValueOnce(new ApiError(0, "NETWORK", null, true))
    .mockRejectedValueOnce(new ApiError(403, "FORBIDDEN", null, false));
  render(<PublishedLedgersPage adapter={a} onUnauthenticated={vi.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: "采购申请" }));
  fireEvent.click(screen.getByRole("button", { name: "新增记录" }));
  const editor = screen.getByRole("region", { name: "新增记录" });
  const quantity = within(editor).getByLabelText("数量（必填）");
  fireEvent.change(quantity, { target: { value: "7" } });
  fireEvent.click(within(editor).getByRole("button", { name: "保存记录" }));
  fireEvent.click(await screen.findByRole("button", { name: "重试原操作" }));

  await screen.findByText("当前授权不允许此操作，原结果未确认。");
  expect(screen.queryByRole("button", { name: "只读刷新记录" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: /^刷新台账$/ })).toHaveAttribute("aria-disabled", "true");
  expect(quantity).toBeDisabled();
  expect(a.query).not.toHaveBeenCalled();
  expect(a.create).toHaveBeenCalledTimes(2);
  expect(vi.mocked(a.create).mock.calls[1]).toEqual(vi.mocked(a.create).mock.calls[0]);
});

it("preserves the ordinary refresh guidance for a definitive first denial", async () => {
  const a = adapter();
  vi.mocked(a.create).mockRejectedValueOnce(new ApiError(403, "FORBIDDEN", null, false));
  await select(a);
  fireEvent.click(screen.getByRole("button", { name: "新增记录" }));
  const editor = screen.getByRole("region", { name: "新增记录" });
  const quantity = within(editor).getByLabelText("数量（必填）");
  fireEvent.change(quantity, { target: { value: "7" } });
  fireEvent.click(within(editor).getByRole("button", { name: "保存记录" }));

  await screen.findByText("当前授权不允许此操作，请刷新台账核对权限。");
  expect(screen.getByRole("button", { name: /^刷新台账$/ })).not.toHaveAttribute("aria-disabled", "true");
  expect(screen.queryByRole("button", { name: "重试原操作" })).not.toBeInTheDocument();
  expect(quantity).toBeEnabled();
  expect(a.create).toHaveBeenCalledTimes(1);
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

it("keeps a draft when only its organization changed and discarding is declined", async () => {
  const a = adapter();
  vi.mocked(a.list).mockResolvedValue([{
    ...ledger,
    organizations: [...ledger.organizations, { id: "warehouse", name: "仓库" }],
  }]);
  await select(a);
  fireEvent.click(screen.getByRole("button", { name: "新增记录" }));
  const editor = screen.getByRole("region", { name: "新增记录" });
  const organization = within(editor).getByLabelText("所属组织");
  fireEvent.change(organization, { target: { value: "warehouse" } });
  const confirmation = vi.spyOn(window, "confirm").mockReturnValue(false);
  try {
    fireEvent.click(within(editor).getByRole("button", { name: "关闭记录" }));
    expect(confirmation).toHaveBeenCalledOnce();
    expect(organization).toHaveValue("warehouse");
    expect(organization).toBeInTheDocument();
  } finally {
    confirmation.mockRestore();
  }
});

it("closes a new draft with only unchanged default values without asking", async () => {
  const a = adapter();
  vi.mocked(a.list).mockResolvedValue([{
    ...ledger,
    definition: {
      ...definition,
      fields: [...definition.fields, { key: "gift", label: "赠品", type: "boolean", required: false }],
    },
  }]);
  await select(a);
  fireEvent.click(screen.getByRole("button", { name: "新增记录" }));
  const editor = screen.getByRole("region", { name: "新增记录" });
  expect(within(editor).getByLabelText("赠品")).not.toBeChecked();
  const confirmation = vi.spyOn(window, "confirm").mockReturnValue(false);
  try {
    fireEvent.click(within(editor).getByRole("button", { name: "关闭记录" }));
    expect(confirmation).not.toHaveBeenCalled();
    expect(editor).not.toBeInTheDocument();
  } finally {
    confirmation.mockRestore();
  }
});

it("does not keep old ledger names or list results after a fresh directory denial", async () => {
  const a = adapter();
  vi.mocked(a.list).mockResolvedValueOnce([ledger]).mockRejectedValueOnce(new ApiError(403, "FORBIDDEN", null, false));
  await select(a);
  fireEvent.click(screen.getByRole("button", { name: /^刷新台账$/ }));
  await screen.findByRole("alert");
  expect(screen.queryByRole("button", { name: "采购申请" })).not.toBeInTheDocument();
  expect(screen.queryByText("共 1 条记录")).not.toBeInTheDocument();
  expect(a.create).not.toHaveBeenCalled();
});

it("cancels the previous adapter read and never shows its records under a new adapter", async () => {
  const old = adapter(), next = adapter();
  let signal: AbortSignal | undefined;
  let complete!: (items: PublishedLedger[]) => void;
  vi.mocked(old.list).mockImplementation(current => {
    signal = current;
    return new Promise(resolve => { complete = resolve; });
  });
  vi.mocked(next.list).mockResolvedValue([{ ...ledger, key: "shift", definition: { ...definition, name: "交接记录" } }]);
  const dom = render(<PublishedLedgersPage adapter={old} onUnauthenticated={vi.fn()} />);
  await waitFor(() => expect(old.list).toHaveBeenCalledOnce());
  dom.rerender(<PublishedLedgersPage adapter={next} onUnauthenticated={vi.fn()} />);
  await screen.findByRole("button", { name: "交接记录" });
  expect(signal?.aborted).toBe(true);
  complete([ledger]);
  expect(screen.queryByRole("button", { name: "采购申请" })).not.toBeInTheDocument();
});
