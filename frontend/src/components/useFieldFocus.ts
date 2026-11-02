import { useCallback, useLayoutEffect, useRef, useState } from "react";
import { DRAFT_FIELD_ORDER, type FindingDraftErrors, type FindingDraftField } from "../domain/findingDraft";

/**
 * 字段焦点登记。字段在提交期间是禁用的，禁用元素无法获得焦点：出错字段的聚焦要等重新渲染成可编辑后再做。
 * 用 layout effect 同步聚焦，焦点不会比错误文字晚一帧出现。
 */
export function useFieldFocus(editing: boolean) {
  const refs = useRef<Partial<Record<FindingDraftField, HTMLElement | null>>>({});
  const [pending, setPending] = useState<FindingDraftField | null>(null);

  useLayoutEffect(() => {
    if (!pending || !editing) return;
    refs.current[pending]?.focus();
    setPending(null);
  }, [pending, editing]);

  /** 登记控件的 ref 回调。 */
  const fieldRef = (field: FindingDraftField) => (node: HTMLElement | null) => {
    refs.current[field] = node;
  };
  const focusNow = useCallback((field: FindingDraftField) => refs.current[field]?.focus(), []);
  const focusFirstError = useCallback((errors: FindingDraftErrors) => {
    const first = DRAFT_FIELD_ORDER.find((field) => errors[field]);
    if (first) setPending(first);
  }, []);

  return { fieldRef, focusNow, focusFirstError };
}
