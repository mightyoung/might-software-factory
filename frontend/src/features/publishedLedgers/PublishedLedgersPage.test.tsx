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

it("keeps draft, selected ledger and filters when cancelling local navigation", async () => {
  const prototype = HTMLDialogElement.prototype;
  const previousShow = Object.getOwnPropertyDescriptor(prototype, "showModal");
  const previousClose = Object.getOwnPropertyDescriptor(prototype, "close");
  Object.defineProperties(prototype, {
    showModal: { configurable: true, value(this: HTMLDialogElement) { this.setAttribute("open", ""); } },
    close: { configurable: true, value(this: HTMLDialogElement) { this.removeAttribute("open"); } },
  });
  const nativeConfirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  try {
    const a = adapter();
    vi.mocked(a.list).mockResolvedValue([ledger, {
      ...ledger, key: "other", definition: { ...definition, name: "其他台账" },
    }]);
    await select(a);
    const filter = screen.getByLabelText("数量");
    fireEvent.change(filter, { target: { value: "3" } });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));
    fireEvent.click(await screen.findByRole("button", { name: "新增记录" }));
    const draft = () => within(screen.getByRole("region", { name: "新增记录" })).getByLabelText("数量（必填）");
    fireEvent.change(draft(), { target: { value: "17" } });
    const requestCounts = [a.list, a.query, a.detail].map(fn => vi.mocked(fn).mock.calls.length);
    for (const name of ["刷新台账", "其他台账", "新增记录", "查看记录", "关闭记录"]) {
      const trigger = screen.getByRole("button", { name });
      trigger.focus();
      fireEvent.click(trigger);
      const confirmation = await screen.findByRole("alertdialog", { name: "放弃未保存的记录修改？" });
      const keep = within(confirmation).getByRole("button", { name: "继续编辑" });
      expect(keep).toHaveFocus();
      fireEvent.click(keep);
      expect(trigger).toHaveFocus();
      expect(draft()).toHaveValue(17);
      expect(filter).toHaveValue(3);
      expect(screen.getByRole("button", { name: "采购申请" })).toHaveAttribute("aria-current", "true");
      expect([a.list, a.query, a.detail].map(fn => vi.mocked(fn).mock.calls.length)).toEqual(requestCounts);
    }
    fireEvent.click(screen.getByRole("button", { name: "查看记录" }));
    const confirmation = await screen.findByRole("alertdialog");
    fireEvent.click(within(confirmation).getByRole("button", { name: "确认放弃" }));
    await screen.findByRole("region", { name: "记录详情" });
    expect(a.detail).toHaveBeenCalledExactlyOnceWith("purchase", "record", expect.any(AbortSignal));
    expect(nativeConfirm).not.toHaveBeenCalled();
    for (const write of [a.create, a.update, a.remove]) expect(write).not.toHaveBeenCalled();
  } finally {
    cleanup();
    nativeConfirm.mockRestore();
    for (const [name, descriptor] of [["showModal", previousShow], ["close", previousClose]] as const) {
      if (descriptor) Object.defineProperty(prototype, name, descriptor);
      else Reflect.deleteProperty(prototype, name);
    }
  }
});

it("requires accessible confirmation for the current draft and retries only its original deletion", async () => {
  const prototype = HTMLDialogElement.prototype;
  const previousShow = Object.getOwnPropertyDescriptor(prototype, "showModal");
  const previousClose = Object.getOwnPropertyDescriptor(prototype, "close");
  Object.defineProperties(prototype, {
    showModal: { configurable: true, value(this: HTMLDialogElement) { this.setAttribute("open", ""); } },
    close: { configurable: true, value(this: HTMLDialogElement) { this.removeAttribute("open"); } },
  });
  const nativeConfirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  try {
    const a = adapter();
    vi.mocked(a.remove).mockRejectedValueOnce(new ApiError(0, "NETWORK", null, true));
    const props = { adapter: a, onUnauthenticated: vi.fn() };
    const view = render(<PublishedLedgersPage {...props} />);
    fireEvent.click(await screen.findByRole("button", { name: "采购申请" }));
    fireEvent.click(await screen.findByRole("button", { name: "查看记录" }));
    const remove = await screen.findByRole("button", { name: "删除草稿" });
    const confirmation = () => screen.findByRole("alertdialog", { name: "删除这条草稿记录？" });
    remove.focus();
    fireEvent.click(remove);
    const dialog = await confirmation();
    expect(within(dialog).getByRole("button", { name: "取消" })).toHaveFocus();
    fireEvent(dialog, new Event("cancel", { cancelable: true }));
    expect(remove).toHaveFocus();
    expect(screen.getByRole("region", { name: "记录详情" })).toBeInTheDocument();
    expect(a.remove).not.toHaveBeenCalled();

    fireEvent.click(remove);
    await confirmation();
    view.rerender(<PublishedLedgersPage {...props} active={false} />);
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    view.rerender(<PublishedLedgersPage {...props} active />);
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(a.remove).not.toHaveBeenCalled();

    fireEvent.click(remove);
    fireEvent.click(within(await confirmation()).getByRole("button", { name: "确认删除" }));
    await screen.findByRole("alert");
    expect(screen.getByRole("heading", { name: "业务台账" })).toHaveFocus();
    expect(a.remove).toHaveBeenCalledExactlyOnceWith("purchase", "record", {
      requestId: expect.any(String), expectedVersion: 1,
    });
    fireEvent.click(screen.getByRole("button", { name: "重试原操作" }));
    await waitFor(() => expect(a.remove).toHaveBeenCalledTimes(2));
    expect(vi.mocked(a.remove).mock.calls[1]).toEqual(vi.mocked(a.remove).mock.calls[0]);
    expect(nativeConfirm).not.toHaveBeenCalled();
  } finally {
    cleanup();
    nativeConfirm.mockRestore();
    for (const [name, descriptor] of [["showModal", previousShow], ["close", previousClose]] as const) {
      if (descriptor) Object.defineProperty(prototype, name, descriptor);
      else Reflect.deleteProperty(prototype, name);
    }
  }
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
  const prototype = HTMLDialogElement.prototype;
  const show = Object.getOwnPropertyDescriptor(prototype, "showModal");
  const close = Object.getOwnPropertyDescriptor(prototype, "close");
  Object.defineProperties(prototype, {
    showModal: { configurable: true, value(this: HTMLDialogElement) { this.setAttribute("open", ""); } },
    close: { configurable: true, value(this: HTMLDialogElement) { this.removeAttribute("open"); } },
  });
  const confirmation = vi.spyOn(window, "confirm").mockReturnValue(false);
  try {
    const closeRecord = within(editor).getByRole("button", { name: "关闭记录" });
    closeRecord.focus();
    fireEvent.click(closeRecord);
    const dialog = await screen.findByRole("alertdialog", { name: "放弃未保存的记录修改？" });
    fireEvent.click(within(dialog).getByRole("button", { name: "继续编辑" }));
    expect(confirmation).not.toHaveBeenCalled();
    expect(a.create).not.toHaveBeenCalled();
    expect(organization).toHaveValue("warehouse");
    expect(organization).toBeInTheDocument();
    expect(closeRecord).toHaveFocus();
    fireEvent.click(closeRecord);
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "确认放弃" }));
    expect(editor).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "业务台账" })).toHaveFocus();
    expect(a.create).not.toHaveBeenCalled();
  } finally {
    cleanup();
    confirmation.mockRestore();
    for (const [name, descriptor] of [["showModal", show], ["close", close]] as const) {
      if (descriptor) Object.defineProperty(prototype, name, descriptor);
      else Reflect.deleteProperty(prototype, name);
    }
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

it("aborts an in-flight detail read when leaving the bound adapter", async () => {
  const a = adapter();
  vi.mocked(a.detail).mockImplementation(() => new Promise(() => {}));
  const unauthenticated = vi.fn();
  const view = render(<PublishedLedgersPage adapter={a} onUnauthenticated={unauthenticated} />);
  fireEvent.click(await screen.findByRole("button", { name: "采购申请" }));
  fireEvent.click(await screen.findByRole("button", { name: "查看记录" }));
  const signal = vi.mocked(a.detail).mock.calls[0]?.[2];
  expect(signal).toBeInstanceOf(AbortSignal);
  expect(signal?.aborted).toBe(false);
  view.rerender(<PublishedLedgersPage adapter={adapter()} onUnauthenticated={unauthenticated} />);
  expect(signal?.aborted).toBe(true);
  expect(screen.queryByRole("region", { name: "记录详情" })).not.toBeInTheDocument();
});

it.each(["refused", "empty"] as const)(
  "runs the same successful query again and replaces old rows when %s",
  async outcome => {
    const a = adapter();
    await select(a);
    const query = vi.mocked(a.query);
    if (outcome === "refused") {
      query.mockRejectedValueOnce(new ApiError(403, "FORBIDDEN", null, false));
    } else {
      query.mockResolvedValueOnce({ records: [], total: 0, page: 1, size: 20, hasMore: false });
    }
    fireEvent.click(screen.getByRole("button", { name: "查询" }));
    if (outcome === "refused") {
      expect(await screen.findByRole("alert")).toHaveTextContent("当前授权不允许");
    } else {
      await screen.findByText("共 0 条记录");
    }
    expect(query).toHaveBeenCalledTimes(2);
    expect(query.mock.calls[1]?.[1]).toEqual(query.mock.calls[0]?.[1]);
    expect(screen.queryByText("共 1 条记录")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "查看记录" })).not.toBeInTheDocument();
  },
);

it("retries the same failed query and clears its read error after success", async () => {
  const a = adapter();
  const query = vi.mocked(a.query);
  query.mockRejectedValueOnce(new ApiError(403, "FORBIDDEN", null, false));
  render(<PublishedLedgersPage adapter={a} onUnauthenticated={vi.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: "采购申请" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("当前授权不允许");
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  await screen.findByText("共 1 条记录");
  expect(query).toHaveBeenCalledTimes(2);
  expect(query.mock.calls[1]?.[1]).toEqual(query.mock.calls[0]?.[1]);
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

it("notifies session expiry and hides prior rows when the same query returns 401", async () => {
  const a = adapter();
  const expired = vi.fn();
  render(<PublishedLedgersPage adapter={a} onUnauthenticated={expired} />);
  fireEvent.click(await screen.findByRole("button", { name: "采购申请" }));
  await screen.findByText("共 1 条记录");
  vi.mocked(a.query).mockRejectedValueOnce(new ApiError(401, "UNAUTHORIZED", null, false));
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  await waitFor(() => expect(expired).toHaveBeenCalledOnce());
  expect(a.query).toHaveBeenCalledTimes(2);
  expect(screen.queryByText("共 1 条记录")).not.toBeInTheDocument();
});
