import { createHttpClient } from "../api/httpClient";
import { createHttpSessionAdapter } from "./httpSessionAdapter";
import { MENUS, syntheticSessionAdapter, type SessionAdapter } from "./sessionAdapter";

/**
 * VITE_API_MODE=http 时走同源 /api 的真实会话；其余（默认）仍是合成演示。
 * 后端暂无应用/权限/菜单接口（frontend-contract-gap S1/S2），http 模式下这三项是前端的**呈现假设**：
 * 只有生产安全应用，菜单沿用演示菜单；它们不授权任何操作，服务端对每个请求仍须鉴权，
 * 与后端 finding.create 等权限码的映射待定。
 */
export function createSessionAdapter(mode: string | undefined): SessionAdapter {
  if (mode !== "http") return syntheticSessionAdapter;
  return createHttpSessionAdapter(createHttpClient(), {
    applications: [{ id: "safety", name: "生产安全", entryPath: "safety/findings" }],
    permissions: ["safety.finding.read", "safety.finding.report"],
    menus: { safety: MENUS.safety },
  });
}
