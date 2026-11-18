import { describe, expect, it } from "vitest";
import type { components } from "./generated/asset-foundation";

type Schemas = components["schemas"];
const id = "00112233-4455-6677-8899-aabbccddeeff";

// These are transport inputs before server defaults. Requiring limit in generated types
// would reject valid requests and make this independently compiled fixture fail.
const requestsWithoutLimit: [
  Schemas["ReferenceRequest"],
  Schemas["AnchorReferenceRequest"],
  Schemas["OrganizationPositionReferenceRequest"],
  Schemas["BindingHistoryRequest"],
] = [
  { query: "" },
  { organizationId: id, query: "" },
  { orgId: id, query: "" },
  {},
];

type DefinitionName = "WorkshopDefinition" | "ProductionLineDefinition" | "ProcessStageDefinition"
  | "EquipmentDefinition" | "WorkUnitDefinition" | "HazardSourceDefinition";
type ExactWireUse<T> = [T] extends ["READ" | "CREATE"]
  ? ["READ" | "CREATE"] extends [T] ? true : false : false;
// Compile every generated union independently: component-name tags or widened strings fail tsc.
const definitionUses: { [Name in DefinitionName]: {
  read: Schemas[Name]["use"];
  create: Schemas[Name]["use"];
  exact: ExactWireUse<Schemas[Name]["use"]>;
} } = {
  WorkshopDefinition: { read: "READ", create: "CREATE", exact: true },
  ProductionLineDefinition: { read: "READ", create: "CREATE", exact: true },
  ProcessStageDefinition: { read: "READ", create: "CREATE", exact: true },
  EquipmentDefinition: { read: "READ", create: "CREATE", exact: true },
  WorkUnitDefinition: { read: "READ", create: "CREATE", exact: true },
  HazardSourceDefinition: { read: "READ", create: "CREATE", exact: true },
};

describe("asset foundation generated transport contracts", () => {
  it("uses the actual READ and CREATE discriminator tags for all six definitions", () => {
    expect(Object.values(definitionUses)).toEqual(Array.from({ length: 6 }, () => ({
      read: "READ", create: "CREATE", exact: true,
    })));
  });
  it("allows all four bounded request types to omit the server-defaulted limit", () => {
    expect(requestsWithoutLimit.map((request) => JSON.parse(JSON.stringify(request)))).toEqual([
      { query: "" },
      { organizationId: id, query: "" },
      { orgId: id, query: "" },
      {},
    ]);
  });
});
