import { useId, useRef } from "react";
import { ACCEPTED_MEDIA_TYPES, type FindingEvidenceAdapter, type UploadState } from "../adapters/findingEvidence";
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
  onUnauthenticated?: () => void;
}

/** 只覆盖契约已有的上传环节；“终结证据 / 提交整改”契约尚无，所以到“扫描通过”为止，不提供提交。 */
export function EvidenceUploadPanel({ finding, adapter, onUnauthenticated }: EvidenceUploadPanelProps) {
  const uid = useId();
  const input = useRef<HTMLInputElement>(null);
  const { phase, status, filename, error, start, refresh, cancel } = useEvidenceUpload(
    adapter,
    { findingId: finding.id, roundNo: finding.round },
    finding.version ?? 0,
    onUnauthenticated,
  );
  const busy = phase === "working" || phase === "polling";
  const active = status && !["VERIFIED_PENDING_FINALIZATION", "FAILED", "REJECTED", "CANCELLED", "EXPIRED", "FINALIZED"].includes(status.state);

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
        {status && !busy && active && (
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
      <p className="field-hint">上传只表示文件已进入隔离扫描；证据终结、提交整改要等后端的终结接口。页面刷新后需要重新上传。</p>
    </section>
  );
}
