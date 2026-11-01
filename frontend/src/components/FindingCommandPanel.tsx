import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import {
  FindingCommandError,
  type FindingCommand,
  type FindingCommandAdapter,
  type FindingCommandFailureKind,
} from "../adapters/findingCommands";
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
  build(input: { reason: string; grade: "GENERAL" | "MAJOR" }): FindingCommand;
}

/** 契约已有、前端已接线的动作。按钮出现与否只是呈现，服务端对身份、职责、状态、版本、轮次最终校验。 */
const ACTIONS: Partial<Record<FindingState, readonly ActionDef[]>> = {
  DISCOVERED: [{ id: "init", label: "启动确认流程", build: () => ({ kind: "initialize" }) }],
  PERSON_CONFIRM_PENDING: [
    { id: "person", label: "确认发现", reason: "required", build: ({ reason }) => ({ kind: "confirmPerson", reason }) },
  ],
  SAFETY_CONFIRM_PENDING: [
    {
      id: "safety",
      label: "安全确认",
      reason: "required",
      grade: true,
      build: ({ reason, grade }) => ({ kind: "confirmSafety", reason, grade }),
    },
  ],
  ACCEPTANCE_PENDING: [
    { id: "pass", label: "验收通过", reason: "optional", build: ({ reason }) => ({ kind: "verify", decision: "PASS", ...(reason ? { reason } : {}) }) },
    { id: "fail", label: "退回整改", reason: "required", build: ({ reason }) => ({ kind: "verify", decision: "FAIL", reason }) },
  ],
};

/** 契约暂缺或后端未实现的动作：保持禁用并写明原因，不假装可用。 */
const UNAVAILABLE: Partial<Record<FindingState, readonly { label: string; reason: string }[]>> = {
  PERSON_CONFIRM_PENDING: [{ label: "退回补充", reason: "后端尚未实现“退回补充”。" }],
  SAFETY_CONFIRM_PENDING: [{ label: "退回第一确认", reason: "后端尚未实现“退回第一确认”。" }],
  SAFETY_DISPATCH_PENDING: [{ label: "分派整改", reason: "派单需要选择整改责任人，契约还没有成员查询接口。" }],
  RECTIFYING: [{ label: "提交整改证据", reason: "提交整改需要先上传证据集，契约还没有证据上传接口。" }],
};

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
  /** 命令成功，或因版本/状态冲突需要刷新详情与列表。 */
  onChanged: () => void;
  onUnauthenticated?: () => void;
}

export function FindingCommandPanel({ finding, adapter, onChanged, onUnauthenticated }: FindingCommandPanelProps) {
  const uid = useId();
  const actions = ACTIONS[finding.state] ?? [];
  const unavailable = UNAVAILABLE[finding.state] ?? [];
  const [activeId, setActiveId] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [grade, setGrade] = useState<"" | "GENERAL" | "MAJOR">("");
  const [errors, setErrors] = useState<{ reason?: string; grade?: string }>({});
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<FindingCommandFailureKind | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const attempt = useRef<{ payload: string; key: string } | null>(null);
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);

  const active = actions.find((action) => action.id === activeId) ?? null;
  const version = finding.version;

  function open(id: string) {
    setActiveId(id);
    setReason("");
    setGrade("");
    setErrors({});
    setFailure(null);
    setDone(null);
    attempt.current = null;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active || pending || version === undefined) return;
    const found: typeof errors = {};
    if (active.reason) {
      if (codePointLength(reason) > REASON_MAX) found.reason = `不能超过 ${REASON_MAX} 个字`;
      else if (active.reason === "required" && !hasVisibleCharacter(reason)) found.reason = "请填写意见";
    }
    if (active.grade && !grade) found.grade = "请选择隐患等级";
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) return;

    const command = active.build({ reason: reason.trim(), grade: grade || "GENERAL" });
    const payload = JSON.stringify([finding.id, version, command]);
    if (attempt.current?.payload !== payload) attempt.current = { payload, key: newIdempotencyKey() };
    const abort = new AbortController();
    controller.current = abort;
    setPending(true);
    try {
      const result = await adapter.run(finding.id, version, command, { idempotencyKey: attempt.current.key, signal: abort.signal });
      if (!mounted.current) return;
      setDone(`已完成“${active.label}”，当前状态：${findingStateLabels[result.state]}。`);
      setActiveId(null);
      attempt.current = null;
      onChanged();
    } catch (error) {
      if (!mounted.current || (error instanceof DOMException && error.name === "AbortError")) return;
      const kind = error instanceof FindingCommandError ? error.kind : "unavailable";
      setFailure(kind);
      if (kind === "unauthenticated") onUnauthenticated?.();
      if (REFRESH_ON.has(kind)) {
        setActiveId(null);
        onChanged();
      }
      if (kind === "idempotencyConflict") attempt.current = null;
    } finally {
      if (mounted.current) setPending(false);
    }
  }

  return (
    <div className="command-panel">
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
            disabled={pending || version === undefined}
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
      </div>
      {unavailable.length > 0 && (
        <p className="field-hint">{unavailable.map((item) => item.reason).join(" ")}</p>
      )}

      {active && (
        <form className="report-form" onSubmit={submit} noValidate aria-busy={pending} aria-label={active.label}>
          {active.grade && (
            <div className="report-field">
              <label htmlFor={`${uid}-grade`}>
                隐患等级<span className="required-mark" aria-hidden="true"> *</span>
              </label>
              <select
                id={`${uid}-grade`}
                value={grade}
                disabled={pending}
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
                意见{active.reason === "required" ? <span className="required-mark" aria-hidden="true"> *</span> : "（选填）"}
              </label>
              <textarea
                id={`${uid}-reason`}
                rows={3}
                value={reason}
                disabled={pending}
                aria-invalid={Boolean(errors.reason)}
                onChange={(event) => setReason(event.target.value)}
              />
              {errors.reason && <p className="field-error">{errors.reason}</p>}
            </div>
          )}
          <div className="report-actions">
            <button type="button" className="button button-subtle" disabled={pending} onClick={() => setActiveId(null)}>
              取消
            </button>
            <button type="submit" className="button button-primary" disabled={pending}>
              {pending ? "提交中…" : failure === "unavailable" ? "重试" : `确认${active.label}`}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
