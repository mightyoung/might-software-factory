import { useQuery } from "@tanstack/react-query";
import { createWorkspaceQueryClient } from "../../api/workspaceQueryClient";
import type { ComponentProps } from "react";
import { mockFindingLedger } from "../../adapters/findingLedger";
import { lazy, Suspense, useEffect, useMemo, useRef, useState } from "react";
import {
  classifyFindingLedgerReadFailure,
  type FindingLedgerAdapter,
  type FindingDetailSnapshot,
  type FindingLedgerReadFailureKind,
  type FindingView,
} from "../../adapters/findingLedgerContract";
import { useAppMode } from "../../mode";
import type { FindingCommandAdapter } from "../../adapters/findingCommands";
import type { FindingEvidenceAdapter } from "../../adapters/findingEvidence";
import type { FindingSubmitAdapter } from "../../adapters/findingSubmit";
import { FindingDetailsCore } from "../../components/FindingDetails";
import { LedgerQueryForm } from "../../components/LedgerQueryForm";
import { LedgerTable } from "../../components/LedgerTable";
import { createEmptyQuery, type PageResult, type QueryValue, type QueryValues } from "../../domain/ledger";
import type { FindingRecord } from "../../domain/finding";
import { toggleRow, toggleVisibleRows } from "../../domain/selection";
import { syntheticFindingPresentation } from "../../adapters/syntheticPresentation";
import { findingSchemaFromDefinition, type FindingDefinition } from "../../adapters/findingDefinition";
import { findingReadFailureMessage } from "./readFailureMessage";
import { findingSearchInput, validateFindingSearch, validateFindingState, withFindingState, withoutFindingSelection, type FindingSearch } from "../../routing/findingSearch";
import { closeCurrentTab, navigate, openInNewTab, useLocation } from "../../routing/router";

interface FindingsLedgerProps {
  /** Session mode controls demo wording independently of adapter metadata capabilities. */
  synthetic?: boolean;
  /** Clear a previous security context's URL selection, while preserving cold deep links. */
  resetInitialSelection?: boolean;
  routeSearch?: FindingSearch;
  view: FindingView;
  title: string;
  description: string;
  adapter: FindingLedgerAdapter;
  presentation?: typeof syntheticFindingPresentation;
  /** 提供上报端口且 canReport 为 true 时才显示“新建上报”（仅台账视图）。入口显隐只是呈现，服务端仍须鉴权。 */
  submitAdapter?: FindingSubmitAdapter;
  /** 提供时详情显示真实可用的流程操作；缺省为演示用的禁用按钮。 */
  commandAdapter?: FindingCommandAdapter;
  evidenceAdapter?: FindingEvidenceAdapter;
  canReport?: boolean;
  /** 当前登录者标识；传给上报表单用于登录失效后恢复草稿。 */
  draftKey?: string;
  /** 读取或提交收到“未登录”时通知应用回到登录页。 */
  onUnauthenticated?: () => void;
}

const firstPage: PageResult<FindingRecord> = {
  items: [],
  page: { index: 1, size: 20 },
  total: 0,
  pageCount: 1,
};

const FindingReportFormCore = lazy(() => import("../../components/FindingReportForm").then(({ FindingReportFormCore }) => ({ default: FindingReportFormCore })));

export function FindingsLedgerCore(props: FindingsLedgerProps) {
  const [scope, setScope] = useState({ adapter: props.adapter, submit: props.submitAdapter, generation: 0 });
  if (scope.adapter !== props.adapter || scope.submit !== props.submitAdapter) {
    setScope({ adapter: props.adapter, submit: props.submitAdapter, generation: scope.generation + 1 });
    return null;
  }
  return <FindingsLedgerBody key={scope.generation} {...props} resetInitialSelection={props.resetInitialSelection || scope.generation > 0} />;
}

function FindingsLedgerBody({
  resetInitialSelection = false,
  routeSearch,
  view,
  title,
  description,
  adapter,
  presentation,
  synthetic,
  submitAdapter,
  commandAdapter,
  evidenceAdapter,
  canReport = false,
  draftKey,
  onUnauthenticated,
}: FindingsLedgerProps) {
  const contextMode = useAppMode();
  const appMode = import.meta.env.MSF_BROWSER_MODE ?? contextMode;
  const demo = import.meta.env.MSF_BROWSER_MODE === undefined ? synthetic ?? appMode === "synthetic" : import.meta.env.MSF_BROWSER_MODE === "synthetic";
  const location = useLocation();
  const delimiter = location.indexOf("?");
  const path = delimiter < 0 ? location : location.slice(0, delimiter);
  const search = delimiter < 0 ? "" : location.slice(delimiter);
  const selectionReady = useRef(!resetInitialSelection);
  // App supplies TanStack's validated match; standalone uses the identical projection.
  const { standalone, reporting, relatedId, relatedCode, caseId, findingState } = routeSearch ?? validateFindingSearch(findingSearchInput(search));
  const relatedTo = reporting && relatedId ? { id: relatedId, code: relatedCode ?? relatedId } : null;
  const selectedFindingId = selectionReady.current ? caseId : null;
  useEffect(() => {
    if (selectionReady.current) return;
    selectionReady.current = true;
    navigate(path + withoutFindingSelection(search), true);
  }, [path, search]);
  const [definition, setDefinition] = useState<FindingDefinition | null>(null);
  const schema = useMemo(() => definition ? findingSchemaFromDefinition(definition) : presentation?.schema ?? null, [definition, presentation]);
  const published = adapter.publishedDefinition === true;
  function fromRoute() {
    const values: QueryValues = { ...createEmptyQuery(schema?.queryFields ?? []), ...(findingState && { state: findingState }) };
    return { status: findingState, draft: values, applied: values };
  }
  const [queryState, setQueryState] = useState(fromRoute);
  const [pageIndex, setPageIndex] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [page, setPage] = useState<PageResult<FindingRecord>>(firstPage);
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(() => new Set());
  const [refreshKey, setRefreshKey] = useState(0);
  // Apply a history change before Query observes the old filters or page.
  if (queryState.status !== findingState) {
    setQueryState(fromRoute());
    setPageIndex(1);
    setSelectedIds(new Set());
    setRefreshKey(value => value + 1);
  }
  const { draft: query, applied: appliedQuery } = queryState;
  const [detailSnapshot, setDetailSnapshot] = useState<FindingDetailSnapshot>({ record: null, definition: null });
  const detail = detailSnapshot.record;
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<FindingLedgerReadFailureKind | null>(null);
  const [detailRefreshKey, setDetailRefreshKey] = useState(0);
  const cancelDetail = useRef<(() => void) | null>(null);
  const showReport = canReport && submitAdapter !== undefined && view === "all";

  const [client] = useState(createWorkspaceQueryClient);
  const list = useQuery({
    queryKey: ["finding-list", appMode, path, view, appliedQuery, pageIndex, pageSize, refreshKey],
    enabled: !standalone,
    queryFn: ({ signal }) => adapter.list(
      { query: appliedQuery, page: { index: pageIndex, size: pageSize }, view },
      { signal },
    ),
  }, client);
  const loading = !standalone && (list.isFetching || list.isPending);
  const listError = !standalone && list.error ? classifyFindingLedgerReadFailure(list.error) : null;
  const blockDetail = listError === "unauthenticated" || listError === "forbidden" || (published && listError !== null);
  useEffect(() => {
    if (standalone || !list.data) return;
    setDefinition(list.data.definition ?? null);
    setPage(list.data);
    setPageIndex(list.data.page.index);
  }, [list.data, standalone]);
  useEffect(() => {
    if (!listError) return;
    if (published) { setDefinition(null); setPage(firstPage); }
    if (blockDetail) {
      cancelDetail.current?.();
      setDetailSnapshot({ record: null, definition: null });
      setDetailError(listError);
      setDetailLoading(false);
    }
    if (listError === "unauthenticated" || listError === "forbidden") {
      setSelectedIds(new Set());
      setPage(firstPage);
      navigate(path, true);
    }
    if (listError === "unauthenticated") onUnauthenticated?.();
  }, [list.error, listError, path, blockDetail, published, onUnauthenticated]);

  const publishedDetailBlocked = published && blockDetail;
  useEffect(() => {
    let current = true;
    if (publishedDetailBlocked) return;
    if (!selectedFindingId) {
      setDetailSnapshot({ record: null, definition: null });
      setDetailError(null);
      setDetailLoading(false);
      return () => {
        current = false;
      };
    }
    const queryKey = ["finding-detail", appMode, selectedFindingId, detailRefreshKey];
    const cancel = () => {
      current = false;
      void client.cancelQueries({ queryKey, exact: true });
    };
    cancelDetail.current = cancel;
    // 刷新同一条记录时保留旧内容直到新内容到达（流程命令成功后的提示不会被卸载）；换记录才清空。
    setDetailSnapshot((previous) => previous.record?.id === selectedFindingId ? previous : { record: null, definition: null });
    setDetailError(null);
    setDetailLoading(true);
    void (async () => {
      try {
        const snapshot = await client.fetchQuery({
          queryKey,
          queryFn: async ({ signal }) => adapter.getDetailSnapshot
            ? adapter.getDetailSnapshot(selectedFindingId, { signal })
            : { record: await adapter.get(selectedFindingId, { signal }), definition: null },
        });
        if (!current) return;
        if (published && adapter.getDetailSnapshot && snapshot.record && !snapshot.definition) throw new Error("Missing published detail definition");
        setDetailSnapshot(snapshot);
      } catch (error) {
        if (!current) return;
        const kind = classifyFindingLedgerReadFailure(error);
        if (kind !== "unknown") setDetailSnapshot({ record: null, definition: null });
        setDetailError(kind);
        if (kind === "unauthenticated") onUnauthenticated?.();
      } finally {
        if (!current) return;
        setDetailLoading(false);
      }
    })();
    return () => {
      if (cancelDetail.current === cancel) cancelDetail.current = null;
      cancel();
    };
  }, [adapter, appMode, client, detailRefreshKey, selectedFindingId, publishedDetailBlocked]);

  function openReport(related: { id: string; code: string } | null = null) {
    const extra = related ? `&related=${encodeURIComponent(related.id)}&relatedCode=${encodeURIComponent(related.code)}` : "";
    openInNewTab(`${path}?tab=1&report=1${extra}`);
  }

  /** 独立标签页里“关闭”：能关就关；不是脚本打开的标签页关不掉，就退回台账。 */
  function closeTab() {
    closeCurrentTab();
    navigate(path, true);
  }

  function handleSubmitted() {
    setSelectedIds(new Set());
    setPageIndex(1);
    setRefreshKey((value) => value + 1);
  }

  function openSubmitted(id: string) {
    navigate(`${path}?tab=1&caseId=${encodeURIComponent(id)}`, true);
  }

  function updateQuery(key: string, value: QueryValue) {
    setQueryState(current => ({ ...current, draft: { ...current.draft, [key]: value } }));
  }

  function runQuery(nextQuery: QueryValues) {
    const status = validateFindingState(nextQuery.state);
    const nextSearch = withFindingState(selectedFindingId ? withoutFindingSelection(search) : search, status);
    setQueryState({ status, draft: nextQuery, applied: nextQuery });
    setPageIndex(1);
    setSelectedIds(new Set());
    if (nextSearch !== search) navigate(path + nextSearch + window.location.hash);
    setRefreshKey((value) => value + 1);
  }

  function handleReset() {
    runQuery(createEmptyQuery(schema?.queryFields ?? []));
  }

  function openDetail(record: FindingRecord) {
    openInNewTab(`${path}?tab=1&caseId=${encodeURIComponent(record.id)}`);
  }

  function goToPage(index: number) {
    setSelectedIds(new Set());
    setPageIndex(index);
  }

  const visibleIds = page.items.map((record) => record.id);
  const pageCount = Math.max(1, page.pageCount);

  return (
    <div className={`ledger-layout ${standalone ? "ledger-standalone" : ""}`}>
      {!standalone && <section className="ledger-main" aria-label={title}>
        <div className="page-heading">
          <div>
            <h1>{view === "all" ? definition?.title ?? title : title}</h1>
            <p>{description}</p>
          </div>
          {showReport && (
            <button type="button" className="button button-primary" onClick={() => openReport()}>
              新建上报
            </button>
          )}
        </div>

        {demo && presentation && <presentation.Notice view={view} />}

        {schema && (!published || !listError && definition) && <LedgerQueryForm
          schema={schema}
          values={query}
          onChange={updateQuery}
          onSearch={() => runQuery(query)}
          onReset={handleReset}
        />}

        <div className="table-toolbar">
          <span className="selection-summary">已选 {selectedIds.size} 条（当前页）</span>
          <span className="data-kind-label">{demo ? "示例对象 · 隐患" : "隐患"}</span>
        </div>

        <div className="table-frame" aria-busy={loading}>
          {loading && <div className="table-loading" role="status">正在加载{demo ? "演示记录" : "记录"}…</div>}
          {!loading && listError && (
            <div className="table-error" role="alert">
              <span>{findingReadFailureMessage(listError, "list")}</span>
              <button type="button" className="button button-subtle" onClick={() => setRefreshKey((value) => value + 1)}>
                重试加载
              </button>
            </div>
          )}
          {!loading && !listError && schema && (
            <LedgerTable
              columns={schema.columns}
              rows={page.items}
              getKey={(record) => schema.recordKey(record)}
              getLabel={(record) => schema.recordLabel?.(record) ?? schema.recordKey(record)}
              selectedIds={selectedIds}
              onToggleRow={(id) => setSelectedIds((current) => toggleRow(current, id))}
              onTogglePage={() => setSelectedIds((current) => toggleVisibleRows(current, visibleIds))}
              onOpen={openDetail}
            />
          )}
        </div>

        {!listError && (
        <div className="table-footer">
          <span>共 {page.total} 条{demo ? "演示" : ""}记录</span>
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
      </section>}

      {reporting && submitAdapter && (
        <Suspense fallback={<p role="status">正在载入上报表单…</p>}>
        <FindingReportFormCore
          validateDraft={presentation?.validateDraft}
          adapter={submitAdapter}
          onClose={closeTab}
          onSubmitted={handleSubmitted}
          onOpenSubmitted={openSubmitted}
          onUnauthenticated={onUnauthenticated}
          draftKey={draftKey}
          {...(relatedTo ? { relatedTo } : {})}
        />
        </Suspense>
      )}

      {selectedFindingId && !reporting && (
        <FindingDetailsCore
          Fields={presentation?.Fields}
          Actions={presentation?.Actions}
          definition={publishedDetailBlocked ? null : detailSnapshot.definition}
          finding={publishedDetailBlocked ? null : detail}
          loading={!publishedDetailBlocked && detailLoading}
          error={publishedDetailBlocked ? listError : detailError}
          onClose={closeTab}
          onRetry={() => setDetailRefreshKey((value) => value + 1)}
          commandAdapter={commandAdapter}
          evidenceAdapter={evidenceAdapter}
          onOpenRelated={(id) => openInNewTab(`${path}?tab=1&caseId=${encodeURIComponent(id)}`)}
          {...(submitAdapter ? { onCreateRelated: (record: FindingRecord) => openReport({ id: record.id, code: record.code }) } : {})}
          onForbidden={() => { setDetailSnapshot({ record: null, definition: null }); setDetailError("forbidden"); }}
          onCommandChanged={() => {
            setDetailRefreshKey((value) => value + 1);
            setRefreshKey((value) => value + 1);
          }}
          onUnauthenticated={onUnauthenticated}
        />
      )}
    </div>
  );
}

type Props = Omit<ComponentProps<typeof FindingsLedgerCore>, "adapter"> & {
  adapter?: ComponentProps<typeof FindingsLedgerCore>["adapter"];
};

export function FindingsLedger({ adapter = mockFindingLedger, presentation = syntheticFindingPresentation, ...props }: Props) {
  return <FindingsLedgerCore {...props} adapter={adapter} presentation={presentation} />;
}
