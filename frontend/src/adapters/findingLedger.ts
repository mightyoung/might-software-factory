import { matchesQuery, type PageRequest, type PageResult, type QueryValues } from "../domain/ledger";
import { findingLedgerSchema } from "../features/findings/schema";
import type { FindingRecord, FindingState } from "../domain/finding";

export type FindingView = "all" | "confirmation" | "acceptance";

export interface FindingListRequest {
  query: QueryValues;
  page: PageRequest;
  view: FindingView;
}

/** UI-facing seam only. No HTTP route or server payload is implied by this type. */
export interface FindingLedgerAdapter {
  list(input: FindingListRequest): Promise<PageResult<FindingRecord>>;
  get(id: string): Promise<FindingRecord | null>;
}

const demoHistory = (at: string, title: string, note: string) => [{ at, title, note }];

export const syntheticFindings: readonly FindingRecord[] = [
  {
    id: "DEMO-HZ-001",
    description: "搬运区域隔离设施损坏",
    location: "演示工房 A · 搬运区",
    state: "PERSON_CONFIRM_PENDING",
    reportedAt: "2026-10-29T09:14:00",
    reporter: "示例检查员甲",
    responsible: "待分派",
    riskLabel: "待专业评估",
    round: 1,
    history: demoHistory("2026-10-29 09:14", "发现上报", "合成样例；流程尚待一人确认。"),
  },
  {
    id: "DEMO-HZ-002",
    description: "设备防护罩固定件松动",
    location: "演示工房 B · 设备 03",
    state: "SAFETY_CONFIRM_PENDING",
    reportedAt: "2026-10-28T15:40:00",
    reporter: "示例检查员乙",
    responsible: "待分派",
    riskLabel: "待专业评估",
    round: 1,
    history: demoHistory("2026-10-28 16:05", "一人确认", "合成样例；现处于安全部门确认节点。"),
  },
  {
    id: "DEMO-HZ-003",
    description: "通道临时堆放周转物料",
    location: "演示仓库 · 东侧通道",
    state: "SAFETY_DISPATCH_PENDING",
    reportedAt: "2026-10-27T11:20:00",
    reporter: "示例检查员丙",
    responsible: "待分派",
    riskLabel: "待专业评估",
    round: 1,
    history: demoHistory("2026-10-27 13:30", "安全确认", "合成样例；等待安全部门分派整改任务。"),
  },
  {
    id: "DEMO-HZ-004",
    description: "作业点防护标识需要补充",
    location: "演示产线 01 · 工位 4",
    state: "RECTIFYING",
    reportedAt: "2026-10-26T10:05:00",
    reporter: "示例检查员甲",
    responsible: "示例处理人甲",
    riskLabel: "待专业评估",
    round: 1,
    history: demoHistory("2026-10-27 09:10", "整改处理中", "合成样例；未提交真实整改证据。"),
  },
  {
    id: "DEMO-HZ-005",
    description: "防护设施维护后等待复核",
    location: "演示工房 A · 设备 06",
    state: "ACCEPTANCE_PENDING",
    reportedAt: "2026-10-25T08:32:00",
    reporter: "示例检查员乙",
    responsible: "示例处理人乙",
    riskLabel: "待专业评估",
    round: 2,
    history: demoHistory("2026-10-28 17:20", "提交验收", "合成样例第二轮；等待安全部门验收。"),
  },
  {
    id: "DEMO-HZ-006",
    description: "测试点位围挡已按样例完成复核",
    location: "演示工房 C · 测试点",
    state: "CLOSED",
    reportedAt: "2026-10-24T14:18:00",
    reporter: "示例检查员丙",
    responsible: "示例处理人丙",
    riskLabel: "待专业评估",
    round: 1,
    history: demoHistory("2026-10-26 16:45", "示例流程结束", "仅为流程展示，不代表现场风险已消除。"),
  },
  {
    id: "DEMO-HZ-007",
    description: "工具柜附近地面物品待整理",
    location: "演示仓库 · 工具柜旁",
    state: "RECTIFYING",
    reportedAt: "2026-10-23T16:50:00",
    reporter: "示例检查员甲",
    responsible: "示例处理人甲",
    riskLabel: "待专业评估",
    round: 1,
    history: demoHistory("2026-10-24 10:25", "派发整改", "合成样例；没有实际通知书或派单记录。"),
  },
];

const confirmationStates = new Set<FindingState>(["PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING"]);

function isInView(record: FindingRecord, view: FindingView): boolean {
  if (view === "confirmation") return confirmationStates.has(record.state);
  if (view === "acceptance") return record.state === "ACCEPTANCE_PENDING";
  return true;
}

/** Clearly synthetic, deterministic data adapter for screen rendering and unit tests. */
export const mockFindingLedger: FindingLedgerAdapter = {
  async list({ query, page, view }) {
    const filtered = syntheticFindings.filter(
      (record) => isInView(record, view) && matchesQuery(record, findingLedgerSchema.queryFields, query),
    );
    const pageCount = Math.max(1, Math.ceil(filtered.length / page.size));
    const safeIndex = Math.min(Math.max(page.index, 1), pageCount);
    const start = (safeIndex - 1) * page.size;

    return {
      items: filtered.slice(start, start + page.size),
      page: { index: safeIndex, size: page.size },
      total: filtered.length,
      pageCount,
    };
  },
  async get(id) {
    return syntheticFindings.find((record) => record.id === id) ?? null;
  },
};
