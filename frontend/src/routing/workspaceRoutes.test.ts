import { expect, it } from "vitest";
import { createWorkspaceRouteMatcher } from "./workspaceRoutes";

const finding = { rest: "safety/findings", permission: "safety.finding.read" };
const match = () => createWorkspaceRouteMatcher([finding]);
const recordId = "00000000-0000-4000-8000-000000000001";

it("keeps a static definition exact and does not admit fuzzy or case-changed paths", () => {
  const routes = match();
  expect(routes.match("东区", "safety/findings")).toEqual({ route: finding, entityRoute: null });
  for (const path of ["safety/findings/extra", "safety/Findings", "safety/%66indings"]) {
    expect(routes.match("东区", path)).toEqual({ route: undefined, entityRoute: null });
  }
});

it("matches all four generic ledger routes but preserves validation of business identifiers", () => {
  const routes = match();
  const base = "apps/tooling/entities/tool_loan";
  for (const [tail, page] of [["", "entry"], ["/create", "create"], [`/records/${recordId}`, "detail"], [`/records/${recordId}/edit`, "edit"]]) {
    expect(routes.match("tenant", base + tail).entityRoute).toMatchObject({ appKey: "tooling", entityKey: "tool_loan", page });
  }
  for (const tail of ["/records/short", "/create/edit", "/records/%ZZ", "/other"]) {
    expect(routes.match("tenant", base + tail).entityRoute).toBeNull();
  }
});
