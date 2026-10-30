import {
  findingSourceLabels,
  findingStateLabels,
  findingUrgencyLabels,
  getFindingActions,
  workflowCurrentIndex,
  workflowSteps,
  type FindingRecord,
} from "../domain/finding";
import type { FindingLedgerReadFailureKind } from "../adapters/findingLedger";
import { findingReadFailureMessage } from "../features/findings/readFailureMessage";

interface FindingDetailsProps {
  finding: FindingRecord | null;
  loading: boolean;
  error: FindingLedgerReadFailureKind | null;
  onClose: () => void;
  onRetry: () => void;
}

export function FindingDetails({ finding, loading, error, onClose, onRetry }: FindingDetailsProps) {
  return (
    <aside className="detail-panel" aria-label="隐患详情面板">
      <div className="detail-heading">
        <div>
          <h2>隐患详情</h2>
          {finding && <p>{finding.id}</p>}
        </div>
        <button className="icon-button" type="button" aria-label="关闭详情" onClick={onClose}>
          ×
        </button>
      </div>

      {loading && <p className="detail-state" role="status">正在读取演示记录…</p>}
      {!loading && error && (
        <div className="detail-error" role="alert">
          <p>{findingReadFailureMessage(error, "detail")}</p>
          <button type="button" className="button button-subtle" onClick={onRetry}>
            重试读取
          </button>
        </div>
      )}
      {!loading && !error && !finding && <p className="detail-state">没有找到这条演示记录。</p>}
      {finding && !error && (
        <>
          <section className="detail-section">
            <h3>基本信息</h3>
            <dl className="detail-list">
              <div>
                <dt>隐患摘要</dt>
                <dd>{finding.description}</dd>
              </div>
              <div>
                <dt>所在位置 / 设备</dt>
                <dd>{finding.location}</dd>
              </div>
              <div>
                <dt>上报时间</dt>
                <dd>{finding.reportedAt.replace("T", " ")}</dd>
              </div>
              {finding.foundAt && (
                <div>
                  <dt>发现时间</dt>
                  <dd>
                    {finding.foundAt.replace("T", " ")}
                    {finding.backfill && <span className="inline-tag">补报</span>}
                  </dd>
                </div>
              )}
              {finding.source && (
                <div>
                  <dt>来源</dt>
                  <dd>{findingSourceLabels[finding.source]}</dd>
                </div>
              )}
              {finding.urgency && (
                <div>
                  <dt>紧急程度</dt>
                  <dd>{findingUrgencyLabels[finding.urgency]}</dd>
                </div>
              )}
              {finding.interimMeasure && (
                <div>
                  <dt>临时措施</dt>
                  <dd>{finding.interimMeasure}</dd>
                </div>
              )}
              <div>
                <dt>上报人</dt>
                <dd>{finding.reporter}</dd>
              </div>
              <div>
                <dt>整改责任人</dt>
                <dd>{finding.responsible}</dd>
              </div>
            </dl>
          </section>

          <section className="detail-section state-summary" aria-label="当前状态">
            <div>
              <span>当前状态</span>
              <strong className="status-label status-current">{findingStateLabels[finding.state]}</strong>
            </div>
            <div>
              <span>风险等级</span>
              <strong className="status-label status-muted">{finding.riskLabel}</strong>
            </div>
            <p>风险等级保持待评估，合成样例不作专业判断。</p>
          </section>

          <section className="detail-section workflow-section">
            <div className="section-title-row">
              <h3>流程进度</h3>
              <span>第 {finding.round} 轮 · 演示</span>
            </div>
            <ol className="workflow-list" aria-label="隐患流程节点">
              {workflowSteps.map((step, index) => {
                const complete = finding.state === "CLOSED" || index < workflowCurrentIndex(finding.state);
                const current = finding.state !== "CLOSED" && index === workflowCurrentIndex(finding.state);
                return (
                  <li className={current ? "workflow-current" : complete ? "workflow-complete" : ""} key={step}>
                    <span className="workflow-marker" aria-hidden="true">
                      {complete ? "✓" : current ? "" : ""}
                    </span>
                    <span className="workflow-step-name">{step}</span>
                    {current && <span className="workflow-step-state">当前节点</span>}
                  </li>
                );
              })}
            </ol>
          </section>

          <section className="detail-section history-section">
            <h3>演示轨迹</h3>
            <ul>
              {finding.history.map((entry) => (
                <li key={`${entry.at}-${entry.title}`}>
                  <time>{entry.at}</time>
                  <div>
                    <strong>{entry.title}</strong>
                    <p>{entry.note}</p>
                  </div>
                </li>
              ))}
            </ul>
          </section>

          <p className="action-hint" role="note">
            审批与派单均为本地演示，所有操作暂不可用。正式接入后仍由服务端校验身份、职责、状态、版本、轮次和证据。
          </p>
          <div className="detail-actions">
            {getFindingActions(finding.state).map((action) => (
              <button
                className="button button-disabled"
                type="button"
                disabled
                title={action.reason}
                key={action.label}
              >
                {action.label}
              </button>
            ))}
            {finding.state === "CLOSED" && <span className="closed-note">此示例状态不可变更</span>}
          </div>
        </>
      )}
    </aside>
  );
}
