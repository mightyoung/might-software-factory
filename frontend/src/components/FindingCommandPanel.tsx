import { useId, type FormEvent } from "react";
import type { FindingCommandAdapter } from "../adapters/findingCommands";
import type { FindingRecord } from "../domain/finding";
import { DispatchCandidatePicker } from "./DispatchCandidatePicker";
import { BLOCKED, FAILURE_MESSAGE, type ActionDef, type CommandInputErrors, type DispatchErrors, type DispatchValues } from "../features/findings/commandActions";
import { FindingReviewHistory } from "./FindingReviewHistory";
import { useFindingCommandRunner } from "../features/findings/useFindingCommandRunner";

export interface FindingCommandPanelProps {
  finding: FindingRecord;
  adapter: FindingCommandAdapter;
  /** 详情正在重新读取：此时屏幕上的版本可能已过期，暂不允许提交。 */
  refreshing?: boolean;
  /** 命令成功，或因版本/状态冲突需要刷新详情与列表。 */
  onChanged: () => void;
  onUnauthenticated?: () => void;
  onForbidden?: () => void;
  readBlocked?: boolean;
}

interface FormProps {
  action: ActionDef;
  reason: string;
  grade: string;
  errors: CommandInputErrors;
  busy: boolean;
  retry: boolean;
  canSubmit: boolean;
  fieldsBlocked: boolean;
  onReason: (value: string) => void;
  onGrade: (value: string) => void;
  onCancel: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  /** 派单用的表单状态与候选查询；非派单动作不用。 */
  dispatch?: {
    finding: FindingRecord;
    adapter: FindingCommandAdapter;
    values: DispatchValues;
    errors: DispatchErrors;
    onChange: (values: DispatchValues) => void;
    onStale: () => void;
    onUnauthenticated?: () => void;
  };
}

function CommandForm({ action, reason, grade, errors, busy, retry, canSubmit, fieldsBlocked, onReason, onGrade, onCancel, onSubmit, dispatch }: FormProps) {
  const uid = useId();
  return (
    <form className="report-form" onSubmit={onSubmit} noValidate aria-busy={busy} aria-label={action.label}>
      {action.grade && (
        <div className="report-field">
          <label htmlFor={`${uid}-grade`}>
            隐患等级<span className="required-mark" aria-hidden="true"> *</span>
          </label>
          <select id={`${uid}-grade`} value={grade} disabled={busy || fieldsBlocked} aria-invalid={Boolean(errors.grade)} onChange={(event) => onGrade(event.target.value)}>
            <option value="">请选择</option>
            <option value="GENERAL">一般</option>
            <option value="MAJOR">重大</option>
          </select>
          {errors.grade && <p className="field-error">{errors.grade}</p>}
        </div>
      )}
      {action.dispatch && dispatch && (
        <>
          <DispatchCandidatePicker
            findingId={dispatch.finding.id}
            findingVersion={dispatch.finding.version}
            adapter={dispatch.adapter}
            value={dispatch.values.assigneeMemberId}
            onChange={(id) => dispatch.onChange({ ...dispatch.values, assigneeMemberId: id })}
            error={dispatch.errors.assignee}
            disabled={busy || fieldsBlocked}
            onStale={dispatch.onStale}
            onUnauthenticated={dispatch.onUnauthenticated}
          />
          <div className="report-field">
            <label htmlFor={`${uid}-dueAt`}>整改期限<span className="required-mark" aria-hidden="true"> *</span></label>
            <input id={`${uid}-dueAt`} type="datetime-local" value={dispatch.values.dueAt} disabled={busy || fieldsBlocked} aria-invalid={Boolean(dispatch.errors.dueAt)} onChange={(event) => dispatch.onChange({ ...dispatch.values, dueAt: event.target.value })} />
            <p className="field-hint">按北京时间（Asia/Shanghai）填写，与你的电脑时区无关。</p>
            {dispatch.errors.dueAt && <p className="field-error">{dispatch.errors.dueAt}</p>}
          </div>
          <div className="report-field">
            <label htmlFor={`${uid}-requirements`}>整改要求<span className="required-mark" aria-hidden="true"> *</span></label>
            <textarea id={`${uid}-requirements`} rows={3} value={dispatch.values.requirements} disabled={busy || fieldsBlocked} aria-invalid={Boolean(dispatch.errors.requirements)} onChange={(event) => dispatch.onChange({ ...dispatch.values, requirements: event.target.value })} />
            {dispatch.errors.requirements && <p className="field-error">{dispatch.errors.requirements}</p>}
          </div>
        </>
      )}
      {action.reason && (
        <div className="report-field">
          <label htmlFor={`${uid}-reason`}>
            {action.supplement ? "补充说明" : "意见"}{action.reason === "required" ? <span className="required-mark" aria-hidden="true"> *</span> : "（选填）"}
          </label>
          <textarea id={`${uid}-reason`} rows={3} value={reason} disabled={busy || fieldsBlocked} aria-invalid={Boolean(errors.reason)} onChange={(event) => onReason(event.target.value)} />
          {errors.reason && <p className="field-error">{errors.reason}</p>}
        </div>
      )}
      <div className="report-actions">
        <button type="button" className="button button-subtle" disabled={busy || fieldsBlocked} onClick={onCancel}>
          取消
        </button>
        {!canSubmit&&<p role="note">草稿已保留。请读取最新状态并选择当前可用操作后提交。</p>}
        <button type="submit" className="button button-primary" disabled={busy || !canSubmit}>
          {busy ? "提交中…" : retry ? "重试" : `确认${action.label}`}
        </button>
      </div>
    </form>
  );
}

export function FindingCommandPanel({ finding, adapter, refreshing = false, onChanged, onUnauthenticated, onForbidden, readBlocked=false }: FindingCommandPanelProps) {
  const run = useFindingCommandRunner({ finding, adapter, onChanged, onUnauthenticated, onForbidden, readBlocked, refreshing });
  const { actions, unavailable } = run;
  const noVersion = run.version === undefined;
  const blocked = run.pending || refreshing || noVersion || readBlocked || run.uncertain;

  return (
    <div className="command-panel">
      {adapter.history && <>
        {run.historyLoading && <p role="status">正在读取确认记录与可用操作…</p>}
        {run.historyFailure && <div role="alert"><p>无法读取确认记录，请重试。草稿已保留。</p><button className="button button-subtle" onClick={run.refreshHistory}>重新读取确认记录</button></div>}
        {run.history && <><p>当前为第 {run.history.reviewCycle} 次确认</p><FindingReviewHistory entries={run.history.entries} />{run.history.hasMore && <button className="button button-subtle" disabled={run.pagePending || run.historyLoading} onClick={run.loadMore}>{run.pagePending ? "读取中…" : "读取更多记录"}</button>}</>}
        {!run.historyLoading && !run.historyFailure && run.history?.blockedReason && <p role="note">{BLOCKED[run.history.blockedReason]}</p>}
        {!run.historyLoading && !run.historyFailure && run.history && !run.history.blockedReason && ["PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING", "REPORTER_SUPPLEMENT_PENDING"].includes(run.history?.currentState ?? finding.state) && actions.length === 0 && <p role="note">当前流程暂不支持此操作，请联系管理员。</p>}
      </>}
      {run.done && <p className="report-success" role="status">{run.done}</p>}
      {run.failure && <p className="report-summary" role="alert">{FAILURE_MESSAGE[run.failure]}</p>}
      {noVersion && actions.length > 0 && <p className="action-hint" role="note">这条记录没有版本号，无法安全地执行流程操作。</p>}
      {refreshing && <p className="field-hint" role="status">正在重新读取最新状态…</p>}
      <div className="detail-actions">
        {actions.map((action) => (
          <button
            key={action.id}
            type="button"
            className={`button ${run.active?.id === action.id ? "button-subtle" : "button-primary"}`}
            disabled={blocked}
            aria-pressed={run.active?.id === action.id}
            onClick={() => run.open(action.id)}
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
      {unavailable.length > 0 && <p className="field-hint">{unavailable.map((item) => item.reason).join(" ")}</p>}
      {run.active && (
        <CommandForm
          action={run.active}
          reason={run.reason}
          grade={run.grade}
          errors={run.errors}
          busy={run.pending || refreshing || run.historyLoading}
          fieldsBlocked={run.uncertain}
          canSubmit={run.canSubmit}
          retry={run.failure === "unavailable"}
          onReason={run.setReason}
          onGrade={run.setGrade}
          onCancel={run.cancel}
          onSubmit={run.submit}
          dispatch={{ finding, adapter, values: run.dispatch, errors: run.dispatchErrors, onChange: run.setDispatch, onStale: onChanged, onUnauthenticated }}
        />
      )}
    </div>
  );
}
