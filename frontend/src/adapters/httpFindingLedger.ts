import type { components } from "../api/generated/finding-ledger";
import { classifyHttpError } from "../api/classifyError";
import { ApiError, type HttpClient } from "../api/httpClient";
import type { FindingRecord, FindingState } from "../domain/finding";
import { findingLedgerSchema } from "../features/findings/schema";
import {
  mapLedgerQueryToEntityRequest,
  type EntityQueryRequest,
  type RegisteredEntityV1,
} from "./entityQueryContract";
import {
  FindingLedgerAccessError,
  type FindingLedgerAdapter,
  type FindingListRequest,
  type FindingView,
} from "./findingLedger";

type Item = components["schemas"]["FindingListItem"];
type Detail = components["schemas"]["FindingDetail"];

/**
 * 界面查询项 → 后端字段。reportedAt 没有绑定：契约里 createdAt 是 DATETIME，而映射器只把日期输入映射到 DATE 字段，
 * 不把无时区日期擅自转成时间点，所以按上报日期筛选会明确失败，而不是被悄悄忽略。
 */
const FIELD_BINDINGS = { keyword: "code", state: "state" } as const;

const VIEW_STATES: Record<Exclude<FindingView, "all">, readonly FindingState[]> = {
  confirmation: ["PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING"],
  acceptance: ["ACCEPTANCE_PENDING"],
};

/** 契约列表/详情没有上报人、责任人、历史（frontend-contract-gap R4），不臆造。 */
const NOT_PROVIDED = "—";

const GRADE_LABELS: Record<components["schemas"]["FindingDetail"]["grade"], string> = {
  UNKNOWN: "待专业评估",
  GENERAL: "一般",
  MAJOR: "重大",
};

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

export function toFindingRecord(item: Item | Detail): FindingRecord {
  return {
    id: item.id,
    code: required(item.code, "code"),
    description: required(item.description, "description"),
    // location 在契约里可为 null（旧数据无法推断），这是合法的“未提供”。
    location: item.location ?? NOT_PROVIDED,
    state: required(item.state, "state"),
    reportedAt: required(item.createdAt, "createdAt"),
    reporter: NOT_PROVIDED,
    responsible: NOT_PROVIDED,
    riskLabel: GRADE_LABELS[required(item.grade, "grade")],
    round: required(item.roundNo, "roundNo"),
    // version 不在列表的默认投影里，只有详情有；缺失时流程命令保持禁用。
    ...(item.version ? { version: item.version } : {}),
    history: [],
    ...(item.discoveredAt ? { foundAt: item.discoveredAt } : {}),
    ...(item.sourceCategory ? { source: item.sourceCategory } : {}),
    ...(item.urgency ? { urgency: item.urgency } : {}),
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

/**
 * 隐患台账读取的 HTTP 实现。字段注册表取自 GET /entities/finding/schema 并按 schemaVersion 之外的
 * 生命周期缓存在本适配器实例内——登录、切租户或换账号时必须新建实例，不得跨授权范围复用。
 */
export function createHttpFindingLedger(client: HttpClient): FindingLedgerAdapter {
  let schemaPromise: Promise<RegisteredEntityV1> | null = null;

  /**
   * 共享请求不绑定任何一个调用方的取消信号：React StrictMode 会先挂载、卸载再挂载，
   * 第一次调用的取消不能让第二次调用拿到一个已被取消的缓存请求。调用方各自在等待结束后检查自己的信号。
   */
  async function registeredSchema(signal?: AbortSignal): Promise<RegisteredEntityV1> {
    if (!schemaPromise) {
      const pending = client.get<{ data: RegisteredEntityV1 }>("/api/v1/entities/finding/schema").then((body) => body.data);
      // 失败不缓存，下次重试。
      pending.catch(() => {
        if (schemaPromise === pending) schemaPromise = null;
      });
      schemaPromise = pending;
    }
    const schema = await schemaPromise;
    signal?.throwIfAborted();
    return schema;
  }

  return {
    async list({ query, page, view }: FindingListRequest, options) {
      try {
        const schema = await registeredSchema(options?.signal);
        const request = withViewFilter(
          mapLedgerQueryToEntityRequest({
            ledgerSchema: findingLedgerSchema,
            registeredSchema: schema,
            fieldBindings: FIELD_BINDINGS,
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
          items: body.data.items.map(toFindingRecord),
          page: { index: body.data.page.number, size: body.data.page.size },
          total: body.data.page.total,
          pageCount: body.data.page.pageCount,
        };
      } catch (error) {
        throw toAccessError(error);
      }
    },
    async get(id, options) {
      try {
        const body = await client.get<components["schemas"]["FindingDetailResponse"]>(
          `/api/v1/entities/finding/${encodeURIComponent(id)}`,
          { signal: options?.signal },
        );
        return toFindingRecord(body.data);
      } catch (error) {
        // 不存在与无权限同为 404，按契约呈现为“找不到”，不区分原因。
        if (error instanceof ApiError && classifyHttpError(error) === "notVisible") return null;
        throw toAccessError(error);
      }
    },
  };
}
