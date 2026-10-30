import { describe, expect, it } from "vitest";
import { toggleVisibleRows } from "./selection";

describe("当前页行选择", () => {
  it("表头切换只影响传入的可见行", () => {
    const afterSelectingPageOne = toggleVisibleRows(new Set<string>(), ["A", "B"]);
    const afterSelectingPageTwo = toggleVisibleRows(afterSelectingPageOne, ["C", "D"]);

    expect([...afterSelectingPageTwo]).toEqual(["A", "B", "C", "D"]);
    expect(toggleVisibleRows(afterSelectingPageTwo, ["C", "D"])).toEqual(new Set(["A", "B"]));
  });

  it("不会把空页当成全选", () => {
    expect(toggleVisibleRows(new Set(["A"]), [])).toEqual(new Set(["A"]));
  });
});
