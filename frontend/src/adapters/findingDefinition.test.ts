import { describe, expect, it } from "vitest";
import { findingDefinitionFixture } from "./findingDefinition.fixtures";
import { findingSchemaFromDefinition, parseFindingDefinition, queryBindings, registeredFindingSchema, validatePublishedFindingDraft } from "./findingDefinition";
import { mapLedgerQueryToEntityRequest } from "./entityQueryContract";
import type { FindingDraft } from "../domain/findingDraft";

const draft: FindingDraft = { source: "FIELD_OBSERVATION", description: "现场情况", location: "作业点", foundAt: "2026-10-30T10:00", urgency: "NORMAL", interimMeasure: "" };
const now = new Date("2026-10-30T10:00:00+08:00");

describe("published Finding definition consumers", () => {
  it("keeps READ and CREATE projections separate and rejects unsupported layouts", () => {
    const read = findingDefinitionFixture();
    expect(parseFindingDefinition(read, "READ")).toBe(read);
    expect(() => parseFindingDefinition(read, "CREATE")).toThrow();
    expect(() => parseFindingDefinition({ ...read, definitionVersion: "2" }, "READ")).toThrow();
    expect(() => parseFindingDefinition({ ...read, layout: { ...read.layout, list: ["unpublished"] } }, "READ")).toThrow();
    const leaked = structuredClone(read); leaked.fields.description!.presentation.edit = { required: true, maxLength: 1, lengthUnit: "UTF16_CODE_UNITS", allowedValues: [] };
    expect(() => parseFindingDefinition(leaked, "READ")).toThrow();
  });

  it("uses server labels, ordering, enum values and typed DATETIME bounds", () => {
    const definition = findingDefinitionFixture();
    definition.fields.code!.presentation.label = "服务端编号";
    definition.layout.list = ["state", "code"];
    const schema = findingSchemaFromDefinition(parseFindingDefinition(definition, "READ"));
    expect(schema.columns.map((column) => [column.key, column.label])).toEqual([["state", "状态"], ["code", "服务端编号"]]);
    expect(schema.queryFields.find((field) => field.key === "createdAt")?.kind).toBe("dateTimeRange");
    const request = mapLedgerQueryToEntityRequest({ ledgerSchema: schema, registeredSchema: registeredFindingSchema(definition), fieldBindings: queryBindings(definition), query: { createdAt: { from: "2026-10-30T09:30", to: "2026-10-31T10:45" } }, page: { index: 1, size: 20 } });
    expect(request.filterAST?.conditions).toEqual([
      { field: "createdAt", operator: "GTE", value: "2026-10-30T09:30:00+08:00" },
      { field: "createdAt", operator: "LTE", value: "2026-10-31T10:45:00+08:00" },
    ]);
    expect(() => mapLedgerQueryToEntityRequest({ ledgerSchema: schema, registeredSchema: registeredFindingSchema(definition), fieldBindings: queryBindings(definition), query: { createdAt: { from: "2026-02-30T09:30", to: "" } }, page: { index: 1, size: 20 } })).toThrow(/DATETIME/);
    expect(() => mapLedgerQueryToEntityRequest({ ledgerSchema: schema, registeredSchema: registeredFindingSchema(definition), fieldBindings: queryBindings(definition), query: { unregistered: "active" }, page: { index: 1, size: 20 } })).toThrow(/schema/);
  });

  it("does not silently drop a published query kind the client cannot render", () => {
    const definition = findingDefinitionFixture(); definition.layout.query = ["organizationId"];
    expect(() => findingSchemaFromDefinition(definition)).toThrow();
  });

  it("validates Unicode code-point limits and UTF-16 limits in their declared units", () => {
    const definition = parseFindingDefinition(findingDefinitionFixture("CREATE"), "CREATE");
    expect(validatePublishedFindingDraft({ ...draft, description: "😀".repeat(10000), location: "😀".repeat(200), interimMeasure: "😀".repeat(2000) }, definition, now)).toEqual({});
    expect(validatePublishedFindingDraft({ ...draft, description: "😀".repeat(10001), location: "😀".repeat(201), interimMeasure: "😀".repeat(2001) }, definition, now)).toMatchObject({ description: expect.any(String), location: expect.any(String), interimMeasure: expect.any(String) });
    expect(validatePublishedFindingDraft({ ...draft, description: "短", urgency: "IMMEDIATE" }, definition, now)).toEqual({});
    expect(validatePublishedFindingDraft({ ...draft, urgency: "URGENT" }, definition, now)).toHaveProperty("urgency");
  });
});
