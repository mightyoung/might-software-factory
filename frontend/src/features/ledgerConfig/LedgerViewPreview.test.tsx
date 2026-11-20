import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { LedgerViewPreview } from "./LedgerViewPreview";
import type { LedgerConfiguration } from "./model";

const definition: LedgerConfiguration = {
  name: "采购申请",
  fields: [
    { key: "name", label: "名称", type: "text", required: true },
    { key: "quantity", label: "数量", type: "number", required: true },
    { key: "date", label: "到货日期", type: "date", required: false },
    { key: "accepted", label: "已验收", type: "boolean", required: false },
  ],
  listFields: ["date", "quantity"],
  queryFields: ["quantity", "name", "accepted", "date"],
};

afterEach(cleanup);

describe("LedgerViewPreview", () => {
  it("uses independent list and query order with disabled controls of each configured type", () => {
    render(<LedgerViewPreview definition={definition} />);
    const list = screen.getByRole("table", { name: "列表预览" });
    expect(within(list).getAllByRole("columnheader").map((cell) => cell.textContent))
      .toEqual(["到货日期", "数量"]);
    const query = screen.getByRole("group", { name: "查询条件预览" });
    expect(Array.from(query.querySelectorAll("label")).map((label) => label.textContent))
      .toEqual(["数量（查询预览）", "名称（查询预览）", "已验收（查询预览）全部是否", "到货日期（查询预览）"]);
    expect(within(query).getByLabelText("数量（查询预览）")).toHaveAttribute("type", "number");
    expect(within(query).getByLabelText("名称（查询预览）")).toHaveAttribute("type", "text");
    expect(within(query).getByLabelText("到货日期（查询预览）")).toHaveAttribute("type", "date");
    expect(within(query).getByRole("combobox")).toHaveValue("");
    for (const control of query.querySelectorAll("input, select, button")) {
      expect(control).toBeDisabled();
    }
    expect(within(list).getByText("暂无预览记录。")).toBeInTheDocument();
  });

  it("updates immediately when draft labels, field types and view selections change", () => {
    const { rerender } = render(<LedgerViewPreview definition={definition} />);
    rerender(<LedgerViewPreview definition={{
      ...definition,
      fields: [{ ...definition.fields[1], label: "采购数量", type: "text" }],
      listFields: ["quantity"],
      queryFields: ["quantity"],
    }} />);
    expect(screen.getAllByRole("columnheader").map((cell) => cell.textContent)).toEqual(["采购数量"]);
    expect(screen.getByLabelText("采购数量（查询预览）")).toHaveAttribute("type", "text");
    expect(screen.queryByLabelText("到货日期（查询预览）")).not.toBeInTheDocument();
  });

  it("supports empty and incomplete drafts without inventing fields or records", () => {
    const { rerender } = render(<LedgerViewPreview definition={{ name: "", fields: [], listFields: [], queryFields: [] }} />);
    expect(screen.getByText("未命名台账")).toBeInTheDocument();
    expect(screen.getByText("尚未选择查询字段。")).toBeInTheDocument();
    expect(screen.getByText("尚未选择列表字段。")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    rerender(<LedgerViewPreview definition={{
      ...definition,
      fields: [{ ...definition.fields[0], label: "" }],
      listFields: ["name", "missing"],
      queryFields: ["name", "missing"],
    }} />);
    expect(screen.getByRole("columnheader", { name: "name" })).toBeInTheDocument();
    expect(screen.getByLabelText("name（查询预览）")).toBeDisabled();
    expect(screen.getAllByText("未找到字段（missing）")).toHaveLength(2);
  });
});
