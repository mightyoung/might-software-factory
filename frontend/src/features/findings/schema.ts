import type { LedgerSchema } from "../../domain/ledger";
import { findingStateLabels, type FindingRecord } from "../../domain/finding";

export const findingLedgerSchema: LedgerSchema<FindingRecord> = {
  entityKey: "safety.finding",
  title: "隐患台账",
  recordKey: (record) => record.id,
  queryFields: [
    {
      key: "keyword",
      label: "隐患编号 / 描述",
      kind: "text",
      placeholder: "输入编号或描述关键词",
      read: (record) => `${record.id} ${record.description}`,
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
      read: (record) => record.reportedAt,
    },
  ],
  columns: [
    { key: "id", label: "隐患编号", kind: "text", read: (record) => record.id },
    { key: "description", label: "隐患摘要", kind: "text", read: (record) => record.description },
    { key: "location", label: "所在位置 / 设备", kind: "text", read: (record) => record.location },
    {
      key: "state",
      label: "状态",
      kind: "status",
      read: (record) => findingStateLabels[record.state],
      tone: (record) => record.state.toLowerCase().replaceAll("_", "-"),
    },
    { key: "reportedAt", label: "上报日期", kind: "text", read: (record) => record.reportedAt.slice(0, 10) },
    { key: "responsible", label: "责任人", kind: "text", read: (record) => record.responsible },
  ],
};
