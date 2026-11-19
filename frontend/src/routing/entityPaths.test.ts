import { describe, expect, it } from "vitest";
import { applicationPath, parseEntityRoute } from "./entityPaths";
const ID = "00000000-0000-4000-8000-000000000001";
describe("registered entity routes", () => {
  it("handles arbitrary app/entity keys and detail/edit without another route registration", () => {
    expect(parseEntityRoute("apps/new-app/entities/custom_entity")).toMatchObject({ appKey: "new-app", entityKey: "custom_entity", page: "entry" });
    expect(parseEntityRoute("apps/new-app/entities/custom_entity/create")?.page).toBe("create");
    expect(parseEntityRoute(`apps/new-app/entities/custom_entity/records/${ID}`)?.page).toBe("detail");
    expect(parseEntityRoute(`apps/new-app/entities/custom_entity/records/${ID}/edit`)).toMatchObject({ page: "edit", id: ID });
  });
  it.each(["apps/a/entities/../create", "apps/a/entities/x?other=1", "apps/a/entities/%2f", "apps/a/entities/x/records/free-text", "apps/a/entities/x/extra", "apps/a/entities/x/records/00000000-0000-4000-8000-000000000001/edit/more"])("rejects malformed route %s", path => expect(parseEntityRoute(path)).toBeNull());
  it("menu path must be a base entity route of the same app", () => { expect(applicationPath("a", "apps/a/entities/x")).toBe(true);expect(applicationPath("a", "apps/b/entities/x")).toBe(false);expect(applicationPath("a", "apps/a/entities/x/create")).toBe(false); });
});
