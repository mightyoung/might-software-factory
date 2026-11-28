import { expect, it } from "vitest";
import { createLedgerRegistration } from "../../../tools/templates/new-ledger-module/registered-ledger";
import { visibleMenuNodes } from "../../domain/session";
import { parseEntityRoute } from "../../routing/entityPaths";

const input = {
  appKey: "inventory", appName: "库存", entityKey: "widget", label: "物品", readPermission: "widget.read",
};

it("routes one sample through the generic ledger and the established tenant encoding", () => {
  const registration = createLedgerRegistration(input);
  expect(registration.href("tenant/a")).toBe("/t/tenant%2Fa/apps/inventory/entities/widget");
  expect(parseEntityRoute(registration.entry.entryPath)).toMatchObject({ appKey: "inventory", entityKey: "widget", page: "entry" });
  expect(visibleMenuNodes([registration.menu], { permissions: [] })).toEqual([]);
  expect(visibleMenuNodes([registration.menu], { permissions: [], canonicalPermissions: ["widget.read"] })).toEqual([registration.menu]);
});

it("refuses arbitrary path segments and malformed permission codes rather than emitting navigation", () => {
  expect(() => createLedgerRegistration({ ...input, entityKey: "../other" })).toThrow(/标识/);
  expect(() => createLedgerRegistration({ ...input, appKey: "not valid" })).toThrow(/标识/);
  expect(() => createLedgerRegistration({ ...input, readPermission: "*" })).toThrow(/权限/);
});
