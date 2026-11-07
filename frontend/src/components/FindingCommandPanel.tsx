import { useId, type FormEvent } from "react";
import type { FindingCommandAdapter } from "../adapters/findingCommands";
import type { FindingRecord } from "../domain/finding";
import {
  ACTIONS,
  FAILURE_MESSAGE,
  UNAVAILABLE,
  type ActionDef,
  type CommandErrors,
  type CommandValues,
  type FieldKey,
} from "../features/findings/commandActions";
import { useFindingCommandRunner } from "../features/findings/useFindingCommandRunner";
import { DispatchCandidatePicker } from "./DispatchCandidatePicker";

export interface FindingCommandPanelProps {
  finding: FindingRecord;
  adapter: FindingCommandAdapter;
  /** 详情正在重新读取：此时屏幕上的版本可能已过期，暂不允许提交。 */
  refreshing?: boolean;
  /** 命令成功，或因版本/状态冲突需要刷新详情与列表。 */
  onChanged: () => void;
  onUnauthenticated?: () => void;
}

interface FormProps {
  action: ActionDef;
  finding: FindingRecord;
  adapter: FindingCommandAdapter;
  values: CommandValues;
  errors: CommandErrors;
  busy: boolean;
  retry: boolean;
  onValue: <K extends keyof CommandValues>(key: K, value: CommandValues[K]) => void;
  onCancel: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onStale: () => void;
  onUnauthenticated?: () => void;
}

interface FieldProps extends FormProps {
  spec: { key: FieldKey; required: boolean };
  uid: string;
}

const REQUIRED_MARK = <span className="required-mark" aria-hidden="true"> *</span>;

function GradeField({ values, errors, busy, onValue, uid }: FieldProps) {
  return (
    <div className="report-field">
      <label htmlFor={`${uid}-grade`}>隐患等级{REQUIRED_MARK}</label>
      <select id={`${uid}-grade`} value={values.grade} disabled={busy} aria-invalid={Boolean(errors.grade)} onChange={(event) => onValue("grade", event.target.value as CommandValues["grade"])}>
        <option value="">请选择</option>
        <option value="GENERAL">一般</option>
        <option value="MAJOR">重大</option>
      </select>
      {errors.grade && <p className="field-error">{errors.grade}</p>}
    </div>
  );
}

function TextField({ spec, values, errors, busy, onValue, uid }: FieldProps) {
  const key = spec.key === "reason" ? "reason" : "requirements";
  const label = key === "reason" ? "意见" : "整改要求";
  return (
    <div className="report-field">
      <label htmlFor={`${uid}-${key}`}>
        {label}
        {spec.required ? REQUIRED_MARK : "（选填）"}
      </label>
      <textarea id={`${uid}-${key}`} rows={3} value={values[key]} disabled={busy} aria-invalid={Boolean(errors[key])} onChange={(event) => onValue(key, event.target.value)} />
      {errors[key] && <p className="field-error">{errors[key]}</p>}
    </div>
  );
}

function DueField({ values, errors, busy, onValue, uid }: FieldProps) {
  return (
    <div className="report-field">
      <label htmlFor={`${uid}-dueAt`}>整改期限{REQUIRED_MARK}</label>
      <input id={`${uid}-dueAt`} type="datetime-local" value={values.dueAt} disabled={busy} aria-invalid={Boolean(errors.dueAt)} onChange={(event) => onValue("dueAt", event.target.value)} />
      <p className="field-hint">按北京时间（Asia/Shanghai）填写，与你的电脑时区无关。</p>
      {errors.dueAt && <p className="field-error">{errors.dueAt}</p>}
    </div>
  );
}

function AssigneeField(props: FieldProps) {
  const { finding, adapter, values, errors, busy, onValue, onStale, onUnauthenticated } = props;
  return (
    <DispatchCandidatePicker
      findingId={finding.id}
      findingVersion={finding.version}
      adapter={adapter}
      value={values.assigneeMemberId}
      onChange={(id) => onValue("assigneeMemberId", id)}
      error={errors.assignee}
      disabled={busy}
      onStale={onStale}
      onUnauthenticated={onUnauthenticated}
    />
  );
}

function CommandField(props: FieldProps) {
  switch (props.spec.key) {
    case "grade":
      return <GradeField {...props} />;
    case "dueAt":
      return <DueField {...props} />;
    case "assignee":
      return <AssigneeField {...props} />;
    default:
      return <TextField {...props} />;
  }
}

function CommandForm(props: FormProps) {
  const uid = useId();
  const { action, busy, retry, onCancel, onSubmit } = props;
  return (
    <form className="report-form" onSubmit={onSubmit} noValidate aria-busy={busy} aria-label={action.label}>
      {action.fields.map((spec) => (
        <CommandField key={spec.key} {...props} spec={spec} uid={uid} />
      ))}
      <div className="report-actions">
        <button type="button" className="button button-subtle" disabled={busy} onClick={onCancel}>
          取消
        </button>
        <button type="submit" className="button button-primary" disabled={busy}>
          {busy ? "提交中…" : retry ? "重试" : `确认${action.label}`}
        </button>
      </div>
    </form>
  );
}

/** 需要候选查询的动作在适配器没有此能力时不可用，并说明原因，不假装可用。 */
function missingCapability(action: ActionDef, adapter: FindingCommandAdapter): string | null {
  const needsCandidates = action.fields.some((field) => field.key === "assignee");
  return needsCandidates && !adapter.searchDispatchCandidates ? "当前连接没有派单候选查询能力。" : null;
}

export function FindingCommandPanel({ finding, adapter, refreshing = false, onChanged, onUnauthenticated }: FindingCommandPanelProps) {
  const run = useFindingCommandRunner({ finding, adapter, onChanged, onUnauthenticated });
  const actions = ACTIONS[finding.state] ?? [];
  const unavailable = UNAVAILABLE[finding.state] ?? [];
  const noVersion = finding.version === undefined;
  const blocked = run.pending || refreshing || noVersion;
  const terminal = finding.state === "CLOSED" || finding.state === "NOT_ESTABLISHED";

  return (
    <div className="command-panel">
      {run.done && <p className="report-success" role="status">{run.done}</p>}
      {run.failure && <p className="report-summary" role="alert">{FAILURE_MESSAGE[run.failure]}</p>}
      {noVersion && actions.length > 0 && <p className="action-hint" role="note">这条记录没有版本号，无法安全地执行流程操作。</p>}
      {refreshing && <p className="field-hint" role="status">正在重新读取最新状态…</p>}
      <div className="detail-actions">
        {actions.map((action) => {
          const missing = missingCapability(action, adapter);
          return (
            <button
              key={action.id}
              type="button"
              className={`button ${run.active?.id === action.id ? "button-subtle" : "button-primary"}`}
              disabled={blocked || missing !== null}
              title={missing ?? undefined}
              aria-pressed={run.active?.id === action.id}
              onClick={() => run.open(action.id)}
            >
              {action.label}
            </button>
          );
        })}
        {unavailable.map((item) => (
          <button key={item.label} type="button" className="button button-disabled" disabled title={item.reason}>
            {item.label}
          </button>
        ))}
        {finding.state === "CLOSED" && <span className="closed-note">已闭环，不可再变更</span>}
        {finding.state === "NOT_ESTABLISHED" && <span className="closed-note">未成立记录已终止；新证据应创建关联记录</span>}
      </div>
      {unavailable.length > 0 && <p className="field-hint">{unavailable.map((item) => item.reason).join(" ")}</p>}
      {run.active && !terminal && (
        <CommandForm
          action={run.active}
          finding={finding}
          adapter={adapter}
          values={run.values}
          errors={run.errors}
          busy={run.pending || refreshing}
          retry={run.failure === "unavailable"}
          onValue={run.setValue}
          onCancel={run.cancel}
          onSubmit={run.submit}
          onStale={onChanged}
          onUnauthenticated={onUnauthenticated}
        />
      )}
    </div>
  );
}
