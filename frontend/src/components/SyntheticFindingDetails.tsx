import type { FindingView } from "../adapters/findingLedgerContract";
import { findingSourceLabels, findingUrgencyLabels, getFindingActions, type FindingRecord } from "../domain/finding";
import { formatDateTime } from "../domain/time";

export function SyntheticFindingFields({ finding }: { finding: FindingRecord }) {
  return (
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
  );
}

export function SyntheticFindingActions({ finding }: { finding: FindingRecord }) {
  return (
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
  );
}

export function SyntheticFindingNotice({ view }: { view: FindingView }) {
  return <>
    <div className="demo-notice" role="note">
      <span className="notice-mark" aria-hidden="true">i</span>
      <p>当前使用<strong>合成演示数据</strong>，只供浏览字段、列表和流程节点；不代表真实企业、人员、事件或审批结果。</p>
    </div>
    {view !== "all" && <p className="view-scope-note">
      {view === "confirmation"
        ? "展示处于两级确认节点的演示记录，不代表具体成员已获待办授权。"
        : "展示处于验收节点的演示记录，不代表已提交真实整改证据。"}
    </p>}
  </>;
}
