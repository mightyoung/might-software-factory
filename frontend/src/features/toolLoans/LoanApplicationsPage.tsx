import { useEffect, useRef, useState } from "react";
import { ApiError } from "../../api/httpClient";
import { MultiItemRows } from "../../components/MultiItemRows";
import { ledgerFailure } from "../ledger/httpEntityLedger";
import { discardEntityDraft, peekEntityDraft, stashEntityDraft } from "../session/draftStash";
import { unknownOutcome, type LoanAdapter, type LoanCandidates, type LoanInput, type LoanRecord } from "./model";

type Action = "create" | "revise" | "cancel";
interface Pending { action: Action; id?: string; input: LoanInput | { definitionVersion: string; expectedVersion: number }; key: string }
interface Draft { department: string; scope: string; purpose: string; tools: string[]; pending: Pending | null; uncertain: boolean }
const EMPTY: LoanCandidates = { departments: [], scopes: [], tools: [] };
const stateLabel = (state: LoanRecord["state"]) => state === "PENDING_ISSUE" ? "待发放" : "已撤销";

export function LoanApplicationsPage({ adapter, owner, identityScope, permissions, onUnauthenticated }: {
  adapter: LoanAdapter; owner: string; identityScope?: { accountId: string; memberId: string }; permissions: readonly string[]; onUnauthenticated(): void;
}) {
  const [records, setRecords] = useState<LoanRecord[]>([]);
  const [selected, setSelected] = useState<LoanRecord | null>(null);
  const [form, setForm] = useState<Action | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const [detailBusy, setDetailBusy] = useState(false);
  const readGeneration = useRef(0);
  useEffect(() => () => { readGeneration.current++; }, [adapter]);
  async function openRecord(id: string) {
    const mine = ++readGeneration.current;
    setDetailBusy(true);setError("");setSelected(null);
    try {
      const result = await adapter.detail(id);
      if (mine === readGeneration.current) setSelected(result);
    } catch (failure) {
      if (mine === readGeneration.current) {
        setError(ledgerFailure(failure));
        if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
      }
    } finally { if (mine === readGeneration.current) setDetailBusy(false); }
  }
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);setError("");
    if (!permissions.includes("tool_loan.read")) { setRecords([]);setLoading(false);return; }
    void adapter.list(controller.signal).then(rows => { if (!controller.signal.aborted) setRecords(rows); }, failure => {
      if (controller.signal.aborted) return;
      setError(ledgerFailure(failure));
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [adapter, permissions, revision, onUnauthenticated]);
  const read = permissions.includes("tool_loan.read");
  return <section className="ledger-main">
    <div className="page-heading"><h1>工装借用申请</h1>
      {permissions.includes("tool_loan.create") && !form && <button className="button button-primary" onClick={() => { readGeneration.current++;setDetailBusy(false);setSelected(null);setForm("create"); }}>新建借用申请</button>}
    </div>
    {error && <p role="alert">{error}</p>}
    {form ? <LoanForm key={`${selected?.id ?? "new"}/${form}`} adapter={adapter} owner={owner} identityKey={JSON.stringify([identityScope?.accountId, identityScope?.memberId])} action={form} record={selected}
      onUnauthenticated={onUnauthenticated} onClose={() => setForm(null)} onSaved={record => { setSelected(record);setForm(null);setRevision(value => value + 1); }} /> : <>
      {read && <button onClick={() => setRevision(value => value + 1)} disabled={loading}>刷新申请</button>}
      {loading && <p role="status">正在读取申请…</p>}
      {read && !loading && records.length === 0 && <p>暂无本人借用申请。</p>}
      {records.length > 0 && <table><caption>本人借用申请（最多显示最近 100 条）</caption><thead><tr><th>用途</th><th>件数</th><th>状态</th><th>申请时间</th><th>操作</th></tr></thead>
        <tbody>{records.map(record => <tr key={record.id}><td>{record.purpose}</td><td>{record.toolIds.length}</td><td>{stateLabel(record.state)}</td><td>{new Date(record.createdAt).toLocaleString()}</td>
          <td><button onClick={() => void openRecord(record.id)}>查看申请</button></td></tr>)}</tbody></table>}
      {detailBusy && <p role="status">正在读取申请详情…</p>}
      {selected && <section aria-label="借用申请详情"><h2>申请详情</h2><p>{stateLabel(selected.state)} · 第 {selected.version} 版</p><p>{selected.purpose}</p>
        <p>申请工装 {selected.toolIds.length} 件</p><ol>{selected.toolIds.map(id => <li key={id}>{selected.tools?.find(tool => tool.id === id)?.label ?? id}</li>)}</ol>
        {selected.state === "PENDING_ISSUE" && permissions.includes("tool_loan.update") && <>
          <button onClick={() => setForm("revise")}>修订申请</button><button onClick={() => setForm("cancel")}>撤销申请</button>
        </>}
      </section>}
    </>}
  </section>;
}

function LoanForm({ adapter, owner, identityKey, action, record, onUnauthenticated, onClose, onSaved }: {
  adapter: LoanAdapter; owner: string; identityKey: string; action: Action; record: LoanRecord | null;
  onUnauthenticated(): void; onClose(): void; onSaved(record: LoanRecord): void;
}) {
  const [currentRecord, setCurrentRecord] = useState(record);
  const draftKey = JSON.stringify([identityKey, "tool-loan", record?.id ?? "new", action]);
  const [draft, setDraft] = useState<Draft>(() => {
    const stored = peekEntityDraft(owner, draftKey)?.values.loan;
    if (stored) {
      try { return JSON.parse(stored) as Draft; } catch { /* Fall back to the last confirmed record. */ }
    }
    return { department: record?.organizationId ?? "", scope: record?.custodyScopeId ?? "", purpose: record?.purpose ?? "", tools: record?.toolIds ?? [""], pending: null, uncertain: false };
  });
  const [candidates, setCandidates] = useState<LoanCandidates>(EMPTY);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [rowErrors, setRowErrors] = useState<Record<number, string>>({});
  const lifetime = useRef(0);
  useEffect(() => () => { lifetime.current++; }, []);
  useEffect(() => {
    stashEntityDraft(owner, draftKey, { values: { loan: JSON.stringify(draft) }, definitionVersion: record?.definitionVersion ?? "1", key: draft.pending?.key ?? null, fingerprint: null, locked: draft.uncertain });
  }, [draft, owner, draftKey, record?.definitionVersion]);
  useEffect(() => {
    if (action === "cancel") return;
    const controller = new AbortController();setLoading(true);setCandidates(EMPTY);
    void adapter.candidates(draft.scope || undefined, controller.signal, record?.id).then(value => { if (!controller.signal.aborted) {
        setCandidates(value);
        if (value.departments.length === 1) setDraft(current => current.department ? current : { ...current, department: value.departments[0]!.id });
      } }, failure => {
      if (controller.signal.aborted) return;
      setError(ledgerFailure(failure));
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [adapter, action, draft.scope, record?.id, onUnauthenticated]);
  const locked = busy || draft.pending !== null;
  function change(values: Partial<Draft>) { setDraft(current => ({ ...current, ...values }));setRowErrors({});setError(""); }
  async function submit() {
    if (busy) return;
    const errors: Record<number, string> = {};
    draft.tools.forEach((id, index) => { if (!id) errors[index] = "请选择工装。";else if (draft.tools.indexOf(id) !== index) errors[index] = "同一申请不能重复选择工装。"; });
    const scope = candidates.scopes.find(value => value.id === draft.scope);
    if (!draft.pending && action !== "cancel" && (!scope || (action === "create" && !candidates.departments.some(department => department.id === draft.department)) || !draft.purpose.trim() || Object.keys(errors).length > 0)) {
      setRowErrors(errors);setError("请填写申请部门、用途、保管范围及全部工装明细。");return;
    }
    const request: Pending = draft.pending ?? {
      action, ...(record ? { id: record.id } : {}), key: crypto.randomUUID(),
      input: action === "cancel" ? { definitionVersion: currentRecord!.definitionVersion, expectedVersion: currentRecord!.version }
        : { definitionVersion: currentRecord?.definitionVersion ?? "1", organizationId: draft.department, custodyScopeId: draft.scope, purpose: draft.purpose, toolIds: [...draft.tools], ...(currentRecord ? { expectedVersion: currentRecord.version } : {}) },
    };
    const mine = lifetime.current;
    setBusy(true);setError("");setDraft(current => ({ ...current, pending: request }));
    try {
      const result = await adapter.command(request.action, request.id, request.input, request.key);
      if (mine !== lifetime.current) return;
      discardEntityDraft(owner, draftKey);onSaved(result);
    } catch (failure) {
      if (mine !== lifetime.current) return;
      setDraft(current => ({ ...current, pending: request, uncertain: unknownOutcome(failure) || (draft.uncertain && !(failure instanceof ApiError && failure.status === 409 && failure.code === "VERSION_CONFLICT" && action !== "create")) }));
      setError(ledgerFailure(failure));
      if (failure instanceof ApiError) {
        const fields: Record<number, string> = {};
        for (const field of failure.fieldErrors) {
          const match = /(?:toolIds|items)\[(\d+)\]/.exec(field.field);
          if (match) fields[Number(match[1])] = field.reason;
        }
        setRowErrors(fields);
        if (failure.status === 401) onUnauthenticated();
      }
    } finally { if (mine === lifetime.current) setBusy(false); }
  }
  return <form aria-label={action === "create" ? "新建借用申请" : action === "revise" ? "修订借用申请" : "撤销借用申请"} onSubmit={event => { event.preventDefault();void submit(); }}>
    <h2>{action === "create" ? "新建借用申请" : action === "revise" ? "修订借用申请" : "确认撤销申请"}</h2>
    {action !== "cancel" && <>
      <label>申请部门<select value={draft.department} disabled={locked || loading || action === "revise"} onChange={event => change({ department: event.target.value })}>
        <option value="">请选择申请部门</option>{action === "revise" && !candidates.departments.some(department => department.id === draft.department) && <option value={draft.department}>申请时部门（{draft.department}）</option>}{candidates.departments.map(department => <option key={department.id} value={department.id}>{department.label}</option>)}
      </select></label>
      <label>保管范围<select value={draft.scope} disabled={locked || loading || action === "revise"} onChange={event => change({ scope: event.target.value, tools: [""] })}>
        <option value="">请选择保管范围</option>{candidates.scopes.map(scope => <option key={scope.id} value={scope.id}>{scope.label}</option>)}
      </select></label>
      <label>借用用途<textarea value={draft.purpose} maxLength={4000} disabled={locked} onChange={event => change({ purpose: event.target.value })} /></label>
      {loading && <p role="status">正在读取可借工装…</p>}
      <MultiItemRows itemLabel="工装" title="工装明细" addLabel="添加工装" emptyLabel="请选择工装" unavailableLabel="原选工装（请核对当前可借范围）" values={draft.tools} options={candidates.tools} disabled={locked || loading || !draft.scope} errors={rowErrors} onChange={tools => change({ tools })} />
    </>}
    {action === "cancel" && <p>撤销后保留申请历史。用途为“{record?.purpose}”，共 {record?.toolIds.length} 件。</p>}
    {error && <p role="alert">{error}</p>}
    {draft.uncertain && <p role="status">此次提交结果尚未确认。请核对后使用原请求重试。</p>}
    <div className="form-actions">
      <button type="submit" className="button button-primary" disabled={busy || loading}>{busy ? "提交中…" : draft.pending ? "核对后重试原请求" : action === "cancel" ? "确认撤销" : "提交申请"}</button>
      {draft.pending && !draft.uncertain && <button type="button" disabled={busy} onClick={async () => {
        const mine = lifetime.current;
        setBusy(true);
        try {
          if (currentRecord) {
            const latest = await adapter.detail(currentRecord.id);
            if (mine !== lifetime.current) return;
            if (latest.state !== "PENDING_ISSUE") { setError("申请已结束，请返回列表核对。");return; }
            setCurrentRecord(latest);
          }
          setDraft(current => ({ ...current, pending: null }));setError("");
        } catch (failure) {
          if (mine === lifetime.current) {
            setError(ledgerFailure(failure));
            if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
          }
        } finally { if (mine === lifetime.current) setBusy(false); }
      }}>读取最新版本并核对后重新提交</button>}
      <button type="button" disabled={busy} onClick={onClose}>返回列表（保留输入）</button>
    </div>
  </form>;
}
