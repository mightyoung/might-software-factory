import type { components } from "../api/generated/finding-workflow";
import { classifyHttpError } from "../api/classifyError";
import { ApiError, type HttpClient } from "../api/httpClient";
import type { FindingUrgency } from "../domain/finding";
import { normalizeDraft, type FindingDraft, type FindingDraftErrors, type FindingDraftField } from "../domain/findingDraft";
import { wallTimeToIso } from "../domain/time";
import { FindingSubmitError, type FindingSubmitAdapter } from "./findingSubmit";
import type { components as DefinitionComponents } from "../api/generated/entity-definition";
import { loadFindingDefinition, REPORT_ORGANIZATION_QUERY } from "./findingDefinition";

type CreateRequest = components["schemas"]["CreateFindingRequest"];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 后端字段路径 → 表单字段。 */
const FIELD_MAP: Readonly<Record<string, FindingDraftField>> = {
  description: "description",
  location: "location",
  interimMeasure: "interimMeasure",
  urgency: "urgency",
  sourceCategory: "source",
  discoveredAt: "foundAt",
};

/**
 * 后端只有 NORMAL / URGENT，且 URGENT 的含义是“需立即处置”；前端的“紧急”档在后端没有对应值，
 * 不擅自并入其他档，返回 null 由调用方作为校验错误拒绝（待产品与后端对齐）。
 */
const URGENCY_MAP: Readonly<Record<FindingUrgency, CreateRequest["urgency"] | null>> = {
  NORMAL: "NORMAL",
  URGENT: null,
  IMMEDIATE: "URGENT",
};

function fieldErrorsFrom(error: ApiError): FindingDraftErrors {
  const result: FindingDraftErrors = {};
  for (const { field } of error.fieldErrors) {
    const key = FIELD_MAP[field.split(/[.[]/)[0] ?? ""];
    if (key) result[key] = "此项未通过服务端校验，请修改后重试";
  }
  return result;
}

function toSubmitError(error: ApiError): FindingSubmitError {
  switch (classifyHttpError(error)) {
    case "invalid":
      return new FindingSubmitError("validation", fieldErrorsFrom(error));
    case "unauthenticated":
      return new FindingSubmitError("unauthenticated");
    case "forbidden":
    case "notVisible":
      return new FindingSubmitError("forbidden");
    case "conflict":
      return new FindingSubmitError("conflict");
    case "workflow":
      return new FindingSubmitError("workflow");
    default:
      return new FindingSubmitError("unavailable");
  }
}

/**
 * POST /api/v1/findings。organizationId 来自有界、当前授权的组织引用查询（服务端创建时仍会复核）；
 * 请求体不含租户、上报人、编号或补报标记，这些全由服务端决定。
 */
export function createHttpFindingSubmit(client: HttpClient): FindingSubmitAdapter {
  return {
    // 与 URGENCY_MAP 一致：后端没有对应值的“紧急”档不提供。
    urgencies: (Object.keys(URGENCY_MAP) as FindingUrgency[]).filter((key) => URGENCY_MAP[key] !== null),
    async loadDefinition(signal) {
      try { return await loadFindingDefinition(client, "CREATE", signal); }
      catch (error) { if (error instanceof ApiError) throw toSubmitError(error); throw error; }
    },
    async queryOrganizations({ query, afterId, limit = 20 }, signal) {
      try {
        if ([...query].length > 100 || query.includes("\0") || !Number.isInteger(limit) || limit < 1 || limit > 50
          || (afterId !== undefined && !UUID.test(afterId))) throw new FindingSubmitError("validation");
        const body = await client.post<DefinitionComponents["schemas"]["ReferenceResponse"]>(
          REPORT_ORGANIZATION_QUERY,
          { body: { query, ...(afterId ? { afterId } : {}), limit }, signal },
        );
        signal?.throwIfAborted();
        const page = body?.data;
        if (body?.meta?.schemaVersion !== "1" || !page || !Array.isArray(page.items) || page.items.length > limit
          || page.items.some((item) => !item || !UUID.test(item.id) || typeof item.name !== "string" || typeof item.code !== "string")
          || new Set(page.items.map((item) => item.id)).size !== page.items.length
          || (page.nextAfterId !== undefined && (!UUID.test(page.nextAfterId) || page.nextAfterId === afterId
            || page.nextAfterId !== page.items.at(-1)?.id))) throw new FindingSubmitError("unavailable");
        return body.data;
      } catch (error) {
        if (error instanceof ApiError) throw toSubmitError(error);
        throw error;
      }
    },
    async submit(input, { idempotencyKey, organizationId, signal }) {
      if (!organizationId) throw new FindingSubmitError("validation");
      const draft: FindingDraft = normalizeDraft(input);
      const urgency = URGENCY_MAP[draft.urgency];
      const discoveredAt = wallTimeToIso(draft.foundAt);
      if (!urgency) throw new FindingSubmitError("validation", { urgency: "后端暂不支持“紧急”档，请选择“一般”或“需立即处置”" });
      if (!discoveredAt) throw new FindingSubmitError("validation", { foundAt: "发现时间格式不正确" });

      const body: CreateRequest = {
        organizationId,
        description: draft.description,
        discoveredAt,
        grade: "UNKNOWN",
        sourceCategory: draft.source,
        urgency,
        location: draft.location,
        ...(draft.interimMeasure ? { interimMeasure: draft.interimMeasure } : {}),
      };
      try {
        await loadFindingDefinition(client, "CREATE", signal);
        const response = await client.post<components["schemas"]["CommandResponse"]>("/api/v1/findings", {
          body,
          headers: { "Idempotency-Key": idempotencyKey },
          signal,
        });
        const { findingId, code, state } = response.data;
        return { id: findingId, code, state };
      } catch (error) {
        if (error instanceof ApiError) throw toSubmitError(error);
        throw error;
      }
    },
  };
}
