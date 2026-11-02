import { useEffect, useRef } from "react";
import { discardDraft, isExpiring, stashDraft } from "../features/session/draftStash";
import type { FindingDraft } from "../domain/findingDraft";

interface Options {
  draftKey?: string;
  draft: FindingDraft;
  organizationId: string;
  dirty: boolean;
  submitted: boolean;
  restored: boolean;
}

/**
 * 表单卸载时，如果是会话被动失效造成的且还有未提交内容，把草稿存进内存草稿箱；
 * 提交成功后清掉，恢复过的草稿被消费后也清掉（再次失效会重新存）。
 */
export function useDraftStash({ draftKey, draft, organizationId, dirty, submitted, restored }: Options): void {
  const latest = useRef({ draft, organizationId, dirty, submitted });
  latest.current = { draft, organizationId, dirty, submitted };

  useEffect(() => {
    if (draftKey && restored) discardDraft(draftKey); // 已读入表单，不重复保留
  }, [draftKey, restored]);

  useEffect(() => {
    if (draftKey && submitted) discardDraft(draftKey);
  }, [draftKey, submitted]);

  useEffect(
    () => () => {
      const { draft: d, organizationId: org, dirty: isDirty, submitted: done } = latest.current;
      if (draftKey && isExpiring() && isDirty && !done) stashDraft(draftKey, { draft: d, organizationId: org });
    },
    [draftKey],
  );
}
