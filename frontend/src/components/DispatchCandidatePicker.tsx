import { useEffect, useId, useRef, useState } from "react";
import {
  FindingCommandError,
  type DispatchCandidate,
  type DispatchCandidatePage,
  type FindingCommandAdapter,
  type FindingCommandFailureKind,
} from "../adapters/findingCommands";
import { codePointLength } from "../domain/findingDraft";

const PREFIX_MAX = 100;
const PAGE_SIZE = 20;
const DEBOUNCE_MS = 250;

type Status = "loading" | "ready" | "error";

interface CandidateState {
  items: readonly DispatchCandidate[];
  hasMore: boolean;
  next: string | null;
  status: Status;
  failure: FindingCommandFailureKind | null;
  loadingMore: boolean;
}

const INITIAL: CandidateState = { items: [], hasMore: false, next: null, status: "loading", failure: null, loadingMore: false };

const FAILURE_TEXT: Partial<Record<FindingCommandFailureKind, string>> = {
  stateConflict: "当前状态不能分派（可能已有责任人或已被他人处理）。",
  forbidden: "你无权为这条隐患选择整改责任人。",
  unauthenticated: "登录已失效，请重新登录。",
};

interface Options {
  findingId: string;
  /** 详情里当前的版本；候选返回的版本与它不同说明记录已变化。 */
  findingVersion: number | undefined;
  adapter: FindingCommandAdapter;
  onStale: () => void;
  onUnauthenticated?: () => void;
}

/** 按显示名前缀搜索候选：输入防抖，新请求取消旧请求，分页追加。 */
function useDispatchCandidates({ findingId, findingVersion, adapter, onStale, onUnauthenticated }: Options, prefix: string) {
  const [state, setState] = useState<CandidateState>(INITIAL);
  const controller = useRef<AbortController | null>(null);
  const [reloadTick, setReloadTick] = useState(0);

  function receive(page: DispatchCandidatePage, append: boolean) {
    if (findingVersion !== undefined && page.findingVersion !== findingVersion) onStale();
    setState((current) => ({
      items: append ? [...current.items, ...page.items] : page.items,
      hasMore: page.hasMore,
      next: page.next,
      status: "ready",
      failure: null,
      loadingMore: false,
    }));
  }

  function fail(error: unknown) {
    const kind = error instanceof FindingCommandError ? error.kind : "unavailable";
    if (kind === "unauthenticated") onUnauthenticated?.();
    setState((current) => ({ ...current, status: "error", failure: kind, loadingMore: false }));
  }

  async function fetchPage(afterMemberId: string | null, append: boolean) {
    controller.current?.abort();
    const abort = new AbortController();
    controller.current = abort;
    try {
      const page = await adapter.searchDispatchCandidates!(findingId, { prefix, afterMemberId, limit: PAGE_SIZE }, abort.signal);
      if (!abort.signal.aborted) receive(page, append);
    } catch (error) {
      if (!abort.signal.aborted) fail(error);
    }
  }

  useEffect(() => {
    if (codePointLength(prefix) > PREFIX_MAX) return;
    setState(INITIAL);
    const timer = window.setTimeout(() => void fetchPage(null, false), prefix ? DEBOUNCE_MS : 0);
    return () => {
      window.clearTimeout(timer);
      controller.current?.abort();
    };
    // fetchPage 每次渲染重建；只在查询词、记录或手动重试变化时重新搜索。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prefix, findingId, reloadTick]);

  return {
    state,
    retry: () => setReloadTick((n) => n + 1),
    loadMore: () => {
      setState((current) => ({ ...current, loadingMore: true }));
      void fetchPage(state.next, true);
    },
  };
}

export interface DispatchCandidatePickerProps extends Options {
  value: string;
  onChange: (memberId: string) => void;
  error?: string;
  disabled: boolean;
}

export function DispatchCandidatePicker({ value, onChange, error, disabled, ...options }: DispatchCandidatePickerProps) {
  const uid = useId();
  const [prefix, setPrefix] = useState("");
  const [chosen, setChosen] = useState<DispatchCandidate | null>(null);
  const tooLong = codePointLength(prefix) > PREFIX_MAX;
  const { state, retry, loadMore } = useDispatchCandidates(options, prefix);

  function pick(candidate: DispatchCandidate) {
    setChosen(candidate);
    onChange(candidate.memberId);
  }

  return (
    <div className="report-field" role="group" aria-labelledby={`${uid}-label`} aria-describedby={error ? `${uid}-error` : undefined}>
      <span id={`${uid}-label`} className="field-label">
        整改责任人<span className="required-mark" aria-hidden="true"> *</span>
      </span>
      <input
        type="search"
        aria-label="按姓名开头搜索成员"
        placeholder="按姓名开头搜索"
        value={prefix}
        disabled={disabled}
        aria-invalid={tooLong}
        onChange={(event) => setPrefix(event.target.value)}
      />
      {tooLong && <p className="field-error">搜索词不能超过 {PREFIX_MAX} 个字</p>}
      <p className="field-hint">候选只是提示；提交时服务端会再次校验权限、版本和成员资格。</p>
      {chosen && value === chosen.memberId && <p className="field-hint" role="status">已选：{chosen.displayName}</p>}

      {state.status === "loading" && <p className="field-hint" role="status">正在加载候选成员…</p>}
      {state.status === "error" && (
        <p className="field-error" role="alert">
          {FAILURE_TEXT[state.failure ?? "unavailable"] ?? "候选成员加载失败。"}
          {(state.failure === "unavailable" || state.failure === null) && (
            <button type="button" className="button button-subtle" onClick={retry}>
              重试
            </button>
          )}
        </p>
      )}
      {state.status === "ready" && state.items.length === 0 && (
        <p className="field-hint">没有符合条件的成员。</p>
      )}
      {state.items.length > 0 && (
        <div className="candidate-list" role="radiogroup" aria-label="候选成员">
          {state.items.map((candidate) => (
            <label key={candidate.memberId}>
              <input
                type="radio"
                name={`${uid}-candidate`}
                checked={value === candidate.memberId}
                disabled={disabled}
                onChange={() => pick(candidate)}
              />
              {candidate.displayName}
            </label>
          ))}
        </div>
      )}
      {state.hasMore && (
        <button type="button" className="button button-subtle" disabled={disabled || state.loadingMore} onClick={loadMore}>
          {state.loadingMore ? "加载中…" : "加载更多"}
        </button>
      )}
      {error && (
        <p className="field-error" id={`${uid}-error`}>
          {error}
        </p>
      )}
    </div>
  );
}
