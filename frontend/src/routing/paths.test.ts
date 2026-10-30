import { describe, expect, it } from "vitest";
import { loginPathWithNext, parsePath, safeNextPath, tenantPath } from "./paths";

describe("租户路径", () => {
  it("构造和解析往返一致，并编码租户 ID", () => {
    expect(tenantPath("demo-tenant", "safety/findings")).toBe("/t/demo-tenant/safety/findings");
    expect(tenantPath("a/b", "/x/")).toBe("/t/a%2Fb/x");
    expect(tenantPath("demo", "")).toBe("/t/demo");
    expect(parsePath("/t/a%2Fb/x/y")).toEqual({ tenantId: "a/b", rest: "x/y" });
    expect(parsePath("/t/demo")).toEqual({ tenantId: "demo", rest: "" });
  });

  it("没有租户前缀或编码畸形时 tenantId 为 null", () => {
    expect(parsePath("/safety/findings")).toEqual({ tenantId: null, rest: "safety/findings" });
    expect(parsePath("/").tenantId).toBeNull();
    expect(parsePath("/t/%E0%A4%A/x").tenantId).toBeNull();
  });
});

describe("登录返回地址", () => {
  it("只接受属于当前租户的本站相对路径，并保留查询串", () => {
    expect(safeNextPath("/t/demo/safety/findings?caseId=1", "demo")).toBe("/t/demo/safety/findings?caseId=1");
  });

  it("接受尚无租户前缀的旧地址（登录后会被规范化），例如旧书签", () => {
    expect(safeNextPath("/safety/findings?caseId=1", "demo")).toBe("/safety/findings?caseId=1");
  });

  it.each([
    ["空值", null],
    ["绝对地址", "https://evil.example/t/demo/x"],
    ["协议相对地址", "//evil.example/t/demo/x"],
    ["反斜杠", "/t/demo/x\\..\\y"],
    ["控制字符", "/t/demo/x\ny"],
    ["其他租户", "/t/other/safety/findings"],
    ["根路径", "/"],
    ["租户根（没有具体页面）", "/t/demo"],
    ["登录页自身", "/login"],
  ])("拒绝%s", (_name, raw) => {
    expect(safeNextPath(raw, "demo")).toBeNull();
  });

  it("为受保护地址生成带 next 的登录地址，根路径和登录页不带 next", () => {
    expect(loginPathWithNext("/t/demo/safety/findings?caseId=1")).toBe(
      "/login?next=%2Ft%2Fdemo%2Fsafety%2Ffindings%3FcaseId%3D1",
    );
    expect(loginPathWithNext("/")).toBe("/login");
    expect(loginPathWithNext("/login?next=x")).toBe("/login");
  });
});
