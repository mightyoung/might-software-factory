import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { act, afterEach, beforeEach, describe, expect, it } from "vitest";
import { FindingsLedger } from "./FindingsLedger";
import { syntheticFindings, type FindingLedgerAdapter } from "../../adapters/findingLedger";
import type { FindingRecord } from "../../domain/finding";
import { navigate } from "../../routing/router";

function resetRoute() {
  navigate("/safety/findings", true);
}

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

beforeEach(resetRoute);
afterEach(() => {
  cleanup();
  resetRoute();
});

describe("隐患台账分页与详情", () => {
  it("查询按字段生成条件，重置会恢复全部演示记录", async () => {
    render(
      <FindingsLedger
        view="all"
        title="隐患台账"
        description="合成样例"
      />,
    );

    await screen.findByText("DEMO-HZ-001");
    fireEvent.change(screen.getByRole("searchbox", { name: "隐患编号 / 描述" }), { target: { value: "隔离" } });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));
    await screen.findByText("共 1 条演示记录");
    expect(screen.getByText("DEMO-HZ-001")).toBeInTheDocument();
    expect(screen.queryByText("DEMO-HZ-002")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "重置" }));
    await screen.findByText("共 7 条演示记录");
    expect(screen.getByText("DEMO-HZ-001")).toBeInTheDocument();
    expect(screen.queryByText("DEMO-HZ-006")).not.toBeInTheDocument();
    expect(screen.getByRole("searchbox", { name: "隐患编号 / 描述" })).toHaveValue("");
  });

  it("选择范围只包括当前页，翻页后清空选择", async () => {
    render(
      <FindingsLedger
        view="all"
        title="隐患台账"
        description="合成样例"
      />,
    );

    await screen.findByText("DEMO-HZ-001");
    fireEvent.click(screen.getByRole("checkbox", { name: "选择 DEMO-HZ-001" }));
    expect(screen.getByText("已选 1 条（当前页）")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await screen.findByText("DEMO-HZ-006");
    await waitFor(() => expect(screen.getByText("已选 0 条（当前页）")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("checkbox", { name: "选择本页记录" }));
    expect(screen.getByRole("checkbox", { name: "选择 DEMO-HZ-006" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "选择 DEMO-HZ-007" })).toBeChecked();
    expect(screen.getByText("已选 2 条（当前页）")).toBeInTheDocument();
  });

  it("显示与流程状态对应的详情，未接后端时禁用审批动作", async () => {
    render(
      <FindingsLedger
        view="all"
        title="隐患台账"
        description="合成样例"
      />,
    );
    await screen.findByText("DEMO-HZ-001");
    fireEvent.click(screen.getAllByRole("button", { name: "详情" })[0]);

    expect(await screen.findByRole("heading", { name: "隐患详情" })).toBeInTheDocument();
    const detail = screen.getByRole("complementary", { name: "隐患详情面板" });
    expect(within(detail).getByText("待一人确认")).toBeInTheDocument();
    expect(within(detail).getByText("待专业评估")).toBeInTheDocument();
    expect(within(detail).getByText(/审批与派单均为本地演示/)).toBeInTheDocument();
    expect(within(detail).getByRole("button", { name: "确认发现" })).toBeDisabled();
    expect(within(detail).getByRole("button", { name: "退回补充" })).toBeDisabled();
    expect(within(detail).getByText("一人确认")).toBeInTheDocument();
  });
  it("列表读取失败后可重试，并从加载状态退出", async () => {
    const record = syntheticFindings[0]!;
    let attempts = 0;
    const adapter: FindingLedgerAdapter = {
      async list() {
        attempts += 1;
        if (attempts === 1) throw new Error("temporary list failure");
        return {
          items: [record],
          page: { index: 1, size: 5 },
          total: 1,
          pageCount: 1,
        };
      },
      async get() {
        return record;
      },
    };

    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" adapter={adapter} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("暂时无法加载隐患记录");
    expect(screen.queryByText("正在加载演示记录…")).not.toBeInTheDocument();
    expect(screen.queryByText(record.id)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "重试加载" }));
    expect(await screen.findByText(record.id)).toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  it("详情读取失败后可重试并显示成功结果", async () => {
    const record = syntheticFindings[0]!;
    let attempts = 0;
    const adapter: FindingLedgerAdapter = {
      async list() {
        return {
          items: [record],
          page: { index: 1, size: 5 },
          total: 1,
          pageCount: 1,
        };
      },
      async get() {
        attempts += 1;
        if (attempts === 1) throw new Error("temporary detail failure");
        return record;
      },
    };
    navigate("/safety/findings?caseId=DEMO-HZ-001", true);

    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" adapter={adapter} />);
    const detail = screen.getByRole("complementary", { name: "隐患详情面板" });
    expect(await within(detail).findByRole("alert")).toHaveTextContent("暂时无法读取隐患详情");
    expect(within(detail).queryByText("正在读取演示记录…")).not.toBeInTheDocument();

    fireEvent.click(within(detail).getByRole("button", { name: "重试读取" }));
    expect(await within(detail).findByText("待一人确认")).toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  it("忽略过期详情请求的拒绝结果", async () => {
    const firstRecord = syntheticFindings[0]!;
    const secondRecord = syntheticFindings[1]!;
    const firstRequest = createDeferred<FindingRecord | null>();
    const secondRequest = createDeferred<FindingRecord | null>();
    const requestedIds: string[] = [];
    const adapter: FindingLedgerAdapter = {
      async list() {
        return {
          items: [firstRecord, secondRecord],
          page: { index: 1, size: 5 },
          total: 2,
          pageCount: 1,
        };
      },
      get(id) {
        requestedIds.push(id);
        return id === firstRecord.id ? firstRequest.promise : secondRequest.promise;
      },
    };
    navigate(`/safety/findings?caseId=${firstRecord.id}`, true);

    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" adapter={adapter} />);
    await waitFor(() => expect(requestedIds).toEqual([firstRecord.id]));

    navigate(`/safety/findings?caseId=${secondRecord.id}`);
    await waitFor(() => expect(requestedIds).toEqual([firstRecord.id, secondRecord.id]));
    secondRequest.resolve(secondRecord);

    const detail = screen.getByRole("complementary", { name: "隐患详情面板" });
    expect(await within(detail).findByText(secondRecord.id)).toBeInTheDocument();
    firstRequest.reject(new Error("stale detail failure"));
    await act(async () => {
      await firstRequest.promise.catch(() => undefined);
    });
    expect(within(detail).queryByRole("alert")).not.toBeInTheDocument();
    expect(within(detail).getByText(secondRecord.id)).toBeInTheDocument();
  });

});
