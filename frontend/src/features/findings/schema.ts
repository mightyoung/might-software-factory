import type { LedgerSchema } from "../../domain/ledger";
import { dateKey } from "../../domain/time";
import { findingStateLabels, type FindingRecord } from "../../domain/finding";

export const findingLedgerSchema: LedgerSchema<FindingRecord> = {
  entityKey: "safety.finding",
  title: "隐患台账",
  recordKey: (record) => record.id,
  recordLabel: (record) => record.code,
  queryFields: [
    {
      key: "keyword",
      // 后端契约：code 支持 EQ/CONTAINS，description 不可筛选；因此只按编号查询，不在已返回的一页上做描述过滤。
      label: "隐患编号",
      kind: "text",
      placeholder: "输入隐患编号",
      read: (record) => record.code,
    },
    {
      key: "state",
      label: "状态",
      kind: "enum",
      read: (record) => record.state,
      options: [
        { value: "DISCOVERED", label: findingStateLabels.DISCOVERED },
        { value: "PERSON_CONFIRM_PENDING", label: findingStateLabels.PERSON_CONFIRM_PENDING },
        { value: "SAFETY_CONFIRM_PENDING", label: findingStateLabels.SAFETY_CONFIRM_PENDING },
        { value: "SAFETY_DISPATCH_PENDING", label: findingStateLabels.SAFETY_DISPATCH_PENDING },
        { value: "RECTIFYING", label: findingStateLabels.RECTIFYING },
        { value: "ACCEPTANCE_PENDING", label: findingStateLabels.ACCEPTANCE_PENDING },
        { value: "CLOSED", label: findingStateLabels.CLOSED },
      ],
    },
    {
      key: "reportedAt",
      label: "上报日期",
      kind: "dateRange",
      read: (record) => dateKey(record.reportedAt),
    },
  ],
  columns: [
    { key: "code", label: "隐患编号", kind: "text", read: (record) => record.code },
    { key: "description", label: "隐患摘要", kind: "text", read: (record) => record.description },
    { key: "location", label: "所在位置 / 设备", kind: "text", read: (record) => record.location },
    {
      key: "state",
      label: "状态",
      kind: "status",
      read: (record) => findingStateLabels[record.state],
      tone: (record) => record.state.toLowerCase().replaceAll("_", "-"),
    },
    { key: "reportedAt", label: "上报日期", kind: "text", read: (record) => dateKey(record.reportedAt) },
    { key: "responsible", label: "责任人", kind: "text", read: (record) => record.responsible },
  ],
};
