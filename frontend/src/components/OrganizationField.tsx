import { useEffect, useState, type Ref } from "react";
import { FindingSubmitError, type FindingSubmitAdapter, type ReportableOrganization } from "../adapters/findingSubmit";
import { ReportField, describedByIds } from "./ReportField";

export type OrganizationsState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; items: readonly ReportableOrganization[] };

export interface ReportableOrganizations {
  state: OrganizationsState;
  /** 当前选择；只有一个可选组织时自动选中。 */
  organizationId: string;
  /** 用户亲自选过：自动选中的单个组织不算“有未提交内容”。 */
  touched: boolean;
  pick(id: string): void;
  reload(): void;
  /** 列表已加载且为空：没有任何可上报的组织，无法提交。 */
  noneAvailable: boolean;
}

/** 载入当前会话有权上报的组织（适配器没有 listOrganizations 时保持 loading 且不请求）。 */
export function useReportableOrganizations(
  adapter: FindingSubmitAdapter,
  onUnauthenticated?: () => void,
  /** 恢复的草稿里选过的组织；视为用户亲自选的。 */
  initialId = "",
): ReportableOrganizations {
  const [state, setState] = useState<OrganizationsState>({ status: "loading" });
  const [organizationId, setOrganizationId] = useState(initialId);
  const [touched, setTouched] = useState(initialId !== "");
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!adapter.listOrganizations) return;
    const abort = new AbortController();
    setState({ status: "loading" });
    adapter
      .listOrganizations(abort.signal)
      .then((items) => {
        setState({ status: "ready", items });
        // 只有一个可选组织且还没选过时自动选中；恢复的选择若已不在列表里（授权变化）则清掉。
        setOrganizationId((current) =>
          items.some((item) => item.id === current) ? current : items.length === 1 ? items[0]!.id : "",
        );
      })
      .catch((error: unknown) => {
        if (abort.signal.aborted) return;
        if (error instanceof FindingSubmitError && error.kind === "unauthenticated") onUnauthenticated?.();
        setState({ status: "error" });
      });
    return () => abort.abort();
    // onUnauthenticated 是回调属性，不作为重新加载的依据。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adapter, tick]);

  return {
    state,
    organizationId,
    touched,
    pick: (id) => {
      setOrganizationId(id);
      setTouched(true);
    },
    reload: () => setTick((n) => n + 1),
    noneAvailable: state.status === "ready" && state.items.length === 0,
  };
}

export function OrganizationField({
  id,
  orgs,
  error,
  disabled,
  selectRef,
}: {
  id: string;
  orgs: ReportableOrganizations;
  error?: string;
  disabled: boolean;
  selectRef: Ref<HTMLSelectElement>;
}) {
  const { state } = orgs;
  const hint = orgs.noneAvailable ? "你当前没有可上报的组织，无法提交。请联系管理员授权。" : undefined;
  return (
    <ReportField id={id} label="所属组织" required error={error} hint={hint}>
      {state.status === "error" ? (
        <p>
          组织列表加载失败。
          <button type="button" className="button button-subtle" onClick={orgs.reload}>
            重试
          </button>
        </p>
      ) : (
        <select
          id={id}
          ref={selectRef}
          value={orgs.organizationId}
          aria-invalid={Boolean(error)}
          aria-describedby={describedByIds(id, { hint: Boolean(hint), error: Boolean(error) })}
          disabled={disabled || state.status !== "ready" || orgs.noneAvailable}
          onChange={(event) => orgs.pick(event.target.value)}
        >
          <option value="">{state.status === "loading" ? "加载中…" : "请选择"}</option>
          {state.status === "ready" &&
            state.items.map((org) => (
              <option key={org.id} value={org.id}>
                {org.name}
              </option>
            ))}
        </select>
      )}
    </ReportField>
  );
}
