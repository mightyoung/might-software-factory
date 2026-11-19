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
