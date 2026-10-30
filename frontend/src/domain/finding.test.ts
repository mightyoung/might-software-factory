import { describe, expect, it } from "vitest";
import { findingStateLabels, findingStates, workflowCurrentIndex, type FindingState } from "./finding";
import { findingLedgerSchema } from "../features/findings/schema";

const expectedLabels: Record<FindingState, string> = {
  PERSON_CONFIRM_PENDING: "待一人确认",
  SAFETY_CONFIRM_PENDING: "待安全确认",
  SAFETY_DISPATCH_PENDING: "待安全部门派单",
  RECTIFYING: "整改中",
  ACCEPTANCE_PENDING: "待安全验收",
  CLOSED: "已闭环",
};

describe("隐患流程状态映射", () => {
  it("查询选项和领域标签完整覆盖六个状态", () => {
    expect(findingStates.map((state) => findingStateLabels[state])).toEqual([
      "待一人确认",
      "待安全确认",
      "待安全部门派单",
      "整改中",
      "待安全验收",
      "已闭环",
    ]);

    const stateField = findingLedgerSchema.queryFields.find((field) => field.key === "state");
    if (stateField?.kind !== "enum") throw new Error("状态查询字段缺失");
    expect(stateField.options).toEqual(
      findingStates.map((state) => ({ value: state, label: expectedLabels[state] })),
    );
  });

  it("每个状态映射到约定的工作流节点", () => {
    expect(findingStates.map(workflowCurrentIndex)).toEqual([1, 2, 3, 4, 5, 5]);
  });
});
