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
