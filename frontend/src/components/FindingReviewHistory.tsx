import type { FindingReviewEntry } from "../adapters/findingCommands";
import { formatDateTime } from "../domain/time";

const decisions = { APPROVE: "确认通过", RETURN_FOR_INFORMATION: "退回补充", NOT_ESTABLISHED: "未成立" };
export function FindingReviewHistory({ entries }: { entries: readonly FindingReviewEntry[] }) {
  const cycles = [...new Set(entries.map(entry => entry.reviewCycle))].sort((a, b) => a - b);
  return <section className="detail-section history-section" aria-label="确认与补充记录">
    <h3>确认与补充记录</h3>
    {entries.length === 0 && <p>暂无确认或补充记录。</p>}
    {cycles.map(cycle => <div key={cycle}><h4>第 {cycle} 次确认</h4><ul>
      {entries.filter(entry => entry.reviewCycle === cycle).map(entry => <li key={entry.id}>
        <time>{formatDateTime(entry.occurredAt)}</time><div>
          <strong>{entry.origin === "REPORTER_SUPPLEMENT" ? "上报人补充说明" : `${entry.stage === "PERSON" ? "一人确认" : "安全确认"} · ${entry.decision ? decisions[entry.decision] : "记录"}`}</strong>
          <p>{entry.actorDisplay.label && entry.actorDisplay.provenance !== "UNAVAILABLE" ? `${entry.actorDisplay.label}${entry.actorDisplay.provenance === "CURRENT_DIRECTORY" ? "（当前通讯录名称）" : "（记录时名称）"}` : `成员 ${entry.actorMemberId.slice(0, 8)}（名称不可用）`}</p>
          <p>{entry.text ?? "未记录意见"}</p>
          {entry.recordedGrade && <p>本次记录等级：{entry.recordedGrade === "GENERAL" ? "一般" : "重大"}</p>}
          {entry.nextReviewCycle && <p>补充后进入第 {entry.nextReviewCycle} 次确认</p>}
        </div>
      </li>)}
    </ul></div>)}
  </section>;
}
