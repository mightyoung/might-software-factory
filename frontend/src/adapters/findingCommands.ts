import type { FindingState } from "../domain/finding";

/** 后端已有契约、前端已接线的流程命令。派单需要成员查询接口、提交整改需要证据集接口，二者契约暂缺，见 docs/frontend-contract-gap.md。 */
export type FindingCommand =
  | { kind: "initialize" }
  | { kind: "confirmPerson"; reason: string }
  | { kind: "confirmSafety"; reason: string; grade: "GENERAL" | "MAJOR" }
  | { kind: "verify"; decision: "PASS" | "FAIL"; reason?: string }
  /** 在当前确认阶段把隐患作为“不成立”终止；不带等级，保留已有等级。 */
  | { kind: "closeNotEstablished"; stage: "PERSON" | "SAFETY"; reason: string }
  /** dueAt 为带偏移的 ISO 时间；assigneeMemberId 必须来自派单候选。 */
  | { kind: "dispatch"; assigneeMemberId: string; dueAt: string; requirements: string };

export interface FindingCommandContext {
  /** 同一份命令的重试复用同一个键；内容变化才换新键。 */
  idempotencyKey: string;
  signal?: AbortSignal;
}

export interface FindingCommandResult {
  state: FindingState;
  roundNo: number;
  version: number;
}

export interface DispatchCandidate {
  memberId: string;
  displayName: string;
}

export interface DispatchCandidatePage {
  items: readonly DispatchCandidate[];
  hasMore: boolean;
  /** hasMore 时用作下一页的 afterMemberId。 */
  next: string | null;
  /** 候选是按这个版本 / 状态 / 轮次算出的快照提示；与详情不一致说明记录已变化。 */
  findingVersion: number;
  state: FindingState;
  roundNo: number;
}

export interface DispatchCandidateQuery {
  /** 显示名前缀（字面匹配，区分大小写，最多 100 个码点）。 */
  prefix?: string;
  afterMemberId?: string | null;
  limit?: number;
}

export interface FindingCommandAdapter {
  /** 当前有资格被派单的成员，只读提示；派单时服务端会重新校验权限、版本和目标资格。缺省表示没有此能力。 */
  searchDispatchCandidates?(
    findingId: string,
    query: DispatchCandidateQuery,
    signal?: AbortSignal,
  ): Promise<DispatchCandidatePage>;
  /** expectedVersion 取自刚读取的详情；服务端对版本、状态、职责、轮次最终校验。 */
  run(
    findingId: string,
    expectedVersion: number,
    command: FindingCommand,
    context: FindingCommandContext,
  ): Promise<FindingCommandResult>;
}

export type FindingCommandFailureKind =
  | "validation"
  | "unauthenticated"
  | "forbidden"
  | "versionConflict"
  | "idempotencyConflict"
  | "stateConflict"
  | "workflow"
  | "unavailable";

export class FindingCommandError extends Error {
  readonly retryable: boolean;
  constructor(
    readonly kind: FindingCommandFailureKind,
    readonly traceId: string | null = null,
  ) {
    super(`Finding command ${kind}`);
    this.name = "FindingCommandError";
    this.retryable = kind === "unavailable";
  }
}
