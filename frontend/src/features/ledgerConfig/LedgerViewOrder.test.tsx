import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LedgerViewOrder } from "./LedgerViewOrder";
import type { LedgerConfiguration } from "./model";

const definition: LedgerConfiguration = {
  name: "采购申请",
  fields: [
    { key: "quantity", label: "数量", type: "number", required: true },
    { key: "date", label: "日期", type: "date", required: false },
    { key: "name", label: "名称", type: "text", required: false },
  ],
  listFields: ["name", "quantity", "date"],
  queryFields: ["date", "name"],
};

afterEach(cleanup);

describe("LedgerViewOrder", () => {
  it("moves configured list and query fields independently without changing the source", () => {
    const onChange = vi.fn();
    render(<LedgerViewOrder definition={definition} disabled={false} onChange={onChange} />);
    const list = screen.getByRole("group", { name: "列表字段排序" });
    expect(within(list).getByRole("button", { name: "上移列表字段 名称（name）" })).toHaveAttribute("aria-disabled", "true");
    expect(within(list).getByRole("button", { name: "下移列表字段 日期（date）" })).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(within(list).getByRole("button", { name: "上移列表字段 数量（quantity）" }));
    expect(onChange).toHaveBeenLastCalledWith("listFields", ["quantity", "name", "date"]);

    fireEvent.click(screen.getByRole("button", { name: "下移查询字段 日期（date）" }));
    expect(onChange).toHaveBeenLastCalledWith("queryFields", ["name", "date"]);
    expect(definition.listFields).toEqual(["name", "quantity", "date"]);
    expect(definition.queryFields).toEqual(["date", "name"]);
  });

  it("disables reordering while the editor is locked or read-only", () => {
    const onChange = vi.fn();
    render(<LedgerViewOrder definition={definition} disabled onChange={onChange} />);
    for (const button of screen.getAllByRole("button")) {
      expect(button).toHaveAttribute("aria-disabled", "true");
      fireEvent.click(button);
    }
    expect(onChange).not.toHaveBeenCalled();
  });

  it("keeps duplicate unknown keys distinct without dropping a legacy row", () => {
    const onChange = vi.fn();
    const legacy = { ...definition, listFields: ["missing", "date", "missing"] };
    const { rerender } = render(<LedgerViewOrder definition={legacy} disabled={false} onChange={onChange} />);
    const list = screen.getByRole("list", { name: "列表字段" });
    const earlier = within(list).getAllByRole("button", { name: "下移列表字段 missing" })[0];
    earlier.focus();
    fireEvent.click(earlier);
    expect(onChange).toHaveBeenCalledWith("listFields", ["date", "missing", "missing"]);
    rerender(<LedgerViewOrder definition={{ ...legacy, listFields: ["date", "missing", "missing"] }} disabled={false} onChange={onChange} />);
    const results = within(list).getAllByRole("button", { name: "下移列表字段 missing" });
    expect(results).toHaveLength(2);
    expect(results[0]).toBe(earlier);
    expect(results[0]).toHaveFocus();
    expect(results[1]).toHaveAttribute("aria-disabled", "true");
  });

  it("keeps incomplete draft keys intact and prompts for empty selections", () => {
    const onChange = vi.fn();
    render(
      <LedgerViewOrder
        definition={{ ...definition, listFields: ["missing", ""], queryFields: [] }}
        disabled={false}
        onChange={onChange}
      />,
    );
    expect(screen.getByText("请先勾选查询字段。")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "上移列表字段 未命名字段" }));
    expect(onChange).toHaveBeenCalledWith("listFields", ["", "missing"]);
  });
});
