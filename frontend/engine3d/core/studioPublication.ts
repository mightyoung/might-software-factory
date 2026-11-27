import { parseStudioSnapshot } from "../schema/studioSnapshot.ts";

export type PublicationPermission = "studio:publish" | "player:read";
export type PublicationTarget = Readonly<{ tenantId: string; screenId: string }>;
export type SyntheticPublicationPrincipal = Readonly<{
  tenantId: string;
  permissions: readonly PublicationPermission[];
}>;

type DeepReadonly<T> = T extends object ? { readonly [K in keyof T]: DeepReadonly<T[K]> } : T;
type ReadonlySnapshot = DeepReadonly<ReturnType<typeof parseStudioSnapshot>>;

export type PublishedStudioVersion = Readonly<PublicationTarget & {
  version: number;
  /** Canonical Studio v1 source, which can be parsed by the existing viewer. */
  source: string;
  snapshot: ReadonlySnapshot;
}>;

const ID = /^[a-z][a-z0-9_-]{0,63}$/;
const MAX_SCREENS = 32;
const MAX_VERSIONS = 8;
const MAX_SOURCE_BYTES = 4 * 1024 * 1024;

function deepFreeze<T>(value: T): DeepReadonly<T> {
  if (value && typeof value === "object") {
    for (const child of Object.values(value)) deepFreeze(child);
    Object.freeze(value);
  }
  return value as DeepReadonly<T>;
}

function authorize(
  principal: SyntheticPublicationPrincipal, target: PublicationTarget, permission: PublicationPermission,
) {
  if (!principal || !target || typeof target.tenantId !== "string" || !ID.test(target.tenantId) ||
      principal.tenantId !== target.tenantId ||
      !Array.isArray(principal.permissions) || !principal.permissions.includes(permission))
    throw new Error("无权操作此租户的发布场景，请核对权限。");
  if (typeof target.screenId !== "string" || !ID.test(target.screenId)) throw new RangeError("场景标识不合法。");
  return target.tenantId + "/" + target.screenId;
}

/** Session-only test projection. Principals are synthetic inputs, not proof of authentication
 * or server RBAC. Real apps must supply authenticated callers and transactional persistence.
 */
export function createStudioPublication() {
  const histories = new Map<string, PublishedStudioVersion[]>();
  let storedBytes = 0;

  return {
    publish(principal: SyntheticPublicationPrincipal, target: PublicationTarget, expectedVersion: number, raw: string) {
      const key = authorize(principal, target, "studio:publish");
      if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 0)
        throw new RangeError("预期版本必须为非负整数。");
      const history = histories.get(key) ?? [];
      const current = history.at(-1);
      if (expectedVersion !== (current?.version ?? 0)) throw new Error("发布版本冲突，请重新读取最新版本。");

      if (typeof raw !== "string") throw new TypeError("发布源必须为 Studio v1 JSON 文本。");
      const snapshot = parseStudioSnapshot(raw);
      if (snapshot.scene.screenId !== target.screenId) throw new RangeError("草稿场景标识不匹配。");
      const source = JSON.stringify({
        ...snapshot,
        floorplan: {
          walls: snapshot.floorplan.walls,
          // Area is derived on every read; never emitted as an input field.
          zones: snapshot.floorplan.zones.map(({ id, points }) => ({ id, points })),
        },
      });
      if (current?.source === source) return current;
      const bytes = new TextEncoder().encode(source).byteLength;
      if (history.length >= MAX_VERSIONS || (history.length === 0 && histories.size >= MAX_SCREENS) ||
          storedBytes + bytes > MAX_SOURCE_BYTES) throw new RangeError("当前会话的发布容量已达上限。");
      const version = Object.freeze({
        tenantId: target.tenantId, screenId: target.screenId,
        version: history.length + 1, source, snapshot: deepFreeze(snapshot),
      });
      history.push(version);
      histories.set(key, history);
      storedBytes += bytes;
      return version;
    },
    read(principal: SyntheticPublicationPrincipal, target: PublicationTarget, version?: number) {
      const key = authorize(principal, target, "player:read");
      if (version !== undefined && (!Number.isSafeInteger(version) || version <= 0))
        throw new RangeError("要读取的版本必须为正整数。");
      const history = histories.get(key);
      return version === undefined ? history?.at(-1) : history?.[version - 1];
    },
  };
}
