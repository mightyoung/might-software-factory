import type { FindingDraft } from "../../domain/findingDraft";

/**
 * 会话被动失效（401）时，保住上报表单里还没提交的内容，同一个人重新登录后恢复。
 * 只放在内存里：不写 localStorage / sessionStorage（隐患描述可能含敏感信息，共用电脑上不应留下）。
 * 用户主动退出、改密、换成另一个账号登录时一律清空。
 */
export interface StashedDraft {
  draft: FindingDraft;
  organizationId: string;
}

const stash = new Map<string, StashedDraft>();
export interface EntityPendingRequest { key: string; fingerprint: string; definitionVersion: string; values: Record<string, string | number | null>; expectedVersion?: number }
export interface EntityDraftStash { request?: EntityPendingRequest; values: Record<string,string>; definitionVersion: string; key: string | null; fingerprint: string | null; locked: boolean; expectedVersion?: number }
const entityDrafts = new Map<string, Map<string, EntityDraftStash>>();
export function peekEntityDraft(owner: string, route: string) { return entityDrafts.get(owner)?.get(route); }
export function stashEntityDraft(owner: string, route: string, draft: EntityDraftStash) {
  const drafts = entityDrafts.get(owner) ?? new Map<string, EntityDraftStash>(); drafts.set(route,draft); entityDrafts.set(owner,drafts);
}
export function discardEntityDraft(owner: string, route: string) { entityDrafts.get(owner)?.delete(route); }
let expiring = false;

export const draftOwnerKey = (tenantId: string, actorId: string): string => `${tenantId}/${actorId}`;

/** 会话即将被动失效：此后卸载的上报表单应当把草稿存下来。 */
export function beginExpiry(): void {
  expiring = true;
}
export function isExpiring(): boolean {
  return expiring;
}

export function stashDraft(owner: string, value: StashedDraft): void {
  stash.set(owner, value);
}
export function peekDraft(owner: string): StashedDraft | undefined {
  return stash.get(owner);
}
export function discardDraft(owner: string): void {
  stash.delete(owner);
}

/** 主动退出 / 改密：全部丢弃，并结束“被动失效”状态。 */
export function clearAllDrafts(): void {
  stash.clear();
  entityDrafts.clear();
  expiring = false;
}

/** 登录成功：结束被动失效状态，并丢弃不属于当前登录者的草稿。 */
export function onSignedIn(owner: string): void {
  expiring = false;
  for (const key of [...stash.keys()]) if (key !== owner) stash.delete(key);
  for (const key of [...entityDrafts.keys()]) if (key !== owner) entityDrafts.delete(key);
}
