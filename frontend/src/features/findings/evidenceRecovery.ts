import type { EvidenceDeclaration } from "../../adapters/findingEvidence";

export interface EvidenceRecovery {
  initializeKey: string;
  expectedFindingVersion: number;
  evidenceSetId?: string;
  uploadIntentId?: string;
  reserveKey?: string;
  declaration?: EvidenceDeclaration;
  fileSha256?: string;
  receiveAttempted?: boolean;
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function evidenceRecoveryKey(owner: string | undefined, findingId: string, roundNo: number): string | null {
  return owner ? `msf-evidence-v1:${JSON.stringify([owner, findingId, roundNo])}` : null;
}

export function readEvidenceRecovery(key: string | null): EvidenceRecovery | null {
  if (!key) return null;
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw || raw.length > 4096) return null;
    const value = JSON.parse(raw) as EvidenceRecovery;
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    if (!uuid.test(value.initializeKey) || !Number.isSafeInteger(value.expectedFindingVersion) || value.expectedFindingVersion < 1) return null;
    for (const id of [value.evidenceSetId, value.uploadIntentId, value.reserveKey]) if (id !== undefined && !uuid.test(id)) return null;
    if (value.fileSha256 !== undefined && !/^[a-f0-9]{64}$/.test(value.fileSha256)) return null;
    if (value.receiveAttempted !== undefined && typeof value.receiveAttempted !== "boolean") return null;
    if (value.declaration !== undefined) {
      const declaration = value.declaration;
      if (!declaration || typeof declaration !== "object" || Array.isArray(declaration)
        || typeof declaration.findingId !== "string" || !uuid.test(declaration.findingId)
        || !Number.isSafeInteger(declaration.roundNo) || declaration.roundNo < 1
        || declaration.evidenceSetId !== value.evidenceSetId
        || declaration.expectedFindingVersion !== value.expectedFindingVersion
        || declaration.expectedEvidenceRevision !== 0
        || typeof declaration.filename !== "string" || !declaration.filename.trim() || declaration.filename.length > 255
        || !["image/png", "image/jpeg"].includes(declaration.declaredMediaType)
        || !Number.isSafeInteger(declaration.declaredSizeBytes) || declaration.declaredSizeBytes < 1
        || declaration.declaredSizeBytes > 64 * 1024 * 1024) return null;
    }
    return value;
  } catch { return null; }
}

export function writeEvidenceRecovery(key: string | null, value: EvidenceRecovery | null): void {
  if (!key) return;
  try {
    if (value) sessionStorage.setItem(key, JSON.stringify(value));
    else sessionStorage.removeItem(key);
  } catch { /* 存储不可用仍保留本次页面内恢复；不保存文件字节或整改说明。 */ }
}
