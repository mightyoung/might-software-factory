import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { FindingsLedger } from "./FindingsLedger";
import { syntheticFindings, type FindingLedgerAdapter } from "../../adapters/findingLedger";
import {
  findingStateLabels,
  findingStates,
  getFindingActions,
  workflowCurrentIndex,
  workflowSteps,
} from "../../domain/finding";
import type { FindingRecord } from "../../domain/finding";
import { navigate } from "../../routing/router";

function createPagedFixtureAdapter(): FindingLedgerAdapter {
  const rows: FindingRecord[] = Array.from({ length: 23 }, (_, index) => ({
    ...syntheticFindings[index % syntheticFindings.length]!,
    id: `TEST-HZ-${String(index + 1).padStart(3, "0")}`,
  }));
  return {
    async list({ page }) {
      const pageCount = Math.max(1, Math.ceil(rows.length / page.size));
      const safeIndex = Math.min(Math.max(page.index, 1), pageCount);
      const start = (safeIndex - 1) * page.size;
      return {
        items: rows.slice(start, start + page.size),
        page: { index: safeIndex, size: page.size },
        total: rows.length,
        pageCount,
      };
    },
    async get(id) {
      return rows.find((row) => row.id === id) ?? null;
    },
  };
}

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

async function traverseHistory(direction: "back" | "forward") {
  await act(async () => {
    await new Promise<void>((resolve) => {
      window.addEventListener("popstate", () => resolve(), { once: true });
      window.history[direction]();
    });
  });
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
    expect(screen.getByText("DEMO-HZ-006")).toBeInTheDocument();
    expect(screen.getByRole("searchbox", { name: "隐患编号 / 描述" })).toHaveValue("");
  });

  it("选择范围只包括当前页，翻页后清空选择", async () => {
    render(
      <FindingsLedger
        view="all"
        title="隐患台账"
        description="合成样例"
        adapter={createPagedFixtureAdapter()}
      />,
    );

    const pageSize = screen.getByRole("combobox", { name: "每页条数" });
    expect(pageSize).toHaveValue("20");
    expect(Array.from((pageSize as HTMLSelectElement).options).map((option) => option.value)).toEqual(["20", "50", "100"]);

    await screen.findByText("TEST-HZ-001");
    fireEvent.click(screen.getByRole("checkbox", { name: "选择 TEST-HZ-001" }));
    expect(screen.getByText("已选 1 条（当前页）")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await screen.findByText("TEST-HZ-021");
    await waitFor(() => expect(screen.getByText("已选 0 条（当前页）")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("checkbox", { name: "选择本页记录" }));
    expect(screen.getByRole("checkbox", { name: "选择 TEST-HZ-021" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "选择 TEST-HZ-023" })).toBeChecked();
    expect(screen.getByText("已选 3 条（当前页）")).toBeInTheDocument();
  });

  it("筛选打开的详情时回到第一页并清空选择与详情", async () => {
    render(
      <FindingsLedger
        view="all"
        title="隐患台账"
        description="合成样例"
        adapter={createPagedFixtureAdapter()}
      />,
    );

    await screen.findByText("TEST-HZ-001");
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await screen.findByText("TEST-HZ-021");
    expect(screen.getByText("2 / 2")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("checkbox", { name: "选择 TEST-HZ-021" }));
    fireEvent.click(screen.getAllByRole("button", { name: "详情" })[0]!);
    const detail = screen.getByRole("complementary", { name: "隐患详情面板" });
    expect(await within(detail).findByText("TEST-HZ-021")).toBeInTheDocument();

    fireEvent.change(screen.getByRole("searchbox", { name: "隐患编号 / 描述" }), {
      target: { value: "TEST-HZ-023" },
    });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));

    await screen.findByText("共 1 条演示记录");
    expect(screen.getByText("TEST-HZ-023")).toBeInTheDocument();
    expect(screen.getByText("1 / 1")).toBeInTheDocument();
    expect(screen.getByText("已选 0 条（当前页）")).toBeInTheDocument();
    expect(screen.queryByRole("complementary", { name: "隐患详情面板" })).not.toBeInTheDocument();
    expect(window.location.search).toBe("");
  });

  it.each(findingStates)("状态 %s 的详情映射和业务动作保持受限", async (state) => {
    const record = syntheticFindings.find((item) => item.state === state);
    if (!record) throw new Error(`Missing synthetic record for state ${state}`);

    let detailReads = 0;
    const adapter: FindingLedgerAdapter = {
      async list() {
        return { items: [record], page: { index: 1, size: 20 }, total: 1, pageCount: 1 };
      },
      async get(id) {
        detailReads += 1;
        return id === record.id ? record : null;
      },
    };
    navigate(`/safety/findings?caseId=${record.id}`, true);

    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" adapter={adapter} />);
    const detail = screen.getByRole("complementary", { name: "隐患详情面板" });
    expect(await within(detail).findByText(findingStateLabels[state])).toBeInTheDocument();
    expect(within(detail).getByText("待专业评估")).toBeInTheDocument();

    const workflow = within(detail).getByRole("list", { name: "隐患流程节点" });
    expect(within(workflow).getAllByRole("listitem")).toHaveLength(workflowSteps.length);
    if (state === "CLOSED") {
      expect(within(workflow).queryByText("当前节点")).not.toBeInTheDocument();
      expect(within(detail).getByText("此示例状态不可变更")).toBeInTheDocument();
    } else {
      const currentNode = within(workflow).getByText("当前节点");
      expect(currentNode.closest("li")?.querySelector(".workflow-step-name")?.textContent)
        .toBe(workflowSteps[workflowCurrentIndex(state)]);
    }

    const actionHint = within(detail).getByRole("note");
    expect(actionHint).toHaveTextContent("审批与派单均为本地演示");
    expect(actionHint).toHaveTextContent("正式接入后仍由服务端校验身份、职责、状态、版本、轮次和证据");

    const actions = getFindingActions(state);
    if (state === "CLOSED") {
      expect(actions).toHaveLength(0);
    }
    for (const action of actions) {
      const button = within(detail).getByRole("button", { name: action.label });
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute("title", action.reason);
      fireEvent.click(button);
      fireEvent.click(button);
    }
    expect(detailReads).toBe(1);
    expect(within(detail).getByText(findingStateLabels[state])).toBeInTheDocument();
  });

  it("关闭详情后浏览器后退不会重新打开已关闭面板", async () => {
    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" />);
    await screen.findByText("DEMO-HZ-001");
    fireEvent.click(screen.getAllByRole("button", { name: "详情" })[0]!);
    const detail = screen.getByRole("complementary", { name: "隐患详情面板" });
    expect(await within(detail).findByText("DEMO-HZ-001")).toBeInTheDocument();

    fireEvent.click(within(detail).getByRole("button", { name: "关闭详情" }));
    await waitFor(() => expect(window.location.search).toBe(""));
    expect(screen.queryByRole("complementary", { name: "隐患详情面板" })).not.toBeInTheDocument();

    await traverseHistory("back");
    expect(window.location.search).not.toContain("caseId=");
    expect(screen.queryByRole("complementary", { name: "隐患详情面板" })).not.toBeInTheDocument();
  });

  it("浏览器后退与前进会关闭和重新打开详情", async () => {
    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" />);
    await screen.findByText("DEMO-HZ-001");
    fireEvent.click(screen.getAllByRole("button", { name: "详情" })[0]!);
    const detail = screen.getByRole("complementary", { name: "隐患详情面板" });
    expect(await within(detail).findByText("DEMO-HZ-001")).toBeInTheDocument();

    await traverseHistory("back");
    await waitFor(() => expect(window.location.search).toBe(""));
    expect(screen.queryByRole("complementary", { name: "隐患详情面板" })).not.toBeInTheDocument();

    await traverseHistory("forward");
    await waitFor(() => expect(window.location.search).toBe("?caseId=DEMO-HZ-001"));
    expect(await within(screen.getByRole("complementary", { name: "隐患详情面板" })).findByText("DEMO-HZ-001"))
      .toBeInTheDocument();
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
          page: { index: 1, size: 20 },
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
          page: { index: 1, size: 20 },
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
          page: { index: 1, size: 20 },
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


  it("忽略迟到的旧详情成功响应", async () => {
    const firstRecord = syntheticFindings[0]!;
    const secondRecord = syntheticFindings[1]!;
    const firstRequest = createDeferred<FindingRecord | null>();
    const secondRequest = createDeferred<FindingRecord | null>();
    const requestedIds: string[] = [];
    const adapter: FindingLedgerAdapter = {
      async list() {
        return {
          items: [firstRecord, secondRecord],
          page: { index: 1, size: 20 },
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
    firstRequest.resolve(firstRecord);
    await act(async () => {
      await firstRequest.promise;
    });

    expect(within(detail).getByText(secondRecord.id)).toBeInTheDocument();
    expect(within(detail).queryByText(firstRecord.id)).not.toBeInTheDocument();
  });

  it("忽略迟到的旧列表成功响应", async () => {
    const firstRecord = syntheticFindings[0]!;
    const filteredRecord = syntheticFindings[1]!;
    const firstRequest = createDeferred<{
      items: readonly FindingRecord[];
      page: { index: number; size: number };
      total: number;
      pageCount: number;
    }>();
    const filteredRequest = createDeferred<{
      items: readonly FindingRecord[];
      page: { index: number; size: number };
      total: number;
      pageCount: number;
    }>();
    let listCalls = 0;
    const adapter: FindingLedgerAdapter = {
      list() {
        listCalls += 1;
        return listCalls === 1 ? firstRequest.promise : filteredRequest.promise;
      },
      async get(id) {
        return syntheticFindings.find((record) => record.id === id) ?? null;
      },
    };

    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" adapter={adapter} />);
    await waitFor(() => expect(listCalls).toBe(1));
    fireEvent.change(screen.getByRole("searchbox", { name: "隐患编号 / 描述" }), {
      target: { value: filteredRecord.id },
    });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));
    await waitFor(() => expect(listCalls).toBe(2));

    filteredRequest.resolve({
      items: [filteredRecord],
      page: { index: 1, size: 20 },
      total: 1,
      pageCount: 1,
    });
    await screen.findByText(filteredRecord.id);
    firstRequest.resolve({
      items: [firstRecord],
      page: { index: 1, size: 20 },
      total: 2,
      pageCount: 1,
    });
    await act(async () => {
      await firstRequest.promise;
    });

    expect(screen.getByText(filteredRecord.id)).toBeInTheDocument();
    expect(screen.queryByText(firstRecord.id)).not.toBeInTheDocument();
    expect(screen.getByText("共 1 条演示记录")).toBeInTheDocument();
  });
});
