// Explicit MOCK-only V3 fixture. This models wire contracts, not Java, Flowable,
// real role assignment, tenant isolation, durable storage or production acceptance.
import { randomUUID } from "node:crypto";

const tenantId = "44444444-4444-4444-8444-444444444444";
const members = {
  PERSON: "55555555-5555-4555-8555-555555555555",
  SAFETY: "66666666-6666-4666-8666-666666666666",
  REPORTER: "77777777-7777-4777-8777-777777777777",
};
const blank = /^[\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]*$/;
const edges = /^[\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+|[\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+$/g;
const validText = text => typeof text === "string" && text.length <= 4000 && !blank.test(text);
const meta = () => ({ requestId: randomUUID(), traceId: randomUUID(), schemaVersion: "1" });
const failure = (status, code) => ({ status, body: { code, message: "MOCK V3 fixture refusal", traceId: randomUUID(), retryable: status === 503, fieldErrors: [] } });

export function createReviewCycleFixture() {
  const now = new Date().toISOString();
  const finding = {
    id: "33333333-3333-4333-8333-333333333333", code: "YH-20261103-0999",
    organizationId: "11111111-1111-4111-8111-111111111111", organizationName: "一车间",
    description: "MOCK V3：循环补充与两级复核", location: "MOCK 工位", discoveredAt: now,
    sourceCategory: "INSPECTION", urgency: "NORMAL", interimMeasure: null, backfill: false,
    sourceEventId: null, riskId: null, relatedFindingId: null,
    grade: "UNKNOWN", state: "PERSON_CONFIRM_PENDING", roundNo: 1, version: 1,
    reviewCycle: 1, reviewProtocol: "CYCLE_V1", createdAt: now, updatedAt: now,
  };
  const entries = [];
  const receipts = new Map();
  const stage = () => finding.state === "PERSON_CONFIRM_PENDING" ? "PERSON" : finding.state === "SAFETY_CONFIRM_PENDING" ? "SAFETY" : null;
  function history(session) {
    const currentStage = stage();
    const supplement = finding.state === "REPORTER_SUPPLEMENT_PENDING";
    const expected = supplement ? "REPORTER" : currentStage;
    const allowed = expected !== null && session.mockReviewRole === expected;
    return { data: {
      entries: [...entries], currentVersion: finding.version, currentState: finding.state,
      reviewCycle: finding.reviewCycle, reviewProtocol: "CYCLE_V1", processDefinitionKey: "findingRemediationV3",
      reviewStage: currentStage,
      availableReviewActions: allowed ? (supplement ? ["SUPPLEMENT"] : ["APPROVE", "RETURN_FOR_INFORMATION", "NOT_ESTABLISHED"]) : [],
      blockedReason: allowed ? null : expected ? "NOT_CURRENT_REVIEW_PARTICIPANT" : "NO_REVIEW_ACTION_AT_CURRENT_STATE",
      hasMore: false, nextCursor: null,
    }, meta: meta() };
  }
  function command(session, path, body, key) {
    if (typeof key !== "string" || !/^[0-9a-f-]{36}$/i.test(key)) return failure(400, "INVALID_REQUEST");
    if (!body || typeof body !== "object" || Array.isArray(body)) return failure(400, "INVALID_REQUEST");
    const supplement = path.endsWith("/supplements");
    const safetyApproval = !supplement && body.stage === "SAFETY" && body.decision === "APPROVE";
    const fields = supplement ? ["expectedVersion", "reviewCycle", "statement"] : ["expectedVersion", "reviewCycle", "stage", "decision", "reason", ...(safetyApproval ? ["grade"] : [])];
    if (Object.keys(body).some(field => !fields.includes(field)) || fields.some(field => !Object.hasOwn(body, field))) return failure(400, "INVALID_REQUEST");
    if (!Number.isSafeInteger(body.expectedVersion) || body.expectedVersion < 1 || !Number.isInteger(body.reviewCycle) || body.reviewCycle < 1 || !validText(supplement ? body.statement : body.reason)) return failure(400, "INVALID_REQUEST");
    if (!supplement && (!["PERSON", "SAFETY"].includes(body.stage) || !["APPROVE", "RETURN_FOR_INFORMATION", "NOT_ESTABLISHED"].includes(body.decision) || (safetyApproval && !["GENERAL", "MAJOR"].includes(body.grade)))) return failure(400, "INVALID_REQUEST");
    if (session.mockReviewRole !== (supplement ? "REPORTER" : body.stage)) return failure(403, "ACTION_DENIED");
    const canonical = JSON.stringify([session.memberId, path, body]);
    const previous = receipts.get(key);
    if (previous) return previous.canonical === canonical ? { status: 200, body: previous.response } : failure(409, "IDEMPOTENCY_CONFLICT");
    if (body.expectedVersion !== finding.version) return failure(409, "VERSION_CONFLICT");
    if (body.reviewCycle !== finding.reviewCycle || (supplement ? finding.state !== "REPORTER_SUPPLEMENT_PENDING" : body.stage !== stage())) return failure(409, "COMMAND_CONFLICT");
    if (!supplement && body.stage === "SAFETY" && !entries.some(entry => entry.reviewCycle === finding.reviewCycle && entry.stage === "PERSON" && entry.decision === "APPROVE")) return failure(422, "WORKFLOW_RULE");
    const cycle = finding.reviewCycle;
    entries.push({
      id: supplement ? `reporter-supplement:${randomUUID()}` : `review-decision:${finding.id}:${cycle}:${body.stage}`,
      origin: supplement ? "REPORTER_SUPPLEMENT" : "REVIEW_DECISION", reviewCycle: cycle,
      stage: supplement ? null : body.stage, decision: supplement ? null : body.decision,
      actorMemberId: session.memberId, actorDisplay: { label: null, provenance: "UNAVAILABLE" },
      occurredAt: new Date().toISOString(), text: (supplement ? body.statement : body.reason).replace(edges, ""),
      recordedGrade: safetyApproval ? body.grade : null, nextReviewCycle: supplement ? cycle + 1 : null,
    });
    if (supplement) { finding.reviewCycle += 1; finding.state = "PERSON_CONFIRM_PENDING"; }
    else if (body.decision === "RETURN_FOR_INFORMATION") finding.state = "REPORTER_SUPPLEMENT_PENDING";
    else if (body.decision === "NOT_ESTABLISHED") finding.state = "NOT_ESTABLISHED";
    else if (body.stage === "PERSON") finding.state = "SAFETY_CONFIRM_PENDING";
    else { finding.state = "SAFETY_DISPATCH_PENDING"; finding.grade = body.grade; }
    finding.version += 1;
    finding.updatedAt = new Date().toISOString();
    const response = { data: { findingId: finding.id, code: finding.code, state: finding.state, roundNo: finding.roundNo, version: finding.version, reviewCycle: finding.reviewCycle }, meta: meta() };
    receipts.set(key, { canonical, response });
    return { status: 200, body: response };
  }
  return {
    finding, history, command,
    login(loginKey) {
      const role = /^mock-(person|safety|reporter)$/.exec(loginKey)?.[1].toUpperCase();
      if (!role) return null;
      return { actorId: members[role], accountId: members[role], memberId: members[role], tenantId, mockReviewRole: role };
    },
  };
}
