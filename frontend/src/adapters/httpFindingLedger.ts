import type { components } from "../api/generated/finding-ledger";
import { classifyHttpError } from "../api/classifyError";
import { ApiError, type HttpClient } from "../api/httpClient";
import type { FindingRecord, FindingState } from "../domain/finding";
import { displayPublishedValue, findingSchemaFromDefinition, loadFindingDefinition, queryBindings, registeredFindingSchema, type FindingDefinition } from "./findingDefinition";
import {
  mapLedgerQueryToEntityRequest,
  type EntityQueryRequest,
} from "./entityQueryContract";
import {
  FindingLedgerAccessError,
  type FindingLedgerAdapter,
  type FindingDetailSnapshot,
  type FindingReadOptions,
  type FindingListRequest,
  type FindingView,
} from "./findingLedger";

type Item = components["schemas"]["FindingListItem"];
type Detail = components["schemas"]["FindingDetail"];

const VIEW_STATES: Record<Exclude<FindingView, "all">, readonly FindingState[]> = {
  confirmation: ["PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING"],
  acceptance: ["ACCEPTANCE_PENDING"],
};

/** 契约列表/详情没有上报人、责任人、历史（frontend-contract-gap R4），不臆造。 */
const NOT_PROVIDED = "—";

/** 服务端响应缺少默认投影里必有的字段：按“读取失败”处理，不替服务端编造状态或内容。 */
export class MalformedFindingResponseError extends Error {
  constructor(field: string) {
    super(`Finding response is missing required field "${field}"`);
    this.name = "MalformedFindingResponseError";
  }
}

function required<T>(value: T | undefined, field: string): T {
  if (value === undefined) throw new MalformedFindingResponseError(field);
  return value;
}

export function toFindingRecord(item: Item | Detail, definition: FindingDefinition): FindingRecord {
  return {
    registeredValues: Object.fromEntries(Object.entries(item).filter(([, value]) => value === null || ["string", "number", "boolean"].includes(typeof value))) as Readonly<Record<string, string | number | boolean | null>>,
    id: item.id,
    code: required(item.code, "code"),
    description: required(item.description, "description"),
    // location 在契约里可为 null（旧数据无法推断），这是合法的“未提供”。
    location: item.location ?? NOT_PROVIDED,
    state: required(item.state, "state"),
    reportedAt: required(item.createdAt, "createdAt"),
    reporter: NOT_PROVIDED,
    responsible: NOT_PROVIDED,
    riskLabel: definition.fields.grade ? displayPublishedValue(definition.fields.grade, required(item.grade, "grade")) : NOT_PROVIDED,
    round: required(item.roundNo, "roundNo"),
    // version 不在列表的默认投影里，只有详情有；缺失时流程命令保持禁用。
    ...(item.version ? { version: item.version } : {}),
    history: [],
    ...(item.reviewCycle ? { reviewCycle: item.reviewCycle } : {}),
    ...(item.reviewProtocol ? { reviewProtocol: item.reviewProtocol } : {}),
    ...(item.discoveredAt ? { foundAt: item.discoveredAt } : {}),
    ...(item.sourceCategory ? { source: item.sourceCategory } : {}),
    // 后端 URGENT 表示“需立即处置”，与提交适配器的 IMMEDIATE → URGENT 对应。
    ...(item.urgency ? { urgency: item.urgency === "URGENT" ? "IMMEDIATE" : item.urgency } : {}),
    ...(item.interimMeasure ? { interimMeasure: item.interimMeasure } : {}),
    ...(typeof item.backfill === "boolean" ? { backfill: item.backfill } : {}),
  };
}

function toAccessError(error: unknown): unknown {
  if (!(error instanceof ApiError)) return error;
  switch (classifyHttpError(error)) {
    case "unauthenticated":
      return new FindingLedgerAccessError("unauthenticated");
    case "forbidden":
    case "notVisible":
      return new FindingLedgerAccessError("forbidden");
    default:
      return error; // 其余按“未知失败”由界面显示通用提示
  }
}

/** 视图过滤作为额外 AND 条件追加；与用户自选的状态条件求交集。 */
function withViewFilter(request: EntityQueryRequest, view: FindingView): EntityQueryRequest {
  if (view === "all") return request;
  const conditions = [
    ...(request.filterAST?.conditions ?? []),
    { field: "state", operator: "IN" as const, value: VIEW_STATES[view] },
  ];
  return { ...request, filterAST: { operator: "AND", conditions } };
}

/** Every read obtains a current effective definition; no adapter-lifetime cache. */
export function createHttpFindingLedger(client: HttpClient): FindingLedgerAdapter {
  async function getDetailSnapshot(id: string, options?: FindingReadOptions): Promise<FindingDetailSnapshot> {
    try {
      const definition = await loadFindingDefinition(client, "READ", options?.signal);
      const body = await client.get<components["schemas"]["FindingDetailResponse"]>(
        `/api/v1/entities/finding/${encodeURIComponent(id)}`, { signal: options?.signal },
      );
      options?.signal?.throwIfAborted();
      return { record: toFindingRecord(body.data, definition), definition };
    } catch (error) {
      if (error instanceof ApiError && classifyHttpError(error) === "notVisible") return { record: null, definition: null };
      throw toAccessError(error);
    }
  }
  return {
    publishedDefinition: true,
    getDetailSnapshot,
    async list({ query, page, view }: FindingListRequest, options) {
      try {
        const definition = await loadFindingDefinition(client, "READ", options?.signal);
        const schema = registeredFindingSchema(definition);
        const request = withViewFilter(
          mapLedgerQueryToEntityRequest({
            ledgerSchema: findingSchemaFromDefinition(definition),
            registeredSchema: schema,
            fieldBindings: queryBindings(definition),
            fields: schema.defaultFields,
            query,
            page,
          }),
          view,
        );
        const body = await client.post<components["schemas"]["FindingListResponse"]>(
          "/api/v1/entities/finding/query",
          { body: request, signal: options?.signal },
        );
        return {
          definition,
          items: body.data.items.map((item) => toFindingRecord(item, definition)),
          page: { index: body.data.page.number, size: body.data.page.size },
          total: body.data.page.total,
          pageCount: body.data.page.pageCount,
        };
      } catch (error) {
        throw toAccessError(error);
      }
    },
    async get(id, options) {
      return (await getDetailSnapshot(id, options)).record;
    },
  };
}
