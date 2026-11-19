import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError } from "../../api/httpClient";
import { useLeaveGuard } from "../../routing/router";
import { discardEntityDraft, isExpiring, peekEntityDraft, stashEntityDraft, type EntityPendingRequest } from "../session/draftStash";
import { formValues, initialDraft } from "./formValues";
import { ledgerFailure } from "./httpEntityLedger";
import { ReferencePicker } from "./ReferencePicker";
import { valueLabel, type EntityLedgerAdapter, type LedgerCommand, type LedgerDefinition, type LedgerDraft, type LedgerRecord, type LedgerUse } from "./model";

export function EntityLedgerForm({ definition, use, adapter, record, owner, draftKey, onSuccess, onUnauthenticated, onRefresh }: {
  definition: LedgerDefinition; use: LedgerUse; adapter: EntityLedgerAdapter; record?: LedgerRecord; owner: string; draftKey: string;
  onSuccess(result: LedgerCommand): void; onUnauthenticated(): void; onRefresh(): void;
}) {
  const restored = useRef(peekEntityDraft(owner, draftKey));
  const [draft, setDraft] = useState<LedgerDraft>(() => ({ ...initialDraft(definition, use, record?.values), ...restored.current?.values }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [locked, setLocked] = useState(restored.current?.locked ?? false);
  const [pending, setPending] = useState<EntityPendingRequest | null>(() => restored.current?.request ?? null);
  const [dirty, setDirty] = useState(Boolean(restored.current));
  const [referenceChoices, setReferenceChoices] = useState<Record<string, string>>({});
  const expectedVersion = useRef(restored.current?.expectedVersion ?? record?.version);
  const definitionMismatch = restored.current !== undefined && restored.current.definitionVersion !== definition.definitionVersion;
  const [reviewed, setReviewed] = useState(!definitionMismatch);
  const saved = useRef(false);
  const lifetime = useRef(0);
  useEffect(() => {
    lifetime.current += 1;
    return () => { lifetime.current += 1; };
  }, [owner, draftKey]);
  const latest = useRef({ draft, pending, locked }); latest.current = { draft, pending, locked };
  useLeaveGuard(dirty && !busy, "有尚未保存的台账输入，确定离开？");
  useEffect(() => () => {
    if (isExpiring() && !saved.current) {
      const current = latest.current;
      stashEntityDraft(owner, draftKey, { request: current.pending ?? undefined, values: current.draft, definitionVersion: definition.definitionVersion, key: current.pending?.key ?? null, fingerprint: current.pending?.fingerprint ?? null, locked: current.locked, expectedVersion: expectedVersion.current });
    }
  }, [owner, draftKey, definition.definitionVersion]);
  function change(key: string, value: string) { setDraft(current => ({ ...current, [key]: value })); setDirty(true); setErrors(current => ({ ...current, [key]: "" })); }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || (!reviewed && !locked)) return;
    const generation = lifetime.current;
    let request = pending;
    if (!locked) {
      const validation = formValues(definition, use, draft);
      for (const key of definition.layout[use === "CREATE" ? "create" : "edit"]) {
        if (definition.inputFields[key]!.type === "REFERENCE" && draft[key] && referenceChoices[key] !== draft[key]) validation.errors[key] = "请从当前授权候选重新选择。";
      }
      setErrors(validation.errors);
      if (Object.keys(validation.errors).length) return;
      const fingerprint = JSON.stringify([definition.definitionVersion, expectedVersion.current, validation.values]);
      if (pending && pending.fingerprint !== fingerprint) { setError("内容已变化，请核对原提交后明确开始新的提交。"); return; }
      request = pending ?? { key: crypto.randomUUID(), fingerprint, definitionVersion: definition.definitionVersion, expectedVersion: expectedVersion.current, values: { ...validation.values } };
    }
    if (!request) return;
    // Once sent, an uncertain request is retried verbatim. Fresh reference searches
    // cannot reconstruct its original candidate page; authorization stays server-side.
    const submittedDefinition = { ...definition, definitionVersion: request.definitionVersion };
    stashEntityDraft(owner, draftKey, { request, values: draft, definitionVersion: definition.definitionVersion, key: request.key, fingerprint: request.fingerprint, locked: true, expectedVersion: request.expectedVersion });
    setPending(request); setLocked(true); latest.current = { draft, pending: request, locked: true }; setBusy(true); setError("");
    try {
      const result = use === "CREATE" ? await adapter.create(submittedDefinition, request.values, request.key)
        : await adapter.update(submittedDefinition, { ...record!, version: request.expectedVersion! }, request.values, request.key);
      if (lifetime.current !== generation) return;
      saved.current = true; setDirty(false); discardEntityDraft(owner, draftKey); onSuccess(result);
    } catch (failure) {
      if (lifetime.current !== generation) return;
      // UPDATE checks the original receipt before its version guard. This exact
      // rejection confirms no commit for that request; all other uncertain outcomes stay locked.
      const versionRejected = use === "EDIT" && failure instanceof ApiError && failure.status === 409 && failure.code === "VERSION_CONFLICT";
      const unknown = !versionRejected && (locked || !(failure instanceof ApiError) || failure.status === 0 || failure.status >= 500);
      setLocked(unknown); latest.current = { draft, pending: request, locked: unknown }; setError(ledgerFailure(failure));
      stashEntityDraft(owner, draftKey, { request, values: draft, definitionVersion: definition.definitionVersion, key: request.key, fingerprint: request.fingerprint, locked: unknown, expectedVersion: request.expectedVersion });
      if (failure instanceof ApiError && failure.status === 401) {
        onUnauthenticated();
      }
    } finally { if (lifetime.current === generation) setBusy(false); }
  }
  const layout = definition.layout[use === "CREATE" ? "create" : "edit"];
  return <form className="report-form operational-form" onSubmit={submit} noValidate>
    <h2>{use === "CREATE" ? definition.actions.create.label : definition.actions.update.label}</h2>
    {(definitionMismatch && !reviewed) && <p role="alert">字段定义与保留输入的版本不同，请逐项核对。<button type="button" onClick={() => setReviewed(true)}>已核对当前定义</button></p>}
    {layout.map(key => {
      const field = definition.inputFields[key]!;
      const id = `entity-field-${key}`;
      const disabled = busy || locked;
      return <div className="report-field" key={key}>
        {field.type !== "REFERENCE" && <label htmlFor={id}>{field.label}{field.required ? " *" : ""}</label>}
        {field.type === "REFERENCE" ? <ReferencePicker id={id} label={field.label} referenceKey={field.referenceKey!} definition={definition} use={use} adapter={adapter} value={draft[key] ?? ""} disabled={disabled} onUnauthenticated={onUnauthenticated} onValidated={value => setReferenceChoices(current => ({ ...current, [key]: value }))} onChange={value => { change(key, value); setReferenceChoices(current => ({ ...current, [key]: value })); }} />
          : field.type === "MULTILINE_TEXT" ? <textarea id={id} value={draft[key] ?? ""} disabled={disabled} aria-invalid={Boolean(errors[key])} onChange={event => change(key, event.target.value)} />
            : field.type === "ENUM" ? <select id={id} value={draft[key] ?? ""} disabled={disabled} onChange={event => change(key, event.target.value)}><option value="">请选择</option>{field.allowedValues.map(value => <option key={value} value={value}>{valueLabel(definition.fields[key]?.valueLabels, value)}</option>)}</select>
              : <input id={id} type="text" inputMode={field.type === "INTEGER" ? "numeric" : undefined} placeholder={field.type === "DATE_TIME" ? "2026-11-15T10:30:00+08:00" : undefined} value={draft[key] ?? ""} disabled={disabled} aria-invalid={Boolean(errors[key])} onChange={event => change(key, event.target.value)} />}
        {errors[key] && <p role="alert">{errors[key]}</p>}
      </div>;
    })}
    {error && <p role="alert">{error}</p>}
    {locked && <p>当前输入已锁定以保留原请求；重试仍使用原请求标识。</p>}
    <div className="form-actions">
      <button type="submit" className="button button-primary" disabled={busy || (!reviewed && !locked)}>{busy ? "提交中…" : locked ? "核对后重试原请求" : "保存"}</button>
      {error && <button type="button" className="button button-subtle" disabled={busy} onClick={() => { stashEntityDraft(owner, draftKey, { request: pending ?? undefined, values: draft, definitionVersion: definition.definitionVersion, key: pending?.key ?? null, fingerprint: pending?.fingerprint ?? null, locked, expectedVersion: expectedVersion.current }); onRefresh(); }}>刷新定义和记录核对</button>}
      {pending && !locked && <button type="button" disabled={busy} onClick={() => { discardEntityDraft(owner, draftKey); setPending(null); setError(""); expectedVersion.current = record?.version; }}>已核对，开始新提交</button>}
    </div>
  </form>;
}
