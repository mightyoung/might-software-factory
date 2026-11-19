import { useEffect, useRef, useState } from "react";
import { ApiError } from "../../api/httpClient";
import { ledgerFailure } from "./httpEntityLedger";
import type { EntityLedgerAdapter, LedgerDefinition, LedgerReferenceData, LedgerUse } from "./model";

export function ReferencePicker({ id, label, referenceKey, definition, use, adapter, value, disabled, onChange, onValidated, onUnauthenticated }: {
  id: string; label: string; referenceKey: string; definition: LedgerDefinition; use: LedgerUse; adapter: EntityLedgerAdapter;
  value: string; disabled: boolean; onChange(value: string): void; onValidated(value: string): void; onUnauthenticated(): void;
}) {
  const latest = useRef({ value, onValidated }); latest.current = { value, onValidated };
  const [search, setSearch] = useState("");
  const [result, setResult] = useState<LedgerReferenceData | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);
  const [selected, setSelected] = useState<{ id: string; label: string } | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError("");
    const timer = window.setTimeout(() => {
      adapter.reference(definition, referenceKey, use, search, controller.signal).then(page => {
        if (controller.signal.aborted) return;
        setResult(page); setLoading(false);
        if (page.options.some(option => option.id === latest.current.value)) latest.current.onValidated(latest.current.value);
      }).catch((failure: unknown) => {
        if (controller.signal.aborted) return;
        setResult(null); setLoading(false); setError(ledgerFailure(failure)); latest.current.onValidated("");
        if (failure instanceof ApiError && failure.status === 401) onUnauthenticated();
      });
    }, 150);
    return () => { controller.abort(); window.clearTimeout(timer); };
  }, [adapter, definition, referenceKey, use, search, attempt, onUnauthenticated]);
  const known = result?.options.find(option => option.id === value) ?? (selected?.id === value ? selected : null);
  return <div className="query-field">
    <label htmlFor={`${id}-search`}>检索{label}</label>
    <input id={`${id}-search`} type="search" value={search} disabled={disabled} onChange={event => { if ([...event.target.value].length <= 200) setSearch(event.target.value); }} />
    <select id={id} aria-label={label} value={known?.id ?? ""} disabled={disabled || loading || Boolean(error)} onChange={event => {
      const option = result?.options.find(candidate => candidate.id === event.target.value) ?? null;
      setSelected(option); onChange(option?.id ?? "");
    }}>
      <option value="">{loading ? "加载候选中…" : "请选择授权对象"}</option>
      {known && !result?.options.some(option => option.id === known.id) && <option value={known.id}>{known.label}（已选）</option>}
      {result?.options.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}
    </select>
    {value && !known && !loading && <span role="alert">原选择尚未核实，请从当前候选重新选择。</span>}
    {result?.hasMore && <span>还有更多候选，请缩小检索范围。</span>}
    {error && <p role="alert">{error}<button type="button" className="text-button" disabled={disabled} onClick={() => setAttempt(v => v + 1)}>重试候选</button></p>}
  </div>;
}
