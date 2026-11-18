import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  FindingCommandError,
  type FindingCommand,
  type FindingCommandAdapter,
  type FindingCommandFailureKind,
  type FindingReviewHistory as ReviewHistory,
} from "../../adapters/findingCommands";
import { newIdempotencyKey } from "../../adapters/findingSubmit";
import { findingStateLabels, type FindingRecord } from "../../domain/finding";
import { codePointLength, hasVisibleCharacter } from "../../domain/findingDraft";

import {
  ACTIONS,
  EMPTY_DISPATCH,
  REFRESH_ON,
  REASON_MAX,
  reviewActions,
  validateDispatchInput,
  type ActionDef,
  type DispatchErrors,
  type DispatchValues,
} from "./commandActions";
interface Options { finding: FindingRecord; adapter: FindingCommandAdapter; onChanged: () => void; onUnauthenticated?: () => void; onForbidden?: () => void; readBlocked?: boolean; refreshing?: boolean; }
export function useFindingCommandRunner({finding,adapter,onChanged,onUnauthenticated,onForbidden,readBlocked=false,refreshing=false}:Options) {
  const [history, setHistory] = useState<ReviewHistory | null>(null);
  const [historyLoading, setHistoryLoading] = useState(Boolean(adapter.history));
  const [historyFailure, setHistoryFailure] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [pagePending, setPagePending] = useState(false);
  const [draftAction, setDraftAction] = useState<ActionDef | null>(null);
  const reviewState = ["PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING", "REPORTER_SUPPLEMENT_PENDING"].includes(history?.currentState ?? finding.state);
  const actions = reviewState ? reviewActions(historyLoading || historyFailure ? null : history) : (ACTIONS[finding.state] ?? []).filter((action) => !action.dispatch || adapter.searchDispatchCandidates);
  // 派单需要适配器能查候选成员；没有此能力就不假装可用。
  const canSearchCandidates = Boolean(adapter.searchDispatchCandidates);
  const unavailable = finding.state === "SAFETY_DISPATCH_PENDING" ? (canSearchCandidates ? [] : [{ label: "分派整改", reason: "当前连接没有派单候选查询能力。" }]) : finding.state === "RECTIFYING" ? [{ label: "提交整改证据", reason: "提交整改需要先上传证据集，契约还没有证据上传接口。" }] : [];
  const [activeId, setActiveId] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [grade, setGrade] = useState<"" | "GENERAL" | "MAJOR">("");
  const [errors, setErrors] = useState<{ reason?: string; grade?: string }>({});
  const [dispatch, setDispatch] = useState<DispatchValues>(EMPTY_DISPATCH);
  const [dispatchErrors, setDispatchErrors] = useState<DispatchErrors>({});
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<FindingCommandFailureKind | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const attempt = useRef<{ payload: string; key: string; command: FindingCommand; version: number } | null>(null);
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);
  const historyRequest = useRef(0);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);

  useEffect(() => {
    if (!adapter.history) return;
    const abort = new AbortController();
    historyRequest.current += 1;
    setHistoryLoading(true);
    setHistoryFailure(false);
    adapter.history(finding.id, { signal: abort.signal }).then(value => {
      if (!abort.signal.aborted) setHistory(value);
    }).catch(error => {
      if (abort.signal.aborted) return;
      setHistoryFailure(true);
      if (error instanceof FindingCommandError && ["unauthenticated", "forbidden"].includes(error.kind)) clearProtectedState(error.kind);
    }).finally(() => { if (!abort.signal.aborted) setHistoryLoading(false); });
    return () => abort.abort();
  }, [adapter, finding.id, finding.version, refresh]);

  const uncertain = failure === "unavailable" && attempt.current !== null;
  const active = uncertain ? draftAction : actions.find((action) => action.id === activeId) ?? draftAction;
  const version = reviewState ? history?.currentVersion : finding.version;
  const canSubmit = !refreshing && (uncertain || (!readBlocked && actions.some(action => action.id === activeId)));

  function clearProtectedState(kind: string) {
    attempt.current = null;
    setActiveId(null);
    setDraftAction(null);
    setReason("");
    setGrade("");
    setDispatch(EMPTY_DISPATCH);
    setHistory(null);
    if (kind === "unauthenticated") onUnauthenticated?.();
    else onForbidden?.();
  }

  async function loadMore() {
    if (!adapter.history || !history?.nextCursor || pagePending) return;
    setPagePending(true);
    const request = historyRequest.current;
    try {
      const page = await adapter.history(finding.id, { cursor: history.nextCursor });
      if (mounted.current && request === historyRequest.current) {
        if (page.currentVersion !== history.currentVersion || page.reviewCycle !== history.reviewCycle) { setRefresh(value => value + 1); return; }
        setHistory({ ...page, entries: [...history.entries, ...page.entries.filter(entry => !history.entries.some(old => old.id === entry.id))] });
      }
    } catch (error) {
      if (mounted.current && request === historyRequest.current) { setHistoryFailure(true); if (error instanceof FindingCommandError && ["unauthenticated", "forbidden"].includes(error.kind)) clearProtectedState(error.kind); }
    } finally { if (mounted.current) setPagePending(false); }
  }

  function open(id: string) {
    if (uncertain) return;
    setActiveId(id);
    setDraftAction(actions.find(action => action.id === id) ?? null);
    setReason("");
    setGrade("");
    setDispatch(EMPTY_DISPATCH);
    setDispatchErrors({});
    setErrors({});
    setFailure(null);
    setDone(null);
    attempt.current = null;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active || pending || refreshing || version === undefined || !canSubmit) return;
    const found: typeof errors = {};
    if (active.reason && !uncertain) {
      if ((active.cycleText ? reason.length : codePointLength(reason)) > REASON_MAX) found.reason = `不能超过 ${REASON_MAX} 个字`;
      else if (active.cycleText && /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(reason)) found.reason = "请检查文字内容";
      else if (active.reason === "required" && !hasVisibleCharacter(reason)) found.reason = "请填写意见";
    }
    if (!uncertain && active.grade && !grade) found.grade = "请选择隐患等级";
    const dispatchFound = active.dispatch && !uncertain ? validateDispatchInput(dispatch) : {};
    setErrors(found);
    setDispatchErrors(dispatchFound);
    setFailure(null);
    if (Object.keys(found).length > 0 || Object.keys(dispatchFound).length > 0) return;

    const command = uncertain ? attempt.current!.command : active.build({ reason: active.cycleText ? reason : reason.trim(), grade: grade || "GENERAL", dispatch });
    const commandVersion = uncertain ? attempt.current!.version : version;
    const payload = JSON.stringify([finding.id, commandVersion, command]);
    if (attempt.current?.payload !== payload) attempt.current = { payload, key: newIdempotencyKey(), command, version: commandVersion };
    const abort = new AbortController();
    controller.current = abort;
    setPending(true);
    try {
      const result = await adapter.run(finding.id, commandVersion, command, { idempotencyKey: attempt.current.key, signal: abort.signal });
      if (!mounted.current) return;
      setDone(`已完成“${active.label}”，当前状态：${findingStateLabels[result.state]}。`);
      setActiveId(null);
      setDraftAction(null);
      setReason("");
      setDispatch(EMPTY_DISPATCH);
      attempt.current = null;
      setRefresh(value => value + 1);
      onChanged();
    } catch (error) {
      if (!mounted.current || (error instanceof DOMException && error.name === "AbortError")) return;
      const kind = error instanceof FindingCommandError ? error.kind : "unavailable";
      setFailure(kind);
      if (kind === "unauthenticated" || kind === "forbidden") clearProtectedState(kind);
      if (REFRESH_ON.has(kind)) {
        attempt.current = null;
        setRefresh(value => value + 1);
        onChanged();
      }
      if (kind === "idempotencyConflict") attempt.current = null;
    } finally {
      if (mounted.current) setPending(false);
    }
  }


  return { history,historyLoading,historyFailure,refreshHistory:()=>setRefresh(value=>value+1),pagePending,loadMore,actions,unavailable,active,activeId,uncertain,canSubmit,version,open,cancel:()=>{setActiveId(null);setDraftAction(null);},reason,setReason,grade,setGrade,errors,dispatch,setDispatch,dispatchErrors,pending,failure,done,submit };
}
