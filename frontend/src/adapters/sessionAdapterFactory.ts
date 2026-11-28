import type { EntityLedgerFactory } from "../features/ledger/model";
import type { PermissionAdminAdapter } from "./permissionAdmin";
import { parseAppMode } from "../mode";
import type { FindingStore } from "../App";
import { createHttpAppSession, httpAppAdapterFactories } from "./httpAppAdapters";
import { syntheticSessionAdapter, type SessionAdapter } from "./sessionAdapter";

/** HTTP navigation and permissions come exclusively from the server bootstrap/menu. */
export function createSessionAdapter(mode: string | undefined): SessionAdapter {
  if (parseAppMode(mode) === "synthetic") return syntheticSessionAdapter;
  return createHttpAppSession();
}

/** http 模式下权限配置走服务端；演示模式返回 undefined，使用内存合成实现。 */
export function createPermissionAdminFactory(mode: string | undefined): (() => PermissionAdminAdapter) | undefined {
  if (parseAppMode(mode) === "synthetic") return undefined;
  return httpAppAdapterFactories.createPermissionAdmin;
}

/** http 模式的台账、上报及流程命令均走服务端；每个登录身份新建 store，避免跨账号复用 schema 缓存。 */
export function createFindingStoreFactory(mode: string | undefined): (() => FindingStore) | undefined {
  if (parseAppMode(mode) === "synthetic") return undefined;
  return httpAppAdapterFactories.createFindingStore;
}

/** One standard adapter serves every server-registered entity, isolated by the app identity boundary. */
export function createEntityLedgerFactory(mode: string | undefined): EntityLedgerFactory | undefined {
  if (parseAppMode(mode) === "synthetic") return undefined;
  return httpAppAdapterFactories.createEntityLedger;
}

export function createLoanFactory(mode: string | undefined) {
  if (parseAppMode(mode) === "synthetic") return undefined;
  return httpAppAdapterFactories.createLoans;
}
