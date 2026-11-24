import type { ComponentProps } from "react";
import { mockFindingLedger } from "../../adapters/findingLedger";
import { useEffect, useMemo, useRef, useState } from "react";
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
import { FindingReportFormCore } from "../../components/FindingReportForm";
import { LedgerQueryForm } from "../../components/LedgerQueryForm";
import { LedgerTable } from "../../components/LedgerTable";
import { createEmptyQuery, type PageResult, type QueryValue, type QueryValues } from "../../domain/ledger";
import type { FindingRecord } from "../../domain/finding";
import { toggleRow, toggleVisibleRows } from "../../domain/selection";
import { syntheticFindingPresentation } from "../../adapters/syntheticPresentation";
import { findingSchemaFromDefinition, type FindingDefinition } from "../../adapters/findingDefinition";
import { findingReadFailureMessage } from "./readFailureMessage";
import { closeCurrentTab, navigate, openInNewTab, useLocation } from "../../routing/router";

interface FindingsLedgerProps {
  /** Session mode controls demo wording independently of adapter metadata capabilities. */
  synthetic?: boolean;
  /** Clear a previous security context's URL selection, while preserving cold deep links. */
  resetInitialSelection?: boolean;
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
  const appMode=useAppMode();
  const demo=synthetic??appMode==="synthetic";
  const location = useLocation();
  const [path, search = ""] = location.split("?");
  const selectionReady = useRef(!resetInitialSelection);
  const params = new URLSearchParams(search);
  // tab=1：详情 / 新建上报在独立标签页里整页展示，不再占用台账右侧面板。
  const standalone = params.get("tab") === "1";
  const reporting = standalone && params.get("report") === "1";
  const relatedId = params.get("related");
  const relatedTo = reporting && relatedId ? { id: relatedId, code: params.get("relatedCode") ?? relatedId } : null;
  const selectedFindingId = selectionReady.current ? params.get("caseId") : null;
  useEffect(() => {
    if (selectionReady.current) return;
    selectionReady.current = true;
    const parameters = new URLSearchParams(search);
    parameters.delete("caseId");
    const suffix = parameters.toString();
    navigate(`${path}${suffix ? `?${suffix}` : ""}`, true);
  }, [path, search]);
  const [definition, setDefinition] = useState<FindingDefinition | null>(null);
  const schema = useMemo(() => definition ? findingSchemaFromDefinition(definition) : presentation?.schema ?? null, [definition, presentation]);
  const published = adapter.publishedDefinition === true;
  const [query, setQuery] = useState<QueryValues>(() => createEmptyQuery(presentation?.schema.queryFields ?? []));
  const [appliedQuery, setAppliedQuery] = useState<QueryValues>(() => createEmptyQuery(presentation?.schema.queryFields ?? []));
  const [pageIndex, setPageIndex] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [page, setPage] = useState<PageResult<FindingRecord>>(firstPage);
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(() => new Set());
  const [loading, setLoading] = useState(true);
  const [listError, setListError] = useState<FindingLedgerReadFailureKind | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [detailSnapshot, setDetailSnapshot] = useState<FindingDetailSnapshot>({ record: null, definition: null });
  const detail = detailSnapshot.record;
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<FindingLedgerReadFailureKind | null>(null);
  const [detailRefreshKey, setDetailRefreshKey] = useState(0);
  const detailController = useRef<AbortController | null>(null);
  const showReport = canReport && submitAdapter !== undefined && view === "all";

  useEffect(() => {
    if (standalone) { setLoading(false); return; }
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
        setDefinition(result.definition ?? null);
        setPage(result);
        setPageIndex(result.page.index);
      } catch (error) {
        if (!current || controller.signal.aborted) return;
        const kind = classifyFindingLedgerReadFailure(error);
        setListError(kind);
        if (published) { setDefinition(null); setPage(firstPage); setDetailSnapshot({ record: null, definition: null }); setDetailError(kind); }
        if (kind === "unauthenticated" || kind === "forbidden") {
          detailController.current?.abort();
          setDetailSnapshot({ record: null, definition: null });
          setDetailError(kind);
          setDetailLoading(false);
          setSelectedIds(new Set());
          setPage(firstPage);
          navigate(path, true);
        }
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
  }, [adapter, appliedQuery, pageIndex, pageSize, refreshKey, view, path, standalone]);

  useEffect(() => {
    let current = true;
    if (!selectedFindingId) {
      setDetailSnapshot({ record: null, definition: null });
      setDetailError(null);
      setDetailLoading(false);
      return () => {
        current = false;
      };
    }
    const controller = new AbortController();
    detailController.current = controller;
    // 刷新同一条记录时保留旧内容直到新内容到达（流程命令成功后的提示不会被卸载）；换记录才清空。
    setDetailSnapshot((previous) => previous.record?.id === selectedFindingId ? previous : { record: null, definition: null });
    setDetailError(null);
    setDetailLoading(true);
    void (async () => {
      try {
        const snapshot = adapter.getDetailSnapshot
          ? await adapter.getDetailSnapshot(selectedFindingId, { signal: controller.signal })
          : { record: await adapter.get(selectedFindingId, { signal: controller.signal }), definition: null };
        if (published && adapter.getDetailSnapshot && snapshot.record && !snapshot.definition) throw new Error("Missing published detail definition");
        if (!current || controller.signal.aborted) return;
        setDetailSnapshot(snapshot);
      } catch (error) {
        if (!current || controller.signal.aborted) return;
        const kind = classifyFindingLedgerReadFailure(error);
        if (kind !== "unknown") setDetailSnapshot({ record: null, definition: null });
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
    const emptyQuery = createEmptyQuery(schema?.queryFields ?? []);
    setQuery(emptyQuery);
    runQuery(emptyQuery);
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

        {schema && (!published || definition) && <LedgerQueryForm
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
      )}

      {selectedFindingId && !reporting && (
        <FindingDetailsCore
          Fields={presentation?.Fields}
          Actions={presentation?.Actions}
          definition={detailSnapshot.definition}
          finding={detail}
          loading={detailLoading}
          error={detailError}
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
