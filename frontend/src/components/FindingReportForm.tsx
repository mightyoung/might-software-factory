import { useEffect, useId, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { useAppMode } from "../mode";
import { OrganizationField, useReportableOrganizations } from "./OrganizationField";
import { ReportDraftFields } from "./ReportDraftFields";
import { ReportSuccess } from "./ReportSuccess";
import {
  FindingSubmitError,
  newIdempotencyKey,
  type FindingSubmitAdapter,
  type FindingSubmitFailureKind,
  type FindingSubmitResult,
} from "../adapters/findingSubmit";
import {
  DRAFT_FIELD_ORDER,
  createEmptyDraft,
  hasDraftErrors,
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

  const [initial] = useState(() => createEmptyDraft(now()));
  const [draft, setDraft] = useState<FindingDraft>(initial);
  const [errors, setErrors] = useState<FindingDraftErrors>({});
  const [phase, setPhase] = useState<Phase>({ name: "editing" });
  const [submitFailure, setSubmitFailure] = useState<FindingSubmitFailureKind | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  // 组织选择：仅当适配器提供 listOrganizations 时启用。
  const needsOrganization = adapter.listOrganizations !== undefined;
  const live = useAppMode() === "http";
  const orgs = useReportableOrganizations(adapter, onUnauthenticated);
  const organizationId = orgs.organizationId;
  const [orgError, setOrgError] = useState<string | null>(null);
  const orgRef = useRef<HTMLSelectElement | null>(null);
  // 选了组织，“请选择所属组织”的错误随即消失。
  useEffect(() => {
    if (organizationId) setOrgError(null);
  }, [organizationId]);
  const [pendingFocus, setPendingFocus] = useState<FindingDraftField | null>(null);

  const fieldRefs = useRef<Partial<Record<FindingDraftField, HTMLElement | null>>>({});
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);
  // 同一份内容的重试复用同一个幂等键；内容变化才生成新键。
  const lastAttempt = useRef<{ payload: string; key: string } | null>(null);

  const submitting = phase.name === "submitting";
  const dirty = orgs.touched || JSON.stringify(normalizeDraft(draft)) !== JSON.stringify(normalizeDraft(initial));
  const errorCount = Object.keys(errors).length + (orgError ? 1 : 0);

  useEffect(() => {
    mounted.current = true;
    fieldRefs.current.source?.focus();
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);

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
    const orgMissing = needsOrganization && !organizationId;
    setOrgError(orgMissing ? "请选择所属组织" : null);
    if (orgMissing || hasDraftErrors(found)) {
      // 组织在表单最上方：缺它时先把焦点交给它，其余错误随后按顺序处理。
      if (orgMissing) orgRef.current?.focus();
      else focusFirstError(found);
      return;
    }

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
    setOrgError(null);
    setSubmitFailure(null);
    setConfirmDiscard(false);
    lastAttempt.current = null;
    setPhase({ name: "editing" });
    queueMicrotask(() => fieldRefs.current.source?.focus());
  }

  if (phase.name === "submitted") {
    return (
      <ReportSuccess
        key="submitted"
        result={phase.result}
        live={live}
        onClose={onClose}
        onOpen={onOpenSubmitted}
        onStartAnother={startAnother}
        onKeyDown={onKeyDown}
      />
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
          <OrganizationField
            id={`${uid}-org`}
            orgs={orgs}
            error={orgError ?? undefined}
            disabled={submitting}
            selectRef={orgRef}
          />
        )}

        <ReportDraftFields
          idPrefix={uid}
          draft={draft}
          errors={errors}
          disabled={submitting}
          live={live}
          urgencies={adapter.urgencies}
          now={now}
          update={update}
          fieldRef={(field) => (node) => {
            fieldRefs.current[field] = node;
          }}
        />

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
              disabled={submitting || (needsOrganization && orgs.noneAvailable)}
            >
              {submitting ? "提交中…" : submitFailure === "unavailable" ? "重试提交" : "提交上报"}
            </button>
          </div>
        )}
      </form>
    </aside>
  );
}
