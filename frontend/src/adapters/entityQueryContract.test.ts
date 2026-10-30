import { describe, expect, it } from "vitest";
import {
  createEntityQueryFilter,
  EntityQueryMappingError,
  ENTITY_QUERY_CONTRACT,
  mapLedgerQueryToEntityRequest,
  type EntityQueryResponse,
} from "./entityQueryContract";
import {
  contractFixtureFieldBindings,
  contractFixtureLedgerSchema,
  contractFixtureRegisteredSchema,
} from "./entityQueryContract.fixtures";

const base = {
  ledgerSchema: contractFixtureLedgerSchema,
  registeredSchema: contractFixtureRegisteredSchema,
  fieldBindings: contractFixtureFieldBindings,
  query: { keyword: "  guard  ", state: "OPEN", reportedDate: { from: "2026-10-01", to: "2026-10-31" }, score: "2.5" },
  page: { index: 2, size: 20 },
};

describe("Entity Query Contract v1 adapter seam", () => {
  it("identifies the exact shared component artifact without claiming an HTTP implementation", () => {
    expect(ENTITY_QUERY_CONTRACT).toEqual({
      version: "1.0.0",
      sourcePath: "contracts/openapi/entity-query.yaml",
      sourceCommit: "3d08108831e4283a38f618944bae30662d13063b",
      status: "contract-only",
    });
  });

  it("maps typed UI filters, page number, size and explicit sort into the v1 request shape", () => {
    const request = mapLedgerQueryToEntityRequest({
      ...base,
      sort: [{ field: "reportedDate", direction: "DESC" }],
      fields: ["id", "description", "state"],
    });

    expect(request).toEqual({
      filterAST: {
        operator: "AND",
        conditions: [
          { field: "description", operator: "CONTAINS", value: "guard" },
          { field: "state", operator: "EQ", value: "OPEN" },
          { field: "reportedDate", operator: "GTE", value: "2026-10-01" },
          { field: "reportedDate", operator: "LTE", value: "2026-10-31" },
          { field: "score", operator: "GTE", value: 2.5 },
        ],
      },
      sort: [{ field: "reportedDate", direction: "DESC" }],
      page: { number: 2 },
      size: 20,
      fields: ["id", "description", "state"],
    });
    expect("view" in request).toBe(false);
  });

  it("omits empty filters, default sort and projection instead of inventing values", () => {
    const request = mapLedgerQueryToEntityRequest({
      ...base,
      query: { keyword: "  ", state: "", reportedDate: { from: "", to: "" }, score: "" },
      page: { index: 1, size: 20 },
    });

    expect(request).toEqual({ page: { number: 1 }, size: 20 });
    expect(request).not.toHaveProperty("filterAST");
    expect(request).not.toHaveProperty("sort");
    expect(request).not.toHaveProperty("fields");
  });

  it("keeps explicit empty text distinct from a null check through the internal shape helper", () => {
    const emptyText = createEntityQueryFilter("description", "EQ", "");
    const nullCheck = createEntityQueryFilter("description", "IS_NULL");

    expect(emptyText).toEqual({ field: "description", operator: "EQ", value: "" });
    expect(nullCheck).toEqual({ field: "description", operator: "IS_NULL" });
    expect(nullCheck).not.toHaveProperty("value");
    expect(() => createEntityQueryFilter("description", "EQ", null)).toThrow(EntityQueryMappingError);
    expect(() => createEntityQueryFilter("description", "IS_NULL", "")).toThrow(EntityQueryMappingError);
  });

  it("validates page sizes and the contract offset ceiling", () => {
    expect(() => mapLedgerQueryToEntityRequest({ ...base, page: { index: 1, size: 5 } })).toThrow(/20, 50, or 100/);
    expect(() => mapLedgerQueryToEntityRequest({ ...base, page: { index: 502, size: 20 } })).toThrow(/maximum offset/);
    expect(mapLedgerQueryToEntityRequest({ ...base, page: { index: 501, size: 20 } }).page).toEqual({ number: 501 });
  });

  it("rejects unregistered fields, unsupported operators, unknown enum values and unrepresentable number input", () => {
    expect(() => mapLedgerQueryToEntityRequest({
      ...base,
      fieldBindings: { ...contractFixtureFieldBindings, keyword: "unknownField" },
    })).toThrow(/not present in the registered schema/);
    expect(() => mapLedgerQueryToEntityRequest({
      ...base,
      fieldBindings: { ...contractFixtureFieldBindings, keyword: "internalNote" },
    })).toThrow(/type TEXT|Operator CONTAINS/);
    expect(() => mapLedgerQueryToEntityRequest({ ...base, query: { ...base.query, state: "REJECTED" } })).toThrow(/not registered/);
    expect(() => mapLedgerQueryToEntityRequest({ ...base, query: { ...base.query, score: "NaN" } })).toThrow(/finite/);
  });

  it("rejects integer or decimal query values that JavaScript would silently round", () => {
    expect(() => mapLedgerQueryToEntityRequest({
      ...base,
      query: { ...base.query, score: "9007199254740993" },
    })).toThrow(/safe integer range/);
    expect(() => mapLedgerQueryToEntityRequest({
      ...base,
      fieldBindings: { ...contractFixtureFieldBindings, score: "price" },
      query: { ...base.query, score: "90071992547409.93" },
    })).toThrow(/decimal precision loss/);

    const exactMoney = mapLedgerQueryToEntityRequest({
      ...base,
      fieldBindings: { ...contractFixtureFieldBindings, score: "price" },
      query: { ...base.query, score: "12.50" },
    });
    const lastCondition = exactMoney.filterAST?.conditions[exactMoney.filterAST.conditions.length - 1];
    expect(lastCondition).toEqual({ field: "price", operator: "GTE", value: 12.5 });
  });

  it("rejects invalid, reversed, or DATETIME-backed date-only ranges", () => {
    expect(() => mapLedgerQueryToEntityRequest({
      ...base,
      query: { ...base.query, reportedDate: { from: "2026-02-30", to: "" } },
    })).toThrow(/Invalid DATE/);
    expect(() => mapLedgerQueryToEntityRequest({
      ...base,
      query: { ...base.query, reportedDate: { from: "2026-10-31", to: "2026-10-01" } },
    })).toThrow(/lower bound exceeds upper bound/);
    expect(() => mapLedgerQueryToEntityRequest({
      ...base,
      fieldBindings: { ...contractFixtureFieldBindings, reportedDate: "createdAt" },
    })).toThrow(/cannot represent this UI query/);
  });

  it("checks explicit sort and projection against registered schema limits", () => {
    expect(() => mapLedgerQueryToEntityRequest({ ...base, sort: [
      { field: "score", direction: "ASC" }, { field: "state", direction: "ASC" },
      { field: "reportedDate", direction: "ASC" }, { field: "id", direction: "ASC" },
    ] })).toThrow(/at most 3/);
    expect(() => mapLedgerQueryToEntityRequest({ ...base, sort: [
      { field: "internalNote", direction: "ASC" },
    ] })).toThrow(/not sortable/);
    expect(() => mapLedgerQueryToEntityRequest({ ...base, fields: ["internalNote"] })).toThrow(/not selectable/);
    expect(() => mapLedgerQueryToEntityRequest({ ...base, sort: [
      { field: "state", direction: "ASC" }, { field: "state", direction: "DESC" },
    ] })).toThrow(/Duplicate sort field/);
  });

  it("preserves an unavailable response total as absent instead of fabricating zero", () => {
    const response: EntityQueryResponse<unknown> = {
      data: { items: [], page: { size: 20 as const, number: 1 } },
      meta: { requestId: "fixture-request", traceId: "fixture-trace", schemaVersion: "fixture-1" },
    };
    expect(response.data.page).not.toHaveProperty("total");
  });
});
