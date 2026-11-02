import { createHttpClient } from "../api/httpClient";
import type { FindingStore } from "../App";
import { createHttpFindingCommands } from "./httpFindingCommands";
import { createHttpFindingLedger } from "./httpFindingLedger";
import { createHttpFindingSubmit } from "./httpFindingSubmit";
import { createHttpSessionAdapter } from "./httpSessionAdapter";
import { syntheticSessionAdapter, type SessionAdapter } from "./sessionAdapter";

/** HTTP navigation and permissions come exclusively from the server bootstrap/menu. */
export function createSessionAdapter(mode: string | undefined): SessionAdapter {
  if (apiMode(mode) === "synthetic") return syntheticSessionAdapter;
  return createHttpSessionAdapter(createHttpClient());
}

/** http 模式的台账、上报及流程命令均走服务端；每个登录身份新建 store，避免跨账号复用 schema 缓存。 */
export function createFindingStoreFactory(mode: string | undefined): (() => FindingStore) | undefined {
  if (apiMode(mode) === "synthetic") return undefined;
  return () => {
    const client = createHttpClient();
    return {
      ledger: createHttpFindingLedger(client),
      submit: createHttpFindingSubmit(client),
      commands: createHttpFindingCommands(client),
    };
  };
}

function apiMode(mode: string | undefined): "synthetic" | "http" {
  if (mode === undefined || mode === "" || mode === "synthetic") return "synthetic";
  if (mode === "http") return "http";
  throw new Error("Unsupported VITE_API_MODE; choose synthetic or http");
}
