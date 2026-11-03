import type { components as HistoryComponents } from "../api/generated/finding-review-history";
import type { components } from "../api/generated/finding-workflow";
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
    case "reviewDecision": {
      const { kind: _, ...body } = command;
      return { path: `${base}/review-decisions`, body };
    }
    case "supplement":
      return { path: `${base}/supplements`, body: { reviewCycle: command.reviewCycle, statement: command.statement } };
    case "closeNotEstablished":
      return { path: `${base}/close-not-established`, body: { stage: command.stage, reason: command.reason } };
    case "initialize":
      return { path: `${base}/workflow-initialization`, body: {} };
    case "confirmPerson":
      return { path: `${base}/confirm/person`, body: { reason: command.reason } };
    case "confirmSafety":
      return { path: `${base}/confirm/safety`, body: { reason: command.reason, grade: command.grade } };
    case "verify":
      return {
        path: `${base}/verifications`,
        body: { decision: command.decision, ...(command.reason ? { reason: command.reason } : {}) },
      };
  }
}

function classify(error: ApiError): FindingCommandFailureKind {
  switch (error.status) {
    case 400:
      return "validation";
    case 401:
      return "unauthenticated";
    case 403:
    case 404: // 不存在与无权同为 404，不区分
      return "forbidden";
    case 409:
      if (error.code === "VERSION_CONFLICT") return "versionConflict";
      if (error.code === "IDEMPOTENCY_CONFLICT") return "idempotencyConflict";
      return "stateConflict"; // COMMAND_CONFLICT 及未知 409 一律按“状态已变化”处理
    case 422:
      return "workflow";
    default:
      return "unavailable";
  }
}

export function createHttpFindingCommands(client: HttpClient): FindingCommandAdapter {
  return {
    async history(findingId, options) {
      try {
        const query = options?.cursor ? `&cursor=${encodeURIComponent(options.cursor)}` : "";
        return (await client.get<HistoryComponents["schemas"]["HistoryResponse"]>(
          `/api/v1/findings/${encodeURIComponent(findingId)}/review-history?pageSize=20${query}`, { signal: options?.signal },
        )).data;
      } catch (error) {
        if (error instanceof ApiError) throw new FindingCommandError(classify(error), error.traceId);
        throw error;
      }
    },
    async run(findingId, expectedVersion, command, { idempotencyKey, signal }) {
      const { path, body } = endpoint(findingId, command);
      try {
        const response = await client.post<Response | components["schemas"]["ReviewCycleCommandResponse"]>(path, {
          body: { expectedVersion, ...body },
          headers: { "Idempotency-Key": idempotencyKey },
          signal,
        });
        const { state, roundNo, version } = response.data;
        return { state, roundNo, version, ...("reviewCycle" in response.data ? { reviewCycle: response.data.reviewCycle } : {}) };
      } catch (error) {
        if (error instanceof ApiError) throw new FindingCommandError(classify(error), error.traceId);
        throw error;
      }
    },
  };
}
