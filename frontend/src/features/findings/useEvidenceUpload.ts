import { useCallback, useEffect, useRef, useState } from "react";
import {
  FindingEvidenceError,
  TERMINAL_UPLOAD_STATES,
  checkUploadFile,
  type EvidenceFailureKind,
  type FindingEvidenceAdapter,
  type UploadScope,
  type UploadStatus,
} from "../../adapters/findingEvidence";

const POLL_MS = 2000;
const MAX_POLLS = 90; // 约 3 分钟；超过后让用户手动刷新状态，不无限轮询。

export const EVIDENCE_FAILURE_MESSAGE: Record<EvidenceFailureKind, string> = {
  validation: "上传信息被服务端拒绝，请换一个文件再试。",
  unauthenticated: "登录已失效，请重新登录。",
  forbidden: "只有当前整改责任人可以上传证据。",
  conflict: "记录已变化（版本、证据集或状态），请刷新详情后再试。",
  alreadyReceived: "这个文件已经收到，不能重复上传。",
  tooLarge: "文件超过允许的大小。",
  unsupportedMedia: "服务端不接受这种文件类型。",
  assignment: "整改任务的责任人还没有解析成功，请联系管理员。",
  rateLimited: "操作太频繁，请稍后再试。",
  unavailable: "服务暂时不可用，操作尚未确认成功。可以直接重试，重试不会重复执行。",
};

/** 证据集与建集幂等键记在内存里；刷新页面后由 currentSet 读取接口找回证据集（适配器不支持时才需要重新开始）。 */
interface EditableEvidenceSet {
  evidenceSetId: string;
  evidenceRevision: number;
}
const memory = new Map<string, { setKey: string; set: EditableEvidenceSet | null }>();
export function clearEvidenceMemory(): void {
  memory.clear();
}

export type UploadPhase = "idle" | "working" | "polling" | "stalled";

export interface EvidenceUploadModel {
  phase: UploadPhase;
  status: UploadStatus | null;
  filename: string | null;
  error: string | null;
  /** 当前证据集；上传终结后用它提交整改。 */
  evidenceSetId: string | null;
  start: (file: File) => void;
  refresh: () => void;
  cancel: () => void;
}

export function useEvidenceUpload(
  adapter: FindingEvidenceAdapter,
  scope: UploadScope,
  findingVersion: number,
  onUnauthenticated?: () => void,
): EvidenceUploadModel {
  const [phase, setPhase] = useState<UploadPhase>("idle");
  const [status, setStatus] = useState<UploadStatus | null>(null);
  const [filename, setFilename] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const abort = useRef<AbortController | null>(null);
  const run = useRef(0);
  const memoKey = `${scope.findingId}:${scope.roundNo}`;

  useEffect(() => () => abort.current?.abort(), []);

  const fail = useCallback(
    (cause: unknown) => {
      if (cause instanceof DOMException && cause.name === "AbortError") return;
      if (cause instanceof FindingEvidenceError) {
        if (cause.kind === "unauthenticated") onUnauthenticated?.();
        setError(EVIDENCE_FAILURE_MESSAGE[cause.kind]);
      } else {
        setError(EVIDENCE_FAILURE_MESSAGE.unavailable);
      }
      setPhase("idle");
    },
    [onUnauthenticated],
  );

  const poll = useCallback(
    async (intentId: string, signal: AbortSignal, token: number) => {
      for (let i = 0; i < MAX_POLLS; i++) {
        const next = await adapter.getStatus(scope, intentId, signal);
        if (run.current !== token) return;
        setStatus(next);
        if (TERMINAL_UPLOAD_STATES.includes(next.state)) return setPhase("idle");
        await new Promise((resolve) => setTimeout(resolve, POLL_MS));
        if (signal.aborted || run.current !== token) return;
      }
      setPhase("stalled");
    },
    [adapter, scope],
  );

  // 刷新页面后找回服务端保存的证据集；最近一次上传还没结束就接着轮询。
  useEffect(() => {
    if (!adapter.currentSet || memory.has(memoKey)) return;
    const controller = new AbortController();
    void adapter.currentSet(scope, controller.signal).then(async (current) => {
      if (!current || controller.signal.aborted || memory.has(memoKey)) return;
      memory.set(memoKey, { setKey: crypto.randomUUID(), set: { evidenceSetId: current.evidenceSetId, evidenceRevision: current.evidenceRevision } });
      const latest = current.uploads.at(-1);
      if (!latest) return;
      setStatus(latest);
      if (TERMINAL_UPLOAD_STATES.includes(latest.state)) return;
      const token = ++run.current;
      abort.current = controller;
      setPhase("polling");
      await poll(latest.intentId, controller.signal, token);
    }).catch(() => undefined); // 找不回就当没有；用户仍可重新上传
    return () => controller.abort();
  }, [adapter, memoKey, poll, scope]);

  const start = useCallback(
    (file: File) => {
      const invalid = checkUploadFile(file);
      if (invalid) return setError(invalid);
      abort.current?.abort();
      const controller = new AbortController();
      abort.current = controller;
      const token = ++run.current;
      setError(null);
      setStatus(null);
      setFilename(file.name);
      setPhase("working");
      void (async () => {
        try {
          const entry = memory.get(memoKey) ?? { setKey: crypto.randomUUID(), set: null };
          memory.set(memoKey, entry);
          let set = entry.set;
          if (!set) {
            const created = await adapter.initializeSet(scope, findingVersion, entry.setKey, controller.signal);
            set = { evidenceSetId: created.evidenceSetId, evidenceRevision: created.initialEvidenceRevision };
          }
          if (adapter.currentSet) {
            // 初始化重放仍返回创建事实；其他页面也可能已发布，每次新预约前均读取当前可编辑版本。
            const current = await adapter.currentSet(scope, controller.signal);
            if (!current) throw new FindingEvidenceError("conflict");
            set = { evidenceSetId: current.evidenceSetId, evidenceRevision: current.evidenceRevision };
          }
          if (run.current !== token || controller.signal.aborted) return;
          memory.set(memoKey, { ...entry, set });
          const reserved = await adapter.reserveUpload(
            scope,
            {
              evidenceSetId: set.evidenceSetId,
              expectedFindingVersion: findingVersion,
              expectedEvidenceRevision: set.evidenceRevision,
              filename: file.name,
              declaredMediaType: file.type,
              declaredSizeBytes: file.size,
            },
            crypto.randomUUID(),
            controller.signal,
          );
          if (run.current !== token) return;
          setStatus(reserved);
          // 字节上传失败时服务端可能已落盘：契约要求用 GET 状态确认，不能自动重发。
          const received = await adapter.sendContent(scope, reserved.intentId, file, controller.signal).catch((cause: unknown) => {
            if (cause instanceof FindingEvidenceError && (cause.kind === "unavailable" || cause.kind === "alreadyReceived")) return reserved;
            throw cause;
          });
          if (run.current !== token) return;
          setStatus(received);
          setPhase("polling");
          await poll(received.intentId, controller.signal, token);
        } catch (cause) {
          if (run.current === token) fail(cause);
        }
      })();
    },
    [adapter, fail, findingVersion, memoKey, poll, scope],
  );

  const refresh = useCallback(() => {
    if (!status) return;
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    const token = ++run.current;
    setError(null);
    setPhase("polling");
    poll(status.intentId, controller.signal, token).catch((cause) => run.current === token && fail(cause));
  }, [fail, poll, status]);

  const cancel = useCallback(() => {
    if (!status) return;
    adapter.cancel(scope, status.intentId).then(setStatus, fail);
  }, [adapter, fail, scope, status]);

  return { phase, status, filename, error, evidenceSetId: memory.get(memoKey)?.set?.evidenceSetId ?? null, start, refresh, cancel };
}
