import { useForm } from "react-hook-form";
import { useQuery } from "@tanstack/react-query";
import { createWorkspaceQueryClient } from "../../api/workspaceQueryClient";
import { Button } from "@astryxdesign/core/Button";
import { CheckboxInput } from "@astryxdesign/core/CheckboxInput";
import { useContext, useEffect, useMemo, useRef, useState } from "react";
import { LedgerConfigRouteScope } from "../../routing/ledgerConfigRouteScope";
import { WorkbenchConfirmDialog } from "../../components/WorkbenchConfirmDialog";
import { useUnsavedGuard } from "../../components/useUnsavedGuard";
import { createHttpLedgerConfig } from "./model";
import { LedgerViewPreview } from "./LedgerViewPreview";
import { LedgerViewOrder } from "./LedgerViewOrder";
import { ApiError, createHttpClient } from "../../api/httpClient";
import type {
  ConfigurationSnapshot,
  ConfigurationSummary,
  LedgerConfigAdapter,
  LedgerConfiguration,
  LedgerField,
} from "./model";
import "./ledger-config.css";

const EMPTY_METADATA = { key: "", name: "" };
const EMPTY_STRUCTURE: Omit<LedgerConfiguration, "name"> = {
  fields: [],
  listFields: [],
  queryFields: [],
};
const fieldTypes = {
  text: "文本",
  number: "数字",
  date: "日期",
  boolean: "是/否",
};

type SwitchDraft =
  | { kind: "select"; item: ConfigurationSummary }
  | { kind: "create"; source: ConfigurationSnapshot | null }
  | { kind: "restore"; version: number };

type Operation = {
  action: "save" | "restore" | "publish";
  key: string;
  version: number;
  sourceVersion?: number;
  send: () => Promise<ConfigurationSnapshot>;
};

const orderedViews = [
  { property: "listFields", label: "列表字段顺序" },
  { property: "queryFields", label: "查询字段顺序" },
] as const;

function ConfigurationOrder({ definition }: { definition: LedgerConfiguration }) {
  return (
    <>
      {orderedViews.map(({ property, label }) => (
        <div className="config-order" key={property}>
          <p>{label}</p>
          {definition[property].length ? (
            <ol aria-label={label}>
              {definition[property].map((key, index) => {
                const field = definition.fields.find((candidate) => candidate.key === key);
                return <li key={index}>{field?.label ? `${field.label}（${key}）` : key}</li>;
              })}
            </ol>
          ) : <p>无</p>}
        </div>
      ))}
    </>
  );
}

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
      <ConfigurationOrder definition={definition} />
    </div>
  );
}

type LedgerConfigProps = {
  adapter: LedgerConfigAdapter;
  active?: boolean;
  canManage: boolean;
  canPublish: boolean;
  onUnauthenticated: () => void;
};

export function LedgerConfigPage(props: LedgerConfigProps) {
  const [scope, setScope] = useState(() => ({ adapter: props.adapter, generation: 0 }));
  if (scope.adapter !== props.adapter) {
    setScope({ adapter: props.adapter, generation: scope.generation + 1 });
    return null;
  }
  return <LedgerConfigSession key={scope.generation} {...props} />;
}

function LedgerConfigSession({ adapter, canManage, canPublish, onUnauthenticated, active = true }: LedgerConfigProps) {
  const { watch, setValue, reset } = useForm({ defaultValues: EMPTY_METADATA });
  const { key, name } = watch();
  const [snapshot, setSnapshot] = useState<ConfigurationSnapshot | null>(null);
  const [structure, setStructure] = useState(EMPTY_STRUCTURE);
  const draft = { name, ...structure };
  const [history, setHistory] = useState<ConfigurationSnapshot[]>([]);
  const [compared, setCompared] = useState<ConfigurationSnapshot | null>(null);
  const [copySource, setCopySource] = useState<ConfigurationSnapshot | null>(null);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [failedAuthorization, setFailedAuthorization] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const lifetime = useRef(0);
  const runningOperation = useRef(false);
  const [recovery, setRecovery] = useState<Operation | null>(null);
  const [pendingSwitch, setPendingSwitch] = useState<SwitchDraft | null>(null);
  useEffect(() => { setPendingSwitch(null); }, [adapter, active]);
  const { name: savedName, ...savedStructure } = snapshot?.definition ?? { name: "", ...EMPTY_STRUCTURE };
  const dirty = snapshot
    ? name !== savedName || JSON.stringify(structure) !== JSON.stringify(savedStructure)
    : Boolean(key || draft.name || draft.fields.length);
  const protectingOperation = runningOperation.current || recovery !== null;
  const locked = busy || recovery !== null;
  useUnsavedGuard(
    dirty || protectingOperation,
    (protectingOperation ? "配置操作尚未确认。" : "配置尚未保存。") + "可在此标签页返回。确定离开？",
    active,
  );

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
    else setError("操作未确认成功，请稍后重试。");
  }
  const [reads] = useState(createWorkspaceQueryClient);
  const listKey = ["ledger-config-directory", loadAttempt] as const;
  const directory = useQuery({
    queryKey: listKey,
    enabled: active,
    queryFn: ({ signal }) => adapter.list(signal),
  }, reads);
  const loading = active && (directory.isPending || directory.isFetching);
  const items = directory.isSuccess && !loading ? directory.data : [];
  const loadStatus = directory.error instanceof ApiError ? directory.error.status : 0;
  const denied = loadStatus === 401 || loadStatus === 403;
  const blocked = denied || (failedAuthorization && (!directory.isSuccess || loading));
  // A reactivation rechecks authority before revealing a retained loaded draft.
  const hideDraft = blocked || (loading && (snapshot !== null || copySource !== null));
  const listError = directory.isError
    ? loadStatus === 401 ? "登录已失效，请重新登录。"
      : loadStatus === 403 ? "你没有执行此配置操作的权限。"
        : "暂时无法读取台账配置，请稍后重试。"
    : "";
  useEffect(() => {
    if (denied) setFailedAuthorization(true);
    else if (directory.isSuccess && !loading) setFailedAuthorization(false);
  }, [denied, directory.isSuccess, loading]);
  useEffect(() => {
    if (loadStatus === 401) onUnauthenticated();
  }, [directory.error, loadStatus, onUnauthenticated]);
  useEffect(() => {
    if (!active) void reads.cancelQueries({ queryKey: ["ledger-config-directory"] });
  }, [active, reads]);
  useEffect(() => {
    lifetime.current += 1;
    return () => { lifetime.current += 1; };
  }, []);

  function switchDraft(target: SwitchDraft, confirmed = false) {
    if (!active || blocked || loading || locked || runningOperation.current || (target.kind !== "select" && !canManage)) return;
    if (target.kind === "select" && !items.some(item => item.key === target.item.key)) return;
    if (dirty && !confirmed) {
      setPendingSwitch((current) => current ?? target);
    } else if (target.kind === "select") {
      void select(target.item);
    } else if (target.kind === "create") {
      create(target.source);
    } else {
      mutate("restore", target.version);
    }
  }
  function showSnapshot(next: ConfigurationSnapshot) {
    setSnapshot(next);
    const { name, ...structure } = next.definition;
    setValue("name", name);
    setStructure(structure);
    setCompared(null);
    setCopySource(null);
  }
  function updateDirectory(next: ConfigurationSnapshot) {
    if (reads.getQueryState(listKey)?.status !== "success") return;
    reads.setQueryData<ConfigurationSummary[]>(listKey, current => current && [
      { ...next, latestVersion: next.version },
      ...current.filter((item) => item.key !== next.key),
    ]);
  }
  async function select(item: ConfigurationSummary) {
    const mine = lifetime.current;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const versions = await adapter.versions(item.key);
      if (mine !== lifetime.current) return;
      const latest = versions[0];
      if (!latest) throw new Error("Empty configuration history");
      setValue("key", item.key);
      showSnapshot(latest);
      setHistory(versions);
    } catch (cause) {
      if (mine === lifetime.current) failed(cause);
    } finally {
      if (mine === lifetime.current) setBusy(false);
    }
  }
  function create(source: ConfigurationSnapshot | null) {
    setSnapshot(null);
    if (source) {
      const { name, ...structure } = structuredClone(source.definition);
      reset({ key: "", name: name + "副本" });
      setStructure(structure);
    } else {
      reset(EMPTY_METADATA);
      setStructure(EMPTY_STRUCTURE);
    }
    setCopySource(source);
    setHistory([]);
    setCompared(null);
    setError("");
    setNotice("");
  }
  function updateField(index: number, patch: Partial<LedgerField>) {
    setStructure((current) => {
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
    setStructure((current) => ({
      ...current,
      [property]: current[property].includes(field)
        ? current[property].filter((value) => value !== field)
        : [...current[property], field],
    }));
  }
  function mutate(action: Operation["action"], sourceVersion?: number) {
    const target = key.trim();
    if (
      blocked || loading || locked || runningOperation.current ||
      !(action === "publish" ? canPublish : canManage) ||
      !target ||
      (action === "publish" && (dirty || !snapshot))
    ) return;
    if (copySource && (target === copySource.key || items.some((item) => item.key === target))) {
      setError(target === copySource.key ? "新台账必须使用与来源不同的标识。" : "新台账标识已存在，请使用其他标识。");
      return;
    }
    const version = snapshot?.version ?? 0;
    const publishedVersion = snapshot?.publishedVersion ?? null;
    // Capture the immutable request at the first explicit action. A retry cannot
    // pick up another ledger, edited draft, new version, or idempotency key.
    const id = action === "publish" ? null : crypto.randomUUID();
    void runOperation({
      action, key, version, sourceVersion,
      send: () => action === "save"
        ? adapter.save(key, draft, version, id!)
        : action === "restore"
          ? adapter.restore(key, sourceVersion!, version, id!)
          : adapter.publish(key, version, publishedVersion),
    });
  }

  async function runOperation(operation: Operation) {
    const { action, key: operationKey, version, sourceVersion } = operation;
    if (blocked || loading || busy || runningOperation.current || !(action === "publish" ? canPublish : canManage)) return;
    const mine = lifetime.current;
    runningOperation.current = true;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      let next;
      try {
        next = await operation.send();
      } catch (cause) {
        // Publish checks the draft head before its idempotent current-pointer test.
        // A newer draft can therefore cause our previously successful retry to conflict.
        if (!(recovery === operation && action === "publish" &&
          cause instanceof ApiError && cause.status === 409 && cause.code === "VERSION_CONFLICT")) throw cause;
        const versions = await adapter.versions(operationKey);
        if (mine !== lifetime.current) return;
        const latest = versions[0];
        if (!latest) throw new Error("Empty configuration history");
        setRecovery(null);
        if (latest.publishedVersion !== version) {
          setError("无法确认原发布结果，请重新打开此台账核对当前发布版本。");
          return;
        }
        showSnapshot(latest);
        setHistory(versions);
        updateDirectory(latest);
        setNotice(`版本 ${version} 已发布；已载入最新草稿版本 ${latest.version}。`);
        return;
      }
      if (mine !== lifetime.current) return;
      setRecovery(null);
      showSnapshot(next);
      setHistory((current) =>
        action === "publish"
          ? current.map((entry) => ({
              ...entry,
              publishedVersion: next.publishedVersion,
            }))
          : [next, ...current],
      );
      updateDirectory(next);
      setNotice(
        action === "publish"
          ? `已发布版本 ${next.version}。`
          : action === "restore"
            ? `已从版本 ${sourceVersion} 创建新草稿 ${next.version}，可核对后发布。`
            : `已保存草稿版本 ${next.version}。`,
      );
    } catch (cause) {
      if (mine !== lifetime.current) return;
      const definitiveConflict =
        cause instanceof ApiError && cause.status === 409 &&
        cause.code === "VERSION_CONFLICT" && (action !== "publish" || recovery !== operation);
      const unknownResult =
        !(cause instanceof ApiError) || cause.status === 0 || cause.status >= 500 ||
        (recovery === operation && !definitiveConflict);
      setRecovery(unknownResult ? operation : null);
      if (unknownResult && !(cause instanceof ApiError && cause.status === 401)) {
        setError("操作未确认成功。重试沿用原版本、内容和请求标识。");
      } else if (copySource && definitiveConflict) {
        setError("新台账标识已被使用。你的输入已保留，请使用其他标识后重新保存。");
      } else {
        failed(cause);
      }
    } finally {
      runningOperation.current = false;
      if (mine === lifetime.current) setBusy(false);
    }
  }
  return (
    <section className="config-page">
      {active && !hideDraft && !loading && pendingSwitch && <WorkbenchConfirmDialog
        title="放弃未保存的修改？"
        description={pendingSwitch.kind === "restore"
          ? "恢复历史版本会替换未保存的修改，并创建新草稿，不会直接发布。"
          : "切换台账后，当前没有保存的配置修改会丢失。"}
        confirmLabel={pendingSwitch.kind === "restore" ? "放弃并恢复" : "放弃并切换"}
        onCancel={() => setPendingSwitch(null)}
        onConfirm={() => {
          setPendingSwitch(null);
          switchDraft(pendingSwitch, true);
        }}
      />}
      <div className="page-heading">
        <h1>台账配置</h1>
        {canManage && !blocked && (
          <Button
            label="新建台账"
            variant="secondary"
            isDisabled={locked || loading}
            onClick={() => switchDraft({ kind: "create", source: null })}
          />
        )}
      </div>
      {loading && <p role="status">正在载入配置…</p>}
      {(listError || (!hideDraft && error)) && <p role="alert">{listError || error}</p>}
      {directory.isError && loadStatus !== 401 && (
        <Button
          label="重新载入台账列表"
          isDisabled={busy}
          variant="secondary"
          onClick={() => setLoadAttempt((value) => value + 1)}
        />
      )}
      {!hideDraft && notice && <p role="status">{notice}</p>}
      {!hideDraft && copySource && <p role="status">已从台账 {copySource.definition.name}（{copySource.key}）的版本 {copySource.version} 复制配置，请用新标识保存草稿。</p>}
      {!hideDraft && <div className="config-layout">
        <nav aria-label="已配置台账" className="config-list">
          {items.length === 0 && !loading && !directory.isError && (
            <p>暂无台账配置。</p>
          )}
          {items.map((item) => (
            <button
              key={item.key}
              type="button"
              disabled={locked}
              aria-current={snapshot?.key === item.key ? "true" : undefined}
              onClick={() => switchDraft({ kind: "select", item })}
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
          <fieldset disabled={!canManage || locked || loading}>
            <legend>
              {snapshot ? `配置版本 ${snapshot.version}` : "新台账草稿"}
            </legend>
            <label>
              台账标识
              <input
                value={key}
                disabled={snapshot !== null}
                pattern="[a-z][a-z0-9_]*"
                onChange={(event) => setValue("key", event.target.value)}
                placeholder="purchase_request"
              />
            </label>
            <label>
              台账名称
              <input
                value={draft.name}
                onChange={(event) => setValue("name", event.target.value)}
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
                  isDisabled={!canManage || locked}
                  onChange={() =>
                    updateField(index, { required: !field.required })
                  }
                />
                <CheckboxInput
                  label="列表显示"
                  value={draft.listFields.includes(field.key)}
                  isDisabled={!canManage || locked || !field.key}
                  onChange={() => toggleView("listFields", field.key)}
                />
                <CheckboxInput
                  label="允许查询"
                  value={draft.queryFields.includes(field.key)}
                  isDisabled={!canManage || locked || !field.key}
                  onChange={() => toggleView("queryFields", field.key)}
                />
                <Button
                  label={`移除字段 ${index + 1}`}
                  variant="secondary"
                  isDisabled={!canManage || locked}
                  onClick={() =>
                    setStructure((current) => ({
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
                isDisabled={locked || loading}
                onClick={() =>
                  setStructure((current) => ({
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
          <LedgerViewOrder
            definition={draft}
            disabled={!canManage || locked || loading}
            onChange={(property, keys) =>
              setStructure((current) => ({ ...current, [property]: keys }))
            }
          />
          <LedgerViewPreview definition={draft} />
          {recovery && (
            <Button
              label="重试原操作"
              isDisabled={busy || !(recovery.action === "publish" ? canPublish : canManage)}
              onClick={() => void runOperation(recovery)}
            />
          )}
          {(canManage || canPublish) && (
            <div className="config-actions">
              {canManage && (
                <Button
                  label={busy ? "处理中…" : "保存草稿"}
                  isDisabled={locked || loading || !dirty || !key.trim()}
                  onClick={() => void mutate("save")}
                />
              )}
              {canPublish && (
                <Button
                  label="发布当前版本"
                  variant="secondary"
                  isDisabled={
                    locked ||
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
                      isDisabled={locked}
                      onClick={() => setCompared(entry)}
                    />
                    {canManage && <Button label={`复制版本 ${entry.version}为新台账`} variant="secondary" isDisabled={locked} onClick={() => switchDraft({ kind: "create", source: entry })} />}
                    {canManage && (
                      <Button
                        label={`恢复版本 ${entry.version}为新草稿`}
                        variant="secondary"
                        isDisabled={locked}
                        onClick={() => switchDraft({ kind: "restore", version: entry.version })}
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
              {orderedViews.map(({ property, label }) =>
                JSON.stringify(compared.definition[property]) !== JSON.stringify(draft[property])
                  ? <p key={property}>{label}已变化。</p> : null,
              )}
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
      </div>}
    </section>
  );
}

export default function HttpLedgerConfigPage() {
  const scope = useContext(LedgerConfigRouteScope);
  const adapter = useMemo(() => createHttpLedgerConfig(createHttpClient()), []);
  return scope && <LedgerConfigPage {...scope} adapter={adapter} />;
}
