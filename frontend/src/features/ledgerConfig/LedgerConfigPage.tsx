import { Button } from "@astryxdesign/core/Button";
import { CheckboxInput } from "@astryxdesign/core/CheckboxInput";
import { useEffect, useMemo, useRef, useState } from "react";
import { createHttpLedgerConfig } from "./model";
import { ApiError, createHttpClient } from "../../api/httpClient";
import type {
  ConfigurationSnapshot,
  ConfigurationSummary,
  LedgerConfigAdapter,
  LedgerConfiguration,
  LedgerField,
} from "./model";
import "./ledger-config.css";

const empty = (): LedgerConfiguration => ({
  name: "",
  fields: [],
  listFields: [],
  queryFields: [],
});
const fieldTypes = {
  text: "文本",
  number: "数字",
  date: "日期",
  boolean: "是/否",
};

function ConfigurationPreview({
  definition,
}: {
  definition: LedgerConfiguration;
}) {
  return (
    <div className="config-preview">
      <p>{definition.name || "未命名台账"}</p>
      <table>
        <thead>
          <tr>
            <th>字段</th>
            <th>类型</th>
            <th>必填</th>
            <th>列表</th>
            <th>查询</th>
          </tr>
        </thead>
        <tbody>
          {definition.fields.map((field, index) => (
            <tr key={index}>
              <td>
                {field.label || "未命名字段"}
                <small>{field.key}</small>
              </td>
              <td>{fieldTypes[field.type]}</td>
              <td>{field.required ? "是" : "否"}</td>
              <td>
                {definition.listFields.includes(field.key) ? "显示" : "隐藏"}
              </td>
              <td>
                {definition.queryFields.includes(field.key) ? "允许" : "关闭"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function LedgerConfigPage({
  adapter,
  canManage,
  canPublish,
  onUnauthenticated,
}: {
  adapter: LedgerConfigAdapter;
  canManage: boolean;
  canPublish: boolean;
  onUnauthenticated: () => void;
}) {
  const [items, setItems] = useState<ConfigurationSummary[]>([]);
  const [key, setKey] = useState("");
  const [snapshot, setSnapshot] = useState<ConfigurationSnapshot | null>(null);
  const [draft, setDraft] = useState<LedgerConfiguration>(empty);
  const [history, setHistory] = useState<ConfigurationSnapshot[]>([]);
  const [compared, setCompared] = useState<ConfigurationSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const lifetime = useRef(0);
  const pending = useRef<{ signature: string; id: string } | null>(null);
  const dirty =
    snapshot === null ||
    JSON.stringify(draft) !== JSON.stringify(snapshot.definition);

  function failed(cause: unknown) {
    if (cause instanceof ApiError && cause.status === 401) {
      onUnauthenticated();
      return;
    }
    if (cause instanceof ApiError && cause.status === 403)
      setError("你没有执行此配置操作的权限。");
    else if (cause instanceof ApiError && cause.status === 409)
      setError(
        "配置已被其他人更新。你的输入已保留，请重新打开此台账核对最新版本。",
      );
    else if (cause instanceof ApiError && cause.status === 400)
      setError("配置未通过校验，请检查字段标识、名称以及列表和查询字段。");
    else setError("操作未确认成功。请重试，保存重试会沿用同一请求标识。");
  }
  useEffect(() => {
    const controller = new AbortController();
    const mine = ++lifetime.current;
    setLoading(true);
    setLoadFailed(false);
    setError("");
    adapter
      .list(controller.signal)
      .then((next) => {
        if (mine === lifetime.current) setItems(next);
      })
      .catch((cause) => {
        if (!controller.signal.aborted && mine === lifetime.current) {
          setLoadFailed(true);
          failed(cause);
        }
      })
      .finally(() => {
        if (mine === lifetime.current) setLoading(false);
      });
    return () => {
      controller.abort();
      lifetime.current += 1;
    };
  }, [adapter, loadAttempt]); // The adapter is recreated at the identity boundary.

  function discardAllowed() {
    return (
      !dirty ||
      (!snapshot && !key && !draft.name && !draft.fields.length) ||
      window.confirm("当前配置有未保存的修改，继续切换吗？")
    );
  }
  async function select(item: ConfigurationSummary) {
    if (busy || !discardAllowed()) return;
    const mine = lifetime.current;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const versions = await adapter.versions(item.key);
      if (mine !== lifetime.current) return;
      const latest = versions[0];
      if (!latest) throw new Error("Empty configuration history");
      setKey(item.key);
      setSnapshot(latest);
      setDraft(latest.definition);
      setHistory(versions);
      setCompared(null);
      pending.current = null;
    } catch (cause) {
      if (mine === lifetime.current) failed(cause);
    } finally {
      if (mine === lifetime.current) setBusy(false);
    }
  }
  function create() {
    if (busy || !discardAllowed()) return;
    setKey("");
    setSnapshot(null);
    setDraft(empty());
    setHistory([]);
    setCompared(null);
    setError("");
    setNotice("");
    pending.current = null;
  }
  function updateField(index: number, patch: Partial<LedgerField>) {
    setDraft((current) => {
      const previous = current.fields[index]!;
      const fields = current.fields.map((field, position) =>
        position === index ? { ...field, ...patch } : field,
      );
      const rename = (keys: string[]) =>
        keys.map((value) =>
          value === previous.key && patch.key !== undefined ? patch.key : value,
        );
      return {
        ...current,
        fields,
        listFields: rename(current.listFields),
        queryFields: rename(current.queryFields),
      };
    });
  }
  function toggleView(property: "listFields" | "queryFields", field: string) {
    setDraft((current) => ({
      ...current,
      [property]: current[property].includes(field)
        ? current[property].filter((value) => value !== field)
        : [...current[property], field],
    }));
  }
  async function mutate(
    action: "save" | "restore" | "publish",
    sourceVersion?: number,
  ) {
    if (
      busy ||
      !(action === "publish" ? canPublish : canManage) ||
      !key.trim() ||
      (action === "publish" && (dirty || !snapshot))
    )
      return;
    const mine = lifetime.current;
    const version = snapshot?.version ?? 0;
    const signature = JSON.stringify([
      action,
      key,
      version,
      action === "save" ? draft : sourceVersion,
    ]);
    if (pending.current?.signature !== signature)
      pending.current = { signature, id: crypto.randomUUID() };
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const next =
        action === "save"
          ? await adapter.save(key, draft, version, pending.current.id)
          : action === "restore"
            ? await adapter.restore(
                key,
                sourceVersion!,
                version,
                pending.current.id,
              )
            : await adapter.publish(
                key,
                version,
                snapshot?.publishedVersion ?? null,
              );
      if (mine !== lifetime.current) return;
      setSnapshot(next);
      setDraft(next.definition);
      setCompared(null);
      pending.current = null;
      setHistory((current) =>
        action === "publish"
          ? current.map((entry) => ({
              ...entry,
              publishedVersion: next.publishedVersion,
            }))
          : [next, ...current],
      );
      setItems((current) => [
        { ...next, latestVersion: next.version },
        ...current.filter((item) => item.key !== next.key),
      ]);
      setNotice(
        action === "publish"
          ? `已发布版本 ${next.version}。`
          : action === "restore"
            ? `已从版本 ${sourceVersion} 创建新草稿 ${next.version}，可核对后发布。`
            : `已保存草稿版本 ${next.version}。`,
      );
    } catch (cause) {
      if (mine === lifetime.current) failed(cause);
    } finally {
      if (mine === lifetime.current) setBusy(false);
    }
  }
  return (
    <section className="config-page">
      <div className="page-heading">
        <h1>台账配置</h1>
        {canManage && (
          <Button
            label="新建台账"
            variant="secondary"
            isDisabled={busy}
            onClick={create}
          />
        )}
      </div>
      {loading && <p role="status">正在载入配置…</p>}
      {error && <p role="alert">{error}</p>}
      {loadFailed && (
        <Button
          label="重新载入台账列表"
          variant="secondary"
          onClick={() => setLoadAttempt((value) => value + 1)}
        />
      )}
      {notice && <p role="status">{notice}</p>}
      <div className="config-layout">
        <nav aria-label="已配置台账" className="config-list">
          {items.length === 0 && !loading && !loadFailed && (
            <p>暂无台账配置。</p>
          )}
          {items.map((item) => (
            <button
              key={item.key}
              type="button"
              disabled={busy}
              aria-current={snapshot?.key === item.key ? "true" : undefined}
              onClick={() => void select(item)}
            >
              {item.definition.name || item.key}
              <small>
                最新 {item.latestVersion} ·{" "}
                {item.publishedVersion
                  ? `已发布 ${item.publishedVersion}`
                  : "未发布"}
              </small>
            </button>
          ))}
        </nav>
        <div className="config-editor">
          <fieldset disabled={!canManage || busy || loading}>
            <legend>
              {snapshot ? `配置版本 ${snapshot.version}` : "新台账草稿"}
            </legend>
            <label>
              台账标识
              <input
                value={key}
                disabled={snapshot !== null}
                pattern="[a-z][a-z0-9_]*"
                onChange={(event) => setKey(event.target.value)}
                placeholder="purchase_request"
              />
            </label>
            <label>
              台账名称
              <input
                value={draft.name}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    name: event.target.value,
                  }))
                }
              />
            </label>
            <h2>字段</h2>
            {draft.fields.map((field, index) => (
              <fieldset key={index} className="config-field">
                <legend>字段 {index + 1}</legend>
                <label>
                  字段标识
                  <input
                    value={field.key}
                    onChange={(event) =>
                      updateField(index, { key: event.target.value })
                    }
                  />
                </label>
                <label>
                  字段名称
                  <input
                    value={field.label}
                    onChange={(event) =>
                      updateField(index, { label: event.target.value })
                    }
                  />
                </label>
                <label>
                  字段类型
                  <select
                    value={field.type}
                    onChange={(event) =>
                      updateField(index, {
                        type: event.target.value as LedgerField["type"],
                      })
                    }
                  >
                    {Object.entries(fieldTypes).map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <CheckboxInput
                  label="必填"
                  value={field.required}
                  isDisabled={!canManage || busy}
                  onChange={() =>
                    updateField(index, { required: !field.required })
                  }
                />
                <CheckboxInput
                  label="列表显示"
                  value={draft.listFields.includes(field.key)}
                  isDisabled={!canManage || busy || !field.key}
                  onChange={() => toggleView("listFields", field.key)}
                />
                <CheckboxInput
                  label="允许查询"
                  value={draft.queryFields.includes(field.key)}
                  isDisabled={!canManage || busy || !field.key}
                  onChange={() => toggleView("queryFields", field.key)}
                />
                <Button
                  label={`移除字段 ${index + 1}`}
                  variant="secondary"
                  isDisabled={!canManage || busy}
                  onClick={() =>
                    setDraft((current) => ({
                      ...current,
                      fields: current.fields.filter(
                        (_, position) => position !== index,
                      ),
                      listFields: current.listFields.filter(
                        (value) => value !== field.key,
                      ),
                      queryFields: current.queryFields.filter(
                        (value) => value !== field.key,
                      ),
                    }))
                  }
                />
              </fieldset>
            ))}
            {canManage && (
              <Button
                label="添加字段"
                variant="secondary"
                isDisabled={busy || loading}
                onClick={() =>
                  setDraft((current) => ({
                    ...current,
                    fields: [
                      ...current.fields,
                      { key: "", label: "", type: "text", required: false },
                    ],
                  }))
                }
              />
            )}
          </fieldset>
          {(canManage || canPublish) && (
            <div className="config-actions">
              {canManage && (
                <Button
                  label={busy ? "处理中…" : "保存草稿"}
                  isDisabled={busy || loading || !dirty || !key.trim()}
                  onClick={() => void mutate("save")}
                />
              )}
              {canPublish && (
                <Button
                  label="发布当前版本"
                  variant="secondary"
                  isDisabled={
                    busy ||
                    dirty ||
                    !snapshot ||
                    snapshot.publishedVersion === snapshot.version
                  }
                  onClick={() => void mutate("publish")}
                />
              )}
            </div>
          )}
          {history.length > 0 && (
            <section aria-label="版本历史">
              <h2>版本历史</h2>
              <ul className="config-history">
                {history.map((entry) => (
                  <li key={entry.version}>
                    <span>
                      版本 {entry.version}
                      {entry.version === snapshot?.publishedVersion
                        ? " · 已发布"
                        : ""}
                      {entry.sourceVersion
                        ? ` · 恢复自 ${entry.sourceVersion}`
                        : ""}
                      <small>
                        {new Date(entry.savedAt).toLocaleString()} · 保存人{" "}
                        {entry.savedBy}
                      </small>
                    </span>
                    <Button
                      label={`比较版本 ${entry.version}`}
                      variant="secondary"
                      isDisabled={busy}
                      onClick={() => setCompared(entry)}
                    />
                    {canManage && (
                      <Button
                        label={`恢复版本 ${entry.version}为新草稿`}
                        variant="secondary"
                        isDisabled={busy}
                        onClick={() => {
                          if (discardAllowed())
                            void mutate("restore", entry.version);
                        }}
                      />
                    )}
                  </li>
                ))}
              </ul>
            </section>
          )}
          {compared && (
            <section aria-label="版本比较">
              <h2>版本 {compared.version} 与当前草稿的差异</h2>
              <p>
                {JSON.stringify(compared.definition) === JSON.stringify(draft)
                  ? "配置内容相同。"
                  : "配置内容不同，可核对下方两份配置。"}
              </p>
              <div className="config-comparison">
                <div>
                  <h3>历史版本 {compared.version}</h3>
                  <ConfigurationPreview definition={compared.definition} />
                </div>
                <div>
                  <h3>当前草稿</h3>
                  <ConfigurationPreview definition={draft} />
                </div>
              </div>
            </section>
          )}
        </div>
      </div>
    </section>
  );
}

export default function HttpLedgerConfigPage(props: {
  canManage: boolean;
  canPublish: boolean;
  onUnauthenticated: () => void;
}) {
  const adapter = useMemo(() => createHttpLedgerConfig(createHttpClient()), []);
  return <LedgerConfigPage {...props} adapter={adapter} />;
}
