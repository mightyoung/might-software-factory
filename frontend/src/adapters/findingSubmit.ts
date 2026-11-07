import type { FindingState, FindingUrgency } from "../domain/finding";
import type { FindingDraft, FindingDraftErrors } from "../domain/findingDraft";
import type { FindingDefinition } from "./findingDefinition";

export interface FindingSubmitContext {
  /**
   * 命令幂等键：同一份内容的重试必须复用同一个键，服务端据此保证重复提交不产生第二条隐患；
   * 同键异参应被拒绝（conflict）。
   */
  idempotencyKey: string;
  /** 所属组织：仅当适配器提供组织引用查询或 listOrganizations 时必填，来自当前会话有权上报的组织。 */
  organizationId?: string;
  signal?: AbortSignal;
}

export interface ReportableOrganization {
  id: string;
  code: string;
  name: string;
}

export interface ReportableOrganizationQuery {
  query: string;
  afterId?: string;
  limit?: number;
}
export interface ReportableOrganizationPage {
  items: readonly ReportableOrganization[];
  nextAfterId?: string;
}

export interface FindingSubmitResult {
  id: string;
  code: string;
  state: FindingState;
  /** 后端回执目前不含提交时间和补报标记（frontend-contract-gap W6），HTTP 实现不推断。 */
  submittedAt?: string;
  backfill?: boolean;
}

/**
 * 上报提交端口。请求不携带上报人、租户或角色：这些只能来自服务端验证过的会话。
 * HTTP 实现读取当前 CREATE 定义并使用分页组织引用；合成实现保持显式演示。
 */
export interface FindingSubmitAdapter {
  loadDefinition?(signal?: AbortSignal): Promise<FindingDefinition>;
  queryOrganizations?(request: ReportableOrganizationQuery, signal?: AbortSignal): Promise<ReportableOrganizationPage>;
  submit(draft: FindingDraft, context: FindingSubmitContext): Promise<FindingSubmitResult>;
  /** 服务端接受的紧急程度；缺省为全部。表单只提供这些选项，避免用户选了必然被拒的档位。 */
  readonly urgencies?: readonly FindingUrgency[];
  /** 合成或兼容端口；有此方法时表单要求选择组织，空列表表示无可上报组织。 */
  listOrganizations?(signal?: AbortSignal): Promise<readonly ReportableOrganization[]>;
}

/** 仅为界面语义分类，对应设计里的 401/403/409/422/503；真实传输需显式映射服务端行为。 */
export type FindingSubmitFailureKind = "validation" | "workflow" | "unauthenticated" | "forbidden" | "conflict" | "unavailable";

export class FindingSubmitError extends Error {
  readonly kind: FindingSubmitFailureKind;
  readonly fieldErrors: FindingDraftErrors;
  readonly retryable: boolean;

  constructor(kind: FindingSubmitFailureKind, fieldErrors: FindingDraftErrors = {}) {
    super(`Finding submit ${kind}`);
    this.name = "FindingSubmitError";
    this.kind = kind;
    this.fieldErrors = fieldErrors;
    this.retryable = kind === "unavailable";
  }
}

/** crypto.randomUUID 只在安全上下文可用（经局域网 http 访问时没有），因此保留 getRandomValues 兜底。 */
export function newIdempotencyKey(): string {
  const webCrypto = globalThis.crypto;
  if (typeof webCrypto?.randomUUID === "function") return webCrypto.randomUUID();
  const bytes = new Uint8Array(16);
  webCrypto.getRandomValues(bytes);
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
