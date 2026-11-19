import { createHttpLoans } from "../features/toolLoans/model";
import { createHttpEntityLedger } from "../features/ledger/httpEntityLedger";
import type { EntityLedgerFactory } from "../features/ledger/model";
import { createHttpPermissionAdmin } from "./httpPermissionAdmin";
import type { PermissionAdminAdapter } from "./permissionAdmin";
import { parseAppMode } from "../mode";
import { createHttpClient } from "../api/httpClient";
import type { FindingStore } from "../App";
import { createHttpFindingCommands } from "./httpFindingCommands";
import { createHttpFindingEvidence } from "./httpFindingEvidence";
import { createHttpFindingLedger } from "./httpFindingLedger";
import { createHttpFindingSubmit } from "./httpFindingSubmit";
import { createHttpSessionAdapter } from "./httpSessionAdapter";
import { syntheticSessionAdapter, type SessionAdapter } from "./sessionAdapter";

/** HTTP navigation and permissions come exclusively from the server bootstrap/menu. */
export function createSessionAdapter(mode: string | undefined): SessionAdapter {
  if (parseAppMode(mode) === "synthetic") return syntheticSessionAdapter;
  return createHttpSessionAdapter(createHttpClient());
}

/** http 模式下权限配置走服务端；演示模式返回 undefined，使用内存合成实现。 */
export function createPermissionAdminFactory(mode: string | undefined): (() => PermissionAdminAdapter) | undefined {
  if (parseAppMode(mode) === "synthetic") return undefined;
  return () => createHttpPermissionAdmin(createHttpClient());
}

/** http 模式的台账、上报及流程命令均走服务端；每个登录身份新建 store，避免跨账号复用 schema 缓存。 */
export function createFindingStoreFactory(mode: string | undefined): (() => FindingStore) | undefined {
  if (parseAppMode(mode) === "synthetic") return undefined;
  return () => {
    const client = createHttpClient();
    return {
      ledger: createHttpFindingLedger(client),
      submit: createHttpFindingSubmit(client),
      commands: createHttpFindingCommands(client),
      evidence: createHttpFindingEvidence(client),
    };
  };
}

/** One standard adapter serves every server-registered entity, isolated by the app identity boundary. */
export function createEntityLedgerFactory(mode: string | undefined): EntityLedgerFactory | undefined {
  if (parseAppMode(mode) === "synthetic") return undefined;
  return (app, entity) => createHttpEntityLedger(createHttpClient(), app, entity);
}

export function createLoanFactory(mode: string | undefined) {
  if (parseAppMode(mode) === "synthetic") return undefined;
  return () => createHttpLoans(createHttpClient());
}
