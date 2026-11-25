import { SyntheticFindingFields, SyntheticFindingActions } from "./SyntheticFindingDetails";
import {
  findingStateLabels,
  workflowCurrentIndex,
  workflowSteps,
  type FindingRecord,
} from "../domain/finding";
import { formatDateTime } from "../domain/time";
import type { FindingLedgerReadFailureKind } from "../adapters/findingLedger";
import type { FindingCommandAdapter } from "../adapters/findingCommands";
import { FindingRemediationHistory } from "./FindingRemediationHistory";
import { FindingCommandPanel } from "./FindingCommandPanel";
import { EvidenceUploadPanel } from "./EvidenceUploadPanel";
import type { FindingEvidenceAdapter } from "../adapters/findingEvidence";
import { useAppMode } from "../mode";
import { findingReadFailureMessage } from "../features/findings/readFailureMessage";

import { displayPublishedValue, publishedFieldValue, type FindingDefinition } from "../adapters/findingDefinition";

interface FindingDetailsProps {
  definition?: FindingDefinition | null;
  Fields?: typeof SyntheticFindingFields;
  Actions?: typeof SyntheticFindingActions;
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
  onForbidden?: () => void;
}

export function FindingDetailsCore({ Fields, Actions, definition, finding, loading, error, onClose, onRetry, commandAdapter, evidenceAdapter, onCreateRelated, onOpenRelated, onCommandChanged, onUnauthenticated, onForbidden }: FindingDetailsProps) {
  const contextMode = useAppMode();
  const demo = (import.meta.env.MSF_BROWSER_MODE ?? contextMode) === "synthetic";
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
            {definition ? <dl className="detail-list">{definition.layout.detail.map((key) => <div key={key}>
              <dt>{definition.fields[key]!.presentation.label}</dt>
              <dd>{displayPublishedValue(definition.fields[key]!, publishedFieldValue(finding, key))}</dd>
            </div>)}</dl> : Fields ? <Fields finding={finding} /> : null}

                      {finding.relatedFindingId && (
              <p className="detail-related">
                关联记录：由一条未成立记录补充新证据而来
                {onOpenRelated && (
                  <button className="link-button" type="button" onClick={() => onOpenRelated(finding.relatedFindingId!)}>
                    {" "}查看原记录
                  </button>
                )}
              </p>
            )}
          </section>

          <section className="detail-section state-summary" aria-label="当前状态">
            <div>
              <span>当前状态</span>
              <strong className="status-label status-current">{definition?.fields.state ? displayPublishedValue(definition.fields.state, publishedFieldValue(finding, "state")) : findingStateLabels[finding.state]}</strong>
            </div>
            <div>
              <span>风险等级</span>
              <strong className="status-label status-muted">{definition?.fields.grade ? displayPublishedValue(definition.fields.grade, publishedFieldValue(finding, "grade")) : finding.riskLabel}</strong>
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
              <span>第 {finding.round} 轮整改{demo ? " · 演示" : ""}</span>
            </div>
            {finding.state === "REPORTER_SUPPLEMENT_PENDING" ? <p>确认已退回，等待原上报人补充说明。补充后重新完成两级确认。</p> : <ol className="workflow-list" aria-label="隐患流程节点">
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
            </ol>}
          </section>

          {!demo && <FindingRemediationHistory findingId={finding.id} submissions={finding.submittedRemediations} truncated={finding.submittedRemediationsTruncated} />}

          {!commandAdapter?.history && <section className="detail-section history-section">
            <h3>{demo ? "演示轨迹" : "处理轨迹"}</h3>
            {!demo && finding.history.length === 0 && <p className="field-hint">后端暂未提供处理轨迹。</p>}
            {finding.historyTruncated && <p className="field-hint">仅显示最近 200 条处理轨迹。</p>}
            <ul>
              {finding.history.map((entry) => (
                <li key={entry.id ?? `${entry.at}-${entry.title}`}>
                  <time>{formatDateTime(entry.at)}</time>
                  <div>
                    <strong>{entry.title}</strong>
                    <p>{entry.note}</p>
                  </div>
                </li>
              ))}
            </ul>
          </section>}

          {commandAdapter && evidenceAdapter && finding.state === "RECTIFYING" && finding.version !== undefined && (
            <EvidenceUploadPanel key={`${finding.id}:${finding.round}`} finding={finding} adapter={evidenceAdapter} commands={commandAdapter} onSubmitted={onCommandChanged ?? onRetry} onUnauthenticated={onUnauthenticated} />
          )}

          {onCreateRelated && finding.state === "NOT_ESTABLISHED" && (
            <div className="detail-actions">
              <button className="button button-primary" type="button" onClick={() => onCreateRelated(finding)}>
                基于此创建关联上报
              </button>
            </div>
          )}

          {!commandAdapter && Actions && <Actions finding={finding} />}
        </>
      )}
      {finding && commandAdapter && (!error || error === "unknown") && (
        <FindingCommandPanel
          key={finding.id}
          finding={finding}
          adapter={commandAdapter}
          onChanged={onCommandChanged ?? onRetry}
          onUnauthenticated={onUnauthenticated}
          refreshing={loading}
          readBlocked={Boolean(error)}
          onForbidden={onForbidden}
        />
      )}
    </aside>
  );
}

export function FindingDetails({ Fields = SyntheticFindingFields, Actions = SyntheticFindingActions, ...props }: FindingDetailsProps) {
  return <FindingDetailsCore {...props} Fields={Fields} Actions={Actions} />;
}
