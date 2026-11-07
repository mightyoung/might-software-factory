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
  expiring = false;
}

/** 登录成功：结束被动失效状态，并丢弃不属于当前登录者的草稿。 */
export function onSignedIn(owner: string): void {
  expiring = false;
  for (const key of [...stash.keys()]) if (key !== owner) stash.delete(key);
}
