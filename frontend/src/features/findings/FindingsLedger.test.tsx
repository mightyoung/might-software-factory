import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { FindingsLedger } from "./FindingsLedger";
import {
  FindingLedgerAccessError,
  syntheticFindings,
  type FindingLedgerAdapter,
} from "../../adapters/findingLedger";
import {
  findingStateLabels,
  findingStates,
  getFindingActions,
  workflowCurrentIndex,
  workflowSteps,
} from "../../domain/finding";
import type { FindingRecord } from "../../domain/finding";
import { matchesQuery } from "../../domain/ledger";
import { findingLedgerSchema } from "./schema";
import { navigate, setTabOpener } from "../../routing/router";
import { findingDefinitionFixture } from "../../adapters/findingDefinition.fixtures";
import { ModeProvider } from "../../mode";

function createPagedFixtureAdapter(): FindingLedgerAdapter {
  const rows: FindingRecord[] = Array.from({ length: 23 }, (_, index) => ({
    ...syntheticFindings[index % syntheticFindings.length]!,
    id: `00000000-0000-4000-9000-${String(index + 1).padStart(12, "0")}`,
    code: `TEST-HZ-${String(index + 1).padStart(3, "0")}`,
  }));
  return {
    async list({ query, page }) {
      const filtered = rows.filter((record) =>
        matchesQuery(record, findingLedgerSchema.queryFields, query),
      );
      const pageCount = Math.max(1, Math.ceil(filtered.length / page.size));
      const safeIndex = Math.min(Math.max(page.index, 1), pageCount);
      const start = (safeIndex - 1) * page.size;
      return {
        items: filtered.slice(start, start + page.size),
        page: { index: safeIndex, size: page.size },
        total: filtered.length,
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
    fireEvent.change(screen.getByRole("searchbox", { name: "隐患编号" }), { target: { value: "HZ-001" } });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));
    await screen.findByText("共 1 条演示记录");
    expect(screen.getByText("DEMO-HZ-001")).toBeInTheDocument();
    expect(screen.queryByText("DEMO-HZ-002")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "重置" }));
    await screen.findByText("共 7 条演示记录");
    expect(screen.getByText("DEMO-HZ-001")).toBeInTheDocument();
    expect(screen.getByText("DEMO-HZ-006")).toBeInTheDocument();
    expect(screen.getByRole("searchbox", { name: "隐患编号" })).toHaveValue("");
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

  it("点详情在新标签页打开，台账的页码、勾选和筛选原样保留", async () => {
    const opened: string[] = [];
    setTabOpener((url) => { opened.push(url); });
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
    fireEvent.click(screen.getByRole("checkbox", { name: "选择 TEST-HZ-021" }));
    fireEvent.click(screen.getAllByRole("button", { name: "详情" })[0]!);

    expect(opened).toHaveLength(1);
    expect(opened[0]).toMatch(/\?tab=1&caseId=/);
    expect(screen.queryByRole("complementary", { name: "隐患详情面板" })).not.toBeInTheDocument();
    expect(window.location.search).toBe("");
    expect(screen.getByText("2 / 2")).toBeInTheDocument();
    expect(screen.getByText("已选 1 条（当前页）")).toBeInTheDocument();
  });

  it.each(findingStates)("状态 %s 的详情映射和业务动作保持受限", async (state) => {
    // 用现有演示行派生边界状态，避免改动台账总数和编号。
    const record =
      syntheticFindings.find((item) => item.state === state) ??
      (state === "DISCOVERED" || state === "NOT_ESTABLISHED" || state === "REPORTER_SUPPLEMENT_PENDING"
        ? { ...syntheticFindings[0]!, state }
        : undefined);
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

    if (state === "REPORTER_SUPPLEMENT_PENDING") {
      expect(within(detail).getByText(/确认已退回，等待原上报人补充说明/)).toBeInTheDocument();
      expect(within(detail).queryByRole("list", { name: "隐患流程节点" })).not.toBeInTheDocument();
    } else {
    const workflow = within(detail).getByRole("list", { name: "隐患流程节点" });
    expect(within(workflow).getAllByRole("listitem")).toHaveLength(workflowSteps.length);
    if (state === "CLOSED" || state === "NOT_ESTABLISHED") {
      expect(within(workflow).queryByText("当前节点")).not.toBeInTheDocument();
      if (state === "CLOSED") {
        expect(within(detail).getByText("此示例状态不可变更")).toBeInTheDocument();
      } else {
        expect(within(detail).getByText("未成立记录已终止；新证据应创建关联记录")).toBeInTheDocument();
      }
    } else {
      const currentNode = within(workflow).getByText("当前节点");
      expect(currentNode.closest("li")?.querySelector(".workflow-step-name")?.textContent)
        .toBe(workflowSteps[workflowCurrentIndex(state)]);
    }

    }

    const actionHint = within(detail).getByRole("note");
    expect(actionHint).toHaveTextContent("审批与派单均为本地演示");
    expect(actionHint).toHaveTextContent("正式接入后仍由服务端校验身份、职责、状态、版本、轮次和证据");

    const actions = getFindingActions(state);
    if (state === "CLOSED" || state === "NOT_ESTABLISHED") {
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

  it("独立标签页只展示详情，不带台账；关闭后回到台账地址", async () => {
    window.history.replaceState(null, "", "/?tab=1&caseId=00000000-0000-4000-8000-000000000001");
    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" />);
    const detail = await screen.findByRole("complementary", { name: "隐患详情面板" });
    expect(await within(detail).findByText("DEMO-HZ-001")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "新建上报" })).not.toBeInTheDocument();
    expect(screen.queryByRole("columnheader")).not.toBeInTheDocument();

    fireEvent.click(within(detail).getByRole("button", { name: "关闭详情" }));
    await waitFor(() => expect(window.location.search).toBe(""));
    await waitFor(() => expect(screen.queryByRole("complementary", { name: "隐患详情面板" })).not.toBeInTheDocument());
  });

  it("关闭详情时取消尚未完成的详情读取", async () => {
    const record = syntheticFindings[0]!;
    const pendingRead = createDeferred<FindingRecord | null>();
    let detailSignal: AbortSignal | undefined;
    const adapter: FindingLedgerAdapter = {
      async list() {
        return { items: [record], page: { index: 1, size: 20 }, total: 1, pageCount: 1 };
      },
      get(_id, options) {
        detailSignal = options?.signal;
        return pendingRead.promise;
      },
    };

    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" adapter={adapter} />);
    await screen.findByText(record.code);
    fireEvent.click(screen.getAllByRole("button", { name: "详情" })[0]!);
    await waitFor(() => expect(detailSignal).toBeDefined());

    const detail = screen.getByRole("complementary", { name: "隐患详情面板" });
    fireEvent.click(within(detail).getByRole("button", { name: "关闭详情" }));
    await waitFor(() => expect(screen.queryByRole("complementary", { name: "隐患详情面板" })).not.toBeInTheDocument());
    expect(detailSignal?.aborted).toBe(true);

    pendingRead.reject(new Error("closed detail failure"));
    await act(async () => {
      await pendingRead.promise.catch(() => undefined);
    });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
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
    expect(screen.queryByText(record.code)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "重试加载" }));
    expect(await screen.findByText(record.code)).toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  it.each([
    {
      kind: "unauthenticated" as const,
      listMessage: "当前未完成身份验证，无法读取隐患记录。",
      detailMessage: "当前未完成身份验证，无法读取隐患详情。",
    },
    {
      kind: "forbidden" as const,
      listMessage: "当前身份无权读取隐患记录。",
      detailMessage: "当前身份无权读取隐患详情。",
    },
  ])("区分 $kind 语义状态且不显示服务端错误细节", async ({ kind, listMessage, detailMessage }) => {
    const adapter: FindingLedgerAdapter = {
      async list() {
        throw new FindingLedgerAccessError(kind);
      },
      async get() {
        throw new FindingLedgerAccessError(kind);
      },
    };

    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" adapter={adapter} />);
    const listAlert = await screen.findByRole("alert");
    expect(listAlert).toHaveTextContent(listMessage);
    expect(listAlert).not.toHaveTextContent("Finding ledger read");
    expect(screen.queryByText("正在加载演示记录…")).not.toBeInTheDocument();

    navigate("/safety/findings?caseId=00000000-0000-4000-8000-000000000001");
    const detail = await screen.findByRole("complementary", { name: "隐患详情面板" });
    const detailAlert = await within(detail).findByRole("alert");
    expect(detailAlert).toHaveTextContent(detailMessage);
    expect(detailAlert).not.toHaveTextContent("Finding ledger read");
    expect(within(detail).queryByText("正在读取演示记录…")).not.toBeInTheDocument();
  });

  it("成功返回零条记录时显示空状态", async () => {
    const adapter: FindingLedgerAdapter = {
      async list() {
        return { items: [], page: { index: 1, size: 20 }, total: 0, pageCount: 1 };
      },
      async get() {
        return null;
      },
    };

    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" adapter={adapter} />);
    expect(await screen.findByText("当前筛选条件下没有演示记录。")).toBeInTheDocument();
    expect(screen.getByText("共 0 条演示记录")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
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
    navigate("/safety/findings?caseId=00000000-0000-4000-8000-000000000001", true);

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
    const readSignals: AbortSignal[] = [];
    const adapter: FindingLedgerAdapter = {
      async list() {
        return {
          items: [firstRecord, secondRecord],
          page: { index: 1, size: 20 },
          total: 2,
          pageCount: 1,
        };
      },
      get(id, options) {
        requestedIds.push(id);
        if (options?.signal) readSignals.push(options.signal);
        return id === firstRecord.id ? firstRequest.promise : secondRequest.promise;
      },
    };
    navigate(`/safety/findings?caseId=${firstRecord.id}`, true);

    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" adapter={adapter} />);
    await waitFor(() => expect(requestedIds).toEqual([firstRecord.id]));

    navigate(`/safety/findings?caseId=${secondRecord.id}`);
    await waitFor(() => expect(requestedIds).toEqual([firstRecord.id, secondRecord.id]));
    expect(readSignals[0]?.aborted).toBe(true);
    expect(readSignals[1]?.aborted).toBe(false);
    secondRequest.resolve(secondRecord);

    const detail = screen.getByRole("complementary", { name: "隐患详情面板" });
    expect(await within(detail).findByText(secondRecord.code)).toBeInTheDocument();
    firstRequest.reject(new Error("stale detail failure"));
    await act(async () => {
      await firstRequest.promise.catch(() => undefined);
    });
    expect(within(detail).queryByRole("alert")).not.toBeInTheDocument();
    expect(within(detail).getByText(secondRecord.code)).toBeInTheDocument();
  });


  it("忽略迟到的旧详情成功响应", async () => {
    const firstRecord = syntheticFindings[0]!;
    const secondRecord = syntheticFindings[1]!;
    const firstRequest = createDeferred<FindingRecord | null>();
    const secondRequest = createDeferred<FindingRecord | null>();
    const requestedIds: string[] = [];
    const readSignals: AbortSignal[] = [];
    const adapter: FindingLedgerAdapter = {
      async list() {
        return {
          items: [firstRecord, secondRecord],
          page: { index: 1, size: 20 },
          total: 2,
          pageCount: 1,
        };
      },
      get(id, options) {
        requestedIds.push(id);
        if (options?.signal) readSignals.push(options.signal);
        return id === firstRecord.id ? firstRequest.promise : secondRequest.promise;
      },
    };
    navigate(`/safety/findings?caseId=${firstRecord.id}`, true);

    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" adapter={adapter} />);
    await waitFor(() => expect(requestedIds).toEqual([firstRecord.id]));

    navigate(`/safety/findings?caseId=${secondRecord.id}`);
    await waitFor(() => expect(requestedIds).toEqual([firstRecord.id, secondRecord.id]));
    expect(readSignals[0]?.aborted).toBe(true);
    expect(readSignals[1]?.aborted).toBe(false);
    secondRequest.resolve(secondRecord);

    const detail = screen.getByRole("complementary", { name: "隐患详情面板" });
    expect(await within(detail).findByText(secondRecord.code)).toBeInTheDocument();
    firstRequest.resolve(firstRecord);
    await act(async () => {
      await firstRequest.promise;
    });

    expect(within(detail).getByText(secondRecord.code)).toBeInTheDocument();
    expect(within(detail).queryByText(firstRecord.code)).not.toBeInTheDocument();
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
    const listSignals: AbortSignal[] = [];
    const adapter: FindingLedgerAdapter = {
      list(_input, options) {
        listCalls += 1;
        if (options?.signal) listSignals.push(options.signal);
        return listCalls === 1 ? firstRequest.promise : filteredRequest.promise;
      },
      async get(id) {
        return syntheticFindings.find((record) => record.id === id) ?? null;
      },
    };

    render(<FindingsLedger view="all" title="隐患台账" description="合成样例" adapter={adapter} />);
    await waitFor(() => expect(listCalls).toBe(1));
    fireEvent.change(screen.getByRole("searchbox", { name: "隐患编号" }), {
      target: { value: filteredRecord.code },
    });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));
    await waitFor(() => expect(listCalls).toBe(2));
    expect(listSignals[0]?.aborted).toBe(true);
    expect(listSignals[1]?.aborted).toBe(false);

    filteredRequest.resolve({
      items: [filteredRecord],
      page: { index: 1, size: 20 },
      total: 1,
      pageCount: 1,
    });
    await screen.findByText(filteredRecord.code);
    firstRequest.resolve({
      items: [firstRecord],
      page: { index: 1, size: 20 },
      total: 2,
      pageCount: 1,
    });
    await act(async () => {
      await firstRequest.promise;
    });

    expect(screen.getByText(filteredRecord.code)).toBeInTheDocument();
    expect(screen.queryByText(firstRecord.code)).not.toBeInTheDocument();
    expect(screen.getByText("共 1 条演示记录")).toBeInTheDocument();
  });
});

it("renders published list/detail labels and clears the old context before accepting a new adapter", async () => {
  const definition = findingDefinitionFixture();
  definition.title = "服务端台账"; definition.fields.code!.presentation.label = "发布编号"; definition.fields.description!.presentation.label = "发布描述";
  definition.layout.list = ["code", "state"]; definition.layout.detail = ["description"];
  const row = { ...syntheticFindings[0]!, code: "PUBLISHED-A", registeredValues: { id: syntheticFindings[0]!.id, code: "PUBLISHED-A", state: "PERSON_CONFIRM_PENDING", description: "已授权内容" } };
  const adapter: FindingLedgerAdapter = { publishedDefinition: true, list: async () => ({ items: [row], page: { index: 1, size: 20 }, total: 1, pageCount: 1, definition }), get: async () => row, getDetailSnapshot: async () => ({ record: row, definition }) };
  const props = { view: "all" as const, title: "旧标题", description: "", adapter };
  const view = render(<ModeProvider value="http"><FindingsLedger {...props} /></ModeProvider>);
  await screen.findByRole("heading", { name: "服务端台账" });
  expect(screen.getByRole("columnheader", { name: "发布编号" })).toBeInTheDocument();
  expect(screen.queryByRole("columnheader", { name: "责任人" })).not.toBeInTheDocument();
  expect(screen.getByLabelText("上报日期起")).toHaveAttribute("type", "datetime-local");
  const next = createDeferred<Awaited<ReturnType<FindingLedgerAdapter["list"]>>>();
  let nextReads = 0;
  const nextAdapter: FindingLedgerAdapter = { publishedDefinition: true, list: () => next.promise, get: async () => { ++nextReads; return null; } };
  view.rerender(<ModeProvider value="http"><FindingsLedger {...props} adapter={nextAdapter} /></ModeProvider>);
  expect(screen.queryByText("PUBLISHED-A")).not.toBeInTheDocument();
  await act(async () => next.resolve({ items: [], page: { index: 1, size: 20 }, total: 0, pageCount: 1, definition }));
  await screen.findByText("共 0 条记录");
  expect(screen.queryByText("共 0 条演示记录")).not.toBeInTheDocument();
  expect(screen.queryByText("合成演示数据")).not.toBeInTheDocument();
  expect(nextReads).toBe(0);
  expect(window.location.search).not.toContain("caseId");
});

it("renders the current HTTP detail definition rather than the prior list definition", async () => {
  const { createHttpFindingLedger } = await import("../../adapters/httpFindingLedger");
  const listDefinition = findingDefinitionFixture();
  listDefinition.layout.list = ["code"]; listDefinition.layout.detail = ["description"];
  listDefinition.fields.description!.presentation.label = "列表旧描述";
  const detailDefinition = findingDefinitionFixture();
  detailDefinition.layout.detail = ["location"]; detailDefinition.fields.location!.presentation.label = "当前地点";
  const item = { id: "00000000-0000-4000-8000-000000000301", code: "CURRENT-DETAIL", description: "旧描述正文", location: "当前作业点", createdAt: "2026-11-07T00:00:00Z", grade: "UNKNOWN", state: "RECTIFYING", roundNo: 1, version: 1 };
  let definitionCalls = 0;
  const adapter = createHttpFindingLedger({
    get: async (path: string) => path.includes("/definition?use=READ")
      ? { data: ++definitionCalls === 1 ? listDefinition : detailDefinition, meta: { schemaVersion: "1" } }
      : { data: item },
    post: async () => ({ data: { items: [item], page: { number: 1, size: 20, total: 1, pageCount: 1 } } }),
  } as unknown as import("../../api/httpClient").HttpClient);
  render(<ModeProvider value="http"><FindingsLedger view="all" title="隐患台账" description="" adapter={adapter} /></ModeProvider>);
  const row = (await screen.findByText("CURRENT-DETAIL")).closest("tr")!;
  fireEvent.click(within(row).getByRole("button", { name: "详情" }));
  const panel = await screen.findByRole("complementary", { name: "隐患详情面板" });
  await within(panel).findByText("当前地点");
  expect(within(panel).getByText("当前作业点")).toBeInTheDocument();
  expect(within(panel).queryByText("列表旧描述")).not.toBeInTheDocument();
  expect(within(panel).queryByText("旧描述正文")).not.toBeInTheDocument();
  expect(definitionCalls).toBe(2);
});
