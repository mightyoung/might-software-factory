import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RouteLink } from "../../routing/router";
import { ApiError } from "../../api/httpClient";
import { LedgerConfigPage } from "./LedgerConfigPage";
import {
  createHttpLedgerConfig,
  type ConfigurationSnapshot,
  type LedgerConfigAdapter,
} from "./model";

const first: ConfigurationSnapshot = {
  key: "purchase",
  version: 1,
  publishedVersion: null,
  savedAt: "2026-11-20T00:00:00Z",
  savedBy: "author-1",
  sourceVersion: null,
  definition: {
    name: "采购申请",
    fields: [
      { key: "quantity", label: "数量", type: "number", required: true },
    ],
    listFields: ["quantity"],
    queryFields: [],
  },
};
function adapter(): LedgerConfigAdapter {
  return {
    list: vi.fn().mockResolvedValue([{ ...first, latestVersion: 1 }]),
    versions: vi.fn().mockResolvedValue([first]),
    save: vi.fn().mockResolvedValue({ ...first, version: 2 }),
    restore: vi
      .fn()
      .mockResolvedValue({ ...first, version: 2, sourceVersion: 1 }),
    publish: vi.fn().mockResolvedValue({ ...first, publishedVersion: 1 }),
  };
}
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("LedgerConfigPage", () => {
  it("retries a failed list load without creating a draft version", async () => {
    const api = adapter();
    vi.mocked(api.list).mockRejectedValueOnce(
      new ApiError(503, "DEPENDENCY_UNAVAILABLE", null, true),
    );
    render(
      <LedgerConfigPage
        adapter={api}
        canManage
        canPublish={false}
        onUnauthenticated={vi.fn()}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "重新载入台账列表" }),
    );
    expect(
      await screen.findByRole("button", { name: /采购申请/ }),
    ).toBeInTheDocument();
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(api.save).not.toHaveBeenCalled();
  });
  it("compares history and restores a new draft before an explicit publish", async () => {
    const api = adapter();
    render(
      <LedgerConfigPage
        adapter={api}
        canManage
        canPublish
        onUnauthenticated={vi.fn()}
      />,
    );
    fireEvent.click(await screen.findByRole("button", { name: /采购申请/ }));
    fireEvent.click(await screen.findByRole("button", { name: "比较版本 1" }));
    expect(screen.getByText("配置内容相同。")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "恢复版本 1为新草稿" }));
    expect(
      await screen.findByText(/已从版本 1 创建新草稿 2/),
    ).toBeInTheDocument();
    expect(api.restore).toHaveBeenCalledWith(
      "purchase",
      1,
      1,
      expect.any(String),
    );
    expect(api.publish).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "发布当前版本" }));
    await waitFor(() =>
      expect(api.publish).toHaveBeenCalledWith("purchase", 2, null),
    );
  });
  it("retains unsaved input on conflicts and reuses request IDs after unknown save results", async () => {
    const api = adapter();
    vi.mocked(api.save)
      .mockRejectedValueOnce(new ApiError(0, "NETWORK", null, true))
      .mockRejectedValueOnce(
        new ApiError(409, "VERSION_CONFLICT", null, false),
      );
    render(
      <LedgerConfigPage
        adapter={api}
        canManage
        canPublish={false}
        onUnauthenticated={vi.fn()}
      />,
    );
    fireEvent.click(await screen.findByRole("button", { name: /采购申请/ }));
    fireEvent.change(await screen.findByLabelText("台账名称"), {
      target: { value: "采购申请新版" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    await screen.findByText(/操作未确认成功/);
    fireEvent.click(screen.getByRole("button", { name: "重试原操作" }));
    await screen.findByText(/配置已被其他人更新/);
    expect(screen.getByLabelText("台账名称")).toHaveValue("采购申请新版");
    expect(vi.mocked(api.save).mock.calls[0]?.[3]).toBe(
      vi.mocked(api.save).mock.calls[1]?.[3],
    );
  });
  it("warns before discarding dirty configuration and confirms route navigation", async () => {
    const api = adapter();
    const ask = vi.spyOn(window, "confirm").mockReturnValue(false);
    render(
      <>
        <RouteLink to="/safety/findings">隐患台账</RouteLink>
        <LedgerConfigPage adapter={api} canManage canPublish onUnauthenticated={vi.fn()} />
      </>,
    );
    fireEvent.click(await screen.findByRole("button", { name: /采购申请/ }));
    fireEvent.change(await screen.findByLabelText("台账名称"), {
      target: { value: "采购申请新版" },
    });
    const departure = new Event("beforeunload", { cancelable: true });
    expect(window.dispatchEvent(departure)).toBe(false);
    fireEvent.click(screen.getByRole("link", { name: "隐患台账" }));
    expect(ask).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "新建台账" }));
    expect(ask).toHaveBeenCalledTimes(2);
    expect(screen.getByLabelText("台账名称")).toHaveValue("采购申请新版");
  });

  it.each(["save", "restore", "publish"] as const)(
    "keeps the original %s request until confirmed and doesn't replace it via a different action",
    async (action) => {
      const api = adapter();
      const call = vi.mocked(api[action]);
      call.mockRejectedValueOnce(new ApiError(0, "NETWORK", null, true));
      render(<LedgerConfigPage adapter={api} canManage canPublish onUnauthenticated={vi.fn()} />);
      fireEvent.click(await screen.findByRole("button", { name: /采购申请/ }));
      await screen.findByRole("button", { name: "比较版本 1" });
      const actionLabel = {
        save: "保存草稿",
        restore: "恢复版本 1为新草稿",
        publish: "发布当前版本",
      }[action];
      if (action === "save")
        fireEvent.change(screen.getByLabelText("台账名称"), {
          target: { value: "采购申请新版" },
        });
      fireEvent.click(screen.getByRole("button", { name: actionLabel }));
      expect(await screen.findByText(/操作未确认成功/)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "新建台账" })).toHaveAttribute("aria-disabled", "true");
      expect(screen.getByLabelText("台账名称")).toBeDisabled();
      expect(screen.getByRole("button", { name: /采购申请.*最新/ })).toBeDisabled();
      expect(call).toHaveBeenCalledTimes(1);
      fireEvent.click(screen.getByRole("button", { name: "重试原操作" }));
      await waitFor(() => expect(call).toHaveBeenCalledTimes(2));
      expect(call.mock.calls[1]).toEqual(call.mock.calls[0]);
      await waitFor(() => expect(screen.getByRole("button", { name: "新建台账" })).not.toHaveAttribute("aria-disabled", "true"));
    },
  );

  it("reads back a lost publish response when a newer draft conflicts with an exact retry", async () => {
    const api = adapter();
    const newer = {
      ...first,
      version: 2,
      publishedVersion: 1,
      definition: { ...first.definition, name: "后来保存的草稿" },
    };
    vi.mocked(api.versions).mockResolvedValueOnce([first]).mockResolvedValueOnce([newer, { ...first, publishedVersion: 1 }]);
    vi.mocked(api.publish)
      .mockRejectedValueOnce(new ApiError(0, "NETWORK", null, true))
      .mockRejectedValueOnce(new ApiError(409, "VERSION_CONFLICT", null, false));
    render(<LedgerConfigPage adapter={api} canManage canPublish onUnauthenticated={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: /采购申请/ }));
    await screen.findByRole("button", { name: "比较版本 1" });
    fireEvent.click(screen.getByRole("button", { name: "发布当前版本" }));
    await screen.findByText(/操作未确认成功/);
    fireEvent.click(screen.getByRole("button", { name: "重试原操作" }));
    expect(await screen.findByText("版本 1 已发布；已载入最新草稿版本 2。")).toBeInTheDocument();
    expect(screen.getByLabelText("台账名称")).toHaveValue("后来保存的草稿");
    expect(api.publish).toHaveBeenNthCalledWith(1, "purchase", 1, null);
    expect(api.publish).toHaveBeenNthCalledWith(2, "purchase", 1, null);
    expect(api.publish).toHaveBeenCalledTimes(2);
  });

  it("shows both lists' actual field order when only their order changes", async () => {
    const api = adapter();
    const fields = [
      ...first.definition.fields,
      { key: "unit", label: "单位", type: "text", required: false } as const,
    ];
    const oldDefinition = { ...first.definition, fields, listFields: ["quantity", "unit"], queryFields: ["unit", "quantity"] };
    const newDefinition = { ...oldDefinition, listFields: ["unit", "quantity"], queryFields: ["quantity", "unit"] };
    vi.mocked(api.versions).mockResolvedValue([
      { ...first, version: 2, definition: newDefinition },
      { ...first, definition: oldDefinition },
    ]);
    render(<LedgerConfigPage adapter={api} canManage canPublish onUnauthenticated={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: /采购申请/ }));
    fireEvent.click(await screen.findByRole("button", { name: "比较版本 1" }));
    const comparison = screen.getByRole("region", { name: "版本比较" });
    expect(within(comparison).getByText("列表字段顺序已变化。")).toBeInTheDocument();
    expect(within(comparison).getByText("查询字段顺序已变化。")).toBeInTheDocument();
    const orders = within(comparison).getAllByRole("list");
    expect(orders.map((order) => within(order).getAllByRole("listitem").map(item => item.textContent))).toEqual([
      ["数量（quantity）", "单位（unit）"],
      ["单位（unit）", "数量（quantity）"],
      ["单位（unit）", "数量（quantity）"],
      ["数量（quantity）", "单位（unit）"],
    ]);
  });
  it("lets a publisher inspect and publish while structural editing stays disabled", async () => {
    const api = adapter();
    render(
      <LedgerConfigPage
        adapter={api}
        canManage={false}
        canPublish
        onUnauthenticated={vi.fn()}
      />,
    );
    fireEvent.click(await screen.findByRole("button", { name: /采购申请/ }));
    expect(await screen.findByLabelText("台账名称")).toBeDisabled();
    expect(
      screen.queryByRole("button", { name: "保存草稿" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "恢复版本 1为新草稿" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "发布当前版本" }));
    await waitFor(() =>
      expect(api.publish).toHaveBeenCalledWith("purchase", 1, null),
    );
  });
});

it("sends a stable save identifier and current published pointer through the shared HTTP client", async () => {
  const client = {
    get: vi.fn().mockResolvedValue({ items: [] }),
    post: vi.fn().mockResolvedValue(first),
    put: vi.fn(),
  };
  const api = createHttpLedgerConfig(client);
  await api.save("purchase", first.definition, 1, "request-1");
  expect(client.post).toHaveBeenCalledWith(
    "/api/v1/admin/ledger-configurations/purchase/versions",
    {
      body: {
        definition: first.definition,
        expectedVersion: 1,
        requestId: "request-1",
      },
    },
  );
  await api.publish("purchase", 2, 1);
  expect(client.post).toHaveBeenCalledWith(
    "/api/v1/admin/ledger-configurations/purchase/publish",
    { body: { version: 2, expectedVersion: 2, expectedPublishedVersion: 1 } },
  );
});
