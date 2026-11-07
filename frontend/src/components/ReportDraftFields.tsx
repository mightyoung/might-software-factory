import { Fragment, type ReactNode } from "react";
import { publishedInputLength, type FindingDefinition } from "../adapters/findingDefinition";
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
  definition?: FindingDefinition | null;
  organization?: ReactNode;
  now: () => Date;
  update: <K extends FindingDraftField>(key: K, value: FindingDraft[K]) => void;
  /** 登记控件，用于提交失败后把焦点交给第一个出错字段。 */
  fieldRef: (field: FindingDraftField) => (node: HTMLElement | null) => void;
}


export function ReportDraftFields({ idPrefix,draft,errors,disabled,live,urgencies:allowedUrgencies,definition=null,organization,now,update,fieldRef }:ReportDraftFieldsProps) {
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

  const label = (key: string, fallback: string) => definition?.fields[key]?.presentation.label ?? fallback;
  const required = (key: string, fallback: boolean) => definition?.fields[key]?.presentation.edit?.required ?? fallback;
  const limit = (key: string, fallback: number) => definition?.fields[key]?.presentation.edit?.maxLength ?? fallback;
  const length = (key: string, value: string) => definition ? publishedInputLength(value, definition.fields[key]!) : codePointLength(value);
  const htmlMaxLength = (key: string) => {
    const hints = definition?.fields[key]?.presentation.edit;
    return hints?.lengthUnit === "UTF16_CODE_UNITS" && hints.maxLength > 0 ? hints.maxLength : undefined;
  };
  const valueLabel = (key: string, value: string, fallback: string) => definition?.fields[key]?.presentation.valueLabels[value] ?? fallback;
  const sources = definition ? definition.fields.sourceCategory!.presentation.edit!.allowedValues as FindingSource[] : findingSources;
  const urgencies: readonly FindingUrgency[] = definition
    ? definition.fields.urgency!.presentation.edit!.allowedValues.map((value) => value === "URGENT" ? "IMMEDIATE" : value) as FindingUrgency[] : allowedUrgencies ?? findingUrgencies;

  function renderControl(key: string): ReactNode {
    switch (key) {
      case "organizationId": return organization ?? null;
      case "sourceCategory": return (<Field id={ids.source} label={label("sourceCategory", "来源")} required={required("sourceCategory", true)} notWired={!live} error={errors.source}>
          <select
            id={ids.source}
            ref={fieldRef("source")}
            value={draft.source}
            aria-invalid={Boolean(errors.source)}
            aria-describedby={describedBy("source")}
            disabled={submitting}
            onChange={(event) => update("source", event.target.value as FindingSource)}
          >
            {sources.map((source) => (
              <option key={source} value={source}>
                {valueLabel("sourceCategory", source, findingSourceLabels[source])}
              </option>
            ))}
          </select>
        </Field>);
      case "discoveredAt": return (<Field
          id={ids.foundAt}
          label={label("discoveredAt", "发现时间")}
          required={required("discoveredAt", true)}
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
        </Field>);
      case "location": return (<Field id={ids.location} label={label("location", "所在位置 / 设备")} required={required("location", true)} notWired={!live} error={errors.location}>
          <input
            id={ids.location}
            ref={fieldRef("location")}
            type="text"
            value={draft.location}
            maxLength={definition ? htmlMaxLength("location") : DRAFT_LIMITS.locationMax + 20}
            aria-invalid={Boolean(errors.location)}
            aria-describedby={describedBy("location")}
            disabled={submitting}
            onChange={(event) => update("location", event.target.value)}
          />
        </Field>);
      case "description": return (<Field
          id={ids.description}
          label={label("description", "隐患描述")}
          required={required("description", true)}
          error={errors.description}
          hint={`写清楚看到了什么、在哪里。${length("description", draft.description.trim())}/${limit("description", DRAFT_LIMITS.descriptionMax)}`}
        >
          <textarea
            id={ids.description}
            ref={fieldRef("description")}
            rows={4}
            maxLength={definition ? htmlMaxLength("description") : undefined}
            value={draft.description}
            aria-invalid={Boolean(errors.description)}
            aria-describedby={describedBy("description", true)}
            disabled={submitting}
            onChange={(event) => update("description", event.target.value)}
          />
        </Field>);
      case "urgency": return (<fieldset
          className="report-field report-radios"
          aria-describedby={describedBy("urgency")}
          aria-invalid={Boolean(errors.urgency)}
          disabled={submitting}
        >
          <legend>
            {label("urgency", "紧急程度")}{required("urgency", true) && <span className="required-mark" aria-hidden="true"> *</span>}
            {!live && <NotWiredTag />}
          </legend>
          <div className="report-radio-row">
            {urgencies.map((urgency, index) => (
              <label key={urgency} className="report-radio">
                <input
                  type="radio"
                  name={ids.urgency}
                  value={urgency}
                  checked={draft.urgency === urgency}
                  ref={
                    index === 0
                      ? fieldRef("urgency")
                      : undefined
                  }
                  onChange={() => update("urgency", urgency as FindingUrgency)}
                />
                {valueLabel("urgency", urgency === "IMMEDIATE" ? "URGENT" : urgency, findingUrgencyLabels[urgency])}
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
        </fieldset>);
      case "interimMeasure": return (<Field
          id={ids.interimMeasure}
          label={label("interimMeasure", "已采取的临时措施（选填）")}
          required={required("interimMeasure", false)} notWired={!live}
          error={errors.interimMeasure}
          hint={`${length("interimMeasure", draft.interimMeasure.trim())}/${limit("interimMeasure", DRAFT_LIMITS.interimMeasureMax)}`}
        >
          <textarea
            id={ids.interimMeasure}
            ref={fieldRef("interimMeasure")}
            rows={2}
            maxLength={definition ? htmlMaxLength("interimMeasure") : undefined}
            value={draft.interimMeasure}
            aria-invalid={Boolean(errors.interimMeasure)}
            aria-describedby={describedBy("interimMeasure", true)}
            disabled={submitting}
            onChange={(event) => update("interimMeasure", event.target.value)}
          />
        </Field>);
      default: return null;
    }
  }


  return <>{(definition?.layout.create ?? ["organizationId","sourceCategory","discoveredAt","location","description","urgency","interimMeasure"]).map(key=><Fragment key={key}>{renderControl(key)}</Fragment>)}</>;
}
