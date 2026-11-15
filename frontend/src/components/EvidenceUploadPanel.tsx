import { useId, useRef, useState } from "react";
import { FindingCommandError, type FindingCommandAdapter } from "../adapters/findingCommands";
import { newIdempotencyKey } from "../adapters/findingSubmit";
import { ACCEPTED_MEDIA_TYPES, TERMINAL_UPLOAD_STATES, type FindingEvidenceAdapter, type UploadState } from "../adapters/findingEvidence";
import type { FindingRecord } from "../domain/finding";
import { useEvidenceUpload } from "../features/findings/useEvidenceUpload";

const STATE_TEXT: Record<UploadState, string> = {
  INTENT_CREATED: "已登记，等待上传",
  RECEIVING: "正在接收",
  QUARANTINED: "已收到，隔离中，等待扫描",
  SCANNING: "正在扫描",
  VERIFIED_PENDING_FINALIZATION: "扫描通过，等待终结确认",
  RETRY_WAIT: "扫描等待重试",
  FAILED: "处理失败",
  REJECTED: "未通过检查，已拒绝",
  CANCELLED: "已取消",
  EXPIRED: "已过期，请重新上传",
  FINALIZED: "已终结",
};

export interface EvidenceUploadPanelProps {
  finding: FindingRecord;
  adapter: FindingEvidenceAdapter;
  /** 提供时，证据终结发布后可以提交整改。 */
  commands?: FindingCommandAdapter;
  onSubmitted?: () => void;
  onUnauthenticated?: () => void;
}

const STATEMENT_MAX = 4000;

/** 上传 → 隔离扫描 → 服务端终结发布（FINALIZED）后才允许提交整改；服务端会再次校验证据与身份。 */
export function EvidenceUploadPanel({ finding, adapter, commands, onSubmitted, onUnauthenticated }: EvidenceUploadPanelProps) {
  const uid = useId();
  const input = useRef<HTMLInputElement>(null);
  const [statement, setStatement] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  // 同一份内容的重试复用同一个幂等键；内容变了才换新键。
  const attempt = useRef<{ payload: string; key: string } | null>(null);
  const { phase, status, filename, error, evidenceSetId, start, refresh, cancel } = useEvidenceUpload(
    adapter,
    { findingId: finding.id, roundNo: finding.round },
    finding.version ?? 0,
    onUnauthenticated,
  );
  const busy = phase === "working" || phase === "polling";
  const active = status && !["VERIFIED_PENDING_FINALIZATION", "FAILED", "REJECTED", "CANCELLED", "EXPIRED", "FINALIZED"].includes(status.state);

  const ready = status?.state === "FINALIZED" && evidenceSetId !== null && finding.version !== undefined;
  const text = statement.trim();

  async function submit() {
    if (!commands || !ready || submitting) return;
    if (text === "" || [...text].length > STATEMENT_MAX) return setSubmitError(`请填写整改说明，最多 ${STATEMENT_MAX} 个字。`);
    const command = { kind: "submitRemediation" as const, evidenceSetId: evidenceSetId!, statement: text };
    const payload = JSON.stringify([finding.id, finding.version, command]);
    if (attempt.current?.payload !== payload) attempt.current = { payload, key: newIdempotencyKey() };
    setSubmitting(true);
    setSubmitError(null);
    try {
      await commands.run(finding.id, finding.version!, command, { idempotencyKey: attempt.current.key });
      onSubmitted?.();
    } catch (failure) {
      const kind = failure instanceof FindingCommandError ? failure.kind : null;
      if (kind === "unauthenticated") return onUnauthenticated?.();
      if (kind === "versionConflict" || kind === "stateConflict") onSubmitted?.();
      setSubmitError(kind === "unavailable"
        ? "提交结果尚未确认，可以直接重试，重试不会重复执行。"
        : "整改没有提交成功，请刷新详情后再试。");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="evidence-upload" aria-labelledby={`${uid}-title`}>
      <h3 id={`${uid}-title`}>整改证据</h3>
      <div className="report-field">
        <label htmlFor={`${uid}-file`}>选择图片（PNG 或 JPEG，最大 64 MiB）</label>
        <input
          id={`${uid}-file`}
          ref={input}
          type="file"
          accept={ACCEPTED_MEDIA_TYPES.join(",")}
          disabled={busy}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) start(file);
            event.target.value = "";
          }}
        />
      </div>
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
      {status && (
        <p className="field-hint" role="status">
          {filename ? `${filename}：` : ""}
          {STATE_TEXT[status.state]}
          {status.cancelRequested ? "（已请求取消）" : ""}
        </p>
      )}
      {phase === "stalled" && (
        <p className="field-hint">状态长时间没有变化，可以稍后手动刷新。</p>
      )}
      <div className="detail-actions">
        {status && !busy && !TERMINAL_UPLOAD_STATES.includes(status.state) && (
          <button className="button" type="button" onClick={refresh}>
            刷新状态
          </button>
        )}
        {status && active && !status.cancelRequested && (
          <button className="button" type="button" onClick={cancel}>
            取消上传
          </button>
        )}
      </div>
      <p className="field-hint">文件经隔离、扫描并由服务端终结发布后，才能提交整改。</p>
      {commands && (
        <>
          <div className="report-field">
            <label htmlFor={`${uid}-statement`}>整改说明</label>
            <textarea id={`${uid}-statement`} value={statement} disabled={submitting} onChange={(event) => setStatement(event.target.value)} />
          </div>
          {submitError && (
            <p className="field-error" role="alert">
              {submitError}
            </p>
          )}
          <div className="detail-actions">
            <button className="button button-primary" type="button" disabled={!ready || submitting} onClick={submit}>
              {submitting ? "正在提交…" : "提交整改"}
            </button>
          </div>
        </>
      )}
    </section>
  );
}
