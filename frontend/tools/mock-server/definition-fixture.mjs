// Explicit synthetic protocol fixture for the development mock only. No production authority.
const LABELS = {
  id: "内部标识", code: "隐患编号", organizationId: "上报组织", organizationName: "组织名称", description: "隐患描述",
  discoveredAt: "发现时间", sourceCategory: "发现来源", urgency: "紧急程度", location: "所在位置 / 设备", interimMeasure: "临时措施",
  backfill: "补报", sourceEventId: "来源事件", riskId: "关联风险", relatedFindingId: "关联隐患", grade: "隐患等级", state: "状态",
  roundNo: "整改轮次", version: "版本", reviewCycle: "审核轮次", reviewProtocol: "审核协议", createdAt: "上报日期", updatedAt: "更新日期",
};
const VALUES = {
  state: {"DISCOVERED":"已发现","PERSON_CONFIRM_PENDING":"待责任人确认","SAFETY_CONFIRM_PENDING":"待安全确认","REPORTER_SUPPLEMENT_PENDING":"待上报人补充","SAFETY_DISPATCH_PENDING":"待安全派单","RECTIFYING":"整改中","ACCEPTANCE_PENDING":"待验收","NOT_ESTABLISHED":"不成立","CLOSED":"已关闭"},
  sourceCategory: { FIELD_OBSERVATION: "现场发现", INSPECTION: "检查发现", PERSON_REPORT: "人员举报" },
  urgency: { NORMAL: "一般", URGENT: "需立即处置" }, grade: { UNKNOWN: "待专业评估", GENERAL: "一般", MAJOR: "重大" },
};
const INPUTS = ["organizationId", "description", "discoveredAt", "sourceEventId", "riskId", "grade", "sourceCategory", "urgency", "location", "interimMeasure", "relatedFindingId"];
const CONTROLS = ["organizationId", "description", "discoveredAt", "sourceCategory", "urgency", "location", "interimMeasure"];

export function syntheticFindingDefinition(schema, use) {
  const fields = {};
  const source = use === "READ" ? Object.keys(schema.fields) : INPUTS;
  for (const key of source) {
    const query = structuredClone(schema.fields[key] ?? { type: "UUID", nullable: true, selectable: false, sortable: false, operators: [], maxLength: 0, enumValues: [] });
    const presentation = { label: LABELS[key], uiKind: key === "state" ? "STATUS" : query.type === "DATETIME" ? "DATE_TIME" : query.type === "ENUM" ? "ENUM" : "TEXT", history: "CURRENT", referenceKey: null, valueLabels: { ...VALUES[key] }, edit: null };
    if (use === "CREATE") {
      const required = !["interimMeasure", "sourceEventId", "riskId", "relatedFindingId"].includes(key);
      const allowedValues = key === "grade" ? ["UNKNOWN"] : [...query.enumValues];
      const maxLength = key === "description" ? 20000 : key === "location" ? 200 : key === "interimMeasure" ? 2000 : 0;
      presentation.edit = { required, maxLength, lengthUnit: key === "description" ? "UTF16_CODE_UNITS" : "UNICODE_CODE_POINTS", allowedValues };
      presentation.valueLabels = Object.fromEntries(allowedValues.filter(value => value in presentation.valueLabels).map(value => [value, presentation.valueLabels[value]]));
      Object.assign(query, { nullable: !required, selectable: false, sortable: false, operators: [], maxLength, enumValues: allowedValues });
      if (key === "organizationId") Object.assign(presentation, { uiKind: "REFERENCE", referenceKey: "report-organization" });
    }
    fields[key] = { query, presentation };
  }
  return {
    appKey: "safety", entityKey: "finding", definitionVersion: "1", querySchemaVersion: "5", use,
    title: "隐患台账（模拟服务）", publicationChecksum: "c".repeat(64), fields,
    layout: use === "READ" ? { list: ["code", "description", "location", "state", "createdAt"], detail: source, query: ["code", "state", "createdAt"], create: [] } : { list: [], detail: [], query: [], create: CONTROLS },
    actions: use === "READ" ? {} : { "finding.create": { label: "提交上报", position: "CREATE", binding: { method: "POST", permission: "finding.create", path: "/api/v1/findings" }, expectedVersionRequired: false, idempotencyKeyRequired: true, inputFields: INPUTS } },
    references: use === "READ" ? {} : { "report-organization": { binding: { resourceKey: "report-organization", method: "POST", permission: "finding.create", path: "/api/v1/entities/finding/references/report-organization/query" }, entityKey: "organization", history: "CURRENT" } },
  };
}
