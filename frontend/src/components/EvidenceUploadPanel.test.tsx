import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { EvidenceUploadPanel } from "./EvidenceUploadPanel";
import { FindingCommandError, type FindingCommandAdapter } from "../adapters/findingCommands";
import { FindingEvidenceError, checkUploadFile, type FindingEvidenceAdapter, type UploadState, type UploadStatus } from "../adapters/findingEvidence";
import { clearEvidenceMemory } from "../features/findings/useEvidenceUpload";
import type { FindingRecord } from "../domain/finding";

const finding: FindingRecord = {
  id: "f1", code: "YH-1", description: "d", location: "l", state: "RECTIFYING", reportedAt: "2026-10-30T09:00:00+08:00",
  reporter: "—", responsible: "—", riskLabel: "一般", round: 2, history: [], version: 7,
};
const status = (state: UploadState): UploadStatus => ({ intentId: "i1", state, expiresAt: "2026-12-01T00:00:00Z", cancelRequested: false });
const png = () => new File([new Uint8Array([1, 2, 3])], "护栏.png", { type: "image/png" });

function fake(overrides: Partial<FindingEvidenceAdapter> = {}): FindingEvidenceAdapter {
  return {
    initializeSet: vi.fn(async () => ({ evidenceSetId: "s1", initialEvidenceRevision: 0 })),
    reserveUpload: vi.fn(async () => status("INTENT_CREATED")),
    sendContent: vi.fn(async () => status("QUARANTINED")),
    getStatus: vi.fn(async () => status("FINALIZED")),
    cancel: vi.fn(async () => ({ ...status("SCANNING"), cancelRequested: true })),
    ...overrides,
  };
}

async function pick(file: File) {
  const input = screen.getByLabelText(/选择图片/);
  await act(async () => { fireEvent.change(input, { target: { files: [file] } }); });
}

beforeEach(() => { clearEvidenceMemory(); });
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe("整改证据上传面板", () => {
  it("建证据集 → 预约（带当前版本与轮次）→ 传字节 → 轮询到终结发布", async () => {
    const adapter = fake();
    render(<EvidenceUploadPanel finding={finding} adapter={adapter} />);
    await pick(png());
    expect(adapter.initializeSet).toHaveBeenCalledWith({ findingId: "f1", roundNo: 2 }, 7, expect.any(String), expect.anything());
    expect(adapter.reserveUpload).toHaveBeenCalledWith(
      { findingId: "f1", roundNo: 2 },
      { evidenceSetId: "s1", expectedFindingVersion: 7, expectedEvidenceRevision: 0, filename: "护栏.png", declaredMediaType: "image/png", declaredSizeBytes: 3 },
      expect.any(String),
      expect.anything(),
    );
    expect(await screen.findByText(/已终结/)).toBeInTheDocument();
  });

  it("终结发布前不能提交；发布后带证据集和说明提交整改，重试复用同一个幂等键", async () => {
    const run = vi.fn().mockRejectedValueOnce(new FindingCommandError("unavailable")).mockResolvedValue({ state: "ACCEPTANCE_PENDING", roundNo: 2, version: 8 });
    const onSubmitted = vi.fn();
    render(<EvidenceUploadPanel finding={finding} adapter={fake()} commands={{ run } as unknown as FindingCommandAdapter} onSubmitted={onSubmitted} />);
    const button = screen.getByRole("button", { name: "提交整改" });
    expect(button).toBeDisabled();
    await pick(png());
    await screen.findByText(/已终结/);
    fireEvent.change(screen.getByLabelText("整改说明"), { target: { value: " 已加装护栏 " } });
    await act(async () => { fireEvent.click(button); });
    expect(await screen.findByText(/提交结果尚未确认/)).toBeInTheDocument();
    expect(onSubmitted).not.toHaveBeenCalled();
    await act(async () => { fireEvent.click(button); });
    expect(run).toHaveBeenCalledTimes(2);
    expect(run.mock.calls[1]).toEqual(["f1", 7, { kind: "submitRemediation", evidenceSetId: "s1", statement: "已加装护栏" }, { idempotencyKey: run.mock.calls[0][3].idempotencyKey }]);
    expect(onSubmitted).toHaveBeenCalledTimes(1);
  });

  it("刷新后从服务端找回证据集与最近一次上传：不用重新建集，进行中的接着轮询，之后新上传沿用该证据集和版本", async () => {
    const adapter = fake({
      currentSet: vi.fn(async () => ({ evidenceSetId: "s9", evidenceRevision: 4, uploads: [status("SCANNING")] })),
    });
    render(<EvidenceUploadPanel finding={finding} adapter={adapter} />);
    expect(await screen.findByText(/已终结/)).toBeInTheDocument(); // 接着轮询到终态
    expect(adapter.getStatus).toHaveBeenCalledWith({ findingId: "f1", roundNo: 2 }, "i1", expect.anything());
    await pick(png());
    expect(adapter.initializeSet).not.toHaveBeenCalled();
    expect(adapter.reserveUpload).toHaveBeenCalledWith(
      { findingId: "f1", roundNo: 2 },
      expect.objectContaining({ evidenceSetId: "s9", expectedEvidenceRevision: 4 }),
      expect.any(String),
      expect.anything(),
    );
  });

  it("服务端没有可编辑证据集（null）或读取失败时，仍按原流程在第一次上传时建集", async () => {
    for (const currentSet of [vi.fn(async () => null), vi.fn(async () => { throw new FindingEvidenceError("unavailable"); })]) {
      clearEvidenceMemory();
      cleanup();
      const adapter = fake({ currentSet });
      render(<EvidenceUploadPanel finding={finding} adapter={adapter} />);
      await act(async () => {});
      await pick(png());
      expect(adapter.initializeSet).toHaveBeenCalledTimes(1);
    }
  });

  it("本地先拒绝非 PNG/JPEG、空文件与超大文件，不发请求", async () => {
    const adapter = fake();
    render(<EvidenceUploadPanel finding={finding} adapter={adapter} />);
    await pick(new File(["x"], "a.pdf", { type: "application/pdf" }));
    expect(screen.getByRole("alert")).toHaveTextContent("只支持 PNG 或 JPEG");
    expect(adapter.initializeSet).not.toHaveBeenCalled();
    expect(checkUploadFile({ name: "a.png", size: 0, type: "image/png" })).toMatch(/空/);
    expect(checkUploadFile({ name: "a.png", size: 67_108_865, type: "image/png" })).toMatch(/64 MiB/);
    expect(checkUploadFile({ name: "a/b.png", size: 5, type: "image/png" })).toMatch(/文件名/);
  });

  it("字节上传失败（503）不自动重发，改查状态确认", async () => {
    const adapter = fake({ sendContent: vi.fn(async () => { throw new FindingEvidenceError("unavailable"); }) });
    render(<EvidenceUploadPanel finding={finding} adapter={adapter} />);
    await pick(png());
    expect(await screen.findByText(/已终结/)).toBeInTheDocument();
    expect(adapter.sendContent).toHaveBeenCalledTimes(1);
    expect(adapter.getStatus).toHaveBeenCalled();
  });

  it("非整改责任人（403）看到明确原因；登录失效回调上层", async () => {
    const onUnauthenticated = vi.fn();
    const forbidden = fake({ initializeSet: vi.fn(async () => { throw new FindingEvidenceError("forbidden"); }) });
    render(<EvidenceUploadPanel finding={finding} adapter={forbidden} />);
    await pick(png());
    expect(await screen.findByRole("alert")).toHaveTextContent("只有当前整改责任人");
    cleanup();
    clearEvidenceMemory();
    const expired = fake({ initializeSet: vi.fn(async () => { throw new FindingEvidenceError("unauthenticated"); }) });
    render(<EvidenceUploadPanel finding={finding} adapter={expired} onUnauthenticated={onUnauthenticated} />);
    await pick(png());
    await screen.findByRole("alert");
    expect(onUnauthenticated).toHaveBeenCalledTimes(1);
  });

  it("同一条记录重试复用证据集：第二个文件不再建集", async () => {
    const adapter = fake();
    render(<EvidenceUploadPanel finding={finding} adapter={adapter} />);
    await pick(png());
    await screen.findByText(/已终结/);
    await pick(png());
    await screen.findByText(/已终结/);
    expect(adapter.initializeSet).toHaveBeenCalledTimes(1);
    expect(adapter.reserveUpload).toHaveBeenCalledTimes(2);
  });

  it("扫描中可以取消，已请求取消的状态会显示", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const adapter = fake({ getStatus: vi.fn(async () => status("SCANNING")) });
    render(<EvidenceUploadPanel finding={finding} adapter={adapter} />);
    await pick(png());
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    await act(async () => { fireEvent.click(await screen.findByRole("button", { name: "取消上传" })); });
    expect(adapter.cancel).toHaveBeenCalled();
    expect(await screen.findByText(/已请求取消/)).toBeInTheDocument();
  });
});
