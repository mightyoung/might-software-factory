import assert from "node:assert/strict";
import { test } from "node:test";
import { checkTarget } from "./withdraw-after-issue.mjs";

const fixture = {
  D2_ISSUE_ORIGIN: "https://127.0.0.1:18443",
  D2_ISSUE_DATABASE: "msf_d2_issue_test",
  D2_ISSUE_ACK: "isolated-issuance-acceptance:msf_d2_issue_test",
  D2_ISSUE_SCOPE_ID: "11111111-1111-4111-8111-111111111111",
  D2_WITHDRAW_TOOL_ID: "22222222-2222-4222-8222-222222222222",
  D2_PASSWORD_READER: "fixture only",
  D2_PASSWORD_KEEPER: "fixture only",
};
test("accepts only the exact dedicated fixture without returning credentials", () => {
  assert.deepEqual(checkTarget(fixture), {
    origin: fixture.D2_ISSUE_ORIGIN, scope: fixture.D2_ISSUE_SCOPE_ID, tool: fixture.D2_WITHDRAW_TOOL_ID,
  });
});
test("rejects non-loopback, arbitrary DBs and malformed targets before browsing", () => {
  for (const override of [
    { D2_ISSUE_ORIGIN: "https://127.0.0.1:18443/foo" },
    { D2_ISSUE_ORIGIN: "https://127.0.0.1:99999" },
    { D2_ISSUE_ORIGIN: "https://localhost:18443" },
    { D2_ISSUE_ORIGIN: "http://127.0.0.1:18443" },
    { D2_ISSUE_DATABASE: "postgres" }, { D2_ISSUE_ACK: "" },
    { D2_WITHDRAW_TOOL_ID: fixture.D2_ISSUE_SCOPE_ID }, { D2_WITHDRAW_TOOL_ID: "bad" },
    { D2_PASSWORD_KEEPER: "" },
  ]) assert.throws(() => checkTarget({ ...fixture, ...override }));
});
