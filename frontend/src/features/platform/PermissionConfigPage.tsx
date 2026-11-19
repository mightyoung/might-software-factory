import { Button } from "@astryxdesign/core/Button";
import { CheckboxInput } from "@astryxdesign/core/CheckboxInput";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  PermissionAdminError,
  type PermissionAdminAdapter,
  type PermissionAdminData,
  type PermissionAdminFailure,
  type RoleView,
} from "../../adapters/permissionAdmin";

const APPLICATION_LABEL: Readonly<Record<string, string>> = { safety: "生产安全", equipment: "设备管理", platform: "系统设置" };

const FAILURE_MESSAGE: Readonly<Record<PermissionAdminFailure, string>> = {
  unauthenticated: "登录已失效，请重新登录。",
  forbidden: "你没有修改权限配置的权限。",
  conflict: "该角色的权限已被他人修改，已为你重新载入最新内容，请确认后再保存。",
  last_manager: "至少需要保留一个有成员的有效角色拥有“修改权限配置”。",
  no_scope: "该角色目前没有任何权限可继承数据范围，无法新增权限。请先由管理员为它配置数据范围。",
  unknown_permission: "包含不在权限目录中的权限项。",
  unavailable: "服务暂时不可用，请稍后重试。",
};

function failureOf(error: unknown): PermissionAdminFailure {
  return error instanceof PermissionAdminError ? error.kind : "unavailable";
}

export function diffCodes(original: readonly string[], draft: ReadonlySet<string>): { added: string[]; removed: string[] } {
  const before = new Set(original);
  return {
    added: [...draft].filter((code) => !before.has(code)).sort(),
    removed: original.filter((code) => !draft.has(code)).sort(),
  };
}

interface Props {
  adapter: PermissionAdminAdapter;
  canManage: boolean;
  onUnauthenticated: () => void;
  onPermissionsChanged?: () => Promise<void>;
}

export function PermissionConfigPage({ adapter, canManage, onUnauthenticated, onPermissionsChanged }: Props) {
  const lifetime = useRef(0);
  useEffect(() => { lifetime.current += 1;return () => { lifetime.current += 1; }; }, [adapter]);
  const [data, setData] = useState<PermissionAdminData | null>(null);
  const [loadError, setLoadError] = useState<PermissionAdminFailure | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<ReadonlySet<string>>(new Set());
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<{ tone: "ok" | "error"; text: string } | null>(null);

  const reload = useCallback(
    async (signal?: AbortSignal, keepSelection?: string | null) => {
      const mine = lifetime.current;
      try {
        const next = await adapter.load(signal);
        if (signal?.aborted || mine !== lifetime.current) return;
        setData(next);
        setLoadError(null);
        const wanted = keepSelection ?? null;
        const selected = next.roles.find((role) => role.id === wanted) ?? next.roles[0] ?? null;
        setSelectedId(selected?.id ?? null);
        setDraft(new Set(selected?.permissionCodes ?? []));
      } catch (error) {
        if (signal?.aborted || mine !== lifetime.current) return;
        const kind = failureOf(error);
        if (kind === "unauthenticated") onUnauthenticated();
        else setLoadError(kind);
      }
    },
    [adapter, onUnauthenticated],
  );

  useEffect(() => {
    const controller = new AbortController();
    void reload(controller.signal);
    return () => controller.abort();
  }, [reload]);

  const selected: RoleView | null = useMemo(
    () => data?.roles.find((role) => role.id === selectedId) ?? null,
    [data, selectedId],
  );
  const groups = useMemo(() => {
    const byApplication = new Map<string, NonNullable<typeof data>["catalog"][number][]>();
    for (const item of data?.catalog ?? []) {
      byApplication.set(item.application, [...(byApplication.get(item.application) ?? []), item]);
    }
    return [...byApplication.entries()];
  }, [data]);
  const diff = selected ? diffCodes(selected.permissionCodes, draft) : { added: [], removed: [] };
  const dirty = diff.added.length + diff.removed.length > 0;

  function select(role: RoleView) {
    if (saving) return;
    if (dirty && !window.confirm("当前角色有未保存的修改，切换后将丢弃，继续吗？")) return;
    setSelectedId(role.id);
    setDraft(new Set(role.permissionCodes));
    setNotice(null);
  }

  function toggle(code: string) {
    if (!canManage) return;
    setNotice(null);
    setDraft((current) => {
      const next = new Set(current);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });
  }

  async function save() {
    if (!selected || !dirty || saving) return;
    const mine = lifetime.current;
    let persisted = false;
    setSaving(true);
    setNotice(null);
    try {
      const updated = await adapter.saveRolePermissions(selected.id, [...draft].sort(), selected.version);
      if (mine !== lifetime.current) return;
      persisted = true;
      setData((current) =>
        current ? { ...current, roles: current.roles.map((role) => (role.id === updated.id ? updated : role)) } : current,
      );
      setDraft(new Set(updated.permissionCodes));
      await onPermissionsChanged?.();
      if (mine !== lifetime.current) return;
      setNotice({ tone: "ok", text: "已保存，权限即时生效。" });
    } catch (error) {
      if (mine !== lifetime.current) return;
      if (persisted) { setNotice({ tone: "error", text: "权限已保存，但当前会话尚未同步。请重新登录后确认。" });return; }
      const kind = failureOf(error);
      if (kind === "unauthenticated") {
        onUnauthenticated();
        return;
      }
      setNotice({ tone: "error", text: FAILURE_MESSAGE[kind] });
      if (kind === "conflict") await reload(undefined, selected.id);
    } finally {
      if (mine === lifetime.current) setSaving(false);
    }
  }

  if (loadError) {
    return (
      <section className="perm-page" role="alert">
        <h1>权限配置</h1>
        <p>{FAILURE_MESSAGE[loadError]}</p>
        <Button type="button" label="重试" variant="secondary" onClick={() => void reload()} />
      </section>
    );
  }
  if (!data) return <section className="perm-page" role="status">正在载入权限配置…</section>;

  return (
    <section className="perm-page">
      <h1>权限配置</h1>
      <p className="perm-lead">
        按角色勾选可使用的功能。{canManage ? "保存后立即生效，菜单和接口都会按新权限判断。" : "你只有查看权限，无法修改。"}
      </p>
      <div className="perm-layout">
        <nav className="perm-roles" aria-label="角色">
          {data.roles.map((role) => (
            <button
              key={role.id}
              type="button"
              className={`perm-role ${role.id === selectedId ? "perm-role-current" : ""}`}
              aria-current={role.id === selectedId ? "true" : undefined}
              disabled={saving}
              onClick={() => select(role)}
            >
              <span>{role.name}</span>
              <small>{role.memberCount} 人 · {role.permissionCodes.length} 项权限</small>
            </button>
          ))}
        </nav>
        <div className="perm-detail">
          {!selected ? (
            <p>暂无角色。</p>
          ) : (
            <>
              <h2>{selected.name}</h2>
              {groups.map(([application, items]) => (
                <fieldset key={application} className="perm-group" disabled={!canManage || saving}>
                  <legend>{APPLICATION_LABEL[application] ?? application}</legend>
                  {items.map((item) => (
                    <div key={item.code} className="perm-item">
                      <CheckboxInput
                        label={item.label}
                        value={draft.has(item.code)}
                        isDisabled={!canManage || saving}
                        onChange={() => toggle(item.code)}
                      />
                      <code>{item.code}</code>
                    </div>
                  ))}
                </fieldset>
              ))}
              {dirty && (
                <p className="perm-diff" aria-live="polite">
                  待保存：新增 {diff.added.length} 项，移除 {diff.removed.length} 项
                </p>
              )}
              {notice && (
                <p className={notice.tone === "ok" ? "perm-ok" : "perm-error"} role={notice.tone === "ok" ? "status" : "alert"}>
                  {notice.text}
                </p>
              )}
              {canManage && (
                <div className="perm-actions">
                  <Button
                    type="button"
                    label={saving ? "保存中…" : "保存"}
                    variant="primary"
                    isDisabled={!dirty || saving}
                    onClick={() => void save()}
                  />
                  <Button
                    type="button"
                    label="放弃修改"
                    variant="secondary"
                    isDisabled={!dirty || saving}
                    onClick={() => setDraft(new Set(selected.permissionCodes))}
                  />
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </section>
  );
}
