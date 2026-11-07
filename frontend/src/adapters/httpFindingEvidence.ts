import type { components as Upload } from "../api/generated/finding-evidence-upload";
import type { components as Initialization } from "../api/generated/finding-evidence-set-initialization";
import { ApiError, type HttpClient } from "../api/httpClient";
import type { EvidenceUploadContext, FindingEvidenceAdapter } from "./findingEvidence";

function base({ findingId, roundNo }: EvidenceUploadContext): string {
  return `/api/v1/findings/${encodeURIComponent(findingId)}/remediation-rounds/${roundNo}`;
}
const statusPath = (context: EvidenceUploadContext, id: string) => `${base(context)}/evidence-uploads/${encodeURIComponent(id)}`;

export function createHttpFindingEvidence(client: HttpClient): FindingEvidenceAdapter {
  return {
    async initialize(context, expectedFindingVersion, key, signal) {
      return (await client.post<Initialization["schemas"]["InitializationResponse"]>(`${base(context)}/evidence-sets`, {
        body: { expectedFindingVersion }, headers: { "Idempotency-Key": key }, signal,
      })).data;
    },
    async reserve({ findingId, roundNo, ...declaration }, key, signal) {
      return (await client.post<Upload["schemas"]["ReservationResponse"]>(`${base({ findingId, roundNo })}/evidence-uploads`, {
        body: declaration, headers: { "Idempotency-Key": key }, signal,
      })).data;
    },
    async receive(context, id, file, signal) {
      try {
        return (await client.putBytes<Upload["schemas"]["StatusResponse"]>(`${statusPath(context, id)}/content`, file, file.type, { signal })).data;
      } catch (error) {
        // 服务端明确声明已接收才查询同一意图；其他失败的消费结果未知，不自动再次发送字节。
        if (!(error instanceof ApiError) || error.status !== 409 || error.code !== "ALREADY_RECEIVED") throw error;
        return this.status(context, id, signal);
      }
    },
    async status(context, id, signal) {
      return (await client.get<Upload["schemas"]["StatusResponse"]>(statusPath(context, id), { signal })).data;
    },
    async cancel(context, id, signal) {
      return (await client.post<Upload["schemas"]["StatusResponse"]>(`${statusPath(context, id)}/cancel`, { signal })).data;
    },
  };
}
