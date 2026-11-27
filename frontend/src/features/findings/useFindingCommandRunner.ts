import { createWorkspaceQueryClient } from "../../api/workspaceQueryClient";
import { useForm } from "react-hook-form";
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
import { parseCommandInput, type CommandInput } from "./commandInput";

import {
  ACTIONS,
  EMPTY_DISPATCH,
  REFRESH_ON,
  reviewActions,
  validateDispatchInput,
  type ActionDef,
  type CommandInputErrors,
  type DispatchErrors,
  type DispatchValues,
} from "./commandActions";
interface Options { finding: FindingRecord; adapter: FindingCommandAdapter; onChanged: () => void; onUnauthenticated?: () => void; onForbidden?: () => void; readBlocked?: boolean; refreshing?: boolean; }
export function useFindingCommandRunner({finding,adapter,onChanged,onUnauthenticated,onForbidden,readBlocked=false,refreshing=false}:Options) {
  const [historyClient] = useState(createWorkspaceQueryClient);
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
  const unavailable = finding.state === "SAFETY_DISPATCH_PENDING" ? (canSearchCandidates ? [] : [{ label: "分派整改", reason: "当前连接没有派单候选查询能力。" }]) : [];
  const [activeId, setActiveId] = useState<string | null>(null);
  const { watch, setValue, getValues, reset } = useForm<CommandInput>({ defaultValues: { reason: "", grade: "" } });
  const { reason, grade } = watch();
  const [errors, setErrors] = useState<CommandInputErrors>({});
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
    const queryKey = ["finding-review-history", finding.id, finding.version, ++historyRequest.current];
    let current = true;
    setHistoryLoading(true);
    setHistoryFailure(false);
    historyClient.fetchQuery({
      queryKey,
      queryFn: ({ signal }) => adapter.history!(finding.id, { signal }),
    }).then(value => {
      if (current) setHistory(value);
    }).catch(error => {
      if (!current) return;
      setHistoryFailure(true);
      if (error instanceof FindingCommandError && ["unauthenticated", "forbidden"].includes(error.kind)) clearProtectedState(error.kind);
    }).finally(() => { if (current) setHistoryLoading(false); });
    return () => {
      current = false;
      void historyClient.cancelQueries({ queryKey, exact: true });
    };
  }, [adapter, finding.id, finding.version, historyClient, refresh]);

  const uncertain = failure === "unavailable" && attempt.current !== null;
  const active = uncertain ? draftAction : actions.find((action) => action.id === activeId) ?? draftAction;
  const version = reviewState ? history?.currentVersion : finding.version;
  const canSubmit = !refreshing && (uncertain || (!readBlocked && actions.some(action => action.id === activeId)));

  function clearProtectedState(kind: string) {
    attempt.current = null;
    setActiveId(null);
    setDraftAction(null);
    reset();
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
    reset();
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
    const parsed = uncertain ? null : parseCommandInput(active, getValues());
    const dispatchFound = active.dispatch && !uncertain ? validateDispatchInput(dispatch) : {};
    setErrors(parsed && !parsed.success ? parsed.errors : {});
    setDispatchErrors(dispatchFound);
    setFailure(null);
    if (Object.keys(dispatchFound).length > 0 || (parsed && !parsed.success)) return;

    const command = parsed ? active.build({ ...parsed.input, dispatch }) : attempt.current!.command;
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
      reset();
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


  return { history,historyLoading,historyFailure,refreshHistory:()=>setRefresh(value=>value+1),pagePending,loadMore,actions,unavailable,active,activeId,uncertain,canSubmit,version,open,cancel:()=>{setActiveId(null);setDraftAction(null);},reason,setReason:(value:string)=>setValue("reason",value),grade,setGrade:(value:string)=>setValue("grade",value),errors,dispatch,setDispatch,dispatchErrors,pending,failure,done,submit };
}
