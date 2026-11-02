import type { FindingUrgency, FindingSource } from "../domain/finding";
import {
  findingSourceLabels,
  findingSources,
  findingUrgencies,
  findingUrgencyLabels,
} from "../domain/finding";
import {
  DRAFT_LIMITS,
  codePointLength,
  formatLocalDateTime,
  isBackfill,
  type FindingDraft,
  type FindingDraftErrors,
  type FindingDraftField,
} from "../domain/findingDraft";
import { NotWiredTag, ReportField as Field, describedByIds } from "./ReportField";

export interface ReportDraftFieldsProps {
  /** 控件 id 前缀，保证同页多个实例不冲突。 */
  idPrefix: string;
  draft: FindingDraft;
  errors: FindingDraftErrors;
  disabled: boolean;
  /** 真实后端：不显示“后端暂未接入”标签。 */
  live: boolean;
  /** 服务端接受的紧急程度；缺省为全部。 */
  urgencies?: readonly FindingUrgency[];
  now: () => Date;
  update: <K extends FindingDraftField>(key: K, value: FindingDraft[K]) => void;
  /** 登记控件，用于提交失败后把焦点交给第一个出错字段。 */
  fieldRef: (field: FindingDraftField) => (node: HTMLElement | null) => void;
}

/** 上报表单的六个草稿字段；校验与提交流程在 FindingReportForm。 */
export function ReportDraftFields({ idPrefix, draft, errors, disabled, live, urgencies, now, update, fieldRef }: ReportDraftFieldsProps) {
  const ids = {
    source: `${idPrefix}-source`,
    foundAt: `${idPrefix}-foundAt`,
    location: `${idPrefix}-location`,
    description: `${idPrefix}-description`,
    urgency: `${idPrefix}-urgency`,
    interimMeasure: `${idPrefix}-interim`,
  } as const;
  const describedBy = (field: FindingDraftField, hasHint = false) =>
    describedByIds(ids[field], { hint: hasHint, error: Boolean(errors[field]) });
  const submitting = disabled;

  return (
    <>
        <Field id={ids.source} label="来源" required notWired={!live} error={errors.source}>
          <select
            id={ids.source}
            ref={fieldRef("source")}
            value={draft.source}
            aria-invalid={Boolean(errors.source)}
            aria-describedby={describedBy("source")}
            disabled={submitting}
            onChange={(event) => update("source", event.target.value as FindingSource)}
          >
            {findingSources.map((source) => (
              <option key={source} value={source}>
                {findingSourceLabels[source]}
              </option>
            ))}
          </select>
        </Field>

        <Field
          id={ids.foundAt}
          label="发现时间"
          required
          error={errors.foundAt}
          hint={
            isBackfill(draft.foundAt, now())
              ? "发现时间早于当前时间超过 24 小时，将按补报处理：发现时间和上报时间会分别保存。"
              : "按北京时间（Asia/Shanghai）填写，与你的电脑时区无关。"
          }
        >
          <input
            id={ids.foundAt}
            ref={fieldRef("foundAt")}
            type="datetime-local"
            value={draft.foundAt}
            max={formatLocalDateTime(now())}
            aria-invalid={Boolean(errors.foundAt)}
            aria-describedby={describedBy("foundAt", true)}
            disabled={submitting}
            onChange={(event) => update("foundAt", event.target.value)}
          />
        </Field>

        <Field id={ids.location} label="所在位置 / 设备" required notWired={!live} error={errors.location}>
          <input
            id={ids.location}
            ref={fieldRef("location")}
            type="text"
            value={draft.location}
            maxLength={DRAFT_LIMITS.locationMax + 20}
            aria-invalid={Boolean(errors.location)}
            aria-describedby={describedBy("location")}
            disabled={submitting}
            onChange={(event) => update("location", event.target.value)}
          />
        </Field>

        <Field
          id={ids.description}
          label="隐患描述"
          required
          error={errors.description}
          hint={`写清楚看到了什么、在哪里。${codePointLength(draft.description.trim())}/${DRAFT_LIMITS.descriptionMax}`}
        >
          <textarea
            id={ids.description}
            ref={fieldRef("description")}
            rows={4}
            value={draft.description}
            aria-invalid={Boolean(errors.description)}
            aria-describedby={describedBy("description", true)}
            disabled={submitting}
            onChange={(event) => update("description", event.target.value)}
          />
        </Field>

        <fieldset
          className="report-field report-radios"
          aria-describedby={describedBy("urgency")}
          aria-invalid={Boolean(errors.urgency)}
          disabled={submitting}
        >
          <legend>
            紧急程度<span className="required-mark" aria-hidden="true"> *</span>
            {!live && <NotWiredTag />}
          </legend>
          <div className="report-radio-row">
            {(urgencies ?? findingUrgencies).map((urgency, index) => (
              <label key={urgency} className="report-radio">
                <input
                  type="radio"
                  name={ids.urgency}
                  value={urgency}
                  checked={draft.urgency === urgency}
                  ref={index === 0 ? fieldRef("urgency") : undefined}
                  onChange={() => update("urgency", urgency as FindingUrgency)}
                />
                {findingUrgencyLabels[urgency]}
              </label>
            ))}
          </div>
          {errors.urgency && (
            <p className="field-error" id={`${ids.urgency}-error`}>
              {errors.urgency}
            </p>
          )}
          {draft.urgency === "IMMEDIATE" && (
            <p className="report-warning" role="note">
              选择“需立即处置”不会触发任何通知或呼叫。请先通过现场应急渠道报告。
            </p>
          )}
        </fieldset>

        <Field
          id={ids.interimMeasure}
          label="已采取的临时措施（选填）"
          notWired={!live}
          error={errors.interimMeasure}
          hint={`${codePointLength(draft.interimMeasure.trim())}/${DRAFT_LIMITS.interimMeasureMax}`}
        >
          <textarea
            id={ids.interimMeasure}
            ref={fieldRef("interimMeasure")}
            rows={2}
            value={draft.interimMeasure}
            aria-invalid={Boolean(errors.interimMeasure)}
            aria-describedby={describedBy("interimMeasure", true)}
            disabled={submitting}
            onChange={(event) => update("interimMeasure", event.target.value)}
          />
        </Field>

    </>
  );
}
