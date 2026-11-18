import { useEffect, useMemo, useState } from "react";
import { ApiError } from "../../api/httpClient";
import { LedgerTable } from "../../components/LedgerTable";
import { LedgerQueryForm } from "../../components/LedgerQueryForm";
import { createEmptyQuery, type LedgerSchema, type QueryValues } from "../../domain/ledger";
import { wallTimeToIso } from "../../domain/time";
import { tenantPath } from "../../routing/paths";
import { navigate } from "../../routing/router";
import type { EntityRoute } from "../../routing/entityPaths";
import { draftOwnerKey } from "../session/draftStash";
import { EntityLedgerForm } from "./EntityLedgerForm";
import { ledgerFailure } from "./httpEntityLedger";
import { permits, valueLabel, UUID, type DefinitionData, type EntityLedgerAdapter, type LedgerCommand, type LedgerDefinition, type LedgerQuery, type LedgerQueryData, type LedgerRecord, type LedgerUse } from "./model";

function display(record: LedgerRecord, definition: LedgerDefinition, key: string) {
  const value = record.values[key];
  return value === null || value === undefined ? "—" : valueLabel(definition.fields[key]?.valueLabels, String(value));
}
export function querySchema(definition: LedgerDefinition): LedgerSchema<LedgerRecord> {
  return { entityKey: definition.entityKey, title: definition.title, recordKey: record => record.id,
    columns: definition.layout.list.map(key => ({ key, label: definition.fields[key]!.label, kind: "text", read: record => display(record, definition, key) })),
    queryFields: definition.layout.query.map(key => {
      const field = definition.querySchema.fields[key]!, label = key === "id" ? "记录编号" : definition.fields[key]!.label;
      const read = (record: LedgerRecord) => key === "id" ? record.id : String(record.values[key] ?? "");
      if (field.type === "ENUM") return { key, label, kind: "enum", options: field.enumValues.map(value => ({ value, label: valueLabel(definition.fields[key]!.valueLabels, value) })), read };
      if (field.type === "NUMBER") return { key, label, kind: "number", operator: "equals", read: record => typeof record.values[key] === "number" ? record.values[key] as number : null };
      if (field.type === "DATETIME") return { key, label, kind: "dateTimeRange", read };
      return { key, label, kind: "text", read };
    }),
  };
}
export function makeQuery(definition: LedgerDefinition, values: QueryValues, page: number, size: 20 | 50 | 100): LedgerQuery {
  const conditions: NonNullable<LedgerQuery["filterAST"]>["conditions"] = [];
  for (const key of definition.layout.query) {
    const value = values[key], field = definition.querySchema.fields[key]!;
    if (typeof value === "string" && value.trim() !== "") {
      const operator = field.type === "TEXT" && field.operators.includes("CONTAINS") ? "CONTAINS" : "EQ";
      if (!field.operators.includes(operator)) throw new Error("该字段没有受支持的查询操作。");
      let normalized: unknown = value;
      if (field.type === "NUMBER") { if (!/^-?\d+$/.test(value) || !Number.isSafeInteger(Number(value))) throw new Error("查询数值必须是安全范围内的整数。"); normalized = Number(value); }
      if (field.type === "UUID" && !UUID.test(value)) throw new Error("查询引用必须是规范 UUID。");
      conditions.push({ field: key, operator, value: normalized });
    } else if (value && typeof value !== "string") {
      for (const [boundary, operator] of [[value.from, "GTE"], [value.to, "LTE"]] as const) {
        if (!boundary) continue;
        const iso = wallTimeToIso(boundary);
        if (!iso || !field.operators.includes(operator)) throw new Error("时间查询范围无效或未开放。");
        conditions.push({ field: key, operator, value: iso });
      }
    }
  }
  return { filterAST: { operator: "AND", conditions }, page: { number: page }, size };
}

export function EntityLedgerPage({ adapter, route, tenantId, actorId, identityScope, permissions, onUnauthenticated }: {
  adapter: EntityLedgerAdapter; route: EntityRoute; tenantId: string; actorId: string; identityScope?: { accountId: string; memberId: string };
  permissions: readonly string[]; onUnauthenticated(): void;
}) {
  const use: LedgerUse = route.page === "create" ? "CREATE" : route.page === "edit" ? "EDIT" : route.page === "detail" ? "READ"
    : permissions.includes(`${route.entityKey}.read`) ? "READ" : permissions.includes(`${route.entityKey}.create`) ? "CREATE" : "EDIT";
  const requestedPermission = `${route.entityKey}.${use === "READ" ? "read" : use === "CREATE" ? "create" : "update"}`;
  const [definition, setDefinition] = useState<DefinitionData | null>(null);
  const [current, setCurrent] = useState<LedgerRecord | null>(null);
  const [error, setError] = useState(""); const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0); const [command, setCommand] = useState<LedgerCommand | null>(null);
  const [filters, setFilters] = useState<QueryValues>({}), [applied, setApplied] = useState<QueryValues>({});
  const [page, setPage] = useState(1), [size, setSize] = useState<20 | 50 | 100>(20);
  const [result, setResult] = useState<LedgerQueryData | null>(null), [queryError, setQueryError] = useState(""), [querying, setQuerying] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set()); const [queryAttempt, setQueryAttempt] = useState(0);
  const owner = draftOwnerKey(tenantId, actorId);
  const draftKey = JSON.stringify([identityScope?.accountId, identityScope?.memberId, route.base, route.page, route.id, use]);
  function failed(failure: unknown) { if (failure instanceof ApiError && failure.status === 401) onUnauthenticated(); }
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(""); setDefinition(null); setCurrent(null); setResult(null); setCommand(null);
    if (!permissions.includes(requestedPermission)) { setError("当前没有执行此操作的权限。"); setLoading(false); return; }
    adapter.definition(use, controller.signal).then(async data => {
      if (controller.signal.aborted) return;
      if (!permits(permissions, data.effectiveActions, data.definition.actions[use === "READ" ? "read" : use === "CREATE" ? "create" : "update"].permission)) throw new ApiError(403, "ACCESS_DENIED", null, false);
      let record: LedgerRecord | null = null;
      if (route.id) {
        if (!permits(permissions, data.effectiveActions, data.definition.actions.read.permission)) throw new ApiError(403, "ACCESS_DENIED", null, false);
        record = await adapter.record(data.definition, route.id, controller.signal);
        if (use === "EDIT" && !permits(permissions, record.effectiveActions, data.definition.actions.update.permission)) throw new ApiError(403, "ACCESS_DENIED", null, false);
      }
      if (controller.signal.aborted) return;
      setDefinition(data); setCurrent(record); setFilters(createEmptyQuery(querySchema(data.definition).queryFields)); setApplied({}); setPage(1); setLoading(false);
    }).catch(failure => { if (controller.signal.aborted) return; setError(ledgerFailure(failure)); setLoading(false); failed(failure); });
    return () => controller.abort();
    // Permissions and adapters are replaced by the app identity boundary.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adapter, route.page, route.id, route.base, use, attempt]);
  const schema = useMemo(() => definition ? querySchema(definition.definition) : null, [definition]);
  const canRead = definition !== null && permits(permissions, definition.effectiveActions, definition.definition.actions.read.permission);
  const canCreate = definition !== null && permits(permissions, definition.effectiveActions, definition.definition.actions.create.permission);
  const canEdit = definition !== null && current !== null && permits(permissions, definition.effectiveActions, definition.definition.actions.update.permission) && permits(permissions, current.effectiveActions, definition.definition.actions.update.permission);
  useEffect(() => {
    if (!definition || !canRead || route.page !== "entry" || use !== "READ") return;
    const controller = new AbortController(); setQuerying(true); setQueryError(""); setSelected(new Set());
    let query: LedgerQuery;
    try { query = makeQuery(definition.definition, applied, page, size); }
    catch (failure) { setQueryError(ledgerFailure(failure)); setQuerying(false); return; }
    adapter.query(definition.definition, query, controller.signal).then(data => { if (!controller.signal.aborted) { setResult(data); setQuerying(false); } })
      .catch(failure => { if (!controller.signal.aborted) { setResult(null); setQueryError(ledgerFailure(failure)); setQuerying(false); failed(failure); } });
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adapter, definition, canRead, route.page, use, applied, page, size, queryAttempt]);
  function go(tail = "") { navigate(tenantPath(tenantId, `${route.base}${tail}`)); }
  const title = definition?.definition.title ?? "通用台账";
  if (loading) return <section className="ledger-main" role="status">正在加载台账…</section>;
  if (error || !definition || !schema) return <section className="ledger-main"><h1>{title}</h1><p role="alert">{error || "台账定义不可用。"}</p><button type="button" onClick={() => setAttempt(v => v + 1)}>重新加载</button></section>;
  const d = definition.definition;
  return <section className="ledger-main">
    <div className="page-heading"><h1>{d.title}</h1><div>
      {canRead && route.page !== "entry" && <button type="button" className="button button-subtle" onClick={() => go()}>返回台账</button>}
      {canCreate && (route.page !== "create" || command) && <button type="button" className="button button-primary" onClick={() => { if (route.page === "create") setAttempt(v => v + 1); else go("/create"); }}>{d.actions.create.label}</button>}
      {route.page === "detail" && canEdit && <button type="button" className="button button-primary" onClick={() => go(`/records/${current!.id}/edit`)}>{d.actions.update.label}</button>}
    </div></div>
    {command ? <div role="status"><h2>保存成功</h2><p>版本 {command.version}{command.replay ? "（原请求结果）" : ""}</p>{canRead && <button type="button" onClick={() => go(`/records/${command.id}`)}>查看已保存记录</button>}</div>
      : use === "EDIT" && current === null ? <p role="status">当前仅有修改权限，无法浏览或加载档案。</p>
      : (use === "CREATE" || use === "EDIT") ? <EntityLedgerForm key={draftKey} definition={d} use={use} adapter={adapter} record={current ?? undefined} owner={owner} draftKey={draftKey} onUnauthenticated={onUnauthenticated} onSuccess={setCommand} onRefresh={() => setAttempt(v => v + 1)} />
        : current ? <dl>{d.layout.detail.map(key => <div key={key}><dt>{d.fields[key]!.label}</dt><dd>{display(current, d, key)}</dd></div>)}</dl>
          : <>
            <LedgerQueryForm schema={schema} values={filters} onChange={(key, value) => setFilters(previous => ({ ...previous, [key]: value }))} onSearch={() => { setApplied({ ...filters }); setPage(1); setQueryAttempt(v => v + 1); }} onReset={() => { setFilters(createEmptyQuery(schema.queryFields)); setApplied({}); setPage(1); setQueryAttempt(v => v + 1); }} />
            {querying && <p role="status">查询中…</p>}{queryError && <p role="alert">{queryError}</p>}
            {result && <><LedgerTable columns={schema.columns} rows={result.records} getKey={row => row.id} selectedIds={selected} onToggleRow={id => setSelected(previous => { const next = new Set(previous); if (next.has(id)) next.delete(id); else next.add(id); return next; })} onTogglePage={() => setSelected(previous => previous.size === result.records.length ? new Set() : new Set(result.records.map(row => row.id)))} onOpen={row => go(`/records/${row.id}`)} />
              <div className="table-footer"><span>共 {result.total} 条，第 {result.page} 页</span><div className="pagination-controls"><label className="page-size-select"><span className="visually-hidden">每页条数</span><select aria-label="每页条数" value={size} onChange={event => { setSize(Number(event.target.value) as 20 | 50 | 100); setPage(1); }}>{[20, 50, 100].map(value => <option key={value} value={value}>{value}</option>)}</select></label><button type="button" className="button button-subtle" disabled={page <= 1 || querying} onClick={() => setPage(v => v - 1)}>上一页</button><button type="button" className="button button-subtle" disabled={!result.hasMore || querying} onClick={() => setPage(v => v + 1)}>下一页</button></div></div></>}
          </>}
  </section>;
}
