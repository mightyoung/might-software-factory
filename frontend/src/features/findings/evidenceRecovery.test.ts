import { afterEach, describe, expect, it } from "vitest";
import { readEvidenceRecovery } from "./evidenceRecovery";

const key = "recovery-test";
const id = "11111111-1111-4111-8111-111111111111";
const attempt = { initializeKey: id, expectedFindingVersion: 4, evidenceSetId: id, reserveKey: id,
  declaration: { findingId: id, roundNo: 1, evidenceSetId: id, expectedFindingVersion: 4,
    expectedEvidenceRevision: 0, filename: "proof.png", declaredMediaType: "image/png", declaredSizeBytes: 3 } };
afterEach(() => sessionStorage.clear());
describe("evidence recovery validation", () => {
  it("restores a complete bounded declaration", () => {
    sessionStorage.setItem(key, JSON.stringify(attempt));
    expect(readEvidenceRecovery(key)).toEqual(attempt);
  });
  it.each([null, [], "invalid", { ...attempt.declaration, declaredSizeBytes: -1 },
    { ...attempt.declaration, declaredMediaType: "text/html" }, { ...attempt.declaration, roundNo: "1" },
    { ...attempt.declaration, evidenceSetId: "invalid" }, { ...attempt.declaration, expectedFindingVersion: 3 }].map(declaration => ({ declaration })))(
    "rejects malformed or mismatched persisted declarations ($declaration)", ({ declaration }) => {
      sessionStorage.setItem(key, JSON.stringify({ ...attempt, declaration }));
      expect(readEvidenceRecovery(key)).toBeNull();
    });
});
