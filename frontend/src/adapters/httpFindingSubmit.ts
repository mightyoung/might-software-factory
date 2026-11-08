import type { components } from "../api/generated/finding-workflow";
import { classifyHttpError } from "../api/classifyError";
import { ApiError, type HttpClient } from "../api/httpClient";
import type { FindingUrgency } from "../domain/finding";
import { normalizeDraft, type FindingDraft, type FindingDraftErrors, type FindingDraftField } from "../domain/findingDraft";
import { wallTimeToIso } from "../domain/time";
import { FindingSubmitError, type FindingSubmitAdapter } from "./findingSubmit";
import type { components as SessionComponents } from "../api/generated/session-api";

type CreateRequest = components["schemas"]["CreateFindingRequest"];

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
 * POST /api/v1/findings。organizationId 来自 GET /organizations/reportable 的选择（服务端创建时仍会复核）；
 * 请求体不含租户、上报人、编号或补报标记，这些全由服务端决定。
 */
export function createHttpFindingSubmit(client: HttpClient): FindingSubmitAdapter {
  return {
    // 与 URGENCY_MAP 一致：后端没有对应值的“紧急”档不提供。
    urgencies: (Object.keys(URGENCY_MAP) as FindingUrgency[]).filter((key) => URGENCY_MAP[key] !== null),
    async listOrganizations(signal) {
      try {
        const body = await client.get<SessionComponents["schemas"]["ReportableOrganizationsResponse"]>(
          "/api/v1/organizations/reportable",
          { signal },
        );
        return body.data;
      } catch (error) {
        if (error instanceof ApiError) throw toSubmitError(error);
        throw error;
      }
    },
    async submit(input, { idempotencyKey, organizationId, relatedFindingId, signal }) {
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
        ...(relatedFindingId ? { relatedFindingId } : {}),
      };
      try {
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
