import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
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
  it("copies a saved historical version into an independent first draft", async () => {
    const api = adapter();
    const second = {
      ...first,
      version: 2,
      publishedVersion: 2,
      definition: { ...first.definition, name: "新版采购" },
    };
    vi.mocked(api.versions).mockResolvedValue([second, first]);
    vi.mocked(api.save).mockImplementation(async (key, definition) => ({
      ...first,
      key,
      definition,
    }));
    render(
      <LedgerConfigPage
        adapter={api}
        canManage
        canPublish
        onUnauthenticated={vi.fn()}
      />,
    );
    fireEvent.click(await screen.findByRole("button", { name: /采购申请/ }));
    fireEvent.change(await screen.findByLabelText("台账名称"), {
      target: { value: "未保存的更改" },
    });
    vi.spyOn(window, "confirm").mockReturnValue(true);
    fireEvent.click(screen.getByRole("button", { name: "复制版本 1为新台账" }));
    expect(
      screen.getByText(/已从台账 采购申请（purchase）的版本 1/),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("台账标识")).toHaveValue("");
    expect(screen.getByLabelText("台账名称")).toHaveValue("采购申请副本");
    expect(
      screen.queryByRole("region", { name: "版本历史" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "发布当前版本" })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    fireEvent.change(screen.getByLabelText("台账标识"), {
      target: { value: "purchase_copy" },
    });
    fireEvent.change(screen.getByLabelText("字段名称"), {
      target: { value: "复制数量" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    await screen.findByText("已保存草稿版本 1。");
    expect(api.save).toHaveBeenCalledWith(
      "purchase_copy",
      {
        ...first.definition,
        name: "采购申请副本",
        fields: [{ ...first.definition.fields[0], label: "复制数量" }],
      },
      0,
      expect.any(String),
    );
    expect(first.definition.fields[0]?.label).toBe("数量");
    expect(api.publish).not.toHaveBeenCalled();
    expect(screen.queryByText(/已从台账/)).not.toBeInTheDocument();
  });
  it("keeps a dirty source when copying is cancelled and rejects its existing key", async () => {
    const api = adapter();
    vi.mocked(api.list).mockResolvedValue([
      { ...first, latestVersion: 1 },
      {
        ...first,
        key: "existing",
        definition: { ...first.definition, name: "已有台账" },
        latestVersion: 1,
      },
    ]);
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
      target: { value: "待保存" },
    });
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    fireEvent.click(screen.getByRole("button", { name: "复制版本 1为新台账" }));
    expect(screen.getByLabelText("台账名称")).toHaveValue("待保存");
    expect(screen.getByLabelText("台账标识")).toHaveValue("purchase");
    confirm.mockReturnValue(true);
    fireEvent.click(screen.getByRole("button", { name: "复制版本 1为新台账" }));
    fireEvent.change(screen.getByLabelText("台账标识"), {
      target: { value: "purchase" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "新台账必须使用与来源不同的标识。",
    );
    fireEvent.change(screen.getByLabelText("台账标识"), {
      target: { value: "existing" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "新台账标识已存在，请使用其他标识。",
    );
    expect(api.save).not.toHaveBeenCalled();
  });
  it("preserves the copied input and request identifier after unknown results and key collisions", async () => {
    const api = adapter();
    vi.mocked(api.save)
      .mockRejectedValueOnce(new ApiError(0, "NETWORK", null, true))
      .mockRejectedValueOnce(new ApiError(409, "VERSION_CONFLICT", null, false))
      .mockResolvedValueOnce({ ...first, key: "purchase_retry" });
    render(
      <LedgerConfigPage
        adapter={api}
        canManage
        canPublish={false}
        onUnauthenticated={vi.fn()}
      />,
    );
    fireEvent.click(await screen.findByRole("button", { name: /采购申请/ }));
    fireEvent.click(await screen.findByRole("button", { name: "复制版本 1为新台账" }));
    fireEvent.change(screen.getByLabelText("台账标识"), {
      target: { value: "purchase_copy" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    await screen.findByText(/操作未确认成功/);
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    await screen.findByText(/新台账标识已被使用/);
    expect(screen.getByLabelText("台账标识")).toHaveValue("purchase_copy");
    expect(screen.getByLabelText("台账名称")).toHaveValue("采购申请副本");
    expect(vi.mocked(api.save).mock.calls[0]?.[3]).toBe(
      vi.mocked(api.save).mock.calls[1]?.[3],
    );
    fireEvent.change(screen.getByLabelText("台账标识"), {
      target: { value: "purchase_retry" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    await waitFor(() => expect(api.save).toHaveBeenCalledTimes(3));
    expect(vi.mocked(api.save).mock.calls[2]?.[3]).not.toBe(
      vi.mocked(api.save).mock.calls[1]?.[3],
    );
  });
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
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    await screen.findByText(/配置已被其他人更新/);
    expect(screen.getByLabelText("台账名称")).toHaveValue("采购申请新版");
    expect(vi.mocked(api.save).mock.calls[0]?.[3]).toBe(
      vi.mocked(api.save).mock.calls[1]?.[3],
    );
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
    expect(
      screen.queryByRole("button", { name: "复制版本 1为新台账" }),
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
