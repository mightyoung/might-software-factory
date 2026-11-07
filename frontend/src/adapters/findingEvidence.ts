export const evidenceStates = ["INTENT_CREATED", "RECEIVING", "QUARANTINED", "SCANNING", "VERIFIED_PENDING_FINALIZATION", "RETRY_WAIT", "FAILED", "REJECTED", "CANCELLED", "EXPIRED", "FINALIZED"] as const;
export type EvidenceState = (typeof evidenceStates)[number];

export interface EvidenceUploadContext {
  findingId: string;
  roundNo: number;
}

export interface EvidenceSetInitialization {
  evidenceSetId: string;
  /** 初始化事实，不是后续版本；此适配器明确只上传一张图片。 */
  initialEvidenceRevision: 0;
}

export interface EvidenceUploadStatus {
  uploadIntentId: string;
  state: EvidenceState;
  expiresAt: string;
  cancelRequested?: boolean;
}

export interface EvidenceDeclaration extends EvidenceUploadContext {
  evidenceSetId: string;
  expectedFindingVersion: number;
  expectedEvidenceRevision: number;
  filename: string;
  declaredMediaType: "image/png" | "image/jpeg";
  declaredSizeBytes: number;
}

export interface FindingEvidenceAdapter {
  initialize(context: EvidenceUploadContext, expectedFindingVersion: number, key: string, signal?: AbortSignal): Promise<EvidenceSetInitialization>;
  reserve(declaration: EvidenceDeclaration, key: string, signal?: AbortSignal): Promise<EvidenceUploadStatus>;
  receive(context: EvidenceUploadContext, intentId: string, file: File, signal?: AbortSignal): Promise<EvidenceUploadStatus>;
  status(context: EvidenceUploadContext, intentId: string, signal?: AbortSignal): Promise<EvidenceUploadStatus>;
  cancel(context: EvidenceUploadContext, intentId: string, signal?: AbortSignal): Promise<EvidenceUploadStatus>;
}

/** 扫描检查点不表示可提交；只有最终发布完成才允许提交整改。 */
export const evidenceIsReady = (status: EvidenceUploadStatus | null): boolean => status?.state === "FINALIZED";

export const evidenceStateLabels: Record<EvidenceState, string> = {
  INTENT_CREATED: "等待接收文件", RECEIVING: "正在接收文件", QUARANTINED: "文件已隔离，等待扫描",
  SCANNING: "安全扫描中", VERIFIED_PENDING_FINALIZATION: "扫描完成，等待证据发布", RETRY_WAIT: "等待服务端重试",
  FAILED: "处理失败", REJECTED: "文件未通过安全检查", CANCELLED: "已取消", EXPIRED: "上传已过期", FINALIZED: "证据已发布，可提交整改",
};
