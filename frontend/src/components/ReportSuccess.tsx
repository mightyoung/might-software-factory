import type { KeyboardEvent } from "react";
import type { FindingSubmitResult } from "../adapters/findingSubmit";

export function ReportSuccess({
  result,
  live: contextLive,
  onClose,
  onOpen,
  onStartAnother,
  onKeyDown,
}: {
  result: FindingSubmitResult;
  live: boolean;
  onClose: () => void;
  onOpen?: (id: string) => void;
  onStartAnother: () => void;
  onKeyDown: (event: KeyboardEvent<HTMLElement>) => void;
}) {
  const live = import.meta.env.MSF_BROWSER_MODE === undefined ? contextLive : import.meta.env.MSF_BROWSER_MODE === "http";
  return (
    <aside className="detail-panel report-panel" aria-label="新建上报面板" onKeyDown={onKeyDown}>
      <div className="detail-heading">
        <div>
          <h2>新建上报</h2>
          <p>隐患发现{live ? "" : " · 合成演示"}</p>
        </div>
        <button className="icon-button" type="button" aria-label="关闭上报" onClick={onClose}>
          ×
        </button>
      </div>
      <section className="report-success" role="status">
        <h3>已提交上报</h3>
        <p>
          编号 <strong>{result.code}</strong>，当前状态：待一人确认。
        </p>
        {result.backfill && <p>发现时间早于当前时间超过 24 小时，已按补报处理：发现时间和上报时间分别保存。</p>}
        {live ? (
          <p className="field-hint">已写入服务端，由服务端按流程指派确认人；上报人和租户以你的登录身份为准。</p>
        ) : (
          <>
            <p className="field-hint">“来源”“所在位置 / 设备”“紧急程度”“临时措施”标注为后端暂未接入：真实后端目前没有对应字段，接入后这些内容不会被保存。</p>
            <p className="field-hint">合成演示：记录只保存在本页内存中，刷新后消失；没有通知任何人，也不形成真实记录。</p>
          </>
        )}
      </section>
      <div className="report-actions">
        {onOpen && <button type="button" className="button button-primary" onClick={() => onOpen(result.id)}>
          查看详情
        </button>}
        <button type="button" className="button button-subtle" onClick={onStartAnother}>
          继续上报
        </button>
        <button type="button" className="button button-subtle" onClick={onClose}>
          关闭
        </button>
      </div>
    </aside>
  );
}
