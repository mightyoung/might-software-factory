import type { components as UploadComponents } from "../api/generated/finding-evidence-upload";
import type { components as InitComponents } from "../api/generated/finding-evidence-set-initialization";
import { classifyHttpError } from "../api/classifyError";
import { ApiError, type HttpClient } from "../api/httpClient";
import {
  FindingEvidenceError,
  type EvidenceFailureKind,
  type FindingEvidenceAdapter,
  type UploadScope,
  type UploadStatus,
} from "./findingEvidence";

type StatusBody = UploadComponents["schemas"]["StatusResponse"] | UploadComponents["schemas"]["ReservationResponse"];

function base({ findingId, roundNo }: UploadScope): string {
  return `/api/v1/findings/${encodeURIComponent(findingId)}/remediation-rounds/${roundNo}`;
}

function toStatus({ data }: Pick<StatusBody, "data">): UploadStatus {
  return { intentId: data.uploadIntentId, state: data.state, expiresAt: data.expiresAt, cancelRequested: data.cancelRequested === true };
}

function classify(error: ApiError): EvidenceFailureKind {
  if (error.status === 413) return "tooLarge";
  if (error.status === 415) return "unsupportedMedia";
  switch (classifyHttpError(error)) {
    case "invalid":
      return "validation";
    case "unauthenticated":
      return "unauthenticated";
    case "forbidden":
    case "notVisible":
      return "forbidden";
    case "conflict":
      return error.code === "ALREADY_RECEIVED" ? "alreadyReceived" : "conflict";
    case "workflow":
      return "assignment";
    case "rateLimited":
      return "rateLimited";
    default:
      return "unavailable";
  }
}

async function guard<T>(run: () => Promise<T>): Promise<T> {
  try {
    return await run();
  } catch (error) {
    if (error instanceof ApiError) throw new FindingEvidenceError(classify(error), error.traceId);
    throw error;
  }
}

export function createHttpFindingEvidence(client: HttpClient): FindingEvidenceAdapter {
  return {
    currentSet: async (scope, signal) => {
      try {
        const { data } = await client.get<UploadComponents["schemas"]["EvidenceSetResponse"]>(`${base(scope)}/evidence-set`, { signal });
        return {
          evidenceSetId: data.evidenceSetId,
          evidenceRevision: data.evidenceRevision,
          uploads: data.uploads.map((upload) => toStatus({ data: upload })),
        };
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return null; // 还没建过证据集，或看不到
        if (error instanceof ApiError) throw new FindingEvidenceError(classify(error), error.traceId);
        throw error;
      }
    },
    initializeSet: (scope, expectedFindingVersion, key, signal) =>
      guard(async () => {
        const response = await client.post<InitComponents["schemas"]["InitializationResponse"]>(`${base(scope)}/evidence-sets`, {
          body: { expectedFindingVersion },
          headers: { "Idempotency-Key": key },
          signal,
        });
        return { evidenceSetId: response.data.evidenceSetId, initialEvidenceRevision: response.data.initialEvidenceRevision };
      }),
    reserveUpload: (scope, declaration, key, signal) =>
      guard(async () => toStatus(await client.post<StatusBody>(`${base(scope)}/evidence-uploads`, { body: declaration, headers: { "Idempotency-Key": key }, signal }))),
    sendContent: (scope, intentId, content, signal) =>
      guard(async () => toStatus(await client.put<StatusBody>(`${base(scope)}/evidence-uploads/${encodeURIComponent(intentId)}/content`, { rawBody: content, signal }))),
    getStatus: (scope, intentId, signal) =>
      guard(async () => toStatus(await client.get<StatusBody>(`${base(scope)}/evidence-uploads/${encodeURIComponent(intentId)}`, { signal }))),
    cancel: (scope, intentId, signal) =>
      guard(async () => toStatus(await client.post<StatusBody>(`${base(scope)}/evidence-uploads/${encodeURIComponent(intentId)}/cancel`, { signal }))),
  };
}
