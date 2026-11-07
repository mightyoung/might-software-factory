import { describe, expect, it, vi } from "vitest";
import { ApiError, type HttpClient } from "../api/httpClient";
import { createHttpFindingEvidence } from "./httpFindingEvidence";
import { evidenceIsReady } from "./findingEvidence";

const context = { findingId: "finding", roundNo: 2 };
const status = { uploadIntentId: "intent", state: "SCANNING", expiresAt: "2026-11-08T12:00:00Z" };

describe("HTTP finding evidence", () => {
  it("keeps the declaration's six exact fields and sends raw bytes separately", async () => {
    const post = vi.fn(async () => ({ data: status }));
    const putBytes = vi.fn(async () => ({ data: status }));
    const client = { post, putBytes } as unknown as HttpClient;
    const adapter = createHttpFindingEvidence(client);
    const declaration = { ...context, evidenceSetId: "set", expectedFindingVersion: 4, expectedEvidenceRevision: 0, filename: "proof.png", declaredMediaType: "image/png" as const, declaredSizeBytes: 3 };
    await adapter.reserve(declaration, "key");
    expect(post).toHaveBeenCalledWith("/api/v1/findings/finding/remediation-rounds/2/evidence-uploads", { body: { evidenceSetId: "set", expectedFindingVersion: 4, expectedEvidenceRevision: 0, filename: "proof.png", declaredMediaType: "image/png", declaredSizeBytes: 3 }, headers: { "Idempotency-Key": "key" }, signal: undefined });
    const file = new File(["abc"], "proof.png", { type: "image/png" });
    await adapter.receive(context, "intent", file);
    expect(putBytes).toHaveBeenCalledWith("/api/v1/findings/finding/remediation-rounds/2/evidence-uploads/intent/content", file, "image/png", { signal: undefined });
  });

  it("only resolves an explicit ALREADY_RECEIVED by reading status; never resends unknown writes", async () => {
    const get = vi.fn(async () => ({ data: status }));
    const putBytes = vi.fn(async () => { throw new ApiError(409, "ALREADY_RECEIVED", null, false); });
    const adapter = createHttpFindingEvidence({ get, putBytes } as unknown as HttpClient);
    const file = new File(["abc"], "proof.png", { type: "image/png" });
    expect(await adapter.receive(context, "intent", file)).toEqual(status);
    expect(get).toHaveBeenCalledOnce(); expect(putBytes).toHaveBeenCalledOnce();
    putBytes.mockImplementation(async () => { throw new ApiError(0, "NETWORK", null, true); });
    await expect(adapter.receive(context, "intent", file)).rejects.toMatchObject({ code: "NETWORK" });
    expect(get).toHaveBeenCalledOnce(); expect(putBytes).toHaveBeenCalledTimes(2);
  });

  it("does not treat a verified checkpoint as published evidence", () => {
    expect(evidenceIsReady({ ...status, state: "VERIFIED_PENDING_FINALIZATION" })).toBe(false);
    expect(evidenceIsReady({ ...status, state: "FINALIZED" })).toBe(true);
  });
});
