import { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createEmptyQuery, type LedgerSchema, type QueryValue, type QueryValues } from "../domain/ledger";
import { findingLedgerSchema } from "../features/findings/schema";
import { LedgerQueryForm } from "./LedgerQueryForm";

function QueryHarness({ onSearch = () => undefined }: { onSearch?: () => void } = {}) {
  const [values, setValues] = useState<QueryValues>(() => createEmptyQuery(findingLedgerSchema.queryFields));
  return (
    <LedgerQueryForm
      schema={findingLedgerSchema}
      values={values}
      onChange={(key, value: QueryValue) => setValues((current) => ({ ...current, [key]: value }))}
      onSearch={onSearch}
      onReset={() => setValues(createEmptyQuery(findingLedgerSchema.queryFields))}
    />
  );
}

interface DemoReferenceRecord {
  severity: number;
  responsibleOrgId: string;
}

const referenceSchema: LedgerSchema<DemoReferenceRecord> = {
  entityKey: "demo.reference-query",
  title: "类型测试",
  queryFields: [
    { key: "severity", label: "最小数值", kind: "number", operator: "atLeast", read: (record) => record.severity },
    {
      key: "responsibleOrgId",
      label: "责任部门",
      kind: "reference",
      options: [{ id: "demo-org-a", label: "演示部门 A" }],
      read: (record) => record.responsibleOrgId,
    },
  ],
  columns: [],
  recordKey: () => "demo",
};

function ReferenceHarness({ onSearch = () => undefined }: { onSearch?: () => void } = {}) {
  const [values, setValues] = useState<QueryValues>(() => createEmptyQuery(referenceSchema.queryFields));
  return (
    <LedgerQueryForm
      schema={referenceSchema}
      values={values}
      onChange={(key, value: QueryValue) => setValues((current) => ({ ...current, [key]: value }))}
      onSearch={onSearch}
      onReset={() => setValues(createEmptyQuery(referenceSchema.queryFields))}
    />
  );
}

afterEach(cleanup);

describe("通用台账查询表单", () => {
  it("按字段类型生成输入、选择器和日期区间，重置会清空全部条件", () => {
    let submitCount = 0;
    render(<QueryHarness onSearch={() => { submitCount += 1; }} />);
    expect(screen.getByRole("button", { name: "查询" })).toHaveAttribute("type", "submit");
    expect(screen.getByRole("button", { name: "重置" })).toHaveAttribute("type", "button");
    const keyword = screen.getByRole("searchbox", { name: "隐患编号" });
    const state = screen.getByRole("combobox", { name: "状态" });
    const from = screen.getByLabelText("上报日期起");
    const to = screen.getByLabelText("上报日期止");

    fireEvent.change(keyword, { target: { value: "隔离" } });
    fireEvent.change(state, { target: { value: "RECTIFYING" } });
    fireEvent.change(from, { target: { value: "2026-10-01" } });
    fireEvent.change(to, { target: { value: "2026-10-29" } });

    expect(keyword).toHaveValue("隔离");
    expect(state).toHaveValue("RECTIFYING");
    expect(from).toHaveValue("2026-10-01");
    expect(to).toHaveValue("2026-10-29");

    fireEvent.click(screen.getByRole("button", { name: "重置" }));
    expect(keyword).toHaveValue("");
    expect(state).toHaveValue("");
    expect(from).toHaveValue("");
    expect(to).toHaveValue("");
    expect(submitCount).toBe(0);
  });

  it("清除单个文本条件后保留其他条件、返回输入焦点并等待查询提交", () => {
    let submitCount = 0;
    render(<QueryHarness onSearch={() => { submitCount += 1; }} />);
    const keyword = screen.getByRole("searchbox", { name: "隐患编号" });
    const state = screen.getByRole("combobox", { name: "状态" });
    const from = screen.getByLabelText("上报日期起");
    expect(screen.queryByRole("button", { name: "清除 隐患编号" })).not.toBeInTheDocument();
    fireEvent.change(keyword, { target: { value: "YH-001" } });
    fireEvent.change(state, { target: { value: "RECTIFYING" } });
    fireEvent.change(from, { target: { value: "2026-10-01" } });
    const clear = screen.getByRole("button", { name: "清除 隐患编号" });
    expect(clear).toHaveAttribute("type", "button");
    fireEvent.click(clear);
    expect(keyword).toHaveValue("");
    expect(keyword).toHaveFocus();
    expect(state).toHaveValue("RECTIFYING");
    expect(from).toHaveValue("2026-10-01");
    expect(submitCount).toBe(0);
    expect(screen.queryByRole("button", { name: "清除 隐患编号" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "查询" }));
    expect(submitCount).toBe(1);
  });

  it("数值字段使用数字输入，引用字段只能选择传入的对象选项", () => {
    render(<ReferenceHarness />);
    const number = screen.getByRole("spinbutton", { name: "最小数值" });
    const department = screen.getByRole("combobox", { name: "责任部门" });

    fireEvent.change(number, { target: { value: "4" } });
    fireEvent.change(department, { target: { value: "demo-org-a" } });

    expect(number).toHaveValue(4);
    expect(department).toHaveValue("demo-org-a");
    expect(screen.getByRole("option", { name: "演示部门 A" })).toHaveValue("demo-org-a");
  });
  it("允许小数数值条件通过真实表单提交校验", () => {
    let submitCount = 0;
    render(<ReferenceHarness onSearch={() => { submitCount += 1; }} />);
    const number = screen.getByRole("spinbutton", { name: "最小数值" });
    fireEvent.change(number, { target: { value: "4.5" } });

    expect(number).toHaveAttribute("step", "any");
    expect((number as HTMLInputElement).validity.stepMismatch).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "查询" }));
    expect(submitCount).toBe(1);
  });

});
