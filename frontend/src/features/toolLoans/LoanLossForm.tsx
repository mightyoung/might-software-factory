import { useEffect, useRef, useState } from "react";
import { ApiError } from "../../api/httpClient";
import { discardEntityDraft, peekEntityDraft, stashEntityDraft } from "../session/draftStash";
import { ReturnProgress } from "./LoanReturns";
import { unknownOutcome, type LoanAdapter, type LoanRecord, type LossInput } from "./model";

interface LossDraft {
  toolId: string;
  reason: string;
  request?: { key: string; input: LossInput };
  uncertain?: boolean;
}

export function LoanLossForm({ adapter, record, owner, identityKey, onSaved, onUnauthenticated }: {
  adapter: LoanAdapter; record: LoanRecord; owner: string; identityKey: string;
  onSaved(value: LoanRecord): void; onUnauthenticated(): void;
}) {
  const draftKey = JSON.stringify([identityKey, "close-lost", record.id]);
  const empty = { toolId: "", reason: "" };
  const [draft, setDraft] = useState<LossDraft>(() => {
    const saved = peekEntityDraft(owner, draftKey)?.values.loss;
    if (saved) {
      try { return JSON.parse(saved) as LossDraft; } catch { /* Load a fresh form. */ }
    }
    return empty;
  });
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [message, setMessage] = useState("");
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  const items = record.returnItems ?? [];
  const eligible = items.filter(item => item.state === "OUTSTANDING" || item.state === "REQUESTED");
  const label = items.find(item => item.toolId === draft.toolId)?.label ?? draft.toolId;
  const locked = busy || Boolean(draft.request);
  function save(next: LossDraft) {
    setDraft(next);
    stashEntityDraft(owner, draftKey, { values: { loss: JSON.stringify(next) }, definitionVersion: record.definitionVersion,
      key: next.request?.key ?? null, fingerprint: null, locked: Boolean(next.uncertain) });
  }
  async function closeLost() {
    if (busy) return;
    const request = draft.request ?? { key: crypto.randomUUID(), input: {
      definitionVersion: record.definitionVersion, expectedVersion: record.version, toolId: draft.toolId, reason: draft.reason.trim(),
    } };
    save({ ...draft, request, uncertain: true });
    setBusy(true);
    try {
      const result = await adapter.command("close-lost", record.id, request.input, request.key);
      if (!active.current) return;
      if (!result.returnItems?.some(item => item.toolId === request.input.toolId && item.state === "LOST")) {
        throw new Error("Missing lost item in command result");
      }
      discardEntityDraft(owner, draftKey);
      setDraft(empty);setConfirming(false);setMessage(`${label} 已遗失结案。`);onSaved(result);
    } catch (error) {
      if (!active.current) return;
      const conflict = error instanceof ApiError && error.code === "VERSION_CONFLICT";
      const uncertain = unknownOutcome(error) || (Boolean(draft.uncertain) && !conflict);
      save({ ...draft, request, uncertain });setConfirming(false);
      setMessage(uncertain ? "提交结果尚未确认，请核对后重试原请求。" : "办理被拒绝或单据已更新，请读取最新明细并重新核对。");
      if (error instanceof ApiError && error.status === 401) onUnauthenticated();
    } finally { if (active.current) setBusy(false); }
  }
  async function refresh() {
    setBusy(true);
    try {
      const latest = await adapter.lossDetail(record.id);
      if (!active.current) return;
      if (!draft.uncertain) { discardEntityDraft(owner, draftKey);setDraft(empty);setConfirming(false); }
      setMessage("");onSaved(latest);
    } catch (error) {
      if (!active.current) return;
      setMessage("遗失结案明细读取失败，请重新核对。");
      if (error instanceof ApiError && error.status === 401) onUnauthenticated();
    } finally { if (active.current) setBusy(false); }
  }
  return <section aria-label="逐件遗失结案">
    <ReturnProgress items={items} />
    {items.filter(item => item.state === "LOST").map(item => <p key={item.toolId}>{item.label} · 已遗失结案 · {item.reason}</p>)}
    {(eligible.length > 0 || draft.request) && <>
      <label>遗失工装<select disabled={locked || confirming} value={draft.toolId} onChange={event => save({ ...draft, toolId: event.target.value })}>
        <option value="">请选择工装</option>
        {items.filter(item => eligible.includes(item) || item.toolId === draft.toolId).map(item =>
          <option key={item.toolId} value={item.toolId}>{item.label}{item.state === "REQUESTED" ? "（待接收）" : ""}</option>)}
      </select></label>
      <label>遗失原因<textarea disabled={locked || confirming} value={draft.reason} maxLength={4000} onChange={event => save({ ...draft, reason: event.target.value })} /></label>
      {confirming && <p>确认将 {label} 标为遗失？该件将解除占用并保持不可借。如有待接收请求，将一并终止。</p>}
      {draft.request ? <button disabled={busy} onClick={() => void closeLost()}>核对后重试原请求</button> : confirming ? <>
        <button disabled={busy} onClick={() => void closeLost()}>确认遗失结案</button>
        <button disabled={busy} onClick={() => setConfirming(false)}>返回修改</button>
      </> : <button disabled={busy || !draft.toolId || !draft.reason.trim()} onClick={() => setConfirming(true)}>核对遗失结论</button>}
    </>}
    <button disabled={busy} onClick={() => void refresh()}>读取最新遗失明细</button>
    {message && <p role="status">{message}</p>}
  </section>;
}
