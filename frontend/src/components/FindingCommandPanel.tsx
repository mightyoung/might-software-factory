import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import {
  FindingCommandError,
  type FindingCommand,
  type FindingCommandAdapter,
  type FindingCommandFailureKind,
  type FindingReviewHistory as ReviewHistory,
} from "../adapters/findingCommands";
import { DispatchCandidatePicker } from "./DispatchCandidatePicker";
import { FindingEvidenceUpload } from "./FindingEvidenceUpload";
import { wallTimeToIso } from "../domain/time";
import { FindingReviewHistory } from "./FindingReviewHistory";
import { newIdempotencyKey } from "../adapters/findingSubmit";
import { findingStateLabels, type FindingRecord, type FindingState } from "../domain/finding";
import { codePointLength, hasVisibleCharacter } from "../domain/findingDraft";

const REASON_MAX = 4000;

interface ActionDef {
  id: string;
  label: string;
  /** 需要填写的输入。 */
  reason?: "required" | "optional";
  grade?: boolean;
  supplement?: boolean;
  cycleText?: boolean;
  dispatch?: boolean;
  build(input: { reason: string; grade: "GENERAL" | "MAJOR"; assigneeMemberId: string; dueAt: string; requirements: string }): FindingCommand;
}

/** 契约已有、前端已接线的动作。按钮出现与否只是呈现，服务端对身份、职责、状态、版本、轮次最终校验。 */
const dispatchAction: ActionDef = { id: "dispatch", label: "分派整改", dispatch: true, build: ({ assigneeMemberId, dueAt, requirements }) => ({ kind: "dispatch", assigneeMemberId, dueAt: wallTimeToIso(dueAt) ?? dueAt, requirements: requirements.trim() }) };
const ACTIONS: Partial<Record<FindingState, readonly ActionDef[]>> = {
  DISCOVERED: [{ id: "init", label: "启动确认流程", build: () => ({ kind: "initialize" }) }],
  SAFETY_DISPATCH_PENDING: [dispatchAction],
  RECTIFYING: [{ ...dispatchAction, id: "redispatch", label: "重新分派" }],
  ACCEPTANCE_PENDING: [
    { id: "pass", label: "验收通过", reason: "optional", build: ({ reason }) => ({ kind: "verify", decision: "PASS", ...(reason ? { reason } : {}) }) },
    { id: "fail", label: "退回整改", reason: "required", build: ({ reason }) => ({ kind: "verify", decision: "FAIL", reason }) },
  ],
};

const BLOCKED: Record<NonNullable<ReviewHistory["blockedReason"]>, string> = {
  WORKFLOW_NOT_INITIALIZED: "确认流程尚未启动。",
  UNSUPPORTED_REVIEW_PROTOCOL: "当前流程暂不支持此操作，请联系管理员。",
  NO_REVIEW_ACTION_AT_CURRENT_STATE: "当前节点没有确认或补充操作。",
  REVIEW_ROUTE_UNAVAILABLE: "确认职责配置缺失或冲突，请联系管理员。",
  NOT_CURRENT_REVIEW_PARTICIPANT: "当前操作应由指定确认人或原上报人完成。",
  PERSON_APPROVAL_REQUIRED: "本次确认需要先完成一人确认。",
  CURRENT_TASK_NOT_ASSIGNED: "当前任务尚未分配给你。",
  ACTION_NOT_AUTHORIZED: "你当前没有此操作权限。",
  CURRENT_TASK_UNAVAILABLE: "当前任务已变化，请刷新后查看。",
};

function reviewActions(history: ReviewHistory | null): readonly ActionDef[] {
  if (!history || history.blockedReason) return [];
  const cycle = history.reviewProtocol === "CYCLE_V1" && history.processDefinitionKey === "findingRemediationV3";
  const legacy = history.reviewProtocol === "LEGACY" && ["findingRemediationV1", "findingRemediationV2"].includes(history.processDefinitionKey ?? "");
  if (!cycle && !legacy) return [];
  if (!Number.isSafeInteger(history.currentVersion) || history.currentVersion < 1 || !Number.isInteger(history.reviewCycle) || history.reviewCycle < 1) return [];
  const stage = history.reviewStage;
  return history.availableReviewActions.flatMap((decision): ActionDef[] => {
    if (decision === "SUPPLEMENT") return cycle && stage === null ? [{ id: decision, label: "补充说明", reason: "required", supplement: true, cycleText: true, build: ({ reason }) => ({ kind: "supplement", reviewCycle: history.reviewCycle, statement: reason }) }] : [];
    if (!["APPROVE", "RETURN_FOR_INFORMATION", "NOT_ESTABLISHED"].includes(decision)) return [];
    if ((stage !== "PERSON" && stage !== "SAFETY") || (!cycle && decision === "RETURN_FOR_INFORMATION") || (!cycle && decision === "NOT_ESTABLISHED" && history.processDefinitionKey !== "findingRemediationV2")) return [];
    const label = decision === "APPROVE" ? (stage === "PERSON" ? "确认发现" : "安全确认") : decision === "RETURN_FOR_INFORMATION" ? "退回补充" : "判定未成立";
    return [{ id: decision, label, reason: "required", grade: stage === "SAFETY" && decision === "APPROVE", cycleText: cycle, build: ({ reason, grade }) => {
      if (cycle) {
        if (stage === "SAFETY" && decision === "APPROVE") return { kind: "reviewDecision", reviewCycle: history.reviewCycle, stage, decision, reason, grade };
        if (stage === "PERSON") return { kind: "reviewDecision", reviewCycle: history.reviewCycle, stage, decision, reason };
        if (decision !== "APPROVE") return { kind: "reviewDecision", reviewCycle: history.reviewCycle, stage, decision, reason };
        throw new Error("Invalid review decision");
      }
      if (decision === "NOT_ESTABLISHED") return { kind: "closeNotEstablished", stage, reason };
      return stage === "PERSON" ? { kind: "confirmPerson", reason } : { kind: "confirmSafety", reason, grade };
    } }];
  });
}

const FAILURE_MESSAGE: Record<FindingCommandFailureKind, string> = {
  validation: "提交内容未通过服务端校验，请检查后重试。",
  unauthenticated: "登录已失效，请重新登录。",
  forbidden: "你无权执行此操作，或这条记录对你不可见。",
  versionConflict: "这条记录刚被他人更新，已为你重新读取最新状态。请确认后再操作。",
  idempotencyConflict: "这次提交与之前的一次提交内容不一致，已被拒绝。请重新操作。",
  stateConflict: "当前流程状态不允许此操作（可能已被他人处理），已为你重新读取最新状态。",
  workflow: "流程规则不满足（例如操作人职责冲突，或没有可用的下一步处理人）。请联系管理员处理。",
  unavailable: "服务暂时不可用，操作尚未确认成功。可以直接重试，重试不会重复执行。",
};

/** 失败后需要重新读取详情的类别：版本或状态已变，当前页面上的内容过期。 */
const REFRESH_ON: ReadonlySet<FindingCommandFailureKind> = new Set(["versionConflict", "stateConflict"]);

export interface FindingCommandPanelProps {
  finding: FindingRecord;
  adapter: FindingCommandAdapter;
  evidenceRecoveryOwnerKey?: string;
  /** 命令成功，或因版本/状态冲突需要刷新详情与列表。 */
  onChanged: () => void;
  onUnauthenticated?: () => void;
  readBlocked?: boolean;
  refreshing?: boolean;
  onForbidden?: () => void;
}

export function FindingCommandPanel({ finding, adapter, evidenceRecoveryOwnerKey, onChanged, onUnauthenticated, readBlocked = false, refreshing = false, onForbidden }: FindingCommandPanelProps) {
  const uid = useId();
  const [history, setHistory] = useState<ReviewHistory | null>(null);
  const [historyLoading, setHistoryLoading] = useState(Boolean(adapter.history));
  const [historyFailure, setHistoryFailure] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [pagePending, setPagePending] = useState(false);
  const [draftAction, setDraftAction] = useState<ActionDef | null>(null);
  const reviewState = ["PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING", "REPORTER_SUPPLEMENT_PENDING"].includes(history?.currentState ?? finding.state);
  const actions = reviewState ? reviewActions(historyLoading || historyFailure ? null : history) : ACTIONS[finding.state] ?? [];
  const unavailable = finding.state === "RECTIFYING" && !adapter.evidence ? [{ label: "提交整改证据", reason: "当前连接没有证据上传能力。" }] : [];
  const [activeId, setActiveId] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [grade, setGrade] = useState<"" | "GENERAL" | "MAJOR">("");
  const [assigneeMemberId, setAssigneeMemberId] = useState("");
  const [dueAt, setDueAt] = useState("");
  const [requirements, setRequirements] = useState("");
  const [errors, setErrors] = useState<{ reason?: string; grade?: string; assignee?: string; dueAt?: string; requirements?: string }>({});
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
  const canSubmit = uncertain || (!readBlocked && !refreshing && actions.some(action => action.id === activeId));

  function clearProtectedState(kind: string) {
    attempt.current = null;
    setActiveId(null);
    setDraftAction(null);
    setReason("");
    setGrade("");
    setAssigneeMemberId(""); setDueAt(""); setRequirements("");
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
    setAssigneeMemberId(""); setDueAt(""); setRequirements("");
    setErrors({});
    setFailure(null);
    setDone(null);
    attempt.current = null;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active || pending || version === undefined || !canSubmit) return;
    const found: typeof errors = {};
    if (active.reason && !uncertain) {
      if ((active.cycleText ? reason.length : codePointLength(reason)) > REASON_MAX) found.reason = `不能超过 ${REASON_MAX} 个字`;
      else if (active.cycleText && /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(reason)) found.reason = "请检查文字内容";
      else if (active.reason === "required" && !hasVisibleCharacter(reason)) found.reason = "请填写意见";
    }
    if (!uncertain && active.grade && !grade) found.grade = "请选择隐患等级";
    if (!uncertain && active.dispatch) {
      if (!assigneeMemberId) found.assignee = "请选择整改责任人";
      if (!dueAt) found.dueAt = "请填写整改期限";
      else if (!wallTimeToIso(dueAt)) found.dueAt = "整改期限格式不正确";
      if (codePointLength(requirements) > 4000) found.requirements = "不能超过 4000 个字";
      else if (!hasVisibleCharacter(requirements)) found.requirements = "请填写整改要求";
    }
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) return;

    const command = uncertain ? attempt.current!.command : active.build({ reason: active.cycleText ? reason : reason.trim(), grade: grade || "GENERAL", assigneeMemberId, dueAt, requirements });
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

  return (
    <div className="command-panel">
      {finding.state === "RECTIFYING" && adapter.evidence && finding.version !== undefined && <FindingEvidenceUpload
        key={JSON.stringify([evidenceRecoveryOwnerKey, finding.id, finding.round])}
        findingId={finding.id}
        roundNo={finding.round}
        version={finding.version}
        adapter={adapter.evidence}
        commands={adapter}
        recoveryOwnerKey={evidenceRecoveryOwnerKey}
        disabled={readBlocked || refreshing}
        onChanged={onChanged}
        onUnauthenticated={onUnauthenticated}
        onForbidden={onForbidden}
      />}
      {refreshing && <p className="field-hint" role="status">正在重新读取最新状态…</p>}
      {actions.some(action => action.dispatch) && !adapter.searchDispatchCandidates && <p className="field-hint">当前连接没有派单候选查询能力。</p>}
      {adapter.history && <>
        {historyLoading && <p role="status">正在读取确认记录与可用操作…</p>}
        {historyFailure && <div role="alert"><p>无法读取确认记录，请重试。草稿已保留。</p><button className="button button-subtle" onClick={() => setRefresh(value => value + 1)}>重新读取确认记录</button></div>}
        {history && <><p>当前为第 {history.reviewCycle} 次确认</p><FindingReviewHistory entries={history.entries} />{history.hasMore && <button className="button button-subtle" disabled={pagePending || historyLoading} onClick={loadMore}>{pagePending ? "读取中…" : "读取更多记录"}</button>}</>}
        {!historyLoading && !historyFailure && reviewState && history?.blockedReason && <p role="note">{BLOCKED[history.blockedReason]}</p>}
        {!historyLoading && !historyFailure && history && !history.blockedReason && reviewState && actions.length === 0 && <p role="note">当前流程暂不支持此操作，请联系管理员。</p>}
      </>}
      {done && (
        <p className="report-success" role="status">
          {done}
        </p>
      )}
      {failure && (
        <p className="report-summary" role="alert">
          {FAILURE_MESSAGE[failure]}
        </p>
      )}
      {version === undefined && actions.length > 0 && (
        <p className="action-hint" role="note">这条记录没有版本号，无法安全地执行流程操作。</p>
      )}
      <div className="detail-actions">
        {actions.map((action) => (
          <button
            key={action.id}
            type="button"
            className={`button ${activeId === action.id ? "button-subtle" : "button-primary"}`}
            disabled={pending || uncertain || readBlocked || refreshing || version === undefined || (action.dispatch && !adapter.searchDispatchCandidates)}
            aria-pressed={activeId === action.id}
            onClick={() => open(action.id)}
          >
            {action.label}
          </button>
        ))}
        {unavailable.map((item) => (
          <button key={item.label} type="button" className="button button-disabled" disabled title={item.reason}>
            {item.label}
          </button>
        ))}
        {finding.state === "CLOSED" && <span className="closed-note">已闭环，不可再变更</span>}
        {finding.state === "NOT_ESTABLISHED" && <span className="closed-note">未成立记录已终止；新证据应创建关联记录</span>}
      </div>
      {unavailable.length > 0 && (
        <p className="field-hint">{unavailable.map((item) => item.reason).join(" ")}</p>
      )}

      {active && (
        <form className="report-form" onSubmit={submit} noValidate aria-busy={pending} aria-label={active.label}>
          {active.dispatch && <>
            <DispatchCandidatePicker findingId={finding.id} findingVersion={finding.version} adapter={adapter} value={assigneeMemberId} onChange={setAssigneeMemberId} error={errors.assignee} disabled={pending || uncertain || refreshing} onStale={onChanged} onUnauthenticated={onUnauthenticated} />
            <div className="report-field"><label htmlFor={`${uid}-dueAt`}>整改期限<span className="required-mark" aria-hidden="true"> *</span></label><input id={`${uid}-dueAt`} type="datetime-local" value={dueAt} disabled={pending || uncertain} aria-invalid={Boolean(errors.dueAt)} onChange={event => setDueAt(event.target.value)} /><p className="field-hint">按北京时间（Asia/Shanghai）填写，与你的电脑时区无关。</p>{errors.dueAt && <p className="field-error">{errors.dueAt}</p>}</div>
            <div className="report-field"><label htmlFor={`${uid}-requirements`}>整改要求<span className="required-mark" aria-hidden="true"> *</span></label><textarea id={`${uid}-requirements`} rows={3} value={requirements} disabled={pending || uncertain} aria-invalid={Boolean(errors.requirements)} onChange={event => setRequirements(event.target.value)} />{errors.requirements && <p className="field-error">{errors.requirements}</p>}</div>
          </>}
          {active.grade && (
            <div className="report-field">
              <label htmlFor={`${uid}-grade`}>
                隐患等级<span className="required-mark" aria-hidden="true"> *</span>
              </label>
              <select
                id={`${uid}-grade`}
                value={grade}
                disabled={pending || uncertain}
                aria-invalid={Boolean(errors.grade)}
                onChange={(event) => setGrade(event.target.value as "" | "GENERAL" | "MAJOR")}
              >
                <option value="">请选择</option>
                <option value="GENERAL">一般</option>
                <option value="MAJOR">重大</option>
              </select>
              {errors.grade && <p className="field-error">{errors.grade}</p>}
            </div>
          )}
          {active.reason && (
            <div className="report-field">
              <label htmlFor={`${uid}-reason`}>
                {active.supplement ? "补充说明" : "意见"}{active.reason === "required" ? <span className="required-mark" aria-hidden="true"> *</span> : "（选填）"}
              </label>
              <textarea
                id={`${uid}-reason`}
                rows={3}
                value={reason}
                disabled={pending || uncertain}
                aria-invalid={Boolean(errors.reason)}
                onChange={(event) => setReason(event.target.value)}
              />
              {errors.reason && <p className="field-error">{errors.reason}</p>}
            </div>
          )}
          <div className="report-actions">
            <button type="button" className="button button-subtle" disabled={pending || uncertain} onClick={() => { setActiveId(null); setDraftAction(null); }}>
              取消
            </button>
            {!canSubmit && <p role="note">草稿已保留。请读取最新状态并选择当前可用操作后提交。</p>}
            <button type="submit" className="button button-primary" disabled={pending || !canSubmit || historyLoading}>
              {pending ? "提交中…" : failure === "unavailable" ? "重试" : active.label.startsWith("确认") ? active.label : `确认${active.label}`}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
