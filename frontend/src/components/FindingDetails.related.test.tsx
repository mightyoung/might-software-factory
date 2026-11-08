import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FindingDetails } from "./FindingDetails";
import type { FindingRecord, FindingState } from "../domain/finding";

const record = (state: FindingState, extra: Partial<FindingRecord> = {}): FindingRecord => ({
  id: "f1", code: "YH-1", description: "d", location: "l", state, reportedAt: "2026-10-30T09:00:00+08:00",
  reporter: "—", responsible: "—", riskLabel: "一般", round: 1, history: [], version: 3, ...extra,
});
afterEach(cleanup);

describe("关联上报入口", () => {
  it("只有“未成立”的记录能发起关联上报，点击把该记录交给上层", () => {
    const onCreateRelated = vi.fn();
    const view = render(<FindingDetails finding={record("NOT_ESTABLISHED")} loading={false} error={null} onClose={vi.fn()} onRetry={vi.fn()} onCreateRelated={onCreateRelated} />);
    fireEvent.click(screen.getByRole("button", { name: "基于此创建关联上报" }));
    expect(onCreateRelated).toHaveBeenCalledWith(expect.objectContaining({ id: "f1", code: "YH-1" }));
    view.rerender(<FindingDetails finding={record("RECTIFYING")} loading={false} error={null} onClose={vi.fn()} onRetry={vi.fn()} onCreateRelated={onCreateRelated} />);
    expect(screen.queryByRole("button", { name: "基于此创建关联上报" })).not.toBeInTheDocument();
  });

  it("本记录由关联上报而来：显示关联行，可查看原记录；没有关联时不显示", () => {
    const onOpenRelated = vi.fn();
    const view = render(<FindingDetails finding={record("PERSON_CONFIRM_PENDING", { relatedFindingId: "p1" })} loading={false} error={null} onClose={vi.fn()} onRetry={vi.fn()} onOpenRelated={onOpenRelated} />);
    fireEvent.click(screen.getByRole("button", { name: /查看原记录/ }));
    expect(onOpenRelated).toHaveBeenCalledWith("p1");
    view.rerender(<FindingDetails finding={record("PERSON_CONFIRM_PENDING")} loading={false} error={null} onClose={vi.fn()} onRetry={vi.fn()} onOpenRelated={onOpenRelated} />);
    expect(screen.queryByText("关联记录")).not.toBeInTheDocument();
  });
});
