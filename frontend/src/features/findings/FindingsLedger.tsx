import { useEffect, useRef, useState } from "react";
import {
  classifyFindingLedgerReadFailure,
  mockFindingLedger,
  type FindingLedgerAdapter,
  type FindingLedgerReadFailureKind,
  type FindingView,
} from "../../adapters/findingLedger";
import type { FindingSubmitAdapter } from "../../adapters/findingSubmit";
import { FindingDetails } from "../../components/FindingDetails";
import { FindingReportForm } from "../../components/FindingReportForm";
import { LedgerQueryForm } from "../../components/LedgerQueryForm";
import { LedgerTable } from "../../components/LedgerTable";
import { createEmptyQuery, type PageResult, type QueryValue, type QueryValues } from "../../domain/ledger";
import type { FindingRecord } from "../../domain/finding";
import { toggleRow, toggleVisibleRows } from "../../domain/selection";
import { findingLedgerSchema } from "./schema";
import { findingReadFailureMessage } from "./readFailureMessage";
import { navigate, useLocation } from "../../routing/router";

interface FindingsLedgerProps {
  view: FindingView;
  title: string;
  description: string;
  adapter?: FindingLedgerAdapter;
  /** 提供上报端口且 canReport 为 true 时才显示“新建上报”（仅台账视图）。入口显隐只是呈现，服务端仍须鉴权。 */
  submitAdapter?: FindingSubmitAdapter;
  canReport?: boolean;
  /** 读取或提交收到“未登录”时通知应用回到登录页。 */
  onUnauthenticated?: () => void;
}

const firstPage: PageResult<FindingRecord> = {
  items: [],
  page: { index: 1, size: 20 },
  total: 0,
  pageCount: 1,
};

export function FindingsLedger({
  view,
  title,
  description,
  adapter = mockFindingLedger,
  submitAdapter,
  canReport = false,
  onUnauthenticated,
}: FindingsLedgerProps) {
  const location = useLocation();
  const [path, search = ""] = location.split("?");
  const selectedFindingId = new URLSearchParams(search).get("caseId");
  const [query, setQuery] = useState<QueryValues>(() => createEmptyQuery(findingLedgerSchema.queryFields));
  const [appliedQuery, setAppliedQuery] = useState<QueryValues>(() => createEmptyQuery(findingLedgerSchema.queryFields));
  const [pageIndex, setPageIndex] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [page, setPage] = useState<PageResult<FindingRecord>>(firstPage);
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(() => new Set());
  const [loading, setLoading] = useState(true);
  const [listError, setListError] = useState<FindingLedgerReadFailureKind | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [detail, setDetail] = useState<FindingRecord | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<FindingLedgerReadFailureKind | null>(null);
  const [detailRefreshKey, setDetailRefreshKey] = useState(0);
  const [reporting, setReporting] = useState(false);
  const reportButtonRef = useRef<HTMLButtonElement>(null);
  const restoreFocus = useRef(false);
  const showReport = canReport && submitAdapter !== undefined && view === "all";

  useEffect(() => {
    let current = true;
    const controller = new AbortController();
    setLoading(true);
    setListError(null);
    void (async () => {
      try {
        const result = await adapter.list(
          { query: appliedQuery, page: { index: pageIndex, size: pageSize }, view },
          { signal: controller.signal },
        );
        if (!current || controller.signal.aborted) return;
        setPage(result);
        setPageIndex(result.page.index);
      } catch (error) {
        if (!current || controller.signal.aborted) return;
        const kind = classifyFindingLedgerReadFailure(error);
        setListError(kind);
        if (kind === "unauthenticated") onUnauthenticated?.();
      } finally {
        if (!current || controller.signal.aborted) return;
        setLoading(false);
      }
    })();
    return () => {
      current = false;
      controller.abort();
    };
  }, [adapter, appliedQuery, pageIndex, pageSize, refreshKey, view]);

  useEffect(() => {
    let current = true;
    if (!selectedFindingId) {
      setDetail(null);
      setDetailError(null);
      setDetailLoading(false);
      return () => {
        current = false;
      };
    }
    const controller = new AbortController();
    setDetail(null);
    setDetailError(null);
    setDetailLoading(true);
    void (async () => {
      try {
        const record = await adapter.get(selectedFindingId, { signal: controller.signal });
        if (!current || controller.signal.aborted) return;
        setDetail(record);
      } catch (error) {
        if (!current || controller.signal.aborted) return;
        const kind = classifyFindingLedgerReadFailure(error);
        setDetail(null);
        setDetailError(kind);
        if (kind === "unauthenticated") onUnauthenticated?.();
      } finally {
        if (!current || controller.signal.aborted) return;
        setDetailLoading(false);
      }
    })();
    return () => {
      current = false;
      controller.abort();
    };
  }, [adapter, detailRefreshKey, selectedFindingId]);

  useEffect(() => {
    if (!reporting && restoreFocus.current) {
      restoreFocus.current = false;
      reportButtonRef.current?.focus();
    }
  }, [reporting]);

  function openReport() {
    // 详情与上报共用右侧面板，打开上报时先关闭详情。
    if (selectedFindingId) navigate(path, true);
    setReporting(true);
  }

  function closeReport() {
    restoreFocus.current = true;
    setReporting(false);
  }

  function handleSubmitted() {
    setSelectedIds(new Set());
    setPageIndex(1);
    setRefreshKey((value) => value + 1);
  }

  function openSubmitted(id: string) {
    setReporting(false);
    navigate(`${path}?caseId=${encodeURIComponent(id)}`);
  }

  function updateQuery(key: string, value: QueryValue) {
    setQuery((current) => ({ ...current, [key]: value }));
  }

  function runQuery(nextQuery: QueryValues) {
    setAppliedQuery({ ...nextQuery });
    setPageIndex(1);
    setSelectedIds(new Set());
    if (selectedFindingId) navigate(path);
    setRefreshKey((value) => value + 1);
  }

  function handleReset() {
    const emptyQuery = createEmptyQuery(findingLedgerSchema.queryFields);
    setQuery(emptyQuery);
    runQuery(emptyQuery);
  }

  function openDetail(record: FindingRecord) {
    navigate(`${path}?caseId=${encodeURIComponent(record.id)}`);
  }

  function goToPage(index: number) {
    setSelectedIds(new Set());
    setPageIndex(index);
  }

  const visibleIds = page.items.map((record) => record.id);
  const pageCount = Math.max(1, page.pageCount);

  return (
    <div className={`ledger-layout ${selectedFindingId || reporting ? "ledger-with-detail" : ""}`}>
      {/* 上报面板打开时主区域不可交互，避免带着未提交内容切换到别的记录或筛选。 */}
      <section className="ledger-main" aria-label={title} inert={reporting}>
        <div className="page-heading">
          <div>
            <h1>{title}</h1>
            <p>{description}</p>
          </div>
          {showReport && (
            <button ref={reportButtonRef} type="button" className="button button-primary" onClick={openReport}>
              新建上报
            </button>
          )}
        </div>

        <div className="demo-notice" role="note">
          <span className="notice-mark" aria-hidden="true">i</span>
          <p>
            当前使用<strong>合成演示数据</strong>，只供浏览字段、列表和流程节点；不代表真实企业、人员、事件或审批结果。
          </p>
        </div>

        {view !== "all" && (
          <p className="view-scope-note">
            {view === "confirmation"
              ? "展示处于两级确认节点的演示记录，不代表具体成员已获待办授权。"
              : "展示处于验收节点的演示记录，不代表已提交真实整改证据。"}
          </p>
        )}

        <LedgerQueryForm
          schema={findingLedgerSchema}
          values={query}
          onChange={updateQuery}
          onSearch={() => runQuery(query)}
          onReset={handleReset}
        />

        <div className="table-toolbar">
          <span className="selection-summary">已选 {selectedIds.size} 条（当前页）</span>
          <span className="data-kind-label">示例对象 · 隐患</span>
        </div>

        <div className="table-frame" aria-busy={loading}>
          {loading && <div className="table-loading" role="status">正在加载演示记录…</div>}
          {!loading && listError && (
            <div className="table-error" role="alert">
              <span>{findingReadFailureMessage(listError, "list")}</span>
              <button type="button" className="button button-subtle" onClick={() => setRefreshKey((value) => value + 1)}>
                重试加载
              </button>
            </div>
          )}
          {!loading && !listError && (
            <LedgerTable
              columns={findingLedgerSchema.columns}
              rows={page.items}
              getKey={(record) => findingLedgerSchema.recordKey(record)}
              selectedIds={selectedIds}
              onToggleRow={(id) => setSelectedIds((current) => toggleRow(current, id))}
              onTogglePage={() => setSelectedIds((current) => toggleVisibleRows(current, visibleIds))}
              onOpen={openDetail}
            />
          )}
        </div>

        {!listError && (
        <div className="table-footer">
          <span>共 {page.total} 条演示记录</span>
          <div className="pagination-controls">
            <label className="page-size-select">
              <span className="visually-hidden">每页条数</span>
              <select
                value={pageSize}
                onChange={(event) => {
                  setPageSize(Number(event.currentTarget.value));
                  setPageIndex(1);
                  setSelectedIds(new Set());
                }}
              >
                <option value={20}>20 条/页</option>
                <option value={50}>50 条/页</option>
                <option value={100}>100 条/页</option>
              </select>
            </label>
            <button type="button" className="page-button" aria-label="上一页" disabled={pageIndex <= 1} onClick={() => goToPage(pageIndex - 1)}>
              ‹
            </button>
            <span className="page-counter">{pageIndex} / {pageCount}</span>
            <button type="button" className="page-button" aria-label="下一页" disabled={pageIndex >= pageCount} onClick={() => goToPage(pageIndex + 1)}>
              ›
            </button>
          </div>
        </div>
        )}
      </section>

      {reporting && submitAdapter && (
        <FindingReportForm
          adapter={submitAdapter}
          onClose={closeReport}
          onSubmitted={handleSubmitted}
          onOpenSubmitted={openSubmitted}
          onUnauthenticated={onUnauthenticated}
        />
      )}

      {selectedFindingId && !reporting && (
        <FindingDetails
          finding={detail}
          loading={detailLoading}
          error={detailError}
          onClose={() => navigate(path, true)}
          onRetry={() => setDetailRefreshKey((value) => value + 1)}
        />
      )}
    </div>
  );
}
