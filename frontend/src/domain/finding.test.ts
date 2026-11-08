import { describe, expect, it } from "vitest";
import { findingStateLabels, findingStates, workflowCurrentIndex, type FindingState } from "./finding";
import { findingLedgerSchema } from "../features/findings/schema";

const expectedLabels: Record<FindingState, string> = {
  DISCOVERED: "已发现·待启动流程",
  PERSON_CONFIRM_PENDING: "待一人确认",
  SAFETY_CONFIRM_PENDING: "待安全确认",
  REPORTER_SUPPLEMENT_PENDING: "待上报人补充",
  SAFETY_DISPATCH_PENDING: "待安全部门派单",
  RECTIFYING: "整改中",
  ACCEPTANCE_PENDING: "待安全验收",
  NOT_ESTABLISHED: "未成立",
  CLOSED: "已闭环",
};

describe("隐患流程状态映射", () => {
  it("查询选项和领域标签完整覆盖契约里的全部状态", () => {
    expect(findingStates.map((state) => findingStateLabels[state])).toEqual([
      "已发现·待启动流程",
      "待一人确认",
      "待安全确认",
      "待上报人补充",
      "待安全部门派单",
      "整改中",
      "待安全验收",
      "未成立",
      "已闭环",
    ]);

    const stateField = findingLedgerSchema.queryFields.find((field) => field.key === "state");
    if (stateField?.kind !== "enum") throw new Error("状态查询字段缺失");
    expect(stateField.options).toEqual(
      findingStates.map((state) => ({ value: state, label: expectedLabels[state] })),
    );
  });

  it("状态枚举与后端 OpenAPI 契约一致（顺序即流程顺序）", () => {
    expect([...findingStates]).toEqual([
      "DISCOVERED",
      "PERSON_CONFIRM_PENDING",
      "SAFETY_CONFIRM_PENDING",
      "REPORTER_SUPPLEMENT_PENDING",
      "SAFETY_DISPATCH_PENDING",
      "RECTIFYING",
      "ACCEPTANCE_PENDING",
      "NOT_ESTABLISHED",
      "CLOSED",
    ]);
  });

  it("每个状态映射到约定的工作流节点", () => {
    expect(findingStates.map(workflowCurrentIndex)).toEqual([0, 1, 2, 0, 3, 4, 5, 0, 5]);
  });
});
