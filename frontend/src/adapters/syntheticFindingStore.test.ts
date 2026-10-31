import { describe, expect, it } from "vitest";
import { FindingSubmitError, newIdempotencyKey } from "./findingSubmit";
import { createSyntheticFindingStore } from "./syntheticFindingStore";
import { syntheticFindings } from "./findingLedger";
import type { FindingDraft } from "../domain/findingDraft";

const NOW = new Date(2026, 9, 30, 14, 30, 45);

function draft(overrides: Partial<FindingDraft> = {}): FindingDraft {
  return {
    source: "INSPECTION",
    description: "  通道护栏连接件松动  ",
    location: "演示仓库 · 北侧通道",
    foundAt: "2026-10-30T13:50",
    urgency: "URGENT",
    interimMeasure: "已拉警戒线",
    ...overrides,
  };
}

async function failureOf(promise: Promise<unknown>): Promise<FindingSubmitError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof FindingSubmitError) return error;
    throw error;
  }
  throw new Error("expected FindingSubmitError");
}

function storeAt() {
  return createSyntheticFindingStore({ now: () => NOW });
}

describe("合成隐患存储 · 上报提交", () => {
  it("成功提交创建“待一人确认”的记录，编号接在种子数据之后，并出现在台账首位", async () => {
    const store = storeAt();
    const result = await store.submit.submit(draft(), { idempotencyKey: "k1" });
    expect(result).toMatchObject({ id: "00000000-0000-4000-8000-000000000008", code: "DEMO-HZ-008", state: "PERSON_CONFIRM_PENDING", backfill: false });

    const list = await store.ledger.list({
      query: {},
      page: { index: 1, size: 20 },
      view: "all",
    });
    expect(list.total).toBe(syntheticFindings.length + 1);
    const created = list.items[0]!;
    expect(created).toMatchObject({
      id: "00000000-0000-4000-8000-000000000008",
      code: "DEMO-HZ-008",
      description: "通道护栏连接件松动",
      state: "PERSON_CONFIRM_PENDING",
      responsible: "待分派",
      riskLabel: "待专业评估",
      reporter: "本人（演示）",
      foundAt: "2026-10-30T13:50",
      source: "INSPECTION",
      urgency: "URGENT",
      interimMeasure: "已拉警戒线",
      reportedAt: "2026-10-30T14:30:45",
    });
    expect((await store.ledger.get("00000000-0000-4000-8000-000000000008"))?.history[0]?.note).toContain("未通知任何人");
  });

  it("新记录属于“待确认”视图，而不属于“整改验收”视图", async () => {
    const store = storeAt();
    await store.submit.submit(draft(), { idempotencyKey: "k1" });
    const base = { query: {}, page: { index: 1, size: 20 } };
    const inbox = await store.ledger.list({ ...base, view: "confirmation" });
    const acceptance = await store.ledger.list({ ...base, view: "acceptance" });
    expect(inbox.items.some((record) => record.code === "DEMO-HZ-008")).toBe(true);
    expect(acceptance.items.some((record) => record.code === "DEMO-HZ-008")).toBe(false);
  });

  it("同一幂等键 + 同一内容重复提交只创建一条记录，并返回同一结果", async () => {
    const store = storeAt();
    const first = await store.submit.submit(draft(), { idempotencyKey: "same" });
    const second = await store.submit.submit(draft(), { idempotencyKey: "same" });
    expect(second).toEqual(first);
    const list = await store.ledger.list({ query: {}, page: { index: 1, size: 100 }, view: "all" });
    expect(list.total).toBe(syntheticFindings.length + 1);
  });

  it("空白差异不改变内容：规范化后相同则视为同一次提交", async () => {
    const store = storeAt();
    const first = await store.submit.submit(draft(), { idempotencyKey: "same" });
    const again = await store.submit.submit(draft({ description: "通道护栏连接件松动" }), { idempotencyKey: "same" });
    expect(again).toEqual(first);
  });

  it("同一幂等键 + 不同内容被拒绝为 conflict，且不创建记录", async () => {
    const store = storeAt();
    await store.submit.submit(draft(), { idempotencyKey: "same" });
    const error = await failureOf(
      store.submit.submit(draft({ description: "另一件完全不同的隐患" }), { idempotencyKey: "same" }),
    );
    expect(error.kind).toBe("conflict");
    expect(error.retryable).toBe(false);
    const list = await store.ledger.list({ query: {}, page: { index: 1, size: 100 }, view: "all" });
    expect(list.total).toBe(syntheticFindings.length + 1);
  });

  it("不同幂等键是不同的命令，各自创建记录", async () => {
    const store = storeAt();
    const a = await store.submit.submit(draft(), { idempotencyKey: "a" });
    const b = await store.submit.submit(draft(), { idempotencyKey: "b" });
    expect(a.id).not.toBe(b.id);
  });

  it("服务端独立校验：绕过界面提交非法内容返回 validation 和字段错误，且不创建记录", async () => {
    const store = storeAt();
    const error = await failureOf(
      store.submit.submit(draft({ description: "短", foundAt: "2026-10-31T09:00" }), { idempotencyKey: "bad" }),
    );
    expect(error.kind).toBe("validation");
    expect(error.fieldErrors.description).toBeDefined();
    expect(error.fieldErrors.foundAt).toBeDefined();
    const list = await store.ledger.list({ query: {}, page: { index: 1, size: 100 }, view: "all" });
    expect(list.total).toBe(syntheticFindings.length);
  });

  it("失败的提交不占用幂等键：修正后同一个键可以成功", async () => {
    const store = storeAt();
    await failureOf(store.submit.submit(draft({ location: "" }), { idempotencyKey: "retry" }));
    const result = await store.submit.submit(draft(), { idempotencyKey: "retry" });
    expect(result.code).toBe("DEMO-HZ-008");
  });

  it("发现时间早于 24 小时按补报处理：发现时间保持原值，历史里注明补报", async () => {
    const store = storeAt();
    const result = await store.submit.submit(draft({ foundAt: "2026-10-27T09:00" }), { idempotencyKey: "late" });
    expect(result.backfill).toBe(true);
    const record = await store.ledger.get(result.id);
    expect(record?.foundAt).toBe("2026-10-27T09:00");
    expect(record?.reportedAt.startsWith("2026-10-30")).toBe(true);
    expect(record?.history[0]?.note).toContain("补报");
  });

  it("已取消的请求抛出 AbortError，且不创建记录", async () => {
    const store = storeAt();
    const controller = new AbortController();
    controller.abort();
    await expect(store.submit.submit(draft(), { idempotencyKey: "x", signal: controller.signal })).rejects.toMatchObject({
      name: "AbortError",
    });
    const list = await store.ledger.list({ query: {}, page: { index: 1, size: 100 }, view: "all" });
    expect(list.total).toBe(syntheticFindings.length);
  });

  it("各个存储实例互不影响，也不修改共享的种子数据", async () => {
    const a = storeAt();
    const b = storeAt();
    await a.submit.submit(draft(), { idempotencyKey: "k" });
    expect((await b.ledger.list({ query: {}, page: { index: 1, size: 100 }, view: "all" })).total).toBe(
      syntheticFindings.length,
    );
    expect(syntheticFindings).toHaveLength(7);
  });

  it("不读写浏览器存储", async () => {
    await storeAt().submit.submit(draft(), { idempotencyKey: "k" });
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });
});

describe("幂等键生成", () => {
  it("每次生成不同的 UUID 形态字符串", () => {
    const a = newIdempotencyKey();
    const b = newIdempotencyKey();
    expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(a).not.toBe(b);
  });

  it("没有 randomUUID（非安全上下文）时使用 getRandomValues 兜底仍得到合法的 v4 UUID", () => {
    const original = globalThis.crypto.randomUUID;
    Object.defineProperty(globalThis.crypto, "randomUUID", { value: undefined, configurable: true });
    try {
      expect(newIdempotencyKey()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    } finally {
      Object.defineProperty(globalThis.crypto, "randomUUID", { value: original, configurable: true });
    }
  });
});
