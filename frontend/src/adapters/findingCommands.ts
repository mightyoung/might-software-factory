import type { components } from "../api/generated/finding-review-history";
import type { FindingState } from "../domain/finding";

/** 后端已有契约、前端已接线的流程命令。派单需要成员查询接口、提交整改需要证据集接口，二者契约暂缺，见 docs/frontend-contract-gap.md。 */
export type FindingReviewHistory = components["schemas"]["HistoryData"];
export type FindingReviewEntry = components["schemas"]["HistoryEntry"];

export type FindingCommand =
  | { kind: "initialize" }
  | { kind: "closeNotEstablished"; stage: "PERSON" | "SAFETY"; reason: string }
  | { kind: "reviewDecision"; reviewCycle: number; stage: "PERSON"; decision: "APPROVE" | "RETURN_FOR_INFORMATION" | "NOT_ESTABLISHED"; reason: string }
  | { kind: "reviewDecision"; reviewCycle: number; stage: "SAFETY"; decision: "APPROVE"; reason: string; grade: "GENERAL" | "MAJOR" }
  | { kind: "reviewDecision"; reviewCycle: number; stage: "SAFETY"; decision: "RETURN_FOR_INFORMATION" | "NOT_ESTABLISHED"; reason: string }
  | { kind: "supplement"; reviewCycle: number; statement: string }
  | { kind: "confirmPerson"; reason: string }
  | { kind: "confirmSafety"; reason: string; grade: "GENERAL" | "MAJOR" }
  | { kind: "verify"; decision: "PASS" | "FAIL"; reason?: string };

export interface FindingCommandContext {
  /** 同一份命令的重试复用同一个键；内容变化才换新键。 */
  idempotencyKey: string;
  signal?: AbortSignal;
}

export interface FindingCommandResult {
  state: FindingState;
  roundNo: number;
  version: number;
  reviewCycle?: number;
}

export interface FindingCommandAdapter {
  history?(findingId: string, options?: { cursor?: string; signal?: AbortSignal }): Promise<FindingReviewHistory>;
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
