import type { SubmittedRemediation } from "../domain/finding";
import { formatDateTime } from "../domain/time";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function contentPath(findingId: string, roundNo: number, item: SubmittedRemediation["evidence"][number]): string | null {
  if (![findingId, item.itemId, item.fileId].every(id => typeof id === "string" && id.length === 36 && UUID.test(id)) || !Number.isSafeInteger(roundNo) || roundNo < 1) return null;
  return `/api/v1/findings/${findingId}/remediation-rounds/${roundNo}/evidence-items/${item.itemId}/content`;
}

export function FindingRemediationHistory({ findingId, submissions = [], truncated = false }: {
  findingId: string;
  submissions?: readonly SubmittedRemediation[];
  truncated?: boolean;
}) {
  return <section className="detail-section" aria-label="已提交整改">
    <h3>已提交整改</h3>
    {submissions.length === 0 && <p className="field-hint">后端暂未提供已提交整改记录。</p>}
    {truncated && <p className="field-hint">仅显示最近 20 次整改提交。</p>}
    {submissions.map(submission => <article key={submission.id}>
      <h4>第 {submission.roundNo} 轮整改</h4>
      <p><time dateTime={submission.submittedAt}>{formatDateTime(submission.submittedAt)}</time> · 提交人：{submission.submitterName ?? "—"}</p>
      <p style={{ whiteSpace: "pre-wrap" }}>{submission.statement}</p>
      <ul>{submission.evidence.map((item, index) => {
        const href = contentPath(findingId, submission.roundNo, item);
        return <li key={item.itemId}>
          {href ? <a href={href} download>下载证据 {index + 1}</a> : <span>证据 {index + 1} 的引用无效，无法下载。</span>}
          <details><summary>证据校验信息</summary>
            <p style={{ overflowWrap: "anywhere" }}>SHA-256：<code>{item.sha256}</code></p>
            <p>校验级别：{["CONTENT", "SCANNED_CONTENT_V2"].includes(item.verificationLevel) ? "内容校验" : "—"}</p>
          </details>
        </li>;
      })}</ul>
    </article>)}
  </section>;
}
