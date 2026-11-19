import { useEffect, useRef, useState } from "react";
import { ApiError } from "../../api/httpClient";
import { discardEntityDraft, peekEntityDraft, stashEntityDraft } from "../session/draftStash";
import { unknownOutcome, type LoanAdapter, type LoanRecord } from "./model";

interface IssueRequest { key: string; definitionVersion: string; expectedVersion: number }
function failureMessage(error: unknown): string {
  if (!(error instanceof ApiError) || error.status === 0 || error.status >= 500) return "发放结果尚未确认，请核对后重试原请求。";
  if (error.status === 401) return "会话已失效，请重新登录后核对。";
  if (error.status === 403 || error.status === 404) return "当前没有此申请的办理资格，或申请已不可见。";
  if (error.code === "VERSION_CONFLICT") return "申请已被修订，请读取最新版本并逐件核对后重新发放。";
  if (error.code === "TOOL_UNAVAILABLE") return "一件或多件工装已不可借，整单发放未完成。请核对工装状态。";
  if (error.code === "IDEMPOTENCY_CONFLICT") return "原请求标识对应的内容不一致，请核对原提交。";
  if (error.status === 409) return "申请状态或工装可借性已变化，请读取最新版本核对。";
  return "发放未通过检查，请核对申请和工装明细。";
}

export function LoanIssueQueue({ adapter, owner, identityKey, onUnauthenticated }: {
  adapter: LoanAdapter; owner: string; identityKey: string; onUnauthenticated(): void;
}) {
  const [records, setRecords] = useState<LoanRecord[]>([]);
  const [selected, setSelected] = useState<LoanRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState("");
  const generation = useRef(0);
  const selectionKey = JSON.stringify([identityKey, "tool-loan-issue-selection"]);
  useEffect(() => {
    const lastId = peekEntityDraft(owner, selectionKey)?.values.loanId;
    if (lastId) void openDetail(lastId);
    return () => { generation.current++; };
  }, [adapter, owner, selectionKey]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);setError("");setRecords([]);
    void adapter.issueQueue(controller.signal).then(rows => {
      if (!controller.signal.aborted) setRecords(rows);
    }, failure => {
      if (controller.signal.aborted) return;
      setError("待发放申请读取失败，请重试。");
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [adapter, revision, onUnauthenticated]);
  async function openDetail(id: string) {
    const mine = ++generation.current;
    setDetailLoading(true);setSelected(null);setError("");
    try {
      const record = await adapter.issueDetail(id);
      if (mine === generation.current) {
        setSelected(record);
        stashEntityDraft(owner, selectionKey, { values: { loanId: record.id }, definitionVersion: record.definitionVersion,
          key: null, fingerprint: null, locked: false });
      }
    } catch (failure) {
      if (mine !== generation.current) return;
      setError(failureMessage(failure));
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    } finally { if (mine === generation.current) setDetailLoading(false); }
  }
  return <section className="operational-queue" aria-label="待发放工作">
    <div className="operational-queue-heading"><h2>待发放工作</h2>
    <button disabled={loading} onClick={() => {
      setRevision(value => value + 1);
      const lastId = peekEntityDraft(owner, selectionKey)?.values.loanId;
      if (lastId) void openDetail(lastId);
    }}>刷新待发放申请</button></div>
    {loading && <p role="status">正在读取待发放申请…</p>}
    {error && <p role="alert">{error}</p>}
    {!loading && !error && records.length === 0 && <p>当前保管范围内没有待发放申请。</p>}
    {records.length > 0 && <div className="operational-table-scroll"><table><caption>可办理的待发放申请（最多最近 100 条）</caption>
      <thead><tr><th>用途</th><th>件数</th><th>申请时间</th><th>操作</th></tr></thead>
      <tbody>{records.map(record => <tr key={record.id}><td>{record.purpose}</td><td>{record.toolIds.length}</td>
        <td>{new Date(record.createdAt).toLocaleString()}</td><td><button onClick={() => void openDetail(record.id)}>核对发放</button></td></tr>)}</tbody>
    </table></div>}
    {detailLoading && <p role="status">正在读取当前申请明细…</p>}
    {selected && <IssueDetail key={selected.id} adapter={adapter} owner={owner} identityKey={identityKey} record={selected}
      onUnauthenticated={onUnauthenticated} onIssued={record => {
        setSelected(record);setRevision(value => value + 1);
      }} />}
  </section>;
}

function IssueDetail({ adapter, owner, identityKey, record, onUnauthenticated, onIssued }: {
  adapter: LoanAdapter; owner: string; identityKey: string; record: LoanRecord;
  onUnauthenticated(): void; onIssued(record: LoanRecord): void;
}) {
  const draftKey = JSON.stringify([identityKey, "tool-loan-issue", record.id]);
  const [current, setCurrent] = useState(record);
  const [pending, setPending] = useState<IssueRequest | null>(() => {
    const stored = peekEntityDraft(owner, draftKey)?.values.issue;
    if (!stored) return null;
    try { return JSON.parse(stored) as IssueRequest; } catch { return null; }
  });
  const [uncertain, setUncertain] = useState(() => peekEntityDraft(owner, draftKey)?.locked ?? false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [issued, setIssued] = useState(["ISSUED", "SETTLED"].includes(record.state) && !peekEntityDraft(owner, draftKey)?.values.issue);
  const generation = useRef(0);
  useEffect(() => () => { generation.current++; }, [adapter]);
  async function issue() {
    if (busy || issued) return;
    const request = pending ?? { key: crypto.randomUUID(), definitionVersion: current.definitionVersion, expectedVersion: current.version };
    const mine = generation.current;
    setPending(request);setBusy(true);setError("");
    // Persist before dispatch so leaving the work page cannot create a second request.
    stashEntityDraft(owner, draftKey, { values: { issue: JSON.stringify(request) }, definitionVersion: request.definitionVersion,
      key: request.key, fingerprint: null, locked: true, expectedVersion: request.expectedVersion });
    try {
      const result = await adapter.command("issue", current.id, { definitionVersion: request.definitionVersion, expectedVersion: request.expectedVersion }, request.key);
      if (mine !== generation.current) return;
      if (result.state !== "ISSUED") throw new Error("Unexpected issue result");
      discardEntityDraft(owner, draftKey);setPending(null);setUncertain(false);setIssued(true);setCurrent(result);onIssued(result);
    } catch (failure) {
      if (mine !== generation.current) return;
      const definitiveVersion = failure instanceof ApiError && failure.status === 409 && failure.code === "VERSION_CONFLICT";
      const locked = unknownOutcome(failure) || (uncertain && !definitiveVersion);
      setUncertain(locked);setError(failureMessage(failure));
      stashEntityDraft(owner, draftKey, { values: { issue: JSON.stringify(request) }, definitionVersion: request.definitionVersion,
        key: request.key, fingerprint: null, locked, expectedVersion: request.expectedVersion });
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    } finally { if (mine === generation.current) setBusy(false); }
  }
  async function reconcile() {
    const mine = generation.current;
    setBusy(true);setError("");
    try {
      const latest = await adapter.issueDetail(current.id);
      if (mine !== generation.current) return;
      setCurrent(latest);
      if (latest.state !== "PENDING_ISSUE") {
        setError("申请已结束或已发放，请核对当前状态。");return;
      }
      discardEntityDraft(owner, draftKey);setPending(null);setUncertain(false);
    } catch (failure) {
      if (mine !== generation.current) return;
      setError(failureMessage(failure));
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    } finally { if (mine === generation.current) setBusy(false); }
  }
  return <section className="operational-detail" aria-label="发放核对">
    <h3>发放核对</h3><p>{current.purpose}</p>{current.usageLocation && <p>使用地点：{current.usageLocation}</p>}<p>第 {current.version} 版 · {current.state === "SETTLED" ? "已结清" : current.state === "ISSUED" ? "已发放" : current.state === "WITHDRAWN" ? "已撤销" : "待发放"}</p>
    <ol>{current.toolIds.map(id => <li key={id}>{current.tools?.find(tool => tool.id === id)?.label ?? id}</li>)}</ol>
    {error && <p role="alert">{error}</p>}
    {uncertain && <p role="status">发放结果尚未确认，请核对后使用原请求重试。</p>}
    {issued ? <p role="status">整单已发放。</p> : <>
      {(pending || current.state === "PENDING_ISSUE") && <button disabled={busy} onClick={() => void issue()}>
        {busy ? "正在办理…" : pending ? "核对后重试原发放请求" : "确认整单发放"}
      </button>}
      {pending && !uncertain && <button disabled={busy} onClick={() => void reconcile()}>读取最新申请并重新核对</button>}
    </>}
  </section>;
}
