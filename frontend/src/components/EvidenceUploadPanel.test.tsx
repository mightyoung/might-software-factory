import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { EvidenceUploadPanel } from "./EvidenceUploadPanel";
import { FindingCommandError, type FindingCommandAdapter } from "../adapters/findingCommands";
import { FindingEvidenceError, checkUploadFile, type CurrentEvidenceSet, type FindingEvidenceAdapter, type UploadState, type UploadStatus } from "../adapters/findingEvidence";
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


// 每次发布推进版本，预约拒绝旧版本；重复查状态不重复推进。
function revisionEnforcingFake(initialRevision = 0, restoring = false): FindingEvidenceAdapter {
  let initialized = restoring;
  let revision = initialRevision;
  const uploads = new Map<string, UploadStatus>(restoring ? [["restored", { ...status("SCANNING"), intentId: "restored" }]] : []);
  return fake({
    currentSet: vi.fn(async () => initialized ? { evidenceSetId: "s1", evidenceRevision: revision, uploads: [...uploads.values()] } : null),
    initializeSet: vi.fn(async () => { initialized = true; return { evidenceSetId: "s1", initialEvidenceRevision: 0 }; }),
    reserveUpload: vi.fn(async (_scope, declaration) => {
      if (declaration.expectedEvidenceRevision !== revision) throw new FindingEvidenceError("conflict");
      const next = { ...status("INTENT_CREATED"), intentId: `i${uploads.size + 1}` };
      uploads.set(next.intentId, next);
      return next;
    }),
    sendContent: vi.fn(async (_scope, intentId) => ({ ...status("QUARANTINED"), intentId })),
    getStatus: vi.fn(async (_scope, intentId) => {
      const current = uploads.get(intentId)!;
      if (current.state !== "FINALIZED") { revision++; uploads.set(intentId, { ...current, state: "FINALIZED" }); }
      return uploads.get(intentId)!;
    }),
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => { resolve = accept; });
  return { promise, resolve };
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

  it("marks invalid remediation text without sending a command and counts Unicode code points after trimming", async () => {
    const run = vi.fn().mockResolvedValue({ state: "ACCEPTANCE_PENDING", roundNo: 2, version: 8 });
    render(<EvidenceUploadPanel finding={finding} adapter={fake()} commands={{ run }} />);
    await pick(png());
    await screen.findByText(/已终结/);
    const input = screen.getByLabelText("整改说明");
    const button = screen.getByRole("button", { name: "提交整改" });
    for (const value of ["  ", "🔧".repeat(4001)]) {
      fireEvent.change(input, { target: { value } });
      fireEvent.click(button);
      expect(input).toHaveAttribute("aria-invalid", "true");
      expect(screen.getByRole("alert")).toHaveTextContent("最多 4000 个字");
      expect(run).not.toHaveBeenCalled();
    }
    const statement = "🔧".repeat(4000);
    fireEvent.change(input, { target: { value: ` ${statement} ` } });
    await act(async () => { fireEvent.click(button); });
    expect(input).not.toHaveAttribute("aria-invalid", "true");
    expect(run).toHaveBeenCalledWith("f1", 7, { kind: "submitRemediation", evidenceSetId: "s1", statement }, expect.anything());
  });

  it("同一 SPA 保留模块缓存离开再返回后，从服务端恢复已发布状态并允许提交", async () => {
    const adapter = revisionEnforcingFake(); const run = vi.fn().mockResolvedValue({ state: "ACCEPTANCE_PENDING", roundNo: 2, version: 8 });
    const view = render(<EvidenceUploadPanel finding={finding} adapter={adapter} commands={{ run } as unknown as FindingCommandAdapter} />);
    await pick(png()); await screen.findByText(/已终结/); view.unmount();
    const reads = vi.mocked(adapter.currentSet!).mock.calls.length;
    render(<EvidenceUploadPanel finding={finding} adapter={adapter} commands={{ run } as unknown as FindingCommandAdapter} />);
    expect(screen.getByRole("button", { name: "提交整改" })).toBeDisabled();
    await screen.findByText(/已终结/);
    expect(adapter.currentSet).toHaveBeenCalledTimes(reads + 1);
    fireEvent.change(screen.getByLabelText("整改说明"), { target: { value: "已整改" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "提交整改" })); });
    expect(run).toHaveBeenCalledWith("f1", 7, { kind: "submitRemediation", evidenceSetId: "s1", statement: "已整改" }, expect.anything());
    expect(adapter.initializeSet).toHaveBeenCalledTimes(1);
  });

  it("保留模块缓存重挂载时，当前身份读取失败或没有证据集不能恢复成已发布", async () => {
    for (const failure of ["forbidden", "unauthenticated", "unavailable", null] as const) {
      clearEvidenceMemory(); cleanup();
      const first = revisionEnforcingFake(); const view = render(<EvidenceUploadPanel finding={finding} adapter={first} />);
      await pick(png()); await screen.findByText(/已终结/); view.unmount();
      const second = fake({ currentSet: vi.fn(async () => { if (failure) throw new FindingEvidenceError(failure); return null; }) });
      const run = vi.fn(); render(<EvidenceUploadPanel finding={finding} adapter={second} commands={{ run } as unknown as FindingCommandAdapter} />);
      await act(async () => {});
      expect(second.currentSet).toHaveBeenCalledTimes(1);
      expect(screen.getByRole("button", { name: "提交整改" })).toBeDisabled();
      expect(screen.queryByText(/已终结/)).not.toBeInTheDocument();
      expect(second.getStatus).not.toHaveBeenCalled(); expect(run).not.toHaveBeenCalled();
    }
  });

  it.each(["FAILED", "SCANNING"] as const)("已有发布但最新上传为 %s 时，不凭旧发布或非零版本启用提交", async latest => {
    const adapter = fake({
      currentSet: vi.fn(async () => ({ evidenceSetId: "s1", evidenceRevision: 9, uploads: [status("FINALIZED"), { ...status(latest), intentId: "latest" }] })),
      getStatus: vi.fn(() => new Promise<UploadStatus>(() => {})),
    });
    const run = vi.fn(); const view = render(<EvidenceUploadPanel finding={finding} adapter={adapter} commands={{ run } as unknown as FindingCommandAdapter} />);
    await screen.findByText(latest === "FAILED" ? /处理失败/ : /正在扫描/);
    expect(screen.getByRole("button", { name: "提交整改" })).toBeDisabled();
    expect(run).not.toHaveBeenCalled();
    if (latest === "SCANNING") expect(adapter.getStatus).toHaveBeenCalledWith({ findingId: "f1", roundNo: 2 }, "latest", expect.anything());
    else expect(adapter.getStatus).not.toHaveBeenCalled();
    view.unmount();
  });

  it("切换身份适配器时立即隐藏旧发布状态，不能在权威读取完成前提交", async () => {
    const first = revisionEnforcingFake(); const run = vi.fn();
    const props = { finding, commands: { run } as unknown as FindingCommandAdapter };
    const view = render(<EvidenceUploadPanel {...props} adapter={first} />);
    await pick(png()); await screen.findByText(/已终结/);
    const read = deferred<CurrentEvidenceSet | null>();
    const second = fake({ currentSet: vi.fn(() => read.promise) });
    view.rerender(<EvidenceUploadPanel {...props} adapter={second} />);
    expect(screen.getByRole("button", { name: "提交整改" })).toBeDisabled();
    expect(screen.queryByText(/已终结/)).not.toBeInTheDocument();
    await act(async () => { read.resolve(null); });
    expect(screen.getByRole("button", { name: "提交整改" })).toBeDisabled();
    expect(second.getStatus).not.toHaveBeenCalled(); expect(run).not.toHaveBeenCalled();
  });

  it("上一轮迟到的恢复响应不能覆盖当前轮次的权威证据集", async () => {
    const old = deferred<CurrentEvidenceSet | null>(); const run = vi.fn();
    const adapter = fake({ currentSet: vi.fn().mockReturnValueOnce(old.promise).mockResolvedValue({ evidenceSetId: "round3", evidenceRevision: 8, uploads: [{ ...status("FINALIZED"), intentId: "round3-intent" }] }) });
    const view = render(<EvidenceUploadPanel finding={finding} adapter={adapter} commands={{ run } as unknown as FindingCommandAdapter} />);
    view.rerender(<EvidenceUploadPanel finding={{ ...finding, round: 3 }} adapter={adapter} commands={{ run } as unknown as FindingCommandAdapter} />);
    await screen.findByText(/已终结/);
    await act(async () => { old.resolve({ evidenceSetId: "round2", evidenceRevision: 1, uploads: [status("CANCELLED")] }); });
    expect(screen.queryByText(/已取消/)).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("整改说明"), { target: { value: "第三轮完成" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "提交整改" })); });
    expect(run).toHaveBeenCalledWith("f1", 7, { kind: "submitRemediation", evidenceSetId: "round3", statement: "第三轮完成" }, expect.anything());
    expect(adapter.currentSet).toHaveBeenLastCalledWith({ findingId: "f1", roundNo: 3 }, expect.anything());
  });

  it("迟到的挂载恢复不能覆盖用户已开始的新上传或提前启用提交", async () => {
    const old = deferred<CurrentEvidenceSet | null>(); const reservation = deferred<UploadStatus>();
    const adapter = fake({
      currentSet: vi.fn().mockReturnValueOnce(old.promise).mockResolvedValue({ evidenceSetId: "new-set", evidenceRevision: 2, uploads: [] }),
      reserveUpload: vi.fn(() => reservation.promise),
      sendContent: vi.fn(async (_scope, intentId) => ({ ...status("QUARANTINED"), intentId })),
      getStatus: vi.fn(async () => ({ ...status("FINALIZED"), intentId: "new-intent" })),
    });
    const run = vi.fn(); render(<EvidenceUploadPanel finding={finding} adapter={adapter} commands={{ run } as unknown as FindingCommandAdapter} />);
    await pick(png());
    await act(async () => { old.resolve({ evidenceSetId: "old-set", evidenceRevision: 1, uploads: [status("FINALIZED")] }); });
    expect(screen.getByRole("button", { name: "提交整改" })).toBeDisabled();
    expect(screen.queryByText(/已终结/)).not.toBeInTheDocument();
    await act(async () => { reservation.resolve({ ...status("INTENT_CREATED"), intentId: "new-intent" }); });
    await screen.findByText(/已终结/);
    expect(adapter.getStatus).toHaveBeenCalledWith({ findingId: "f1", roundNo: 2 }, "new-intent", expect.anything());
    fireEvent.change(screen.getByLabelText("整改说明"), { target: { value: "本次上传完成" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "提交整改" })); });
    expect(run).toHaveBeenCalledWith("f1", 7, { kind: "submitRemediation", evidenceSetId: "new-set", statement: "本次上传完成" }, expect.anything());
  });

  it("恢复进行中的上传发布后，再次预约读取推进后的当前版本", async () => {
    const adapter = revisionEnforcingFake(4, true);
    render(<EvidenceUploadPanel finding={finding} adapter={adapter} />);
    expect(await screen.findByText(/已终结/)).toBeInTheDocument();
    expect(adapter.getStatus).toHaveBeenCalledWith({ findingId: "f1", roundNo: 2 }, "restored", expect.anything());
    await pick(png()); await screen.findByText(/已终结/);
    expect(adapter.initializeSet).not.toHaveBeenCalled();
    expect(adapter.reserveUpload).toHaveBeenCalledWith(
      { findingId: "f1", roundNo: 2 }, expect.objectContaining({ evidenceSetId: "s1", expectedEvidenceRevision: 5 }),
      expect.any(String), expect.anything(),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
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
    const adapter = revisionEnforcingFake();
    vi.mocked(adapter.sendContent).mockRejectedValueOnce(new FindingEvidenceError("unavailable"));
    render(<EvidenceUploadPanel finding={finding} adapter={adapter} />);
    await pick(png());
    expect(await screen.findByText(/已终结/)).toBeInTheDocument();
    expect(adapter.sendContent).toHaveBeenCalledTimes(1);
    const firstIntent = vi.mocked(adapter.reserveUpload).mock.results[0];
    expect(adapter.getStatus).toHaveBeenCalledWith({ findingId: "f1", roundNo: 2 }, (await firstIntent.value).intentId, expect.anything());
    await pick(png()); await screen.findByText(/已终结/);
    expect(adapter.sendContent).toHaveBeenCalledTimes(2); // 每个独立预约只传一次，不重发未知结果的旧请求。
    expect(vi.mocked(adapter.reserveUpload).mock.calls.map((call) => call[1].expectedEvidenceRevision)).toEqual([0, 1]);
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

  it("连续发布两张图片复用证据集，第二次预约使用发布后版本与独立幂等键", async () => {
    const adapter = revisionEnforcingFake();
    render(<EvidenceUploadPanel finding={finding} adapter={adapter} />);
    await pick(png()); await screen.findByText(/已终结/);
    await pick(png()); await screen.findByText(/已终结/);
    expect(adapter.initializeSet).toHaveBeenCalledTimes(1);
    expect(adapter.reserveUpload).toHaveBeenCalledTimes(2);
    const reservations = vi.mocked(adapter.reserveUpload).mock.calls;
    expect(reservations.map((call) => call[1].expectedEvidenceRevision)).toEqual([0, 1]);
    expect(reservations[0][2]).not.toEqual(reservations[1][2]);
    expect(adapter.sendContent).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("初始化响应丢失后复用原键重放，预约使用其他页面发布后的当前版本", async () => {
    // 创建响应始终是 revision0；服务端在客户端收到重放前已有其他页面发布到 revision3。
    const adapter = revisionEnforcingFake(3);
    const initialize = vi.mocked(adapter.initializeSet).getMockImplementation()!;
    let lost = true;
    vi.mocked(adapter.initializeSet).mockImplementation(async (...args) => {
      const result = await initialize(...args);
      if (lost) { lost = false; throw new FindingEvidenceError("unavailable"); }
      return result;
    });
    render(<EvidenceUploadPanel finding={finding} adapter={adapter} />);
    await pick(png()); await screen.findByRole("alert");
    expect(adapter.reserveUpload).not.toHaveBeenCalled();
    await pick(png()); await screen.findByText(/已终结/);
    const initializations = vi.mocked(adapter.initializeSet).mock.calls;
    expect(initializations).toHaveLength(2);
    expect(initializations[0][2]).toEqual(initializations[1][2]);
    expect(adapter.reserveUpload).toHaveBeenCalledTimes(1);
    expect(adapter.reserveUpload).toHaveBeenCalledWith(
      { findingId: "f1", roundNo: 2 }, expect.objectContaining({ evidenceSetId: "s1", expectedEvidenceRevision: 3 }),
      expect.any(String), expect.anything(),
    );
    expect(adapter.sendContent).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("发布后当前证据集读取失败或不可见时，不使用旧版本继续预约或重新建集", async () => {
    for (const failure of ["forbidden", "unauthenticated", "unavailable", null] as const) {
      clearEvidenceMemory(); cleanup();
      const adapter = revisionEnforcingFake(); const onUnauthenticated = vi.fn();
      render(<EvidenceUploadPanel finding={finding} adapter={adapter} onUnauthenticated={onUnauthenticated} />);
      await pick(png()); await screen.findByText(/已终结/);
      vi.mocked(adapter.currentSet!).mockImplementation(async () => {
        if (failure) throw new FindingEvidenceError(failure);
        return null;
      });
      await pick(png()); await screen.findByRole("alert");
      expect(adapter.reserveUpload).toHaveBeenCalledTimes(1);
      expect(adapter.initializeSet).toHaveBeenCalledTimes(1);
      expect(adapter.sendContent).toHaveBeenCalledTimes(1);
      expect(onUnauthenticated).toHaveBeenCalledTimes(failure === "unauthenticated" ? 1 : 0);
    }
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
