import { useEffect, useRef, useState } from "react";
import { ApiError } from "../../api/httpClient";
import { peekEntityDraft, stashEntityDraft } from "../session/draftStash";
import { LoanLossForm } from "./LoanLossForm";
import { LoanReturnForm } from "./LoanReturns";
import type { LoanAdapter, LoanRecord } from "./model";

export function LoanReceiveQueue({ mode, adapter, owner, identityKey, onUnauthenticated }: {
  mode: "loss" | "receive"; adapter: LoanAdapter; owner: string; identityKey: string; onUnauthenticated(): void;
}) {
  const [records, setRecords] = useState<LoanRecord[]>([]);
  const [selected, setSelected] = useState<LoanRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const generation = useRef(0);
  const loss = mode === "loss";
  const title = loss ? "遗失结案工作" : "待接收工作";
  const detail = loss ? adapter.lossDetail : adapter.receiveDetail;
  const queue = loss ? adapter.lossQueue : adapter.receiveQueue;
  const selectionKey = JSON.stringify([identityKey, `tool-loan-${mode}-selection`]);
  useEffect(() => {
    const lastId = peekEntityDraft(owner, selectionKey)?.values.loanId;
    if (lastId) void openDetail(lastId);
    return () => { generation.current++; };
  }, [adapter, owner, selectionKey]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);setError("");setRecords([]);
    void queue(controller.signal).then(rows => {
      if (!controller.signal.aborted) setRecords(rows);
    }, failure => {
      if (controller.signal.aborted) return;
      setError(`${title}读取失败，请重试。`);
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [queue, revision, onUnauthenticated]);
  async function openDetail(id: string) {
    const mine = ++generation.current;
    setDetailLoading(true);setSelected(null);setError("");
    try {
      const record = await detail(id);
      if (mine !== generation.current) return;
      setSelected(record);
      stashEntityDraft(owner, selectionKey, { values: { loanId: record.id }, definitionVersion: record.definitionVersion,
        key: null, fingerprint: null, locked: false });
    } catch (failure) {
      if (mine !== generation.current) return;
      setError("当前没有此借用单的办理资格，或读取暂时失败，请刷新核对。");
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    } finally { if (mine === generation.current) setDetailLoading(false); }
  }
  return <section className="operational-queue" aria-label={title}>
    <div className="operational-queue-heading"><h2>{title}</h2>
    <button disabled={loading} onClick={() => {
      setRevision(value => value + 1);
      const lastId = peekEntityDraft(owner, selectionKey)?.values.loanId;
      if (lastId) void openDetail(lastId);
    }}>{loss ? "刷新在借工装" : "刷新待接收申请"}</button></div>
    {loading && <p role="status">正在读取申请…</p>}
    {error && <p role="alert">{error}</p>}
    {!loading && !error && records.length === 0 && <p>{loss ? "当前没有可办理遗失结案的在借工装。" : "当前保管范围内没有待接收申请。"}</p>}
    {records.length > 0 && <div className="operational-table-scroll"><table><caption>{loss ? "可办理遗失结案的借用单" : "可办理的待接收申请（最多最近 100 条）"}</caption>
      <thead><tr><th>用途</th><th>工装件数</th><th>操作</th></tr></thead>
      <tbody>{records.map(record => <tr key={record.id}><td>{record.purpose}</td><td>{record.toolIds.length}</td>
        <td><button onClick={() => void openDetail(record.id)}>{loss ? "核对遗失工装" : "核对接收"}</button></td></tr>)}</tbody>
    </table></div>}
    {detailLoading && <p role="status">正在读取当前归还明细…</p>}
    {selected && <section className="operational-detail" aria-label={loss ? "遗失结案详情" : "接收申请详情"}><h3>{selected.purpose}</h3>
      {loss ? <LoanLossForm key={selected.id} adapter={adapter} record={selected} owner={owner} identityKey={identityKey}
        onUnauthenticated={onUnauthenticated} onSaved={record => { setSelected(record);setRevision(value => value + 1); }} /> :
      <LoanReturnForm key={selected.id} adapter={adapter} record={selected} action="receive" owner={owner} identityKey={identityKey}
        onUnauthenticated={onUnauthenticated} onSaved={record => { setSelected(record);setRevision(value => value + 1); }} />}
    </section>}
  </section>;
}
