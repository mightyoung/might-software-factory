import {
  findingSourceLabels,
  findingStateLabels,
  findingUrgencyLabels,
  getFindingActions,
  workflowCurrentIndex,
  workflowSteps,
  type FindingRecord,
} from "../domain/finding";
import { formatDateTime } from "../domain/time";
import type { FindingLedgerReadFailureKind } from "../adapters/findingLedger";
import type { FindingCommandAdapter } from "../adapters/findingCommands";
import { FindingCommandPanel } from "./FindingCommandPanel";
import { EvidenceUploadPanel } from "./EvidenceUploadPanel";
import type { FindingEvidenceAdapter } from "../adapters/findingEvidence";
import { useAppMode } from "../mode";
import { findingReadFailureMessage } from "../features/findings/readFailureMessage";

interface FindingDetailsProps {
  finding: FindingRecord | null;
  loading: boolean;
  error: FindingLedgerReadFailureKind | null;
  onClose: () => void;
  onRetry: () => void;
  /** 提供时显示可用的流程操作（需要记录带版本号）；缺省为演示用的禁用按钮。 */
  commandAdapter?: FindingCommandAdapter;
  evidenceAdapter?: FindingEvidenceAdapter;
  /** 提供时，“未成立”的记录可以发起关联上报。 */
  onCreateRelated?: (finding: FindingRecord) => void;
  onOpenRelated?: (id: string) => void;
  onCommandChanged?: () => void;
  onUnauthenticated?: () => void;
}

export function FindingDetails({ finding, loading, error, onClose, onRetry, commandAdapter, evidenceAdapter, onCreateRelated, onOpenRelated, onCommandChanged, onUnauthenticated }: FindingDetailsProps) {
  const demo = useAppMode() === "synthetic";
  const noun = demo ? "演示记录" : "记录";
  return (
    <aside className="detail-panel" aria-label="隐患详情面板">
      <div className="detail-heading">
        <div>
          <h2>隐患详情</h2>
          {finding && <p>{finding.code}</p>}
        </div>
        <button className="icon-button" type="button" aria-label="关闭详情" onClick={onClose}>
          ×
        </button>
      </div>

      {loading && <p className="detail-state" role="status">正在读取{noun}…</p>}
      {!loading && error && (
        <div className="detail-error" role="alert">
          <p>{findingReadFailureMessage(error, "detail")}</p>
          <button type="button" className="button button-subtle" onClick={onRetry}>
            重试读取
          </button>
        </div>
      )}
      {!loading && !error && !finding && <p className="detail-state">没有找到这条{noun}。</p>}
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
              {finding.relatedFindingId && (
                <div>
                  <dt>关联记录</dt>
                  <dd>
                    由一条未成立记录补充新证据而来
                    {onOpenRelated && (
                      <button className="link-button" type="button" onClick={() => onOpenRelated(finding.relatedFindingId!)}>
                        {" "}查看原记录
                      </button>
                    )}
                  </dd>
                </div>
              )}
              <div>
                <dt>上报时间</dt>
                <dd>{formatDateTime(finding.reportedAt)}</dd>
              </div>
              {finding.foundAt && (
                <div>
                  <dt>发现时间</dt>
                  <dd>
                    {formatDateTime(finding.foundAt)}
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
            <p>
              {demo
                ? "风险等级保持待评估，合成样例不作专业判断。"
                : finding.riskLabel === "待专业评估" && "风险等级由有权限人员依据适用规则确认。"}
            </p>
          </section>

          <section className="detail-section workflow-section">
            <div className="section-title-row">
              <h3>流程进度</h3>
              <span>第 {finding.round} 轮{demo ? " · 演示" : ""}</span>
            </div>
            <ol className="workflow-list" aria-label="隐患流程节点">
              {workflowSteps.map((step, index) => {
                const terminal = finding.state === "CLOSED" || finding.state === "NOT_ESTABLISHED";
                const complete = finding.state === "CLOSED" ||
                  (finding.state !== "NOT_ESTABLISHED" && index < workflowCurrentIndex(finding.state));
                const current = !terminal && index === workflowCurrentIndex(finding.state);
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
            <h3>{demo ? "演示轨迹" : "处理轨迹"}</h3>
            {!demo && finding.history.length === 0 && <p className="field-hint">后端暂未提供处理轨迹。</p>}
            {finding.historyTruncated && <p className="field-hint">仅显示最近 200 条处理轨迹。</p>}
            <ul>
              {finding.history.map((entry) => (
                <li key={`${entry.at}-${entry.title}`}>
                  <time>{formatDateTime(entry.at)}</time>
                  <div>
                    <strong>{entry.title}</strong>
                    <p>{entry.note}</p>
                  </div>
                </li>
              ))}
            </ul>
          </section>

          {commandAdapter && evidenceAdapter && finding.state === "RECTIFYING" && finding.version !== undefined && (
            <EvidenceUploadPanel key={`${finding.id}:${finding.round}`} finding={finding} adapter={evidenceAdapter} onUnauthenticated={onUnauthenticated} />
          )}

          {onCreateRelated && finding.state === "NOT_ESTABLISHED" && (
            <div className="detail-actions">
              <button className="button button-primary" type="button" onClick={() => onCreateRelated(finding)}>
                基于此创建关联上报
              </button>
            </div>
          )}

          {commandAdapter ? (
            <FindingCommandPanel
              key={finding.id}
              finding={finding}
              adapter={commandAdapter}
              refreshing={loading}
              onChanged={onCommandChanged ?? onRetry}
              onUnauthenticated={onUnauthenticated}
            />
          ) : (
            <>
              <p className="action-hint" role="note">
                审批与派单均为本地演示，所有操作暂不可用。正式接入后仍由服务端校验身份、职责、状态、版本、轮次和证据。
              </p>
              <div className="detail-actions">
                {getFindingActions(finding.state).map((action) => (
                  <button className="button button-disabled" type="button" disabled title={action.reason} key={action.label}>
                    {action.label}
                  </button>
                ))}
                {finding.state === "CLOSED" && <span className="closed-note">此示例状态不可变更</span>}
                {finding.state === "NOT_ESTABLISHED" && <span className="closed-note">未成立记录已终止；新证据应创建关联记录</span>}
              </div>
            </>
          )}
        </>
      )}
    </aside>
  );
}
