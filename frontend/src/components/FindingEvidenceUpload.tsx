import { useEffect, useId, useRef, useState } from "react";
import { ApiError } from "../api/httpClient";
import { FindingCommandError, type FindingCommandAdapter } from "../adapters/findingCommands";
import { evidenceIsReady, evidenceStateLabels, type EvidenceUploadStatus, type FindingEvidenceAdapter } from "../adapters/findingEvidence";
import { newIdempotencyKey } from "../adapters/findingSubmit";
import { codePointLength, hasVisibleCharacter } from "../domain/findingDraft";
import { evidenceRecoveryKey, readEvidenceRecovery, writeEvidenceRecovery, type EvidenceRecovery } from "../features/findings/evidenceRecovery";

interface Props {
  findingId: string;
  roundNo: number;
  version: number;
  adapter: FindingEvidenceAdapter;
  commands: FindingCommandAdapter;
  /** 只接受已验证的 tenant + actor/account/member 身份组合；缺省时不自动持久恢复。 */
  recoveryOwnerKey?: string;
  disabled?: boolean;
  onChanged: () => void;
  onUnauthenticated?: () => void;
  onForbidden?: () => void;
}

const TERMINAL = new Set(["FINALIZED", "FAILED", "REJECTED", "CANCELLED", "EXPIRED"]);
const MAX_BYTES = 64 * 1024 * 1024;
const POLL_LIMIT = 60;

export function FindingEvidenceUpload({ findingId, roundNo, version, adapter, commands, recoveryOwnerKey, disabled = false, onChanged, onUnauthenticated, onForbidden }: Props) {
  const id = useId();
  const context = { findingId, roundNo };
  const storageKey = evidenceRecoveryKey(recoveryOwnerKey, findingId, roundNo);
  const [recovery, setRecovery] = useState<EvidenceRecovery | null>(() => readEvidenceRecovery(storageKey));
  const recoveryRef = useRef(recovery);
  const [file, setFile] = useState<File | null>(null);
  const [status, setStatus] = useState<EvidenceUploadStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [polling, setPolling] = useState(false);
  const [statement, setStatement] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const mounted = useRef(true);
  const submitAttempt = useRef<{ payload: string; key: string } | null>(null);

  function remember(value: EvidenceRecovery | null) {
    recoveryRef.current = value; setRecovery(value); writeEvidenceRecovery(storageKey, value);
  }

  function failure(error: unknown, receiving = false) {
    if (error instanceof DOMException && error.name === "AbortError") return;
    const kind = error instanceof FindingCommandError ? error.kind : null;
    if ((error instanceof ApiError && error.status === 401) || kind === "unauthenticated") { setPolling(false); onUnauthenticated?.(); return; }
    if ((error instanceof ApiError && [403, 404].includes(error.status)) || kind === "forbidden") { setPolling(false); onForbidden?.(); return; }
    if ((error instanceof ApiError && error.status === 409) || ["versionConflict", "stateConflict"].includes(kind ?? "")) onChanged();
    setMessage(receiving ? "文件接收结果尚未确认，请查询状态。不会自动重复发送文件。" : "操作未完成，请查询当前状态或重试。服务端会重新校验当前身份和流程状态。");
  }

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; abortRef.current?.abort(); }; }, []);
  useEffect(() => {
    if (!recovery?.uploadIntentId || disabled) return;
    const abort = new AbortController();
    adapter.status(context, recovery.uploadIntentId, abort.signal).then(value => { if (!abort.signal.aborted) { setStatus(value); setPolling(!TERMINAL.has(value.state) && !value.cancelRequested); } }).catch(error => { if (!abort.signal.aborted) failure(error); });
    return () => abort.abort();
    // 仅恢复当前身份范围的既有意图；服务端仍校验所有权，恢复不会重新发送字节。
  }, [adapter, findingId, roundNo, recovery?.uploadIntentId, disabled]);

  useEffect(() => {
    if (!polling || !recovery?.uploadIntentId || disabled) return;
    const abort = new AbortController(); let timer: ReturnType<typeof setTimeout>; let count = 0;
    const tick = async () => {
      try {
        const value = await adapter.status(context, recovery.uploadIntentId!, abort.signal);
        if (abort.signal.aborted) return;
        setStatus(value); count++;
        if (TERMINAL.has(value.state) || value.cancelRequested || count >= POLL_LIMIT) {
          setPolling(false);
          if (count >= POLL_LIMIT) setMessage("自动查询已暂停，处理结果尚未确认。可以继续查询同一上传状态。");
        } else timer = setTimeout(tick, 2000);
      } catch (error) { if (!abort.signal.aborted) { setPolling(false); failure(error); } }
    };
    timer = setTimeout(tick, 2000);
    return () => { abort.abort(); clearTimeout(timer); };
  }, [adapter, findingId, roundNo, recovery?.uploadIntentId, polling, disabled]);

  async function upload() {
    if (!file || busy || disabled) return;
    if (!new Set(["image/png", "image/jpeg"]).has(file.type) || file.size < 1 || file.size > MAX_BYTES) { setMessage("请选择一张非空 PNG/JPEG 图片，最大 64 MiB；实际大小还受服务端配置限制。"); return; }
    const abort = new AbortController(); abortRef.current = abort; setBusy(true); setMessage(null);
    let receiving = false;
    try {
      const digest = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", await file.arrayBuffer())), b => b.toString(16).padStart(2, "0")).join("");
      if (abort.signal.aborted) return;
      let attempt = recoveryRef.current ?? { initializeKey: newIdempotencyKey(), expectedFindingVersion: version };
      if (attempt.evidenceSetId && !attempt.reserveKey && !attempt.uploadIntentId) {
        attempt = { ...attempt, expectedFindingVersion: version };
      }
      if (attempt.fileSha256 && attempt.fileSha256 !== digest) { setMessage("这次上传已绑定另一份文件，请先查询或取消原上传。"); return; }
      if (attempt.receiveAttempted) { setMessage("文件已尝试发送，请查询同一上传状态，不能直接重新发送。"); return; }
      if (attempt.declaration && (attempt.declaration.findingId !== findingId || attempt.declaration.roundNo !== roundNo
        || attempt.declaration.evidenceSetId !== attempt.evidenceSetId || attempt.declaration.declaredMediaType !== file.type
        || attempt.declaration.declaredSizeBytes !== file.size || attempt.declaration.filename !== file.name)) {
        setMessage("恢复信息与当前文件或记录不一致，请查询原上传状态。"); return;
      }
      remember(attempt);
      if (!attempt.evidenceSetId) {
        const initialized = await adapter.initialize(context, attempt.expectedFindingVersion, attempt.initializeKey, abort.signal);
        if (abort.signal.aborted || !mounted.current) return;
        attempt = { ...attempt, evidenceSetId: initialized.evidenceSetId }; remember(attempt);
      }
      if (!attempt.uploadIntentId) {
        const declaration = attempt.declaration ?? { ...context, evidenceSetId: attempt.evidenceSetId!, expectedFindingVersion: attempt.expectedFindingVersion, expectedEvidenceRevision: 0, filename: file.name, declaredMediaType: file.type as "image/png" | "image/jpeg", declaredSizeBytes: file.size };
        attempt = { ...attempt, declaration, fileSha256: digest, reserveKey: attempt.reserveKey ?? newIdempotencyKey() }; remember(attempt);
        const reserved = await adapter.reserve(declaration, attempt.reserveKey!, abort.signal);
        if (abort.signal.aborted || !mounted.current) return;
        attempt = { ...attempt, uploadIntentId: reserved.uploadIntentId }; remember(attempt); setStatus(reserved);
      }
      attempt = { ...attempt, receiveAttempted: true }; remember(attempt); receiving = true;
      const value = await adapter.receive(context, attempt.uploadIntentId!, file, abort.signal);
      if (!mounted.current || abort.signal.aborted) return;
      setStatus(value); setPolling(!TERMINAL.has(value.state) && !value.cancelRequested);
    } catch (error) {
      if (mounted.current) {
        // 初始化明确回滚版本冲突时没有意图或字节；刷新后可使用新版本重试。
        // 网络中断或预留/接收结果不明时保留原键，避免遗失服务端已提交的上传。
        if (error instanceof ApiError && error.status === 409 && error.code === "VERSION_CONFLICT"
          && !recoveryRef.current?.uploadIntentId && !receiving) {
          const previous = recoveryRef.current;
          // 预留在幂等回放之后才检查版本；明确版本拒绝意味着此键没有已提交的意图。
          remember(previous?.evidenceSetId ? { initializeKey: previous.initializeKey,
            evidenceSetId: previous.evidenceSetId, expectedFindingVersion: previous.expectedFindingVersion } : null);
        }
        failure(error, receiving);
      }
    }
    finally { if (mounted.current && abortRef.current === abort) setBusy(false); }
  }

  async function query() {
    if (!recovery?.uploadIntentId || busy || disabled) return;
    setBusy(true); setMessage(null); const abort = new AbortController(); abortRef.current = abort;
    try { const value = await adapter.status(context, recovery.uploadIntentId, abort.signal); if (mounted.current && !abort.signal.aborted) { setStatus(value); setPolling(!TERMINAL.has(value.state) && !value.cancelRequested); } }
    catch (error) { if (mounted.current) failure(error); } finally { if (mounted.current && abortRef.current === abort) setBusy(false); }
  }

  async function cancel() {
    if (!recovery?.uploadIntentId || cancelling || disabled) return;
    abortRef.current?.abort(); setPolling(false); setBusy(true); setCancelling(true); const abort = new AbortController(); abortRef.current = abort;
    try { const value = await adapter.cancel(context, recovery.uploadIntentId, abort.signal); if (mounted.current && !abort.signal.aborted) { setStatus(value); setMessage("已请求取消；是否完成清理以服务端后续状态为准。"); } }
    catch (error) { if (mounted.current) failure(error); } finally { if (mounted.current && abortRef.current === abort) { setBusy(false); setCancelling(false); } }
  }

  async function submit() {
    if (!evidenceIsReady(status) || !recovery?.evidenceSetId || busy || disabled) return;
    if (!hasVisibleCharacter(statement) || codePointLength(statement) > 4000) { setMessage("请填写整改说明，最多 4000 个字。"); return; }
    const command = { kind: "submitRemediation" as const, evidenceSetId: recovery.evidenceSetId, statement: statement.trim() };
    const payload = JSON.stringify([findingId, version, command]);
    if (submitAttempt.current?.payload !== payload) submitAttempt.current = { payload, key: newIdempotencyKey() };
    const abort = new AbortController(); abortRef.current = abort; setBusy(true); setMessage(null);
    try { await commands.run(findingId, version, command, { idempotencyKey: submitAttempt.current.key, signal: abort.signal }); if (mounted.current && !abort.signal.aborted) { remember(null); setSubmitted(true); onChanged(); } }
    catch (error) { if (mounted.current) failure(error); } finally { if (mounted.current && abortRef.current === abort) setBusy(false); }
  }

  return <section className="detail-section" aria-label="整改证据上传">
    <h3>整改证据与说明</h3>
    <p className="field-hint">每份证据集上传一张 PNG/JPEG 图片。文件经隔离、扫描和最终发布后才能提交整改。</p>
    {submitted ? <p role="status">整改已提交，等待验收。</p> : <>
      <label htmlFor={`${id}-file`}>整改图片</label>
      <input id={`${id}-file`} type="file" accept="image/png,image/jpeg" disabled={busy || disabled || Boolean(recovery?.receiveAttempted)} onChange={event => setFile(event.target.files?.[0] ?? null)} />
      {!recovery?.receiveAttempted && <button className="button button-subtle" type="button" disabled={!file || busy || disabled} onClick={upload}>{busy ? "正在处理…" : "上传图片"}</button>}
      {status && <p role="status">{evidenceStateLabels[status.state]}{status.cancelRequested ? "（取消已请求，尚未确认清理）" : ""}</p>}
      {recovery?.uploadIntentId && <div className="detail-actions"><button className="button button-subtle" type="button" disabled={busy || disabled} onClick={query}>查询上传状态</button><button className="button button-subtle" type="button" disabled={cancelling || disabled || Boolean(status && TERMINAL.has(status.state)) || status?.cancelRequested} onClick={cancel}>{cancelling ? "正在请求取消…" : "取消上传"}</button></div>}
      {polling && <button className="button button-subtle" type="button" onClick={() => setPolling(false)}>暂停自动查询</button>}
      {status && TERMINAL.has(status.state) && status.state !== "FINALIZED" && <button className="button button-subtle" type="button" disabled={busy || disabled} onClick={() => { remember(null); setStatus(null); setFile(null); setMessage(null); }}>新建另一份证据集</button>}
      <label htmlFor={`${id}-statement`}>整改说明</label><textarea id={`${id}-statement`} value={statement} disabled={busy || disabled} onChange={event => setStatement(event.target.value)} />
      <button className="button button-primary" type="button" disabled={!evidenceIsReady(status) || busy || disabled} onClick={submit}>提交整改</button>
      {!evidenceIsReady(status) && <p className="field-hint">等待当前图片最终发布完成；扫描完成检查点本身不代表可提交。</p>}
      {recovery?.uploadIntentId && <details><summary>上传恢复信息</summary><p>证据集：{recovery.evidenceSetId}</p><p>上传意图：{recovery.uploadIntentId}</p><p>刷新后会按当前登录身份查询同一上传，不会自动重新发送文件。</p></details>}
    </>}
    {message && <p role="alert">{message}</p>}
  </section>;
}
