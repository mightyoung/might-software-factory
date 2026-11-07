import { useEffect, useId, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { FindingSubmitError } from "../adapters/findingSubmit";
import { CREATE_DRAFT_FIELDS, validatePublishedFindingDraft, type FindingDefinition } from "../adapters/findingDefinition";
import { useAppMode } from "../mode";
import { OrganizationField, useReportableOrganizations } from "./OrganizationField";
import { ReportDraftFields } from "./ReportDraftFields";
import { ReportSuccess } from "./ReportSuccess";
import { peekDraft } from "../features/session/draftStash";
import { useDraftStash } from "./useDraftStash";
import { useFieldFocus } from "./useFieldFocus";
import { useReportSubmit } from "./useReportSubmit";
import { useUnsavedGuard } from "./useUnsavedGuard";
import {
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
  onOpenSubmitted?: (id: string) => void;
  /** 服务端判定未登录（401）：由应用回到登录页。 */
  onUnauthenticated?: () => void;
  /** 当前登录者的标识；提供时，会话被动失效后未提交的内容会在同一个人重新登录后恢复。 */
  draftKey?: string;
  now?: () => Date;
  createKey?: () => string;
}

const SUBMIT_FAILURE_MESSAGE: Record<FindingSubmitFailureKind, string> = {
  validation: "服务端校验未通过，请修正标出的内容后再提交。",
  workflow: "当前无法进入确认流程（例如没有可用的确认人），请联系管理员处理。",
  unauthenticated: "登录已失效，请重新登录。",
  forbidden: "你没有提交隐患上报的权限。",
  conflict: "这次提交与之前的一次提交内容不一致，已被拒绝。请关闭面板后重新上报。",
  unavailable: "服务暂时不可用，上报尚未确认成功。可以直接重试，重试不会重复创建记录。",
};

export function FindingReportForm(props:FindingReportFormProps) {
  const [scope,setScope]=useState({adapter:props.adapter,draftKey:props.draftKey,generation:0});
  if(scope.adapter!==props.adapter||scope.draftKey!==props.draftKey){setScope({adapter:props.adapter,draftKey:props.draftKey,generation:scope.generation+1});return null;}
  return <FindingReportFormBody key={scope.generation} {...props}/>;
}
function FindingReportFormBody({
  adapter,
  onClose,
  onSubmitted,
  onOpenSubmitted,
  onUnauthenticated,
  draftKey,
  now = () => new Date(),
  createKey = newIdempotencyKey,
}: FindingReportFormProps) {
  const uid = useId();
  const [initial] = useState(() => createEmptyDraft(now()));
  const [restored] = useState(() => (draftKey ? peekDraft(draftKey) : undefined));
  const [draft, setDraft] = useState<FindingDraft>(restored?.draft ?? initial);
  const [errors, setErrors] = useState<FindingDraftErrors>({});
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const live = useAppMode() === "http";

  // 组织选择：仅当适配器提供 listOrganizations 时启用。
  const needsOrganization = adapter.queryOrganizations !== undefined || adapter.listOrganizations !== undefined;
  const published=adapter.loadDefinition!==undefined;
  const [definitionState,setDefinitionState]=useState<{status:"loading"|"error"}|{status:"ready";value:FindingDefinition}>({status:"loading"});
  const definition=definitionState.status==="ready"?definitionState.value:null;
  const [definitionReload,setDefinitionReload]=useState(0);
  const orgs = useReportableOrganizations(adapter, onUnauthenticated, restored?.organizationId,!published||definition!==null);
  useEffect(()=>{
    if(!adapter.loadDefinition)return;let current=true;const abort=new AbortController();setDefinitionState({status:"loading"});
    adapter.loadDefinition(abort.signal).then(value=>{if(current&&!abort.signal.aborted)setDefinitionState({status:"ready",value});}).catch((error:unknown)=>{
      if(!current||abort.signal.aborted)return;setDefinitionState({status:"error"});orgs.reset();
      if(error instanceof FindingSubmitError&&error.kind==="unauthenticated")onUnauthenticated?.();
    });return ()=>{current=false;abort.abort();};
  },[adapter,definitionReload]);
  const organizationId = orgs.organizationId;
  const [orgError, setOrgError] = useState<string | null>(null);
  const orgRef = useRef<HTMLSelectElement | null>(null);
  useEffect(() => {
    // 选了组织，“请选择所属组织”的错误随即消失。
    if (organizationId) setOrgError(null);
  }, [organizationId]);

  const submit = useReportSubmit({ adapter, createKey, onSubmitted, onUnauthenticated });
  const { phase, failure: submitFailure, submitting } = submit;
  const fieldOrder=useMemo(()=>definition?definition.layout.create.map(key=>CREATE_DRAFT_FIELDS[key]).filter((key):key is FindingDraftField=>key!==undefined):DRAFT_FIELD_ORDER,[definition]);
  const focus = useFieldFocus(phase.name === "editing",fieldOrder);
  const dirty = orgs.touched || JSON.stringify(normalizeDraft(draft)) !== JSON.stringify(normalizeDraft(initial));
  useUnsavedGuard(dirty && phase.name !== "submitted");
  useDraftStash({ draftKey, draft, organizationId, dirty, submitted: phase.name === "submitted", restored: restored !== undefined });
  const errorCount = Object.keys(errors).length + (orgError ? 1 : 0);

  useEffect(() => {
    focus.focusNow("source");
  }, [focus.focusNow]);

  function update<K extends FindingDraftField>(key: K, value: FindingDraft[K]) {
    setDraft((current) => ({ ...current, [key]: value }));
    // 编辑某个字段后清除它自己的错误，其他字段的错误保留到下次提交。
    setErrors((current) => {
      if (!(key in current)) return current;
      const { [key]: _removed, ...rest } = current;
      return rest;
    });
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

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting || (published&&!definition)) return;
    const found = definition?validatePublishedFindingDraft(draft,definition,now()):validateFindingDraft(draft, now());
    setErrors(found);
    const orgMissing = needsOrganization && (!organizationId||orgs.selected?.id!==organizationId);
    setOrgError(orgMissing ? "请选择所属组织" : null);
    if (orgMissing || hasDraftErrors(found)) {
      // 组织在表单最上方：缺它时先把焦点交给它，其余错误随后按顺序处理。
      if (orgMissing) orgRef.current?.focus();
      else focus.focusFirstError(found);
      return;
    }
    const outcome = await submit.send(normalizeDraft(draft), needsOrganization ? organizationId : undefined);
    if (outcome.fieldErrors) {
      setErrors(outcome.fieldErrors);
      focus.focusFirstError(outcome.fieldErrors);
    }
  }

  function startAnother() {
    setDraft(createEmptyDraft(now()));
    setErrors({});
    setOrgError(null);
    setConfirmDiscard(false);
    submit.reset();
    if(published){setDefinitionReload(value=>value+1);orgs.reset();}
    queueMicrotask(() => focus.focusNow("source"));
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
        {restored && (
          <p className="report-banner" role="status">
            已恢复登录失效前还没提交的内容，请检查后再提交。
          </p>
        )}
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

        {published&&definitionState.status==="loading"&&<p role="status">正在读取上报定义…</p>}
        {published&&definitionState.status==="error"&&<p role="alert">上报定义读取失败，尚不能提交。<button type="button" onClick={()=>setDefinitionReload(value=>value+1)}>重试定义</button></p>}
        {(!published||definition)&&<ReportDraftFields idPrefix={uid} draft={draft} errors={errors} disabled={submitting} live={live} urgencies={adapter.urgencies} definition={definition}
          organization={needsOrganization?<OrganizationField id={`${uid}-org`} orgs={orgs} error={orgError??undefined} disabled={submitting} selectRef={orgRef} label={definition?.fields.organizationId?.presentation.label} required={definition?.fields.organizationId?.presentation.edit?.required??true}/>:null}
          now={now} update={update} fieldRef={focus.fieldRef}/>}

        <div className="report-readonly">
          <div>
            <span>{definition?.fields.grade?.presentation.label??"隐患等级"}</span>
            <strong className="status-label status-muted">{definition?.fields.grade?.presentation.valueLabels.UNKNOWN??"待判定"}</strong>
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
              disabled={submitting || (published&&!definition) || (needsOrganization && (orgs.noneAvailable||(!orgs.selected&&orgs.state.status!=="ready")))}
            >
              {submitting ? "提交中…" : submitFailure === "unavailable" ? "重试提交" : definition?.actions["finding.create"]?.label??"提交上报"}
            </button>
          </div>
        )}
      </form>
    </aside>
  );
}
