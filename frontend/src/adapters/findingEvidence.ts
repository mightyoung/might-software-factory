/** 整改证据上传（契约 finding-evidence-set-initialization / finding-evidence-upload）。
 *  只覆盖契约已有的部分：建空证据集、预约、上传字节、查状态、取消。
 *  “封存 / 终结证据”（G025）契约尚无，所以上传到 VERIFIED_PENDING_FINALIZATION 就是前端能走到的终点。 */

export const ACCEPTED_MEDIA_TYPES = ["image/png", "image/jpeg"] as const;
export const MAX_UPLOAD_BYTES = 67_108_864;

export type UploadState =
  | "INTENT_CREATED"
  | "RECEIVING"
  | "QUARANTINED"
  | "SCANNING"
  | "VERIFIED_PENDING_FINALIZATION"
  | "RETRY_WAIT"
  | "FAILED"
  | "REJECTED"
  | "CANCELLED"
  | "EXPIRED"
  | "FINALIZED";

/** 不会再变化的状态：停止轮询。 */
export const TERMINAL_UPLOAD_STATES: readonly UploadState[] = [
  "VERIFIED_PENDING_FINALIZATION",
  "FAILED",
  "REJECTED",
  "CANCELLED",
  "EXPIRED",
  "FINALIZED",
];

export interface UploadStatus {
  intentId: string;
  state: UploadState;
  expiresAt: string;
  cancelRequested: boolean;
}

export interface EvidenceSet {
  evidenceSetId: string;
  initialEvidenceRevision: number;
}

export interface UploadDeclaration {
  evidenceSetId: string;
  expectedFindingVersion: number;
  expectedEvidenceRevision: number;
  filename: string;
  declaredMediaType: string;
  declaredSizeBytes: number;
}

export interface UploadScope {
  findingId: string;
  roundNo: number;
}

export interface FindingEvidenceAdapter {
  initializeSet(scope: UploadScope, expectedFindingVersion: number, idempotencyKey: string, signal?: AbortSignal): Promise<EvidenceSet>;
  reserveUpload(scope: UploadScope, declaration: UploadDeclaration, idempotencyKey: string, signal?: AbortSignal): Promise<UploadStatus>;
  /** 字节必须与声明的大小、类型完全一致；服务端不续传、不覆盖。 */
  sendContent(scope: UploadScope, intentId: string, content: Blob, signal?: AbortSignal): Promise<UploadStatus>;
  getStatus(scope: UploadScope, intentId: string, signal?: AbortSignal): Promise<UploadStatus>;
  cancel(scope: UploadScope, intentId: string, signal?: AbortSignal): Promise<UploadStatus>;
}

export type EvidenceFailureKind =
  | "validation"
  | "unauthenticated"
  | "forbidden"
  | "conflict"
  | "alreadyReceived"
  | "tooLarge"
  | "unsupportedMedia"
  | "assignment"
  | "rateLimited"
  | "unavailable";

export class FindingEvidenceError extends Error {
  readonly retryable: boolean;
  constructor(
    readonly kind: EvidenceFailureKind,
    readonly traceId: string | null = null,
  ) {
    super(`Finding evidence ${kind}`);
    this.name = "FindingEvidenceError";
    this.retryable = kind === "unavailable" || kind === "rateLimited";
  }
}

/** 选文件前的本地检查；服务端仍会按配置的策略再校验。返回给用户看的原因，通过则 null。 */
export function checkUploadFile(file: { name: string; size: number; type: string }): string | null {
  if (!(ACCEPTED_MEDIA_TYPES as readonly string[]).includes(file.type)) return "只支持 PNG 或 JPEG 图片。";
  if (file.size < 1) return "文件是空的。";
  if (file.size > MAX_UPLOAD_BYTES) return "文件超过 64 MiB。";
  const name = file.name;
  if ([...name].length > 255 || /[\u0000-\u001f\u007f/\\]/.test(name) || name === "." || name === "..") {
    return "文件名不合规（过长或包含控制字符、斜杠）。";
  }
  return null;
}
