import { describe, expect, it } from "vitest";
import { parseDefinition } from "./definition";
import { formValues } from "./formValues";
import { makeQuery } from "./EntityLedgerPage";
import { LedgerProtocolError, validTimestamp } from "./model";
import { data, DEF, ID } from "./testFixture";

describe("published ledger definitions", () => {
  it("accepts all seven field types without a special application or entity list", () => {
    expect(parseDefinition(data(), "inventory", "widget", "READ").definition.fields).toEqual(DEF.fields);
  });
  it("supports the reserved technical id in query layout without adding it to business fields", () => {
    const input = data(); input.definition.layout.query.push("id");
    const parsed = parseDefinition(input, "inventory", "widget", "READ");
    expect(parsed.definition.fields.id).toBeUndefined();
    expect(makeQuery(parsed.definition, { id: ID }, 1, 20).filterAST?.conditions).toEqual([{ field: "id", operator: "EQ", value: ID }]);
  });
  it.each(["app", "use", "type", "id", "layout", "action", "query", "reference", "protected", "unknown"])("rejects invalid %s declarations", mutation => {
    const input = data();
    if (mutation === "app") input.definition.appKey = "another";
    if (mutation === "use") input.use = "EDIT";
    if (mutation === "type") (input.definition.fields.code!.type as string) = "DECIMAL";
    if (mutation === "id") delete input.definition.querySchema.fields.id;
    if (mutation === "layout") input.definition.layout.edit.push("scope");
    if (mutation === "action") input.effectiveActions.push("all.admin");
    if (mutation === "query") input.definition.querySchema.fields.count!.type = "TEXT";
    if (mutation === "reference") input.definition.inputFields.scope!.referenceKey = "missing";
    if (mutation === "protected") input.definition.inputFields.id = input.definition.inputFields.code!;
    if (mutation === "unknown") Object.assign(input.definition, { table: "arbitrary" });
    expect(() => parseDefinition(input, "inventory", "widget", "READ")).toThrow(LedgerProtocolError);
  });
});
describe("complete standard form values", () => {
  it("keeps nullable fields, converts integers, and omits unregistered/protected input", () => {
    const result = formValues(DEF, "EDIT", { code: "W", count: "12", note: "", when: "2026-11-15T01:02:03.123456+08:00", external: ID, state: "NORMAL", id: ID, scope: ID, tenantId: ID });
    expect(result.errors).toEqual({});
    expect(result.values).toEqual({ code: "W", count: 12, note: null, when: "2026-11-15T01:02:03.123456+08:00", external: ID, state: "NORMAL" });
  });
  it("rejects unsafe numbers, malformed dates, unknown enum values and Unicode", () => {
    const result = formValues(DEF, "CREATE", { code: "\ud800", count: "9007199254740992", when: "2026-02-30T12:00:00Z", external: "free text", state: "OTHER", scope: "bad" });
    expect(Object.keys(result.errors).sort()).toEqual(["code", "count", "external", "scope", "state", "when"]);
  });
  it("counts code points and accepts microseconds but rejects excess timestamp precision", () => {
    expect(formValues(DEF, "EDIT", { code: "😀".repeat(20) }).errors).toEqual({});
    expect(formValues(DEF, "EDIT", { code: "😀".repeat(21) }).errors.code).toBeTruthy();
    expect(validTimestamp("2026-01-01T12:00:00.123456Z")).toBe(true);
    expect(validTimestamp("2026-01-01T12:00:00.1234567Z")).toBe(false);
    expect(validTimestamp("2026-01-01T12:00:00")).toBe(false);
  });
  it("builds bounded declared queries with one-based page and Shanghai time offsets", () => {
    expect(makeQuery(DEF, { code: "box", count: "3", when: { from: "2026-11-15T10:00", to: "" } }, 1, 20)).toEqual({ filterAST: { operator: "AND", conditions: [{ field: "code", operator: "CONTAINS", value: "box" }, { field: "count", operator: "EQ", value: 3 }, { field: "when", operator: "GTE", value: "2026-11-15T10:00:00+08:00" }] }, page: { number: 1 }, size: 20 });
  });
});
