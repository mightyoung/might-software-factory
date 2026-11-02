import { useEffect, useId, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from "react";
import { useAppMode } from "../mode";
import {
  FindingSubmitError,
  newIdempotencyKey,
  type FindingSubmitAdapter,
  type FindingSubmitFailureKind,
  type FindingSubmitResult,
  type ReportableOrganization,
} from "../adapters/findingSubmit";
import {
  findingSourceLabels,
  findingSources,
  findingUrgencies,
  findingUrgencyLabels,
  type FindingSource,
  type FindingUrgency,
} from "../domain/finding";
import {
  DRAFT_FIELD_ORDER,
  DRAFT_LIMITS,
  codePointLength,
  createEmptyDraft,
  formatLocalDateTime,
  hasDraftErrors,
  isBackfill,
  normalizeDraft,
  validateFindingDraft,
  type FindingDraft,
  type FindingDraftErrors,
  type FindingDraftField,
} from "../domain/findingDraft";

export interface FindingReportFormProps {
  adapter: FindingSubmitAdapter;
  /** 关闭面板：用户放弃（已确认）或提交成功后选择关闭。 */
  onClose: () => void;
  /** 提交成功：用于让台账刷新列表。 */
  onSubmitted: (result: FindingSubmitResult) => void;
  onOpenSubmitted: (id: string) => void;
  /** 服务端判定未登录（401）：由应用回到登录页。 */
  onUnauthenticated?: () => void;
  now?: () => Date;
  createKey?: () => string;
}

type Phase = { name: "editing" } | { name: "submitting" } | { name: "submitted"; result: FindingSubmitResult };

const SUBMIT_FAILURE_MESSAGE: Record<FindingSubmitFailureKind, string> = {
  validation: "服务端校验未通过，请修正标出的内容后再提交。",
  workflow: "当前无法进入确认流程（例如没有可用的确认人），请联系管理员处理。",
  unauthenticated: "登录已失效，请重新登录。",
  forbidden: "你没有提交隐患上报的权限。",
  conflict: "这次提交与之前的一次提交内容不一致，已被拒绝。请关闭面板后重新上报。",
  unavailable: "服务暂时不可用，上报尚未确认成功。可以直接重试，重试不会重复创建记录。",
};

/**
 * 当前后端的创建接口没有这些字段（来源、所在位置、紧急程度、临时措施；见 docs/frontend-contract-gap.md）。
 * 后端要求：不要塞进描述，也不要提交后静默丢弃，未接线的项要明确标识。
 */
const NOT_WIRED_TAG = "后端暂未接入";

function NotWiredTag() {
  return <span className="inline-tag"> {NOT_WIRED_TAG}</span>;
}

function Field({
  id,
  label,
  error,
  hint,
  required,
  notWired,
  children,
}: {
  id: string;
  label: string;
  error?: string;
  hint?: ReactNode;
  required?: boolean;
  /** 当前后端没有对应字段，真实写入后不会被保存。 */
  notWired?: boolean;
  children: ReactNode;
}) {
  return (
    <div className="report-field">
      <label htmlFor={id}>
        {label}
        {required && <span className="required-mark" aria-hidden="true"> *</span>}
        {notWired && <NotWiredTag />}
      </label>
      {children}
      {hint && <p className="field-hint" id={`${id}-hint`}>{hint}</p>}
      {error && (
        <p className="field-error" id={`${id}-error`}>
          {error}
        </p>
      )}
    </div>
  );
}

export function FindingReportForm({
  adapter,
  onClose,
  onSubmitted,
  onOpenSubmitted,
  onUnauthenticated,
  now = () => new Date(),
  createKey = newIdempotencyKey,
}: FindingReportFormProps) {
  const uid = useId();
  const ids = {
    source: `${uid}-source`,
    foundAt: `${uid}-foundAt`,
    location: `${uid}-location`,
    description: `${uid}-description`,
    urgency: `${uid}-urgency`,
    interimMeasure: `${uid}-interim`,
  } as const;

  const [initial] = useState(() => createEmptyDraft(now()));
  const [draft, setDraft] = useState<FindingDraft>(initial);
  const [errors, setErrors] = useState<FindingDraftErrors>({});
  const [phase, setPhase] = useState<Phase>({ name: "editing" });
  const [submitFailure, setSubmitFailure] = useState<FindingSubmitFailureKind | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  // 组织选择：仅当适配器提供 listOrganizations 时启用。
  const needsOrganization = adapter.listOrganizations !== undefined;
  const live = useAppMode() === "http";
  const [orgState, setOrgState] = useState<
    { status: "loading" } | { status: "error" } | { status: "ready"; items: readonly ReportableOrganization[] }
  >({ status: "loading" });
  const [organizationId, setOrganizationId] = useState("");
  const [orgError, setOrgError] = useState<string | null>(null);
  const [orgReloadTick, setOrgReloadTick] = useState(0);
  const [pendingFocus, setPendingFocus] = useState<FindingDraftField | null>(null);

  const fieldRefs = useRef<Partial<Record<FindingDraftField, HTMLElement | null>>>({});
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);
  // 同一份内容的重试复用同一个幂等键；内容变化才生成新键。
  const lastAttempt = useRef<{ payload: string; key: string } | null>(null);

  const submitting = phase.name === "submitting";
  const dirty = JSON.stringify(normalizeDraft(draft)) !== JSON.stringify(normalizeDraft(initial));
  const errorCount = Object.keys(errors).length;

  useEffect(() => {
    mounted.current = true;
    fieldRefs.current.source?.focus();
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);

  useEffect(() => {
    if (!adapter.listOrganizations) return;
    const abort = new AbortController();
    setOrgState({ status: "loading" });
    adapter
      .listOrganizations(abort.signal)
      .then((items) => {
        setOrgState({ status: "ready", items });
        // 只有一个可选组织时直接选中，仍显示出来。
        if (items.length === 1) setOrganizationId(items[0]!.id);
      })
      .catch((error: unknown) => {
        if (abort.signal.aborted) return;
        if (error instanceof FindingSubmitError && error.kind === "unauthenticated") onUnauthenticated?.();
        setOrgState({ status: "error" });
      });
    return () => abort.abort();
    // onUnauthenticated 是回调属性，不作为重新加载的依据。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adapter, orgReloadTick]);

  // 字段在提交期间是禁用的，禁用元素无法获得焦点：等重新渲染成可编辑后再聚焦出错字段。
  // 用 layout effect 在提交阶段同步聚焦，焦点不会比错误文字晚一帧出现。
  useLayoutEffect(() => {
    if (!pendingFocus || phase.name !== "editing") return;
    fieldRefs.current[pendingFocus]?.focus();
    setPendingFocus(null);
  }, [pendingFocus, phase.name]);

  // 有未提交内容时，刷新或关闭标签页由浏览器给出原生确认。
  // 用 useLayoutEffect：守卫要和成功页的 DOM 在同一次提交里同步解除；被动 effect 的清理会晚于 DOM，
  // 留下“页面已显示提交成功，关闭标签页却仍被拦截”的窗口。
  useLayoutEffect(() => {
    if (!dirty || phase.name === "submitted") return;
    function onBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
    }
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty, phase.name]);

  function update<K extends FindingDraftField>(key: K, value: FindingDraft[K]) {
    setDraft((current) => ({ ...current, [key]: value }));
    // 编辑某个字段后清除它自己的错误，其他字段的错误保留到下次提交。
    setErrors((current) => {
      if (!(key in current)) return current;
      const { [key]: _removed, ...rest } = current;
      return rest;
    });
  }

  function focusFirstError(next: FindingDraftErrors) {
    const first = DRAFT_FIELD_ORDER.find((field) => next[field]);
    if (first) setPendingFocus(first);
  }

  function requestClose() {
    if (submitting) return;
    if (dirty && phase.name === "editing") setConfirmDiscard(true);
    else onClose();
  }

  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "Escape") {
      event.stopPropagation();
      requestClose();
    }
  }

  function keyFor(payload: string): string {
    if (lastAttempt.current?.payload === payload) return lastAttempt.current.key;
    const key = createKey();
    lastAttempt.current = { payload, key };
    return key;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting || controller.current) return;

    const found = validateFindingDraft(draft, now());
    setErrors(found);
    setSubmitFailure(null);
    if (needsOrganization && !organizationId) setOrgError("请选择所属组织");
    if (hasDraftErrors(found)) {
      focusFirstError(found);
      return;
    }
    if (needsOrganization && !organizationId) return;

    const payload = normalizeDraft(draft);
    const abort = new AbortController();
    controller.current = abort;
    setPhase({ name: "submitting" });
    try {
      const result = await adapter.submit(payload, {
        idempotencyKey: keyFor(JSON.stringify({ ...payload, organizationId })),
        ...(needsOrganization ? { organizationId } : {}),
        signal: abort.signal,
      });
      if (!mounted.current) return;
      setPhase({ name: "submitted", result });
      onSubmitted(result);
    } catch (error) {
      if (!mounted.current || (error instanceof DOMException && error.name === "AbortError")) return;
      const kind: FindingSubmitFailureKind = error instanceof FindingSubmitError ? error.kind : "unavailable";
      setPhase({ name: "editing" });
      setSubmitFailure(kind);
      if (kind === "validation" && error instanceof FindingSubmitError) {
        setErrors(error.fieldErrors);
        focusFirstError(error.fieldErrors);
      }
      if (kind === "unauthenticated") onUnauthenticated?.();
    } finally {
      if (controller.current === abort) controller.current = null;
    }
  }

  function startAnother() {
    const fresh = createEmptyDraft(now());
    setDraft(fresh);
    setErrors({});
    setSubmitFailure(null);
    setConfirmDiscard(false);
    lastAttempt.current = null;
    setPhase({ name: "editing" });
    queueMicrotask(() => fieldRefs.current.source?.focus());
  }

  const describedBy = (field: FindingDraftField, hasHint = false) =>
    [hasHint ? `${ids[field]}-hint` : null, errors[field] ? `${ids[field]}-error` : null].filter(Boolean).join(" ") || undefined;

  if (phase.name === "submitted") {
    const { result } = phase;
    return (
      <aside key="submitted" className="detail-panel report-panel" aria-label="新建上报面板" onKeyDown={onKeyDown}>
        <div className="detail-heading">
          <div>
            <h2>新建上报</h2>
            <p>隐患发现{live ? "" : " · 合成演示"}</p>
          </div>
          <button className="icon-button" type="button" aria-label="关闭上报" onClick={onClose}>
            ×
          </button>
        </div>
        <section className="report-success" role="status">
          <h3>已提交上报</h3>
          <p>
            编号 <strong>{result.code}</strong>，当前状态：待一人确认。
          </p>
          {result.backfill && <p>发现时间早于当前时间超过 24 小时，已按补报处理：发现时间和上报时间分别保存。</p>}
          {live ? (
            <p className="field-hint">已写入服务端，由服务端按流程指派确认人；上报人和租户以你的登录身份为准。</p>
          ) : (
            <>
              <p className="field-hint">“来源”“所在位置 / 设备”“紧急程度”“临时措施”标注为后端暂未接入：真实后端目前没有对应字段，接入后这些内容不会被保存。</p>
              <p className="field-hint">合成演示：记录只保存在本页内存中，刷新后消失；没有通知任何人，也不形成真实记录。</p>
            </>
          )}
        </section>
        <div className="report-actions">
          <button type="button" className="button button-primary" onClick={() => onOpenSubmitted(result.id)}>
            查看详情
          </button>
          <button type="button" className="button button-subtle" onClick={startAnother}>
            继续上报
          </button>
          <button type="button" className="button button-subtle" onClick={onClose}>
            关闭
          </button>
        </div>
      </aside>
    );
  }

  return (
    <aside key="editing" className="detail-panel report-panel" aria-label="新建上报面板" onKeyDown={onKeyDown}>
      <div className="detail-heading">
        <div>
          <h2>新建上报</h2>
          <p>隐患发现{live ? "" : " · 合成演示"}</p>
        </div>
        <button className="icon-button" type="button" aria-label="关闭上报" onClick={requestClose} disabled={submitting}>
          ×
        </button>
      </div>

      <p className="report-banner" role="note">
        {live ? (
          <>发现紧急危险请先通过现场应急渠道处理，再来上报。提交后进入“待一人确认”，确认人由服务端按流程指派。</>
        ) : (
          <>
            <strong>合成演示：</strong>提交只写入本页内存中的演示数据，不会通知任何人，也不形成真实记录。发现紧急危险请先通过现场应急渠道处理。
            <br />
            标有“后端暂未接入”的项目：当前后端的创建接口没有对应字段，接入真实后端后这些内容目前不会被保存。
          </>
        )}
      </p>

      <form className="report-form" onSubmit={handleSubmit} noValidate aria-busy={submitting}>
        {errorCount > 0 && (
          <p className="report-summary" role="alert">
            有 {errorCount} 项需要修正。
          </p>
        )}
        {submitFailure && (
          <p className="report-summary" role="alert">
            {SUBMIT_FAILURE_MESSAGE[submitFailure]}
          </p>
        )}

        {needsOrganization && (
          <Field
            id={`${uid}-org`}
            label="所属组织"
            required
            error={orgError ?? undefined}
            hint={
              orgState.status === "ready" && orgState.items.length === 0
                ? "你当前没有可上报的组织，无法提交。请联系管理员授权。"
                : undefined
            }
          >
            {orgState.status === "error" ? (
              <p>
                组织列表加载失败。
                <button type="button" className="button button-subtle" onClick={() => setOrgReloadTick((n) => n + 1)}>
                  重试
                </button>
              </p>
            ) : (
              <select
                id={`${uid}-org`}
                value={organizationId}
                aria-invalid={Boolean(orgError)}
                disabled={submitting || orgState.status !== "ready" || orgState.items.length === 0}
                onChange={(event) => {
                  setOrganizationId(event.target.value);
                  setOrgError(null);
                }}
              >
                <option value="">{orgState.status === "loading" ? "加载中…" : "请选择"}</option>
                {orgState.status === "ready" &&
                  orgState.items.map((org) => (
                    <option key={org.id} value={org.id}>
                      {org.name}
                    </option>
                  ))}
              </select>
            )}
          </Field>
        )}

        <Field id={ids.source} label="来源" required notWired={!live} error={errors.source}>
          <select
            id={ids.source}
            ref={(node) => {
              fieldRefs.current.source = node;
            }}
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
            ref={(node) => {
              fieldRefs.current.foundAt = node;
            }}
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
            ref={(node) => {
              fieldRefs.current.location = node;
            }}
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
            ref={(node) => {
              fieldRefs.current.description = node;
            }}
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
            {(adapter.urgencies ?? findingUrgencies).map((urgency, index) => (
              <label key={urgency} className="report-radio">
                <input
                  type="radio"
                  name={ids.urgency}
                  value={urgency}
                  checked={draft.urgency === urgency}
                  ref={
                    index === 0
                      ? (node) => {
                          fieldRefs.current.urgency = node;
                        }
                      : undefined
                  }
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
            ref={(node) => {
              fieldRefs.current.interimMeasure = node;
            }}
            rows={2}
            value={draft.interimMeasure}
            aria-invalid={Boolean(errors.interimMeasure)}
            aria-describedby={describedBy("interimMeasure", true)}
            disabled={submitting}
            onChange={(event) => update("interimMeasure", event.target.value)}
          />
        </Field>

        <div className="report-readonly">
          <div>
            <span>隐患等级</span>
            <strong className="status-label status-muted">待判定</strong>
          </div>
          <p>由有权限人员依据适用规则确认，上报时不可选择。</p>
        </div>
        <p className="field-hint">图片 / 附件上传、关联风险与检查结果尚未接入。</p>

        {/* 两个区域的按钮在同一位置、类型相同：必须用不同的 key 让 React 整体卸载重建。
            否则“继续编辑”(type=button) 会被原地改成“提交上报”(type=submit)，真实浏览器里这次点击随即变成提交。 */}
        {confirmDiscard ? (
          <div key="discard" className="report-discard" role="group" aria-label="放弃确认">
            <p>放弃已填写的内容？</p>
            <button type="button" className="button button-subtle" autoFocus onClick={() => setConfirmDiscard(false)}>
              继续编辑
            </button>
            <button type="button" className="button button-danger" onClick={onClose}>
              放弃
            </button>
          </div>
        ) : (
          <div key="actions" className="report-actions">
            <button type="button" className="button button-subtle" onClick={requestClose} disabled={submitting}>
              取消
            </button>
            <button
              type="submit"
              className="button button-primary"
              disabled={submitting || (needsOrganization && orgState.status === "ready" && orgState.items.length === 0)}
            >
              {submitting ? "提交中…" : submitFailure === "unavailable" ? "重试提交" : "提交上报"}
            </button>
          </div>
        )}
      </form>
    </aside>
  );
}
