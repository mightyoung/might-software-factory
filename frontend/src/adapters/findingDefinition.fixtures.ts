import type { DefinitionField, FindingDefinition } from "./findingDefinition";

/** Synthetic transport fixtures for definition consumers; not evidence of a real authorized response. */
export function findingDefinitionFixture(use: "READ" | "CREATE" = "READ"): FindingDefinition {
  const field = (type: DefinitionField["query"]["type"], label: string, extra: Partial<DefinitionField["query"]> = {}): DefinitionField => ({
    query: { type, nullable: false, selectable: use === "READ", sortable: false, operators: [], maxLength: 0, enumValues: [], ...extra },
    presentation: { label, uiKind: type === "DATETIME" ? "DATE_TIME" : type === "ENUM" ? "ENUM" : "TEXT", history: "CURRENT", referenceKey: null, valueLabels: {}, edit: null },
  });
  const fields: FindingDefinition["fields"] = {
    organizationId: field("UUID", "所属组织"), description: field("TEXT", "隐患描述"), discoveredAt: field("DATETIME", "发现时间"),
    sourceEventId: field("UUID", "来源事件"), riskId: field("UUID", "关联风险"), grade: field("ENUM", "隐患等级", { enumValues: ["UNKNOWN", "GENERAL", "MAJOR"] }),
    sourceCategory: field("ENUM", "来源", { enumValues: ["FIELD_OBSERVATION", "INSPECTION", "PERSON_REPORT"] }),
    urgency: field("ENUM", "紧急程度", { enumValues: ["NORMAL", "URGENT"] }), location: field("TEXT", "所在位置 / 设备"),
    interimMeasure: field("TEXT", "已采取的临时措施（选填）"), relatedFindingId: field("UUID", "关联隐患"),
  };
  fields.sourceCategory!.presentation.valueLabels = { FIELD_OBSERVATION: "现场发现", INSPECTION: "检查发现", PERSON_REPORT: "人员举报" };
  fields.urgency!.presentation.valueLabels = { NORMAL: "一般", URGENT: "需立即处置" };
  fields.grade!.presentation.valueLabels = { UNKNOWN: "待专业评估", GENERAL: "一般", MAJOR: "重大" };
  const definition: FindingDefinition = { appKey: "safety", entityKey: "finding", definitionVersion: "1", querySchemaVersion: "5", use,
    title: "隐患台账", publicationChecksum: "a".repeat(64), fields, layout: { list: [], detail: [], create: [], query: [] }, actions: {}, references: {} };
  if (use === "READ") {
    fields.id = field("UUID", "内部标识");
    fields.code = field("TEXT", "隐患编号", { operators: ["EQ", "CONTAINS"], maxLength: 64 });
    fields.state = field("ENUM", "状态", { operators: ["EQ", "IN"], enumValues: ["DISCOVERED", "PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING", "REPORTER_SUPPLEMENT_PENDING", "SAFETY_DISPATCH_PENDING", "RECTIFYING", "ACCEPTANCE_PENDING", "NOT_ESTABLISHED", "CLOSED"] });
    fields.state.presentation.uiKind = "STATUS";
    fields.state.presentation.valueLabels = { RECTIFYING: "整改中", CLOSED: "已闭环" };
    fields.createdAt = field("DATETIME", "上报日期", { operators: ["GTE", "LTE"] });
    definition.layout = { list: ["code", "description", "location", "state", "createdAt"], detail: ["description", "location", "createdAt", "sourceCategory", "urgency"], create: [], query: ["code", "state", "createdAt"] };
  } else {
    for (const [key, value] of Object.entries(fields)) value.presentation.edit = {
      required: !["interimMeasure", "sourceEventId", "riskId", "relatedFindingId"].includes(key), maxLength: key === "description" ? 20000 : key === "location" ? 200 : key === "interimMeasure" ? 2000 : 0,
      lengthUnit: key === "description" ? "UTF16_CODE_UNITS" : "UNICODE_CODE_POINTS", allowedValues: key === "grade" ? ["UNKNOWN"] : [...value.query.enumValues],
    };
    fields.grade!.query.enumValues = ["UNKNOWN"];
    fields.organizationId!.presentation.uiKind = "REFERENCE"; fields.organizationId!.presentation.referenceKey = "report-organization";
    definition.layout.create = ["organizationId", "description", "discoveredAt", "sourceCategory", "urgency", "location", "interimMeasure"];
    definition.actions["finding.create"] = { label: "提交上报", position: "CREATE", binding: { method: "POST", permission: "finding.create", path: "/api/v1/findings" }, expectedVersionRequired: false, idempotencyKeyRequired: true, inputFields: Object.keys(fields) };
    definition.references["report-organization"] = { binding: { resourceKey: "report-organization", method: "POST", permission: "finding.create", path: "/api/v1/entities/finding/references/report-organization/query" }, entityKey: "organization", history: "CURRENT" };
  }
  return definition;
}
export function definitionResponse(use: "READ" | "CREATE" = "READ") {
  return { data: findingDefinitionFixture(use), meta: { schemaVersion: "1", requestId: "fixture", traceId: "fixture" } };
}
