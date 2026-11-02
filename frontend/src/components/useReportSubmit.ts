import { useEffect, useRef, useState } from "react";
import {
  FindingSubmitError,
  type FindingSubmitAdapter,
  type FindingSubmitFailureKind,
  type FindingSubmitResult,
} from "../adapters/findingSubmit";
import type { FindingDraft, FindingDraftErrors } from "../domain/findingDraft";

export type SubmitPhase = { name: "editing" } | { name: "submitting" } | { name: "submitted"; result: FindingSubmitResult };

export interface SubmitOutcome {
  /** 服务端按字段给出的错误（400）；其余失败看 failure。 */
  fieldErrors?: FindingDraftErrors;
}

interface Options {
  adapter: FindingSubmitAdapter;
  createKey: () => string;
  onSubmitted: (result: FindingSubmitResult) => void;
  onUnauthenticated?: () => void;
}

/** 提交生命周期：阶段、失败类别、取消，以及“同一份内容的重试复用同一个幂等键”。 */
export function useReportSubmit({ adapter, createKey, onSubmitted, onUnauthenticated }: Options) {
  const [phase, setPhase] = useState<SubmitPhase>({ name: "editing" });
  const [failure, setFailure] = useState<FindingSubmitFailureKind | null>(null);
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);
  const lastAttempt = useRef<{ payload: string; key: string } | null>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);

  function keyFor(payload: string): string {
    if (lastAttempt.current?.payload === payload) return lastAttempt.current.key;
    const key = createKey();
    lastAttempt.current = { payload, key };
    return key;
  }

  /** 提交已通过本地校验的草稿。重复触发（已在提交中）直接忽略。 */
  async function send(draft: FindingDraft, organizationId: string | undefined): Promise<SubmitOutcome> {
    if (controller.current) return {};
    const abort = new AbortController();
    controller.current = abort;
    setFailure(null);
    setPhase({ name: "submitting" });
    try {
      const result = await adapter.submit(draft, {
        idempotencyKey: keyFor(JSON.stringify({ ...draft, organizationId })),
        ...(organizationId !== undefined ? { organizationId } : {}),
        signal: abort.signal,
      });
      if (!mounted.current) return {};
      setPhase({ name: "submitted", result });
      onSubmitted(result);
      return {};
    } catch (error) {
      if (!mounted.current || (error instanceof DOMException && error.name === "AbortError")) return {};
      const kind: FindingSubmitFailureKind = error instanceof FindingSubmitError ? error.kind : "unavailable";
      setPhase({ name: "editing" });
      setFailure(kind);
      if (kind === "unauthenticated") onUnauthenticated?.();
      return kind === "validation" && error instanceof FindingSubmitError ? { fieldErrors: error.fieldErrors } : {};
    } finally {
      if (controller.current === abort) controller.current = null;
    }
  }

  /** 回到空白编辑（“继续上报”）：清掉失败和重试用的幂等键。 */
  function reset() {
    lastAttempt.current = null;
    setFailure(null);
    setPhase({ name: "editing" });
  }

  return { phase, failure, send, reset, submitting: phase.name === "submitting" };
}
