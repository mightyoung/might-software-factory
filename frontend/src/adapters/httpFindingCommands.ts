import type { components } from "../api/generated/finding-workflow";
import { classifyHttpError } from "../api/classifyError";
import { ApiError, type HttpClient } from "../api/httpClient";
import {
  FindingCommandError,
  type FindingCommand,
  type FindingCommandAdapter,
  type FindingCommandFailureKind,
} from "./findingCommands";

type Response = components["schemas"]["CommandResponse"];

function endpoint(id: string, command: FindingCommand): { path: string; body: Record<string, unknown> } {
  const base = `/api/v1/findings/${encodeURIComponent(id)}`;
  switch (command.kind) {
    case "initialize":
      return { path: `${base}/workflow-initialization`, body: {} };
    case "confirmPerson":
      return { path: `${base}/confirm/person`, body: { reason: command.reason } };
    case "confirmSafety":
      return { path: `${base}/confirm/safety`, body: { reason: command.reason, grade: command.grade } };
    case "closeNotEstablished":
      return { path: `${base}/close-not-established`, body: { stage: command.stage, reason: command.reason } };
    case "dispatch":
      return {
        path: `${base}/dispatch`,
        body: { assigneeMemberId: command.assigneeMemberId, dueAt: command.dueAt, requirements: command.requirements },
      };
    case "verify":
      return {
        path: `${base}/verifications`,
        body: { decision: command.decision, ...(command.reason ? { reason: command.reason } : {}) },
      };
  }
}

function classify(error: ApiError): FindingCommandFailureKind {
  switch (classifyHttpError(error)) {
    case "invalid":
      return "validation";
    case "unauthenticated":
      return "unauthenticated";
    case "forbidden":
    case "notVisible": // 不存在与无权同为 404，不区分
      return "forbidden";
    case "conflict":
      if (error.code === "VERSION_CONFLICT") return "versionConflict";
      if (error.code === "IDEMPOTENCY_CONFLICT") return "idempotencyConflict";
      return "stateConflict"; // COMMAND_CONFLICT 及未知 409 一律按“状态已变化”处理
    case "workflow":
      return "workflow";
    default:
      return "unavailable";
  }
}

export function createHttpFindingCommands(client: HttpClient): FindingCommandAdapter {
  return {
    async searchDispatchCandidates(findingId, { prefix, afterMemberId, limit }, signal) {
      const params = new URLSearchParams();
      if (prefix) params.set("q", prefix);
      if (limit !== undefined) params.set("limit", String(limit));
      if (afterMemberId) params.set("afterMemberId", afterMemberId);
      const query = params.toString();
      try {
        const body = await client.get<components["schemas"]["DispatchCandidatesResponse"]>(
          `/api/v1/findings/${encodeURIComponent(findingId)}/dispatch-candidates${query ? `?${query}` : ""}`,
          { signal },
        );
        return {
          items: body.data.map(({ memberId, displayName }) => ({ memberId, displayName })),
          hasMore: body.hasMore,
          next: body.nextAfterMemberId,
          findingVersion: body.findingVersion,
          state: body.state,
          roundNo: body.roundNo,
        };
      } catch (error) {
        if (error instanceof ApiError) throw new FindingCommandError(classify(error), error.traceId);
        throw error;
      }
    },
    async run(findingId, expectedVersion, command, { idempotencyKey, signal }) {
      const { path, body } = endpoint(findingId, command);
      try {
        const response = await client.post<Response>(path, {
          body: { expectedVersion, ...body },
          headers: { "Idempotency-Key": idempotencyKey },
          signal,
        });
        const { state, roundNo, version } = response.data;
        return { state, roundNo, version };
      } catch (error) {
        if (error instanceof ApiError) throw new FindingCommandError(classify(error), error.traceId);
        throw error;
      }
    },
  };
}
