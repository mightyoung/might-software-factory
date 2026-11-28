import { fireEvent, render, screen, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { LedgerTable } from "./LedgerTable";

it("reads a visible cell once and keeps controls tied to the stable record id after row replacement", () => {
  const read = vi.fn((row: { id: string; name: string }) => row.name);
  const toggle = vi.fn();
  const open = vi.fn();
  const props = {
    columns: [{ key: "name", label: "名称", kind: "text" as const, read }],
    getKey: (row: { id: string }) => row.id,
    selectedIds: new Set(["b"]),
    onToggleRow: toggle,
    onTogglePage: vi.fn(),
    onOpen: open,
  };
  const first = [{ id: "a", name: "甲" }, { id: "b", name: "乙" }];
  const view = render(<LedgerTable {...props} rows={first} />);
  expect(screen.getByText("乙")).toHaveAttribute("title", "乙");
  expect(read.mock.calls).toEqual([[first[0]], [first[1]]]);
  const replacement = [{ id: "b", name: "乙更新" }, first[0]!];
  view.rerender(<LedgerTable {...props} rows={replacement} />);
  const row = screen.getByText("乙更新").closest("tr")!;
  const checkbox = within(row).getByRole("checkbox", { name: "选择 b" });
  expect(checkbox).toBeChecked();
  fireEvent.click(checkbox);
  fireEvent.click(within(row).getByRole("button", { name: "详情" }));
  expect(toggle).toHaveBeenCalledWith("b");
  expect(open).toHaveBeenCalledWith(replacement[0]);
});

it("recomputes values when a dynamic column keeps its key but changes its reader", () => {
  const props = {
    rows: [{ id: "a" }],
    getKey: (row: { id: string }) => row.id,
    selectedIds: new Set<string>(),
    onToggleRow: vi.fn(),
    onTogglePage: vi.fn(),
    onOpen: vi.fn(),
  };
  const column = { key: "value", label: "值", kind: "text" as const };
  const view = render(<LedgerTable {...props} columns={[{ ...column, read: () => "旧值" }]} />);
  expect(screen.getByText("旧值")).toBeInTheDocument();
  view.rerender(<LedgerTable {...props} columns={[{ ...column, read: () => "新值" }]} />);
  expect(screen.getByText("新值")).toBeInTheDocument();
  expect(screen.queryByText("旧值")).not.toBeInTheDocument();
});
