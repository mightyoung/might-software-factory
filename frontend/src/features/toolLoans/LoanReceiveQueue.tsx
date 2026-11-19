import { useEffect, useRef, useState } from "react";
import { ApiError } from "../../api/httpClient";
import { peekEntityDraft, stashEntityDraft } from "../session/draftStash";
import { LoanReturnForm } from "./LoanReturns";
import type { LoanAdapter, LoanRecord } from "./model";

export function LoanReceiveQueue({ adapter, owner, identityKey, onUnauthenticated }: {
  adapter: LoanAdapter; owner: string; identityKey: string; onUnauthenticated(): void;
}) {
  const [records, setRecords] = useState<LoanRecord[]>([]);
  const [selected, setSelected] = useState<LoanRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const generation = useRef(0);
  const selectionKey = JSON.stringify([identityKey, "tool-loan-receive-selection"]);
  useEffect(() => {
    const lastId = peekEntityDraft(owner, selectionKey)?.values.loanId;
    if (lastId) void openDetail(lastId);
    return () => { generation.current++; };
  }, [adapter, owner, selectionKey]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);setError("");setRecords([]);
    void adapter.receiveQueue(controller.signal).then(rows => {
      if (!controller.signal.aborted) setRecords(rows);
    }, failure => {
      if (controller.signal.aborted) return;
      setError("待接收申请读取失败，请重试。");
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [adapter, revision, onUnauthenticated]);
  async function openDetail(id: string) {
    const mine = ++generation.current;
    setDetailLoading(true);setSelected(null);setError("");
    try {
      const record = await adapter.receiveDetail(id);
      if (mine !== generation.current) return;
      setSelected(record);
      stashEntityDraft(owner, selectionKey, { values: { loanId: record.id }, definitionVersion: record.definitionVersion,
        key: null, fingerprint: null, locked: false });
    } catch (failure) {
      if (mine !== generation.current) return;
      setError("当前没有此借用单的接收资格，或读取暂时失败，请刷新核对。");
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    } finally { if (mine === generation.current) setDetailLoading(false); }
  }
  return <section aria-label="待接收工作">
    <h2>待接收工作</h2>
    <button disabled={loading} onClick={() => {
      setRevision(value => value + 1);
      const lastId = peekEntityDraft(owner, selectionKey)?.values.loanId;
      if (lastId) void openDetail(lastId);
    }}>刷新待接收申请</button>
    {loading && <p role="status">正在读取待接收申请…</p>}
    {error && <p role="alert">{error}</p>}
    {!loading && !error && records.length === 0 && <p>当前保管范围内没有待接收申请。</p>}
    {records.length > 0 && <table><caption>可办理的待接收申请（最多最近 100 条）</caption>
      <thead><tr><th>用途</th><th>工装件数</th><th>操作</th></tr></thead>
      <tbody>{records.map(record => <tr key={record.id}><td>{record.purpose}</td><td>{record.toolIds.length}</td>
        <td><button onClick={() => void openDetail(record.id)}>核对接收</button></td></tr>)}</tbody>
    </table>}
    {detailLoading && <p role="status">正在读取当前归还明细…</p>}
    {selected && <section aria-label="接收申请详情"><h3>{selected.purpose}</h3>
      <LoanReturnForm key={selected.id} adapter={adapter} record={selected} action="receive" owner={owner} identityKey={identityKey}
        onUnauthenticated={onUnauthenticated} onSaved={record => { setSelected(record);setRevision(value => value + 1); }} />
    </section>}
  </section>;
}
