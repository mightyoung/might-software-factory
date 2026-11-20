import { Button } from "@astryxdesign/core/Button";
import { CheckboxInput } from "@astryxdesign/core/CheckboxInput";
import { useEffect, useMemo, useRef, useState } from "react";
import { ApiError, createHttpClient } from "../../api/httpClient";
import type { LedgerConfiguration } from "../ledgerConfig/model";
import { createPublishedLedgerAdapter } from "./model";
import type {
  PublishedLedger,
  PublishedLedgerAdapter,
  Query,
  RecordDetail,
  RecordPage,
  Values,
} from "./model";
import "./published-ledgers.css";

type Editor = {
  detail: RecordDetail | null;
  definition: LedgerConfiguration;
  values: Values;
  organizationId: string;
  editing: boolean;
};
const display = (value: unknown) =>
  value === null || value === undefined
    ? "—"
    : typeof value === "boolean"
      ? value
        ? "是"
        : "否"
      : String(value);

export function PublishedLedgersPage({
  adapter,
  onUnauthenticated,
}: {
  adapter: PublishedLedgerAdapter;
  onUnauthenticated(): void;
}) {
  const [ledgers, setLedgers] = useState<PublishedLedger[]>([]);
  const [ledger, setLedger] = useState<PublishedLedger | null>(null);
  const [result, setResult] = useState<RecordPage | null>(null);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [query, setQuery] = useState<Query>({
    page: 1,
    size: 20,
    filters: [],
  });
  const [attempt, setAttempt] = useState(0);
  const [loading, setLoading] = useState(true);
  const [querying, setQuerying] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const recordForm = useRef<HTMLFormElement>(null);
  const generation = useRef(0);
  const command = useRef<{ signature: string; id: string } | null>(null);
  const original = editor?.detail?.record.values;
  const dirty =
    !!editor?.editing &&
    JSON.stringify(editor.values) !== JSON.stringify(original ?? {});
  function failed(cause: unknown) {
    if (cause instanceof ApiError && cause.status === 401) {
      onUnauthenticated();
      return;
    }
    if (cause instanceof ApiError && cause.status === 403)
      setError("当前授权不允许此操作，请刷新台账核对权限。");
    else if (cause instanceof ApiError && cause.status === 409)
      setError("记录或台账定义已更新。输入已保留，请重新打开记录核对。");
    else if (cause instanceof ApiError && [400, 422].includes(cause.status))
      setError("请检查必填字段、数值、日期和所属组织。");
    else setError("操作未确认成功。请重试，写入重试会沿用原请求标识。");
  }
  useEffect(() => {
    const controller = new AbortController();
    const mine = ++generation.current;
    setLoading(true);
    setError("");
    adapter
      .list(controller.signal)
      .then((items) => {
        if (mine !== generation.current) return;
        setLedgers(items);
        setLedger(
          (current) => items.find((item) => item.key === current?.key) ?? null,
        );
      })
      .catch((cause) => {
        if (!controller.signal.aborted) failed(cause);
      })
      .finally(() => {
        if (mine === generation.current) setLoading(false);
      });
    return () => {
      controller.abort();
      generation.current += 1;
    };
  }, [adapter, attempt]);
  useEffect(() => {
    if (!ledger?.capabilities.read) {
      setResult(null);
      return;
    }
    const controller = new AbortController();
    setQuerying(true);
    setError("");
    adapter
      .query(ledger.key, query, controller.signal)
      .then((next) => {
        if (!controller.signal.aborted) setResult(next);
      })
      .catch((cause) => {
        if (!controller.signal.aborted) {
          setResult(null);
          failed(cause);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setQuerying(false);
      });
    return () => controller.abort();
  }, [adapter, ledger, query, attempt]);
  function mayDiscard() {
    return !dirty || window.confirm("当前记录有未保存的修改，继续吗？");
  }
  function select(next: PublishedLedger) {
    if (busy || !mayDiscard()) return;
    setLedger(next);
    setFilters({});
    setEditor(null);
    setResult(null);
    setNotice("");
    setError("");
    command.current = null;
    setQuery({
      page: 1,
      size: 20,
      filters: [],
    });
  }
  function create() {
    if (!ledger || busy || !mayDiscard()) return;
    setEditor({
      detail: null,
      definition: ledger.definition,
      values: Object.fromEntries(
        ledger.definition.fields
          .filter((field) => field.type === "boolean")
          .map((field) => [field.key, false]),
      ),
      organizationId:
        ledger.organizations.length === 1 ? ledger.organizations[0]!.id : "",
      editing: true,
    });
    setNotice("");
    setError("");
    command.current = null;
  }
  async function open(id: string) {
    if (!ledger || busy || !mayDiscard()) return;
    const mine = generation.current;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const detail = await adapter.detail(ledger.key, id);
      if (mine === generation.current) {
        setEditor({
          detail,
          definition: detail.definition,
          values: detail.record.values,
          organizationId: detail.record.organizationId,
          editing: false,
        });
        command.current = null;
      }
    } catch (cause) {
      if (mine === generation.current) failed(cause);
    } finally {
      if (mine === generation.current) setBusy(false);
    }
  }
  function applyFilters() {
    if (!ledger) return;
    const next: Query["filters"] = [];
    for (const field of ledger.definition.fields.filter((field) =>
      ledger.definition.queryFields.includes(field.key),
    )) {
      const value = filters[field.key];
      if (!value) continue;
      if (field.type === "number" && !Number.isFinite(Number(value))) {
        setError("查询数值格式无效。");
        return;
      }
      next.push({
        field: field.key,
        operator: field.type === "text" ? "contains" : "eq",
        value:
          field.type === "number"
            ? Number(value)
            : field.type === "boolean"
              ? value === "true"
              : value,
      });
    }
    setQuery((current) => ({ ...current, filters: next, page: 1 }));
  }
  async function mutate(action: "save" | "delete") {
    if (!ledger || !editor || busy) return;
    if (action === "delete" && !window.confirm("删除这条草稿记录吗？")) return;
    const mine = generation.current;
    const record = editor.detail?.record;
    const signature = JSON.stringify([
      ledger.key,
      action,
      record?.id,
      record?.version,
      editor.organizationId,
      editor.values,
    ]);
    if (command.current?.signature !== signature)
      command.current = { signature, id: crypto.randomUUID() };
    const requestId = command.current.id;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      if (action === "delete")
        await adapter.remove(ledger.key, record!.id, {
          requestId,
          expectedVersion: record!.version,
        });
      else if (record)
        await adapter.update(ledger.key, record.id, {
          requestId,
          expectedVersion: record.version,
          values: editor.values,
        });
      else
        await adapter.create(ledger.key, {
          requestId,
          definitionVersion: ledger.definitionVersion,
          organizationId: editor.organizationId,
          values: editor.values,
        });
      if (mine !== generation.current) return;
      setEditor(null);
      command.current = null;
      setNotice(action === "delete" ? "草稿已删除。" : "记录已保存。");
      setQuery((current) => ({ ...current, page: 1 }));
    } catch (cause) {
      if (mine === generation.current) failed(cause);
    } finally {
      if (mine === generation.current) setBusy(false);
    }
  }
  const editing = !!editor?.editing;
  return (
    <section className="published-ledgers">
      <div className="page-heading">
        <h1>业务台账</h1>
        <Button
          label="刷新台账"
          variant="secondary"
          isDisabled={busy || loading}
          onClick={() => {
            if (mayDiscard()) {
              setEditor(null);
              setAttempt((value) => value + 1);
            }
          }}
        />
      </div>
      {loading && <p role="status">正在读取已发布台账…</p>}
      {error && <p role="alert">{error}</p>}
      {notice && <p role="status">{notice}</p>}
      <div className="published-layout">
        <nav aria-label="已发布业务台账" className="published-list">
          {!loading && ledgers.length === 0 && <p>暂无可访问的已发布台账。</p>}
          {ledgers.map((item) => (
            <button
              type="button"
              key={item.key}
              aria-current={ledger?.key === item.key ? "true" : undefined}
              disabled={busy}
              onClick={() => select(item)}
            >
              {item.definition.name}
            </button>
          ))}
        </nav>
        <div className="published-content">
          {ledger && (
            <>
              <div className="page-heading">
                <h2>{ledger.definition.name}</h2>
                {ledger.capabilities.create && (
                  <Button
                    label="新增记录"
                    isDisabled={busy || loading}
                    onClick={create}
                  />
                )}
              </div>
              {ledger.capabilities.read && (
                <>
                  <form
                    className="published-query"
                    onSubmit={(event) => {
                      event.preventDefault();
                      applyFilters();
                    }}
                  >
                    {ledger.definition.fields
                      .filter((field) =>
                        ledger.definition.queryFields.includes(field.key),
                      )
                      .map((field) => (
                        <label key={field.key}>
                          {field.label}
                          {field.type === "boolean" ? (
                            <select
                              value={filters[field.key] ?? ""}
                              onChange={(event) =>
                                setFilters((current) => ({
                                  ...current,
                                  [field.key]: event.target.value,
                                }))
                              }
                            >
                              <option value="">全部</option>
                              <option value="true">是</option>
                              <option value="false">否</option>
                            </select>
                          ) : (
                            <input
                              type={
                                field.type === "number"
                                  ? "number"
                                  : field.type === "date"
                                    ? "date"
                                    : "text"
                              }
                              step="any"
                              value={filters[field.key] ?? ""}
                              onChange={(event) =>
                                setFilters((current) => ({
                                  ...current,
                                  [field.key]: event.target.value,
                                }))
                              }
                            />
                          )}
                        </label>
                      ))}
                    <Button
                      label="查询"
                      isDisabled={busy || querying}
                      onClick={applyFilters}
                    />
                  </form>
                  {querying && <p role="status">正在查询记录…</p>}
                  {result && (
                    <>
                      <p>共 {result.total} 条记录</p>
                      <div className="published-table">
                        <table>
                          <caption>{ledger.definition.name}记录</caption>
                          <thead>
                            <tr>
                              {ledger.definition.listFields.map((key) => (
                                <th key={key} scope="col">
                                  {ledger.definition.fields.find(
                                    (field) => field.key === key,
                                  )?.label ?? key}
                                </th>
                              ))}
                              <th scope="col">操作</th>
                            </tr>
                          </thead>
                          <tbody>
                            {result.records.map((record) => (
                              <tr key={record.id}>
                                {ledger.definition.listFields.map((key) => (
                                  <td key={key}>
                                    {display(record.values[key])}
                                  </td>
                                ))}
                                <td>
                                  <Button
                                    label="查看记录"
                                    variant="secondary"
                                    isDisabled={busy}
                                    onClick={() => void open(record.id)}
                                  />
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                      {result.total === 0 && <p>没有符合条件的记录。</p>}
                      <div className="published-actions">
                        <Button
                          label="上一页"
                          variant="secondary"
                          isDisabled={busy || querying || query.page === 1}
                          onClick={() =>
                            setQuery((current) => ({
                              ...current,
                              page: current.page - 1,
                            }))
                          }
                        />
                        <span>第 {query.page} 页</span>
                        <Button
                          label="下一页"
                          variant="secondary"
                          isDisabled={busy || querying || !result.hasMore}
                          onClick={() =>
                            setQuery((current) => ({
                              ...current,
                              page: current.page + 1,
                            }))
                          }
                        />
                      </div>
                    </>
                  )}
                </>
              )}
              {editor && (
                <section
                  className="published-editor"
                  aria-label={editor.detail ? "记录详情" : "新增记录"}
                >
                  <div className="page-heading">
                    <h2>
                      {editor.detail
                        ? editing
                          ? "编辑草稿"
                          : "记录详情"
                        : "新增记录"}
                    </h2>
                    <Button
                      label="关闭记录"
                      variant="secondary"
                      isDisabled={busy}
                      onClick={() => {
                        if (mayDiscard()) setEditor(null);
                      }}
                    />
                  </div>
                  {editor.detail && (
                    <p>
                      定义版本 {editor.detail.record.definitionVersion} ·
                      记录版本 {editor.detail.record.version}
                    </p>
                  )}
                  <form
                    ref={recordForm}
                    onSubmit={(event) => {
                      event.preventDefault();
                      void mutate("save");
                    }}
                  >
                    <fieldset disabled={busy || !editing}>
                      <legend>记录内容</legend>
                      <label>
                        所属组织
                        <select
                          required
                          disabled={!!editor.detail || !editing || busy}
                          value={editor.organizationId}
                          onChange={(event) =>
                            setEditor(
                              (current) =>
                                current && {
                                  ...current,
                                  organizationId: event.target.value,
                                },
                            )
                          }
                        >
                          <option value="">请选择组织</option>
                          {editor.detail &&
                            !ledger.organizations.some(
                              (org) => org.id === editor.organizationId,
                            ) && (
                              <option value={editor.organizationId}>
                                {editor.organizationId}
                              </option>
                            )}
                          {ledger.organizations.map((org) => (
                            <option key={org.id} value={org.id}>
                              {org.name}
                            </option>
                          ))}
                        </select>
                      </label>
                      {editor.definition.fields.map((field) => (
                        <label key={field.key}>
                          {field.type === "boolean" ? (
                            <CheckboxInput
                              label={field.label}
                              value={editor.values[field.key] === true}
                              isDisabled={busy || !editing}
                              onChange={() =>
                                setEditor(
                                  (current) =>
                                    current && {
                                      ...current,
                                      values: {
                                        ...current.values,
                                        [field.key]:
                                          current.values[field.key] !== true,
                                      },
                                    },
                                )
                              }
                            />
                          ) : (
                            <>
                              {field.label}
                              {field.required ? "（必填）" : ""}
                              <input
                                required={field.required}
                                type={
                                  field.type === "number"
                                    ? "number"
                                    : field.type === "date"
                                      ? "date"
                                      : "text"
                                }
                                step="any"
                                value={String(editor.values[field.key] ?? "")}
                                onChange={(event) => {
                                  const value = event.target.value;
                                  setEditor(
                                    (current) =>
                                      current && {
                                        ...current,
                                        values: {
                                          ...current.values,
                                          [field.key]:
                                            value === ""
                                              ? null
                                              : field.type === "number"
                                                ? Number(value)
                                                : value,
                                        },
                                      },
                                  );
                                }}
                              />
                            </>
                          )}
                        </label>
                      ))}
                    </fieldset>
                    <div className="published-actions">
                      {editing && (
                        <Button
                          label={busy ? "处理中…" : "保存记录"}
                          isDisabled={busy || !editor.organizationId}
                          onClick={() => {
                            if (recordForm.current?.reportValidity())
                              void mutate("save");
                          }}
                        />
                      )}
                      {editor.detail &&
                        !editing &&
                        editor.detail.capabilities.update && (
                          <Button
                            label="编辑草稿"
                            variant="secondary"
                            isDisabled={busy}
                            onClick={() =>
                              setEditor(
                                (current) =>
                                  current && { ...current, editing: true },
                              )
                            }
                          />
                        )}
                      {editor.detail?.capabilities.delete && (
                        <Button
                          label="删除草稿"
                          variant="secondary"
                          isDisabled={busy}
                          onClick={() => void mutate("delete")}
                        />
                      )}
                    </div>
                  </form>
                </section>
              )}
            </>
          )}
        </div>
      </div>
    </section>
  );
}
export default function HttpPublishedLedgersPage({
  onUnauthenticated,
}: {
  onUnauthenticated(): void;
}) {
  const adapter = useMemo(
    () => createPublishedLedgerAdapter(createHttpClient()),
    [],
  );
  return (
    <PublishedLedgersPage
      adapter={adapter}
      onUnauthenticated={onUnauthenticated}
    />
  );
}
