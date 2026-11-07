import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/httpClient";
import type { FindingCommandAdapter } from "../adapters/findingCommands";
import type { EvidenceUploadStatus, FindingEvidenceAdapter } from "../adapters/findingEvidence";
import { FindingEvidenceUpload } from "./FindingEvidenceUpload";

const findingId = "11111111-1111-4111-8111-111111111111";
const setId = "22222222-2222-4222-8222-222222222222";
const intentId = "33333333-3333-4333-8333-333333333333";
const status = (state: EvidenceUploadStatus["state"]): EvidenceUploadStatus => ({ uploadIntentId: intentId, state, expiresAt: "2026-11-08T12:00:00Z" });

function fixture() {
  const adapter: FindingEvidenceAdapter = {
    initialize: vi.fn(async () => ({ evidenceSetId: setId, initialEvidenceRevision: 0 as const })),
    reserve: vi.fn(async () => status("INTENT_CREATED")),
    receive: vi.fn(async () => status("VERIFIED_PENDING_FINALIZATION")),
    status: vi.fn(async () => status("VERIFIED_PENDING_FINALIZATION")),
    cancel: vi.fn(async () => ({ ...status("SCANNING"), cancelRequested: true })),
  };
  const commands = { run: vi.fn(async () => ({ state: "ACCEPTANCE_PENDING", roundNo: 1, version: 5 })) } as unknown as FindingCommandAdapter;
  const changed = vi.fn();
  const props = { findingId, roundNo: 1, version: 4, adapter, commands, onChanged: changed, recoveryOwnerKey: "tenant/account/member" };
  return { adapter, commands, changed, props };
}

function selectImage() {
  const file = new File([new Uint8Array([1, 2, 3])], "proof.png", { type: "image/png" });
  Object.defineProperty(file, "arrayBuffer", { value: async () => new Uint8Array([1, 2, 3]).buffer });
  fireEvent.change(screen.getByLabelText("整改图片"), { target: { files: [file] } });
  fireEvent.click(screen.getByRole("button", { name: "上传图片" }));
  return file;
}

beforeEach(() => {
  sessionStorage.clear();
  vi.stubGlobal("crypto", { ...crypto, randomUUID: () => "44444444-4444-4444-8444-444444444444", subtle: { digest: vi.fn(async () => new Uint8Array(32).buffer) } });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("FindingEvidenceUpload", () => {
  it("allows a refreshed version after initialization explicitly rejects a stale version", async () => {
    const f = fixture();
    vi.mocked(f.adapter.initialize).mockRejectedValueOnce(new ApiError(409, "VERSION_CONFLICT", null, false));
    const mounted = render(<FindingEvidenceUpload {...f.props} />);
    selectImage();
    await waitFor(() => expect(f.changed).toHaveBeenCalledOnce());
    mounted.rerender(<FindingEvidenceUpload {...f.props} version={5} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "上传图片" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "上传图片" }));
    await screen.findByText("扫描完成，等待证据发布");
    expect(vi.mocked(f.adapter.initialize).mock.calls[1][1]).toBe(5);
    expect(f.adapter.reserve).toHaveBeenCalledOnce();
  });

  it("retains the confirmed empty set after a reservation explicitly rejects a stale version", async () => {
    const f = fixture();
    vi.mocked(f.adapter.reserve).mockRejectedValueOnce(new ApiError(409, "VERSION_CONFLICT", null, false));
    const mounted = render(<FindingEvidenceUpload {...f.props} />);
    selectImage();
    await waitFor(() => expect(f.changed).toHaveBeenCalledOnce());
    mounted.rerender(<FindingEvidenceUpload {...f.props} version={5} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "上传图片" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "上传图片" }));
    await screen.findByText("扫描完成，等待证据发布");
    expect(f.adapter.initialize).toHaveBeenCalledOnce();
    expect(vi.mocked(f.adapter.reserve).mock.calls[1][0]).toMatchObject({ evidenceSetId: setId, expectedFindingVersion: 5 });
  });

  it("replays the identical reservation when its commit result is unknown despite a version refresh", async () => {
    const f = fixture();
    vi.mocked(f.adapter.reserve).mockRejectedValueOnce(new ApiError(0, "NETWORK", null, true));
    const mounted = render(<FindingEvidenceUpload {...f.props} />);
    selectImage();
    await screen.findByRole("alert");
    mounted.rerender(<FindingEvidenceUpload {...f.props} version={5} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "上传图片" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "上传图片" }));
    await screen.findByText("扫描完成，等待证据发布");
    const [first, replay] = vi.mocked(f.adapter.reserve).mock.calls;
    expect(replay[0]).toEqual(first[0]);
    expect(replay[1]).toBe(first[1]);
    expect(f.adapter.initialize).toHaveBeenCalledOnce();
  });

  it("waits beyond scan verification until finalization, then submits the same evidence set", async () => {
    const f = fixture(); render(<FindingEvidenceUpload {...f.props} />);
    const file = selectImage();
    await screen.findByText("扫描完成，等待证据发布");
    expect(f.adapter.receive).toHaveBeenCalledWith({ findingId, roundNo: 1 }, intentId, file, expect.any(AbortSignal));
    expect(screen.getByRole("button", { name: "提交整改" })).toBeDisabled();
    vi.mocked(f.adapter.status).mockResolvedValue(status("FINALIZED"));
    fireEvent.click(screen.getByRole("button", { name: "查询上传状态" }));
    await screen.findByText("证据已发布，可提交整改");
    fireEvent.change(screen.getByLabelText("整改说明"), { target: { value: " 已更换损坏部件 " } });
    fireEvent.click(screen.getByRole("button", { name: "提交整改" }));
    await screen.findByText("整改已提交，等待验收。");
    expect(f.commands.run).toHaveBeenCalledWith(findingId, 4, { kind: "submitRemediation", evidenceSetId: setId, statement: "已更换损坏部件" }, expect.objectContaining({ idempotencyKey: expect.any(String) }));
    expect(f.changed).toHaveBeenCalledOnce();
  });

  it("does not resend an ambiguously consumed file; recovery queries only its intent", async () => {
    const f = fixture(); vi.mocked(f.adapter.receive).mockRejectedValue(new ApiError(0, "NETWORK", null, true));
    const mounted = render(<FindingEvidenceUpload {...f.props} />); selectImage();
    await screen.findByText("文件接收结果尚未确认，请查询状态。不会自动重复发送文件。");
    expect(f.adapter.receive).toHaveBeenCalledOnce(); mounted.unmount();
    render(<FindingEvidenceUpload {...f.props} />);
    await waitFor(() => expect(f.adapter.status).toHaveBeenCalled());
    expect(f.adapter.initialize).toHaveBeenCalledOnce(); expect(f.adapter.reserve).toHaveBeenCalledOnce(); expect(f.adapter.receive).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "提交整改" })).toBeDisabled();
  });

  it("separates durable recovery by verified identity and does not save files or statements", async () => {
    const f = fixture(); const mounted = render(<FindingEvidenceUpload {...f.props} />); selectImage();
    await screen.findByText("扫描完成，等待证据发布");
    fireEvent.change(screen.getByLabelText("整改说明"), { target: { value: "private remediation explanation" } });
    const stored = sessionStorage.getItem(sessionStorage.key(0)!);
    expect(stored).not.toContain("private remediation explanation"); expect(stored).not.toContain("base64");
    mounted.unmount(); const calls = vi.mocked(f.adapter.status).mock.calls.length;
    render(<FindingEvidenceUpload {...f.props} recoveryOwnerKey="other-tenant/account/member" />);
    expect(screen.getByRole("button", { name: "上传图片" })).toBeDisabled();
    expect(f.adapter.status).toHaveBeenCalledTimes(calls);
  });

  it("cancels an in-flight request and waits for server cancellation rather than declaring cleanup", async () => {
    const f = fixture(); let receivedSignal: AbortSignal | undefined;
    vi.mocked(f.adapter.receive).mockImplementation((_c, _id, _file, signal) => { receivedSignal = signal; return new Promise((_resolve, reject) => signal?.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")))); });
    render(<FindingEvidenceUpload {...f.props} />); selectImage();
    await waitFor(() => expect(f.adapter.receive).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByRole("button", { name: "取消上传" }));
    await screen.findByText("已请求取消；是否完成清理以服务端后续状态为准。");
    expect(receivedSignal?.aborted).toBe(true); expect(f.adapter.cancel).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "提交整改" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "新建另一份证据集" })).not.toBeInTheDocument();
  });
});
