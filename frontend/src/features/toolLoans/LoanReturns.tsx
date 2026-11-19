import { useEffect, useRef, useState } from "react";
import { ApiError } from "../../api/httpClient";
import { discardEntityDraft, peekEntityDraft, stashEntityDraft } from "../session/draftStash";
import { unknownOutcome, type LoanAdapter, type LoanRecord, type ReturnItem, type ReturnRequestInput, type ReceiveInput } from "./model";

type ReturnAction = "return-requests" | "receive";
interface Pending { key: string; input: ReturnRequestInput | ReceiveInput }
interface ItemChoice { selected: boolean; condition: "NORMAL" | "DAMAGED"; reason: string }
interface StoredReturnDraft { pending: Pending | null; choices: Record<string, ItemChoice>; uncertain: boolean }
const itemState = { OUTSTANDING: "在借", REQUESTED: "待接收", RECEIVED: "已接收" };
function failureMessage(error: unknown) {
  if (!(error instanceof ApiError) || error.status === 0 || error.status >= 500) return "提交结果尚未确认，所选明细和原请求标识已保留。请核对后重试原请求。";
  if (error.status === 401) return "会话已失效，请重新登录后核对。";
  if (error.status === 403 || error.status === 404) return "当前没有办理资格，或借用单已不可见。";
  if (error.code === "VERSION_CONFLICT") return "借用单已被其他操作更新，请读取最新明细并重新核对。";
  if (error.code === "IDEMPOTENCY_CONFLICT") return "原请求标识对应的内容不一致，请核对原提交。";
  if (error.status === 409) return "所选明细或办理状态已变化，请读取最新明细核对。";
  return error.fieldErrors.length ? error.fieldErrors.map(field => field.reason).join("；") : "输入未通过检查，请核对所选明细和损坏说明。";
}
export function ReturnProgress({ items }: { items: ReturnItem[] }) {
  const unsettled = items.filter(item => item.state !== "RECEIVED").length;
  const requested = items.filter(item => item.state === "REQUESTED").length;
  return <p role="status">尚未结清 {unsettled} 件，待接收 {requested} 件，已接收 {items.length - unsettled} 件</p>;
}

export function LoanReturnForm({ adapter, action, record, owner, identityKey, onUnauthenticated, onSaved }: {
  adapter: LoanAdapter; action: ReturnAction; record: LoanRecord; owner: string; identityKey: string;
  onUnauthenticated(): void; onSaved(record: LoanRecord): void;
}) {
  const draftKey = JSON.stringify([identityKey, "tool-loan", action, record.id]);
  const [current, setCurrent] = useState(record);
  const [draft, setDraft] = useState<StoredReturnDraft>(() => {
    const stored = peekEntityDraft(owner, draftKey)?.values.returnDraft;
    if (stored) {
      try { return JSON.parse(stored) as StoredReturnDraft; } catch { /* Use the current server detail. */ }
    }
    return { pending: null, choices: {}, uncertain: false };
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const generation = useRef(0);
  useEffect(() => () => { generation.current++; }, [adapter]);
  useEffect(() => {
    if (!draft.pending && Object.keys(draft.choices).length === 0) return;
    stashEntityDraft(owner, draftKey, { values: { returnDraft: JSON.stringify(draft) }, definitionVersion: current.definitionVersion,
      key: draft.pending?.key ?? null, fingerprint: null, locked: draft.uncertain, expectedVersion: draft.pending?.input.expectedVersion });
  }, [owner, draftKey, draft, current.definitionVersion]);
  const items = current.returnItems ?? [];
  const eligible = (item: ReturnItem) => action === "receive" ? item.state === "REQUESTED" : item.state === "OUTSTANDING";
  const selected = items.filter(item => draft.choices[item.toolId]?.selected);
  const locked = busy || draft.pending !== null;
  function change(id: string, values: Partial<ItemChoice>) {
    setDraft(value => ({ ...value, choices: { ...value.choices,
      [id]: { ...(value.choices[id] ?? { selected: false, condition: "NORMAL", reason: "" }), ...values } } }));
    setError("");setNotice("");
  }
  async function submit() {
    if (busy) return;
    if (!draft.pending && selected.length === 0) { setError("请至少选择一件可办理工装。");return; }
    if (!draft.pending && selected.some(item => !eligible(item))) { setError("所选明细状态已变化，请读取最新明细核对。");return; }
    if (!draft.pending && action === "receive" && selected.some(item => {
      const choice = draft.choices[item.toolId]!;
      return choice.condition === "DAMAGED" && !choice.reason.trim();
    })) { setError("损坏接收必须填写损坏说明。");return; }
    const request: Pending = draft.pending ?? { key: crypto.randomUUID(), input: action === "return-requests"
      ? { definitionVersion: current.definitionVersion, expectedVersion: current.version, toolIds: selected.map(item => item.toolId) }
      : { definitionVersion: current.definitionVersion, expectedVersion: current.version, items: selected.map(item => {
        const choice = draft.choices[item.toolId]!;
        return { toolId: item.toolId, condition: choice.condition, ...(choice.reason.trim() ? { reason: choice.reason.trim() } : {}) };
      }) } };
    const mine = generation.current;
    const dispatched = { ...draft, pending: request, uncertain: true };
    setDraft(dispatched);setBusy(true);setError("");setNotice("");
    stashEntityDraft(owner, draftKey, { values: { returnDraft: JSON.stringify(dispatched) }, definitionVersion: current.definitionVersion,
      key: request.key, fingerprint: null, locked: true, expectedVersion: request.input.expectedVersion });
    try {
      const result = await adapter.command(action, current.id, request.input, request.key);
      if (mine !== generation.current) return;
      if (!result.returnItems || !["ISSUED", "SETTLED"].includes(result.state)) throw new Error("Invalid return result");
      discardEntityDraft(owner, draftKey);setDraft({ pending: null, choices: {}, uncertain: false });setCurrent(result);
      setNotice(action === "return-requests" ? "归还申请已提交，所选工装仍占用至保管人接收。" : "所选工装已接收。");onSaved(result);
    } catch (failure) {
      if (mine !== generation.current) return;
      const definitiveVersion = failure instanceof ApiError && failure.status === 409 && failure.code === "VERSION_CONFLICT";
      const uncertain = unknownOutcome(failure) || (draft.uncertain && !definitiveVersion);
      setDraft(value => ({ ...value, pending: request, uncertain }));setError(failureMessage(failure));
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    } finally { if (mine === generation.current) setBusy(false); }
  }
  async function refresh() {
    const mine = generation.current;setBusy(true);setError("");
    try {
      const latest = action === "receive" ? await adapter.receiveDetail(current.id) : await adapter.detail(current.id);
      if (mine !== generation.current) return;
      setCurrent(latest);onSaved(latest);
      // The user may reconcile a definitive failure. An uncertain command retains its original payload and key.
      if (!draft.uncertain) {
        discardEntityDraft(owner, draftKey);setDraft({ pending: null, choices: {}, uncertain: false });
      }
    } catch (failure) {
      if (mine !== generation.current) return;
      setError(failureMessage(failure));
      if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
    } finally { if (mine === generation.current) setBusy(false); }
  }
  return <form aria-label={action === "receive" ? "接收归还工装" : "申请分批归还"} onSubmit={event => { event.preventDefault();void submit(); }}>
    <h3>{action === "receive" ? "接收归还工装" : "申请分批归还"}</h3>
    <p>{current.state === "SETTLED" ? "借用单已结清" : "借用单未结清"} · 第 {current.version} 版</p>
    <ReturnProgress items={items} />
    {current.awaitingOtherCustodian === true && current.state === "ISSUED" && <p role="status">对应保管范围目前无人可办（需另一名有效保管人接收）</p>}
    <ul>{items.map(item => {
      const choice = draft.choices[item.toolId];
      return <li key={item.toolId}>
        <label><input type="checkbox" checked={choice?.selected ?? false} disabled={locked || !eligible(item) || current.state === "SETTLED"}
          onChange={event => change(item.toolId, { selected: event.target.checked })} />{item.label}</label>
        <span> · {itemState[item.state]}{item.condition ? ` · ${item.condition === "DAMAGED" ? "损坏接收" : "正常接收"}` : ""}{item.reason ? ` · ${item.reason}` : ""}</span>
        {action === "receive" && eligible(item) && choice?.selected && <>
          <label>接收情况（{item.label}）<select value={choice.condition} disabled={locked} onChange={event => change(item.toolId, { condition: event.target.value as ItemChoice["condition"] })}>
            <option value="NORMAL">正常</option><option value="DAMAGED">损坏</option>
          </select></label>
          <label>接收说明（{item.label}）<textarea value={choice.reason} maxLength={4000} required={choice.condition === "DAMAGED"} disabled={locked}
            onChange={event => change(item.toolId, { reason: event.target.value })} /></label>
        </>}
      </li>;
    })}</ul>
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {draft.uncertain && <p role="status">原请求结果尚未确认，核对后只能重试该请求。</p>}
    {(draft.pending || items.some(eligible)) && <button type="submit" className="button button-primary" disabled={busy}>
      {busy ? "正在办理…" : draft.pending ? "核对后重试原请求" : action === "receive" ? "确认接收所选工装" : "申请归还所选工装"}
    </button>}
    <button type="button" disabled={busy} onClick={() => void refresh()}>{draft.pending && !draft.uncertain ? "读取最新明细并重新核对" : "刷新归还明细"}</button>
  </form>;
}
