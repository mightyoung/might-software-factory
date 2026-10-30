import { useEffect, useState } from "react";
import { mockFindingLedger, type FindingLedgerAdapter, type FindingView } from "../../adapters/findingLedger";
import { FindingDetails } from "../../components/FindingDetails";
import { LedgerQueryForm } from "../../components/LedgerQueryForm";
import { LedgerTable } from "../../components/LedgerTable";
import { createEmptyQuery, type PageResult, type QueryValue, type QueryValues } from "../../domain/ledger";
import type { FindingRecord } from "../../domain/finding";
import { toggleRow, toggleVisibleRows } from "../../domain/selection";
import { findingLedgerSchema } from "./schema";
import { navigate, useLocation } from "../../routing/router";

interface FindingsLedgerProps {
  view: FindingView;
  title: string;
  description: string;
  adapter?: FindingLedgerAdapter;
}

const firstPage: PageResult<FindingRecord> = {
  items: [],
  page: { index: 1, size: 20 },
  total: 0,
  pageCount: 1,
};

export function FindingsLedger({ view, title, description, adapter = mockFindingLedger }: FindingsLedgerProps) {
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
  const [listError, setListError] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [detail, setDetail] = useState<FindingRecord | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState(false);
  const [detailRefreshKey, setDetailRefreshKey] = useState(0);

  useEffect(() => {
    let current = true;
    setLoading(true);
    setListError(false);
    void Promise.resolve()
      .then(() => adapter.list({ query: appliedQuery, page: { index: pageIndex, size: pageSize }, view }))
      .then((result) => {
        if (!current) return;
        setPage(result);
        setPageIndex(result.page.index);
      })
      .catch(() => {
        if (!current) return;
        setListError(true);
      })
      .finally(() => {
        if (!current) return;
        setLoading(false);
      });
    return () => {
      current = false;
    };
  }, [adapter, appliedQuery, pageIndex, pageSize, refreshKey, view]);

  useEffect(() => {
    let current = true;
    if (!selectedFindingId) {
      setDetail(null);
      setDetailError(false);
      setDetailLoading(false);
      return () => {
        current = false;
      };
    }
    setDetail(null);
    setDetailError(false);
    setDetailLoading(true);
    void Promise.resolve()
      .then(() => adapter.get(selectedFindingId))
      .then((record) => {
        if (!current) return;
        setDetail(record);
      })
      .catch(() => {
        if (!current) return;
        setDetail(null);
        setDetailError(true);
      })
      .finally(() => {
        if (!current) return;
        setDetailLoading(false);
      });
    return () => {
      current = false;
    };
  }, [adapter, detailRefreshKey, selectedFindingId]);

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
    <div className={`ledger-layout ${selectedFindingId ? "ledger-with-detail" : ""}`}>
      <section className="ledger-main" aria-label={title}>
        <div className="page-heading">
          <div>
            <h1>{title}</h1>
            <p>{description}</p>
          </div>
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
              <span>暂时无法加载隐患记录。</span>
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

      {selectedFindingId && (
        <FindingDetails
          finding={detail}
          loading={detailLoading}
          error={detailError}
          onClose={() => navigate(path)}
          onRetry={() => setDetailRefreshKey((value) => value + 1)}
        />
      )}
    </div>
  );
}
