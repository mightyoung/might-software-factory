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

describe("asset foundation generated transport contracts", () => {
  it("allows all four bounded request types to omit the server-defaulted limit", () => {
    expect(requestsWithoutLimit.map((request) => JSON.parse(JSON.stringify(request)))).toEqual([
      { query: "" },
      { organizationId: id, query: "" },
      { orgId: id, query: "" },
      {},
    ]);
  });
});
