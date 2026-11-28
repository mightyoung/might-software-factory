import { useQuery } from "@tanstack/react-query";
import { createWorkspaceQueryClient } from "../../api/workspaceQueryClient";
import { useEffect, useMemo, useState } from "react";
import { ApiError } from "../../api/httpClient";
import { LedgerTable } from "../../components/LedgerTable";
import { LedgerQueryForm } from "../../components/LedgerQueryForm";
import { createEmptyQuery, type LedgerSchema, type QueryValues } from "../../domain/ledger";
import { wallTimeToIso } from "../../domain/time";
import { tenantPath } from "../../routing/paths";
import { navigate } from "../../routing/router";
import type { EntityRoute } from "../../routing/entityPaths";
import { useAppMode } from "../../mode";
import { draftOwnerKey } from "../session/draftStash";
import { EntityLedgerForm } from "./EntityLedgerForm";
import { ledgerFailure } from "./httpEntityLedger";
import { permits, valueLabel, UUID, type EntityLedgerAdapter, type LedgerCommand, type LedgerDefinition, type LedgerQuery, type LedgerRecord, type LedgerUse } from "./model";

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

type EntityLedgerPageProps = {
  adapter: EntityLedgerAdapter; route: EntityRoute; tenantId: string; actorId: string; identityScope?: { accountId: string; memberId: string };
  permissions: readonly string[]; onUnauthenticated(): void;
};

export function EntityLedgerPage(props: EntityLedgerPageProps) {
  const { adapter, route, tenantId, actorId, identityScope, permissions } = props;
  const mode = useAppMode();
  const [scope, setScope] = useState(() => ({ adapter, generation: 0 }));
  if (scope.adapter !== adapter) {
    setScope({ adapter, generation: scope.generation + 1 });
    return null;
  }
  const key = JSON.stringify([mode, tenantId, actorId, identityScope?.accountId, identityScope?.memberId, route.base, route.page, route.id, [...permissions].sort(), scope.generation]);
  return <EntityLedgerSession key={key} {...props} />;
}

function EntityLedgerSession({ adapter, route, tenantId, actorId, identityScope, permissions, onUnauthenticated }: EntityLedgerPageProps) {
  const use: LedgerUse = route.page === "create" ? "CREATE" : route.page === "edit" ? "EDIT" : route.page === "detail" ? "READ"
    : permissions.includes(`${route.entityKey}.read`) ? "READ" : permissions.includes(`${route.entityKey}.create`) ? "CREATE" : "EDIT";
  const requestedPermission = `${route.entityKey}.${use === "READ" ? "read" : use === "CREATE" ? "create" : "update"}`;
  const [client] = useState(createWorkspaceQueryClient);
  const authorized = permissions.includes(requestedPermission);
  const [attempt, setAttempt] = useState(0);
  const [command, setCommand] = useState<LedgerCommand | null>(null);
  const [filters, setFilters] = useState<QueryValues>();
  const [applied, setApplied] = useState<QueryValues>({});
  const [page, setPage] = useState(1);
  const [size, setSize] = useState<20 | 50 | 100>(20);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [queryAttempt, setQueryAttempt] = useState(0);
  const owner = draftOwnerKey(tenantId, actorId);
  const draftKey = JSON.stringify([identityScope?.accountId, identityScope?.memberId, route.base, route.page, route.id, use]);
  const bootstrap = useQuery({
    queryKey: ["entity-ledger", use, route.id, attempt],
    enabled: authorized,
    queryFn: async ({ signal }) => {
      const data = await adapter.definition(use, signal);
      signal.throwIfAborted();
      if (!permits(permissions, data.effectiveActions, data.definition.actions[use === "READ" ? "read" : use === "CREATE" ? "create" : "update"].permission)) throw new ApiError(403, "ACCESS_DENIED", null, false);
      let record: LedgerRecord | null = null;
      if (route.id) {
        if (!permits(permissions, data.effectiveActions, data.definition.actions.read.permission)) throw new ApiError(403, "ACCESS_DENIED", null, false);
        record = await adapter.record(data.definition, route.id, signal);
        if (use === "EDIT" && !permits(permissions, record.effectiveActions, data.definition.actions.update.permission)) throw new ApiError(403, "ACCESS_DENIED", null, false);
      }
      return { definition: data, record };
    },
  }, client);
  const definition = bootstrap.isSuccess ? bootstrap.data.definition : null;
  const current = bootstrap.isSuccess ? bootstrap.data.record : null;
  const schema = useMemo(() => definition ? querySchema(definition.definition) : null, [definition]);
  const emptyFilters = useMemo(() => createEmptyQuery(schema?.queryFields ?? []), [schema]);
  const canRead = definition !== null && permits(permissions, definition.effectiveActions, definition.definition.actions.read.permission);
  const canCreate = definition !== null && permits(permissions, definition.effectiveActions, definition.definition.actions.create.permission);
  const canEdit = definition !== null && current !== null && permits(permissions, definition.effectiveActions, definition.definition.actions.update.permission) && permits(permissions, current.effectiveActions, definition.definition.actions.update.permission);
  const records = useQuery({
    queryKey: ["entity-ledger-records", definition?.definition.definitionVersion, attempt, applied, page, size, queryAttempt],
    enabled: definition !== null && canRead && route.page === "entry" && use === "READ",
    queryFn: ({ signal }) => {
      if (!definition) throw new Error("台账定义不可用。");
      return adapter.query(definition.definition, makeQuery(definition.definition, applied, page, size), signal);
    },
  }, client);
  const failure = bootstrap.error ?? records.error;
  useEffect(() => { if (failure instanceof ApiError && failure.status === 401) onUnauthenticated(); }, [failure, onUnauthenticated]);
  const error = !authorized ? "当前没有执行此操作的权限。" : bootstrap.error ? ledgerFailure(bootstrap.error) : "";
  const result = records.isSuccess ? records.data : null;
  const queryError = records.error ? ledgerFailure(records.error) : "";
  const querying = records.isFetching;
  function reload() {
    setFilters(undefined); setApplied({}); setPage(1); setSelected(new Set()); setCommand(null); setAttempt(value => value + 1);
  }
  function go(tail = "") { navigate(tenantPath(tenantId, `${route.base}${tail}`)); }
  const title = definition?.definition.title ?? "通用台账";
  if (authorized && bootstrap.isPending) return <section className="ledger-main operational-page entity-ledger-page" role="status">正在加载台账…</section>;
  if (error || !definition || !schema) return <section className="ledger-main operational-page entity-ledger-page"><h1>{title}</h1><p role="alert">{error || "台账定义不可用。"}</p><button type="button" onClick={reload}>重新加载</button></section>;
  const d = definition.definition;
  return <section className="ledger-main operational-page entity-ledger-page">
    <div className="page-heading"><h1>{d.title}</h1><div>
      {canRead && route.page !== "entry" && <button type="button" className="button button-subtle" onClick={() => go()}>返回台账</button>}
      {canCreate && (route.page !== "create" || command) && <button type="button" className="button button-primary" onClick={() => { if (route.page === "create") reload(); else go("/create"); }}>{d.actions.create.label}</button>}
      {route.page === "detail" && canEdit && <button type="button" className="button button-primary" onClick={() => go(`/records/${current!.id}/edit`)}>{d.actions.update.label}</button>}
    </div></div>
    {command ? <div role="status"><h2>保存成功</h2><p>版本 {command.version}{command.replay ? "（原请求结果）" : ""}</p>{canRead && <button type="button" onClick={() => go(`/records/${command.id}`)}>查看已保存记录</button>}</div>
      : use === "EDIT" && current === null ? <p role="status">当前仅有修改权限，无法浏览或加载档案。</p>
      : (use === "CREATE" || use === "EDIT") ? <EntityLedgerForm key={draftKey} definition={d} use={use} adapter={adapter} record={current ?? undefined} owner={owner} draftKey={draftKey} onUnauthenticated={onUnauthenticated} onSuccess={setCommand} onRefresh={reload} />
        : current ? <dl className="operational-facts">{d.layout.detail.map(key => <div key={key}><dt>{d.fields[key]!.label}</dt><dd>{display(current, d, key)}</dd></div>)}</dl>
          : <>
            <LedgerQueryForm schema={schema} values={filters ?? emptyFilters} onChange={(key, value) => setFilters(previous => ({ ...(previous ?? emptyFilters), [key]: value }))} onSearch={() => { setApplied({ ...(filters ?? emptyFilters) }); setPage(1); setSelected(new Set()); setQueryAttempt(v => v + 1); }} onReset={() => { setFilters(undefined); setApplied({}); setPage(1); setSelected(new Set()); setQueryAttempt(v => v + 1); }} />
            {querying && <p role="status">查询中…</p>}{queryError && <p role="alert">{queryError}</p>}
            {result && <><LedgerTable columns={schema.columns} rows={result.records} getKey={row => row.id} selectedIds={selected} onToggleRow={id => setSelected(previous => { const next = new Set(previous); if (next.has(id)) next.delete(id); else next.add(id); return next; })} onTogglePage={() => setSelected(previous => previous.size === result.records.length ? new Set() : new Set(result.records.map(row => row.id)))} onOpen={row => go(`/records/${row.id}`)} />
              <div className="table-footer"><span>共 {result.total} 条，第 {result.page} 页</span><div className="pagination-controls"><label className="page-size-select"><span className="visually-hidden">每页条数</span><select aria-label="每页条数" value={size} onChange={event => { setSize(Number(event.target.value) as 20 | 50 | 100); setPage(1); setSelected(new Set()); }}>{[20, 50, 100].map(value => <option key={value} value={value}>{value}</option>)}</select></label><button type="button" className="button button-subtle" disabled={page <= 1 || querying} onClick={() => { setPage(v => v - 1); setSelected(new Set()); }}>上一页</button><button type="button" className="button button-subtle" disabled={!result.hasMore || querying} onClick={() => { setPage(v => v + 1); setSelected(new Set()); }}>下一页</button></div></div></>}
          </>}
  </section>;
}
