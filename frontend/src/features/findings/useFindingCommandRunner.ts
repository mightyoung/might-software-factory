import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  FindingCommandError,
  type FindingCommand,
  type FindingCommandAdapter,
  type FindingCommandFailureKind,
} from "../../adapters/findingCommands";
import { newIdempotencyKey } from "../../adapters/findingSubmit";
import { findingStateLabels, type FindingRecord } from "../../domain/finding";
import { ACTIONS, REFRESH_ON, validateCommandInput, type ActionDef, type CommandInputErrors, type Grade } from "./commandActions";

interface Options {
  finding: FindingRecord;
  adapter: FindingCommandAdapter;
  onChanged: () => void;
  onUnauthenticated?: () => void;
}

/** 一个详情面板里“选动作 → 填写 → 提交 → 刷新”的状态机。同一份命令的重试复用同一个幂等键。 */
export function useFindingCommandRunner({ finding, adapter, onChanged, onUnauthenticated }: Options) {
  const [activeId, setActiveId] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [grade, setGrade] = useState<"" | Grade>("");
  const [errors, setErrors] = useState<CommandInputErrors>({});
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<FindingCommandFailureKind | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const attempt = useRef<{ payload: string; key: string } | null>(null);
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);

  const active = (ACTIONS[finding.state] ?? []).find((action) => action.id === activeId) ?? null;

  function open(id: string) {
    setActiveId(id);
    setReason("");
    setGrade("");
    setErrors({});
    setFailure(null);
    setDone(null);
    attempt.current = null;
  }

  function keyFor(version: number, command: FindingCommand): string {
    const payload = JSON.stringify([finding.id, version, command]);
    if (attempt.current?.payload !== payload) attempt.current = { payload, key: newIdempotencyKey() };
    return attempt.current.key;
  }

  function succeeded(action: ActionDef, state: FindingRecord["state"]) {
    setDone(`已完成“${action.label}”，当前状态：${findingStateLabels[state]}。`);
    setActiveId(null);
    attempt.current = null;
    onChanged();
  }

  function failed(error: unknown) {
    const kind = error instanceof FindingCommandError ? error.kind : "unavailable";
    setFailure(kind);
    if (kind === "unauthenticated") onUnauthenticated?.();
    if (REFRESH_ON.has(kind)) {
      setActiveId(null);
      onChanged();
    }
    if (kind === "idempotencyConflict") attempt.current = null;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const version = finding.version;
    if (!active || pending || version === undefined) return;
    const found = validateCommandInput(active, reason, grade);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) return;

    const command = active.build({ reason: reason.trim(), grade: grade || "GENERAL" });
    const abort = new AbortController();
    controller.current = abort;
    setPending(true);
    try {
      const result = await adapter.run(finding.id, version, command, { idempotencyKey: keyFor(version, command), signal: abort.signal });
      if (mounted.current) succeeded(active, result.state);
    } catch (error) {
      const aborted = error instanceof DOMException && error.name === "AbortError";
      if (mounted.current && !aborted) failed(error);
    } finally {
      if (mounted.current) setPending(false);
    }
  }

  return { active, open, cancel: () => setActiveId(null), reason, setReason, grade, setGrade, errors, pending, failure, done, submit };
}
