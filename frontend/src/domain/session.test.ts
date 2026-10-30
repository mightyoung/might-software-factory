import { describe, expect, it } from "vitest";
import { visibleMenuNodes, type MenuNode } from "./session";

const nodes: MenuNode[] = [
  {
    id: "g",
    label: "分组",
    children: [
      { id: "a", label: "A", path: "x/a", permission: "x.a" },
      { id: "b", label: "B", path: "x/b", permission: "x.b" },
    ],
  },
  { id: "empty", label: "空分组", children: [{ id: "c", label: "C", path: "x/c", permission: "x.c" }] },
  { id: "leaf", label: "叶子", path: "x/leaf", permission: "x.leaf" },
  { id: "nopermission", label: "未声明权限", path: "x/n" },
];

describe("visibleMenuNodes", () => {
  it("只保留有权限的叶子；没有可见子项的分组整体隐藏", () => {
    const result = visibleMenuNodes(nodes, { permissions: ["x.a", "x.leaf"] });
    expect(result.map((node) => node.id)).toEqual(["g", "leaf"]);
    expect(result[0]?.children?.map((child) => child.id)).toEqual(["a"]);
  });

  it("没有任何权限时返回空；未声明权限的叶子默认不显示", () => {
    expect(visibleMenuNodes(nodes, { permissions: [] })).toEqual([]);
  });

  it("不修改传入的菜单定义", () => {
    visibleMenuNodes(nodes, { permissions: ["x.a"] });
    expect(nodes[0]?.children).toHaveLength(2);
  });
});
